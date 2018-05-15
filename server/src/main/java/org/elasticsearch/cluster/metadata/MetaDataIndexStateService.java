/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.cluster.metadata;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.close.CloseIndexClusterStateUpdateRequest;
import org.elasticsearch.action.admin.indices.freeze.FreezeIndexClusterStateUpdateRequest;
import org.elasticsearch.action.admin.indices.open.OpenIndexClusterStateUpdateRequest;
import org.elasticsearch.action.admin.indices.unfreeze.UnfreezeIndexClusterStateUpdateRequest;
import org.elasticsearch.action.support.ActiveShardsObserver;
import org.elasticsearch.cluster.AckedClusterStateUpdateTask;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ack.ClusterStateUpdateResponse;
import org.elasticsearch.cluster.ack.OpenIndexClusterStateUpdateResponse;
import org.elasticsearch.cluster.ack.UnfreezeIndexClusterStateUpdateResponse;
import org.elasticsearch.cluster.block.ClusterBlock;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.block.ClusterBlocks;
import org.elasticsearch.cluster.routing.RoutingTable;
import org.elasticsearch.cluster.routing.allocation.AllocationService;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.Index;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.snapshots.RestoreService;
import org.elasticsearch.snapshots.SnapshotsService;
import org.elasticsearch.threadpool.ThreadPool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Service responsible for submitting open/close and freeze/unfreeze index requests
 */
public class MetaDataIndexStateService extends AbstractComponent {

    public static final ClusterBlock INDEX_CLOSED_BLOCK = new ClusterBlock(4, "index closed", false, false, false, RestStatus.FORBIDDEN, ClusterBlockLevel.READ_WRITE);
    public static final ClusterBlock INDEX_FROZEN_BLOCK = new ClusterBlock(20, "index frozen (read only)",
                false, false, false, RestStatus.FORBIDDEN, EnumSet.of(ClusterBlockLevel.WRITE));

    private final ClusterService clusterService;

    private final AllocationService allocationService;

    private final MetaDataIndexUpgradeService metaDataIndexUpgradeService;
    private final IndicesService indicesService;
    private final ActiveShardsObserver activeShardsObserver;

    @Inject
    public MetaDataIndexStateService(Settings settings, ClusterService clusterService, AllocationService allocationService,
                                     MetaDataIndexUpgradeService metaDataIndexUpgradeService,
                                     IndicesService indicesService, ThreadPool threadPool) {
        super(settings);
        this.indicesService = indicesService;
        this.clusterService = clusterService;
        this.allocationService = allocationService;
        this.metaDataIndexUpgradeService = metaDataIndexUpgradeService;
        this.activeShardsObserver = new ActiveShardsObserver(settings, clusterService, threadPool);
    }

    public void closeIndex(final CloseIndexClusterStateUpdateRequest request, final ActionListener<ClusterStateUpdateResponse> listener) {
        if (request.indices() == null || request.indices().length == 0) {
            throw new IllegalArgumentException("Index name is required");
        }

        final String indicesAsString = Arrays.toString(request.indices());
        clusterService.submitStateUpdateTask("close-indices " + indicesAsString, new AckedClusterStateUpdateTask<ClusterStateUpdateResponse>(Priority.URGENT, request, listener) {
            @Override
            protected ClusterStateUpdateResponse newResponse(boolean acknowledged) {
                return new ClusterStateUpdateResponse(acknowledged);
            }

            @Override
            public ClusterState execute(ClusterState currentState) {
                Set<IndexMetaData> indicesToClose = new HashSet<>();
                for (Index index : request.indices()) {
                    final IndexMetaData indexMetaData = currentState.metaData().getIndexSafe(index);
                    if (indexMetaData.getState() != IndexMetaData.State.CLOSE) {
                        indicesToClose.add(indexMetaData);
                    }
                }

                if (indicesToClose.isEmpty()) {
                    return currentState;
                }

                // Check if index closing conflicts with any running restores
                RestoreService.checkIndexClosing(currentState, indicesToClose);
                // Check if index closing conflicts with any running snapshots
                SnapshotsService.checkIndexClosing(currentState, indicesToClose);
                logger.info("closing indices [{}]", indicesAsString);

                MetaData.Builder mdBuilder = MetaData.builder(currentState.metaData());
                ClusterBlocks.Builder blocksBuilder = ClusterBlocks.builder()
                        .blocks(currentState.blocks());
                for (IndexMetaData openIndexMetadata : indicesToClose) {
                    final String indexName = openIndexMetadata.getIndex().getName();
                    mdBuilder.put(IndexMetaData.builder(openIndexMetadata).state(IndexMetaData.State.CLOSE));
                    blocksBuilder.addIndexBlock(indexName, INDEX_CLOSED_BLOCK);
                }

                ClusterState updatedState = ClusterState.builder(currentState).metaData(mdBuilder).blocks(blocksBuilder).build();

                RoutingTable.Builder rtBuilder = RoutingTable.builder(currentState.routingTable());
                for (IndexMetaData index : indicesToClose) {
                    rtBuilder.remove(index.getIndex().getName());
                }

                //no explicit wait for other nodes needed as we use AckedClusterStateUpdateTask
                return  allocationService.reroute(
                        ClusterState.builder(updatedState).routingTable(rtBuilder.build()).build(),
                        "indices closed [" + indicesAsString + "]");
            }
        });
    }

