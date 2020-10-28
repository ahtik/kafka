/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kafka.controller;

import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.message.BrokerHeartbeatRequestData;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.requests.ApiError;
import org.apache.kafka.common.requests.BrokerRegistrationRequest;
import org.apache.kafka.controller.ClusterControlManager.HeartbeatReply;
import org.apache.kafka.controller.ClusterControlManager.RegistrationReply;
import org.apache.kafka.metadata.FeatureManager;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public interface Controller extends AutoCloseable {
        /**
     * Change partition ISRs.
     *
     * @param brokerId      The ID of the broker making the change.
     * @param brokerEpoch   The epoch of the broker making the change.
     * @param changes       The changes to make.
     *
     * @return              A future yielding a map from partitions to error results.
     */
    CompletableFuture<Map<TopicPartition, Errors>>
        alterIsr(int brokerId, long brokerEpoch, Map<TopicPartition, LeaderAndIsr> changes);

    /**
     * Describe the current configuration of various resources.
     *
     * @param resources     A map from resources to the collection of config keys that we
     *                      want to describe for each.  If the collection is empty, then
     *                      all configuration keys will be described.
     *
     * @return
     */
    CompletableFuture<Map<ConfigResource, ResultOrError<Map<String, String>>>>
        describeConfigs(Map<ConfigResource, Collection<String>> resources);

    /**
     * Elect new partition leaders.
     *
     * @param timeoutMs     The timeout to use.
     * @param parts         The partitions to elect new leaders for.
     * @param unclean       If this is true, we will elect the first live replic if
     *                      there are no in-sync replicas.
     *
     * @return              A future yielding a map from partitions to error results.
     */
    CompletableFuture<Map<TopicPartition, PartitionLeaderElectionResult>>
        electLeaders(int timeoutMs, Set<TopicPartition> parts, boolean unclean);

    /**
     * Perform some incremental configuration changes.
     *
     * @param configChanges The changes.
     * @param validateOnly  True if we should validate the changes but not apply them.
     *
     * @return              A future yielding a map from partitions to error results.
     */
    CompletableFuture<Map<ConfigResource, ApiError>> incrementalAlterConfigs(
        Map<ConfigResource, Map<String, Map.Entry<AlterConfigOp.OpType, String>>> configChanges,
        boolean validateOnly);

    /**
     * Perform some configuration changes using the legacy API.
     *
     * @param newConfigs    The new configuration maps to apply.
     * @param validateOnly  True if we should validate the changes but not apply them.
     *
     * @return              A future yielding a map from partitions to error results.
     */
    CompletableFuture<Map<ConfigResource, ApiError>> legacyAlterConfigs(
        Map<ConfigResource, Map<String, String>> newConfigs, boolean validateOnly);

    /**
     * Process a heartbeat from a broker.
     *
     * @param request      The broker heartbeat request.
     *
     * @return              A future yielding a heartbeat reply.
     */
    CompletableFuture<HeartbeatReply> processBrokerHeartbeat(
        BrokerHeartbeatRequestData request);

    /**
     * Attempt to register the given broker.
     *
     * @param request      The registration request.
     *
     * @return              A future yielding a registration reply.
     */
    CompletableFuture<RegistrationReply> registerBroker(BrokerRegistrationRequest request);

    /**
     * Get the current finalized feature ranges for each feature.
     *
     * @return              A future yielding the feature ranges.
     */
    CompletableFuture<FeatureManager.FinalizedFeaturesAndEpoch> finalizedFeatures();

    /**
     * Begin shutting down, but don't block.  You must still call close to clean up all
     * resources.
     */
    void beginShutdown();

    /**
     * Blocks until we have shut down and freed all resources.
     */
    void close() throws InterruptedException;
}