/**
 * Copyright Pravega Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.pravega.connectors.flink;

import io.pravega.client.ClientConfig;
import io.pravega.client.admin.ReaderGroupManager;
import io.pravega.client.stream.Checkpoint;
import io.pravega.client.stream.ReaderGroup;
import io.pravega.client.stream.ReaderGroupConfig;
import io.pravega.client.stream.ReaderGroupNotFoundException;
import io.pravega.connectors.flink.serialization.CheckpointSerializer;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.core.io.SimpleVersionedSerializer;
import org.apache.flink.runtime.checkpoint.MasterTriggerRestoreHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.concurrent.GuardedBy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.apache.flink.util.Preconditions.checkArgument;
import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * The hook executed in Flink's Checkpoint Coordinator that triggers and restores
 * checkpoints in on a Pravega ReaderGroup.
 */
class ReaderCheckpointHook implements MasterTriggerRestoreHook<Checkpoint> {
    private static final Logger LOG = LoggerFactory.getLogger(ReaderCheckpointHook.class);

    /** The prefix of checkpoint names */
    private static final String PRAVEGA_CHECKPOINT_NAME_PREFIX = "PVG-CHK-";

    /** Default thread pool size of the checkpoint scheduler */
    private static final int DEFAULT_CHECKPOINT_THREAD_POOL_SIZE = 3;

    // ------------------------------------------------------------------------

    /** The reader group used to trigger and restore pravega checkpoints (MUST be closed when Hook Closed) */
    protected ReaderGroup readerGroup;

    /** The reader group manager used to create the reader group (MUST be closed when Hook Closed) */
    protected ReaderGroupManager readerGroupManager;

    /** The logical name of the operator. This is different from the (randomly generated)
     * reader group name, because it is used to identify the state in a checkpoint/savepoint
     * when resuming the checkpoint/savepoint with another job. */
    private final String hookUid;

    /** The serializer for Pravega checkpoints, to store them in Flink checkpoints */
    private final CheckpointSerializer checkpointSerializer;

    /** The timeout on the future returned by the 'initiateCheckpoint()' call */
    private final Time triggerTimeout;

    // The Pravega reader group config.
    private final ReaderGroupConfig readerGroupConfig;

    private final Object scheduledExecutorLock = new Object();

    // A long-lived thread pool for scheduling all checkpoint tasks
    @GuardedBy("scheduledExecutorLock")
    private ScheduledExecutorService scheduledExecutorService;

    ReaderCheckpointHook(String hookUid, String readerGroupName,  String readerGroupScope, Time triggerTimeout, ClientConfig clientConfig, ReaderGroupConfig readerGroupConfig) {
        this.hookUid = checkNotNull(hookUid);
        this.triggerTimeout = triggerTimeout;
        this.readerGroupConfig = readerGroupConfig;
        this.checkpointSerializer = new CheckpointSerializer();

        initializeReaderGroup(readerGroupName, readerGroupScope, clientConfig);
    }

    // ------------------------------------------------------------------------
    protected void initializeReaderGroup(String readerGroupName, String readerGroupScope, ClientConfig clientConfig) {
        readerGroupManager = ReaderGroupManager.withScope(readerGroupScope, clientConfig);
        try {
            readerGroup = readerGroupManager.getReaderGroup(readerGroupName);
        } catch (ReaderGroupNotFoundException e) {
            readerGroupManager.createReaderGroup(readerGroupName, readerGroupConfig);
            readerGroup = readerGroupManager.getReaderGroup(readerGroupName);
        }
    }

    @Override
    public String getIdentifier() {
        return this.hookUid;
    }

    @Override
    public CompletableFuture<Checkpoint> triggerCheckpoint(
            long checkpointId, long checkpointTimestamp, Executor executor) throws Exception {

        ensureScheduledExecutorExists();

        final String checkpointName = createCheckpointName(checkpointId);

        final CompletableFuture<Checkpoint> checkpointResult =
                this.readerGroup.initiateCheckpoint(checkpointName, scheduledExecutorService);

        // Add a timeout to the future, to prevent long blocking calls
        scheduledExecutorService.schedule(() -> checkpointResult.cancel(false), triggerTimeout.toMilliseconds(), TimeUnit.MILLISECONDS);

        return checkpointResult;
    }

    @Override
    public void restoreCheckpoint(long checkpointId, Checkpoint checkpoint) throws Exception {
        // checkpoint can be null when restoring from a savepoint that
        // did not include any state for that particular reader name
        if (checkpoint != null) {
             this.readerGroup.resetReaderGroup(ReaderGroupConfig
                    .builder()
                    .maxOutstandingCheckpointRequest(this.readerGroupConfig.getMaxOutstandingCheckpointRequest())
                    .groupRefreshTimeMillis(this.readerGroupConfig.getGroupRefreshTimeMillis())
                    .disableAutomaticCheckpoints()
                    .startFromCheckpoint(checkpoint)
                    .build());
        }
    }

    @Override
    public void reset() {
        // To avoid the data loss, reset the reader group using the reader config that was initially passed to the job.
        // This can happen when the job recovery happens after a failure but no checkpoint has been taken.
        LOG.info("resetting the reader group to initial state using the RG config {}", this.readerGroupConfig);
        this.readerGroup.resetReaderGroup(this.readerGroupConfig);
    }

    @Override
    public void close() {
        LOG.info("closing reader group Manager");
        this.readerGroupManager.close();

        // close the reader group properly
        LOG.info("closing the reader group");
        this.readerGroup.close();

        synchronized (scheduledExecutorLock) {
            if (scheduledExecutorService != null ) {
                LOG.info("Closing Scheduled Executor for hook {}", hookUid);
                scheduledExecutorService.shutdownNow();
                scheduledExecutorService = null;
            }
        }
    }

    @Override
    public SimpleVersionedSerializer<Checkpoint> createCheckpointDataSerializer() {
        return this.checkpointSerializer;
    }

    // ------------------------------------------------------------------------
    //  utils
    // ------------------------------------------------------------------------

    private void ensureScheduledExecutorExists() {
        synchronized (scheduledExecutorLock) {
            if (scheduledExecutorService == null) {
                LOG.info("Creating Scheduled Executor for hook {}", hookUid);
                scheduledExecutorService = createScheduledExecutorService();
            }
        }
    }

    protected ScheduledExecutorService createScheduledExecutorService() {
        return Executors.newScheduledThreadPool(DEFAULT_CHECKPOINT_THREAD_POOL_SIZE);
    }

    protected ScheduledExecutorService getScheduledExecutorService() {
        return this.scheduledExecutorService;
    }

    static long parseCheckpointId(String checkpointName) {
        checkArgument(checkpointName.startsWith(PRAVEGA_CHECKPOINT_NAME_PREFIX));

        try {
            return Long.parseLong(checkpointName.substring(PRAVEGA_CHECKPOINT_NAME_PREFIX.length()));
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            throw new IllegalArgumentException(e);
        }
    }

    static String createCheckpointName(long checkpointId) {
        return PRAVEGA_CHECKPOINT_NAME_PREFIX + checkpointId;
    }

}
