/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.graphhopper.gtfs;

import com.conveyal.gtfs.model.Transfer;
import com.graphhopper.GraphHopper;
import com.graphhopper.GraphHopperConfig;
import com.graphhopper.routing.querygraph.QueryGraph;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.util.PMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

public class GraphHopperGtfs extends GraphHopper {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphHopperGtfs.class);

    private final GraphHopperConfig ghConfig;
    private GtfsStorage gtfsStorage;
    private PtGraph ptGraph;

    public GraphHopperGtfs(GraphHopperConfig ghConfig) {
        this.ghConfig = ghConfig;
    }

    @Override
    protected void importOSM() {
        if (ghConfig.has("datareader.file")) {
            super.importOSM();
        } else {
            getGraphHopperStorage().create(1000);
        }
    }

    @Override
    protected void importPublicTransit() {
        ptGraph = new PtGraph(getGraphHopperStorage().getDirectory(), 100);
        gtfsStorage = new GtfsStorage(getGraphHopperStorage().getDirectory());
        if (!getGtfsStorage().loadExisting()) {
            ensureWriteAccess();
            getGtfsStorage().create();
            ptGraph.create(100);
            try {
                int idx = 0;
                List<String> gtfsFiles = ghConfig.has("gtfs.file") ? Arrays.asList(ghConfig.getString("gtfs.file", "").split(",")) : Collections.emptyList();
                for (String gtfsFile : gtfsFiles) {
                    getGtfsStorage().loadGtfsFromZipFileOrDirectory("gtfs_" + idx++, new File(gtfsFile));
                }
                getGtfsStorage().postInit();
                Map<String, Transfers> allTransfers = new HashMap<>();
                HashMap<String, GtfsReader> allReaders = new HashMap<>();
                getGtfsStorage().getGtfsFeeds().forEach((id, gtfsFeed) -> {
                    Transfers transfers = new Transfers(gtfsFeed);
                    allTransfers.put(id, transfers);
                    GtfsReader gtfsReader = new GtfsReader(id, getGraphHopperStorage(), ptGraph, ptGraph, getGtfsStorage(), getLocationIndex(), transfers);
                    gtfsReader.connectStopsToStreetNetwork();
                    LOGGER.info("Building transit graph for feed {}", gtfsFeed.feedId);
                    gtfsReader.buildPtNetwork();
                    allReaders.put(id, gtfsReader);
                });
                // interpolateTransfers(allReaders, allTransfers);
            } catch (Exception e) {
                throw new RuntimeException("Error while constructing transit network. Is your GTFS file valid? Please check log for possible causes.", e);
            }
        }
    }

    private void interpolateTransfers(HashMap<String, GtfsReader> readers, Map<String, Transfers> allTransfers) {
        LOGGER.info("Looking for transfers");
        final int maxTransferWalkTimeSeconds = ghConfig.getInt("gtfs.max_transfer_interpolation_walk_time_seconds", 120);
        GraphHopperStorage graphHopperStorage = getGraphHopperStorage();
        QueryGraph queryGraph = QueryGraph.create(graphHopperStorage, Collections.emptyList());
        Weighting transferWeighting = createWeighting(getProfile("foot"), new PMap());
        final GraphExplorer graphExplorer = new GraphExplorer(queryGraph, ptGraph, transferWeighting, getGtfsStorage(), RealtimeFeed.empty(getGtfsStorage()), true, true, false, 5.0, false, 0);
        getGtfsStorage().getStationNodes().values().stream().distinct().map(n -> new Label.NodeId(n, true)).forEach(stationNode -> {
            MultiCriteriaLabelSetting router = new MultiCriteriaLabelSetting(graphExplorer, true, false, false, 0, new ArrayList<>());
            router.setLimitStreetTime(Duration.ofSeconds(maxTransferWalkTimeSeconds).toMillis());
            Iterator<Label> iterator = router.calcLabels(stationNode, Instant.ofEpochMilli(0)).iterator();
            while (iterator.hasNext()) {
                Label label = iterator.next();
                if (label.parent != null) {
                    PtEdgeAttributes edgeIteratorState = ptGraph.getEdgeAttributes(label.edge);
                    if (edgeIteratorState.type == GtfsStorage.EdgeType.EXIT_PT) {
                        GtfsStorageI.PlatformDescriptor fromPlatformDescriptor = getGtfsStorage().getPlatformDescriptorByEdge().get(label.edge);
                        Transfers transfers = allTransfers.get(fromPlatformDescriptor.feed_id);
                        for (PtGraph.PtEdge ptEdge : ptGraph.edgesAround(stationNode.node)) {
                            if (ptEdge.getType() == GtfsStorage.EdgeType.ENTER_PT) {
                                GtfsStorageI.PlatformDescriptor toPlatformDescriptor = getGtfsStorage().getPlatformDescriptorByEdge().get(ptEdge.getId());
                                LOGGER.debug(fromPlatformDescriptor + " -> " + toPlatformDescriptor);
                                if (!toPlatformDescriptor.feed_id.equals(fromPlatformDescriptor.feed_id)) {
                                    LOGGER.debug(" Different feed. Inserting transfer with " + (int) (label.streetTime / 1000L) + " s.");
                                    GtfsReader toFeedReader = readers.get(toPlatformDescriptor.feed_id);
                                    toFeedReader.insertTransferEdges(label.node.node, (int) (label.streetTime / 1000L), toPlatformDescriptor);
                                } else {
                                    List<Transfer> transfersToStop = transfers.getTransfersToStop(toPlatformDescriptor.stop_id, routeIdOrNull(toPlatformDescriptor));
                                    if (transfersToStop.stream().noneMatch(t -> t.from_stop_id.equals(fromPlatformDescriptor.stop_id))) {
                                        GtfsReader toFeedReader = readers.get(toPlatformDescriptor.feed_id);
                                        toFeedReader.insertTransferEdges(label.node.node, (int) (label.streetTime / 1000L), toPlatformDescriptor);
                                        LOGGER.debug("  Inserting transfer with " + (int) (label.streetTime / 1000L) + " s.");
                                    }
                                }
                            }
                        }
                    }
                }
            }
        });
    }

    private String routeIdOrNull(GtfsStorageI.PlatformDescriptor platformDescriptor) {
        if (platformDescriptor instanceof GtfsStorageI.RouteTypePlatform) {
            return null;
        } else {
            return ((GtfsStorageI.RoutePlatform) platformDescriptor).route_id;
        }
    }

    @Override
    public void close() {
        getGtfsStorage().close();
        super.close();
    }

    public GtfsStorage getGtfsStorage() {
        return gtfsStorage;
    }

    public PtGraph getPtGraph() {
        return ptGraph;
    }
}