    public void openIndex(final OpenIndexClusterStateUpdateRequest request, final ActionListener<OpenIndexClusterStateUpdateResponse> listener) {
        onlyOpenIndex(request, ActionListener.wrap(response -> {
            if (response.isAcknowledged()) {
                String[] indexNames = Arrays.stream(request.indices()).map(Index::getName).toArray(String[]::new);
                activeShardsObserver.waitForActiveShards(indexNames, request.waitForActiveShards(), request.ackTimeout(),
                    shardsAcknowledged -> {
                        if (shardsAcknowledged == false) {
                            logger.debug("[{}] indices opened, but the operation timed out while waiting for " +
                                "enough shards to be started.", Arrays.toString(indexNames));
                        }
                        listener.onResponse(new OpenIndexClusterStateUpdateResponse(response.isAcknowledged(), shardsAcknowledged));
                    }, listener::onFailure);
            } else {
                listener.onResponse(new OpenIndexClusterStateUpdateResponse(false, false));
            }
        }, listener::onFailure));
    }

    private void onlyOpenIndex(final OpenIndexClusterStateUpdateRequest request, final ActionListener<ClusterStateUpdateResponse> listener) {
        if (request.indices() == null || request.indices().length == 0) {
            throw new IllegalArgumentException("Index name is required");
        }

        final String indicesAsString = Arrays.toString(request.indices());
        clusterService.submitStateUpdateTask("open-indices " + indicesAsString, new AckedClusterStateUpdateTask<ClusterStateUpdateResponse>(Priority.URGENT, request, listener) {
            @Override
            protected ClusterStateUpdateResponse newResponse(boolean acknowledged) {
                return new ClusterStateUpdateResponse(acknowledged);
            }

            @Override
            public ClusterState execute(ClusterState currentState) {
                List<IndexMetaData> indicesToOpen = new ArrayList<>();
                for (Index index : request.indices()) {
                    final IndexMetaData indexMetaData = currentState.metaData().getIndexSafe(index);
                    if (indexMetaData.getState() != IndexMetaData.State.OPEN) {
                        indicesToOpen.add(indexMetaData);
                    }
                }

                if (indicesToOpen.isEmpty()) {
                    return currentState;
                }

                logger.info("opening indices [{}]", indicesAsString);

                MetaData.Builder mdBuilder = MetaData.builder(currentState.metaData());
                ClusterBlocks.Builder blocksBuilder = ClusterBlocks.builder()
                        .blocks(currentState.blocks());
                final Version minIndexCompatibilityVersion = currentState.getNodes().getMaxNodeVersion()
                    .minimumIndexCompatibilityVersion();
                for (IndexMetaData closedMetaData : indicesToOpen) {
                    final String indexName = closedMetaData.getIndex().getName();
                    IndexMetaData indexMetaData = IndexMetaData.builder(closedMetaData).state(IndexMetaData.State.OPEN).build();
                    // The index might be closed because we couldn't import it due to old incompatible version
                    // We need to check that this index can be upgraded to the current version
                    indexMetaData = metaDataIndexUpgradeService.upgradeIndexMetaData(indexMetaData, minIndexCompatibilityVersion);
                    try {
                        indicesService.verifyIndexMetadata(indexMetaData, indexMetaData);
                    } catch (Exception e) {
                        throw new ElasticsearchException("Failed to verify index " + indexMetaData.getIndex(), e);
                    }

                    mdBuilder.put(indexMetaData, true);
                    blocksBuilder.removeIndexBlock(indexName, INDEX_CLOSED_BLOCK);
                }

                ClusterState updatedState = ClusterState.builder(currentState).metaData(mdBuilder).blocks(blocksBuilder).build();

                RoutingTable.Builder rtBuilder = RoutingTable.builder(updatedState.routingTable());
                for (IndexMetaData index : indicesToOpen) {
                    rtBuilder.addAsFromCloseToOpen(updatedState.metaData().getIndexSafe(index.getIndex()));
                }

                //no explicit wait for other nodes needed as we use AckedClusterStateUpdateTask
                return allocationService.reroute(
                        ClusterState.builder(updatedState).routingTable(rtBuilder.build()).build(),
                        "indices opened [" + indicesAsString + "]");
            }
        });
    }

    public void freezeIndex(final FreezeIndexClusterStateUpdateRequest request, final ActionListener<ClusterStateUpdateResponse> listener) {
        if (request.indices() == null || request.indices().length == 0) {
            throw new IllegalArgumentException("Index name is required");
        }

        final String indicesAsString = Arrays.toString(request.indices());
        clusterService.submitStateUpdateTask("freeze-indices " + indicesAsString,
                new AckedClusterStateUpdateTask<ClusterStateUpdateResponse>(Priority.URGENT, request, listener) {

            @Override
            protected ClusterStateUpdateResponse newResponse(boolean acknowledged) {
                return new ClusterStateUpdateResponse(acknowledged);
            }

            @Override
            public ClusterState execute(ClusterState currentState) {
                Set<IndexMetaData> indicesToFreeze = new HashSet<>();
                for (Index index : request.indices()) {
                    final IndexMetaData indexMetaData = currentState.metaData().getIndexSafe(index);
                    // Only open indices may be frozen
                    if (indexMetaData.getState() == IndexMetaData.State.OPEN) {
                        indicesToFreeze.add(indexMetaData);
                    }
                }

                if (indicesToFreeze.isEmpty()) {
                    return currentState;
                }

                // Check if index closing conflicts with any running restores
                RestoreService.checkIndexClosing(currentState, indicesToFreeze);
                // Check if index closing conflicts with any running snapshots
                // TODO: we might not need to check this for freezing
                SnapshotsService.checkIndexClosing(currentState, indicesToFreeze);
                logger.info("freezing indices {}", indicesAsString);

                MetaData.Builder mdBuilder = MetaData.builder(currentState.metaData());
                ClusterBlocks.Builder blocksBuilder = ClusterBlocks.builder().blocks(currentState.blocks());
                for (IndexMetaData openIndexMetadata : indicesToFreeze) {
                    final String indexName = openIndexMetadata.getIndex().getName();
                    mdBuilder.put(IndexMetaData.builder(openIndexMetadata).state(IndexMetaData.State.FROZEN));
                    blocksBuilder.addIndexBlock(indexName, INDEX_FROZEN_BLOCK);
                }

                ClusterState updatedState = ClusterState.builder(currentState).metaData(mdBuilder).blocks(blocksBuilder).build();

                // no explicit wait for other nodes needed as we use AckedClusterStateUpdateTask
                return updatedState;
            }
        });
    }

    public void unfreezeIndex(final UnfreezeIndexClusterStateUpdateRequest request,
                          final ActionListener<UnfreezeIndexClusterStateUpdateResponse> listener) {
        if (request.indices() == null || request.indices().length == 0) {
            throw new IllegalArgumentException("Index name is required");
        }

        final String indicesAsString = Arrays.toString(request.indices());

        ActionListener<ClusterStateUpdateResponse> newListener = ActionListener.wrap(response -> {
            if (response.isAcknowledged()) {
                String[] indexNames = Arrays.stream(request.indices()).map(Index::getName).toArray(String[]::new);
                activeShardsObserver.waitForActiveShards(indexNames, request.waitForActiveShards(), request.ackTimeout(),
                    shardsAcknowledged -> {
                        if (shardsAcknowledged == false) {
                            logger.debug("[{}] indices unfrozen, but the operation timed out while waiting for " +
                                "enough shards to be started.", Arrays.toString(indexNames));
                        }
                        listener.onResponse(new UnfreezeIndexClusterStateUpdateResponse(response.isAcknowledged(), shardsAcknowledged));
                    }, listener::onFailure);
            } else {
                listener.onResponse(new UnfreezeIndexClusterStateUpdateResponse(false, false));
            }
        }, listener::onFailure);

        clusterService.submitStateUpdateTask("unfreeze-indices " + indicesAsString,
            new AckedClusterStateUpdateTask<ClusterStateUpdateResponse>(Priority.URGENT, request, newListener) {

                @Override
                protected ClusterStateUpdateResponse newResponse(boolean acknowledged) {
                    return new ClusterStateUpdateResponse(acknowledged);
                }

                @Override
                public ClusterState execute(ClusterState currentState) {
                    Set<IndexMetaData> indicesToUnfreeze = new HashSet<>();
                    for (Index index : request.indices()) {
                        final IndexMetaData indexMetaData = currentState.metaData().getIndexSafe(index);
                        if (indexMetaData.getState() == IndexMetaData.State.FROZEN) {
                            indicesToUnfreeze.add(indexMetaData);
                        }
                    }

                    if (indicesToUnfreeze.isEmpty()) {
                        return currentState;
                    }

                    logger.info("unfreezeing indices {}", indicesAsString);

                    MetaData.Builder mdBuilder = MetaData.builder(currentState.metaData());
                    ClusterBlocks.Builder blocksBuilder = ClusterBlocks.builder().blocks(currentState.blocks());
                    for (IndexMetaData openIndexMetadata : indicesToUnfreeze) {
                        final String indexName = openIndexMetadata.getIndex().getName();
                        mdBuilder.put(IndexMetaData.builder(openIndexMetadata).state(IndexMetaData.State.OPEN));
                        blocksBuilder.removeIndexBlock(indexName, INDEX_FROZEN_BLOCK);
                    }

                    ClusterState updatedState = ClusterState.builder(currentState).metaData(mdBuilder).blocks(blocksBuilder).build();

                    // no explicit wait for other nodes needed as we use AckedClusterStateUpdateTask
                    return updatedState;
                }
            });
    }
}