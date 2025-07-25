/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.broker.service.persistent;

import static org.apache.bookkeeper.mledger.util.ManagedLedgerUtils.markDelete;
import static org.apache.bookkeeper.mledger.util.ManagedLedgerUtils.openCursor;
import static org.apache.pulsar.client.impl.GeoReplicationProducerImpl.MSG_PROP_IS_REPL_MARKER;
import static org.apache.pulsar.client.impl.GeoReplicationProducerImpl.MSG_PROP_REPL_SOURCE_POSITION;
import com.google.common.annotations.VisibleForTesting;
import io.netty.buffer.ByteBuf;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.bookkeeper.mledger.AsyncCallbacks.DeleteCursorCallback;
import org.apache.bookkeeper.mledger.ManagedCursor;
import org.apache.bookkeeper.mledger.ManagedLedger;
import org.apache.bookkeeper.mledger.ManagedLedgerException;
import org.apache.bookkeeper.mledger.ManagedLedgerReplayTask;
import org.apache.bookkeeper.mledger.Position;
import org.apache.commons.lang3.StringUtils;
import org.apache.pulsar.broker.PulsarService;
import org.apache.pulsar.broker.service.Producer;
import org.apache.pulsar.broker.service.Topic.PublishContext;
import org.apache.pulsar.common.api.proto.KeyValue;
import org.apache.pulsar.common.api.proto.MessageMetadata;
import org.apache.pulsar.common.protocol.Commands;
import org.apache.pulsar.common.protocol.Markers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class that contains all the logic to control and perform the deduplication on the broker side.
 */
public class MessageDeduplication {

    private final PulsarService pulsar;
    private final PersistentTopic topic;
    private final ManagedLedger managedLedger;
    private final ManagedLedgerReplayTask replayTask;
    private ManagedCursor managedCursor;

    private static final String IS_LAST_CHUNK = "isLastChunk";

    enum Status {

        // Deduplication is initialized
        Initialized,

        // Deduplication is disabled
        Disabled,

        // Initializing deduplication state
        Recovering,

        // Deduplication is in effect
        Enabled,

        // Turning off deduplication
        Removing,

        // Failed to enable/disable
        Failed,
    }

    @VisibleForTesting
    public enum MessageDupStatus {
        // whether a message is a definitely a duplicate or not cannot be determined at this time
        Unknown,
        // message is definitely NOT a duplicate
        NotDup,
        // message is definitely a duplicate
        Dup,
    }

    public static class MessageDupUnknownException extends RuntimeException {
        public MessageDupUnknownException(String topicName, String producerName) {
            super(String.format("[%s][%s]Cannot determine whether the message is a duplicate at this time", topicName,
                    producerName));
        }
    }


    private volatile Status status;

    // Map that contains the highest sequenceId that have been sent by each producers. The map will be updated before
    // the messages are persisted
    @VisibleForTesting
    final Map<String, Long> highestSequencedPushed = new ConcurrentHashMap<>();

    // Map that contains the highest sequenceId that have been persistent by each producers. The map will be updated
    // after the messages are persisted
    @VisibleForTesting
    final Map<String, Long> highestSequencedPersisted = new ConcurrentHashMap<>();

    // Number of persisted entries after which to store a snapshot of the sequence ids map
    private final int snapshotInterval;

    // Counter of number of entries stored after last snapshot was taken
    private int snapshotCounter;

    // The timestamp when the snapshot was taken by the scheduled task last time
    private volatile long lastSnapshotTimestamp = 0L;

    // Max number of producer for which to persist the sequence id information
    private final int maxNumberOfProducers;

    // Map used to track the inactive producer along with the timestamp of their last activity
    private final Map<String, Long> inactiveProducers = new ConcurrentHashMap<>();

    private final String replicatorPrefix;


    private final AtomicBoolean snapshotTaking = new AtomicBoolean(false);

    public MessageDeduplication(PulsarService pulsar, PersistentTopic topic, ManagedLedger managedLedger) {
        this.pulsar = pulsar;
        this.topic = topic;
        this.managedLedger = managedLedger;
        this.status = Status.Initialized;
        this.snapshotInterval = pulsar.getConfiguration().getBrokerDeduplicationEntriesInterval();
        this.maxNumberOfProducers = pulsar.getConfiguration().getBrokerDeduplicationMaxNumberOfProducers();
        this.snapshotCounter = 0;
        this.replicatorPrefix = pulsar.getConfiguration().getReplicatorPrefix();
        this.replayTask = new ManagedLedgerReplayTask("MessageDeduplication", pulsar.getExecutor(), 100);
    }

    public Status getStatus() {
        return status;
    }

    /**
     * Check the status of deduplication. If the configuration has changed, it will enable/disable deduplication,
     * returning a future to track the completion of the task
     */
    public CompletableFuture<Void> checkStatus() {
        boolean shouldBeEnabled = topic.isDeduplicationEnabled();
        synchronized (this) {
            if (status == Status.Recovering || status == Status.Removing) {
                // If there's already a transition happening, check later for status
                pulsar.getExecutor().schedule(this::checkStatus, 1, TimeUnit.MINUTES);
                return CompletableFuture.completedFuture(null);
            }
            if (status == Status.Initialized && !shouldBeEnabled) {
                status = Status.Removing;
                managedLedger.asyncDeleteCursor(PersistentTopic.DEDUPLICATION_CURSOR_NAME,
                        new DeleteCursorCallback() {
                            @Override
                            public void deleteCursorComplete(Object ctx) {
                                status = Status.Disabled;
                                log.info("[{}] Deleted deduplication cursor", topic.getName());
                            }

                            @Override
                            public void deleteCursorFailed(ManagedLedgerException exception, Object ctx) {
                                if (exception instanceof ManagedLedgerException.CursorNotFoundException) {
                                    status = Status.Disabled;
                                } else {
                            log.error("[{}] Deleted deduplication cursor error", topic.getName(), exception);
                        }
                    }
                }, null);
            }

            if (status == Status.Enabled && !shouldBeEnabled) {
                // Disabled deduping
                CompletableFuture<Void> future = new CompletableFuture<>();
                status = Status.Removing;
                managedLedger.asyncDeleteCursor(PersistentTopic.DEDUPLICATION_CURSOR_NAME,
                        new DeleteCursorCallback() {

                            @Override
                            public void deleteCursorComplete(Object ctx) {
                                status = Status.Disabled;
                                managedCursor = null;
                                highestSequencedPushed.clear();
                                highestSequencedPersisted.clear();
                                future.complete(null);
                                log.info("[{}] Disabled deduplication", topic.getName());
                            }

                            @Override
                            public void deleteCursorFailed(ManagedLedgerException exception, Object ctx) {
                                // It's ok for disable message deduplication.
                                if (exception instanceof ManagedLedgerException.CursorNotFoundException) {
                                    status = Status.Disabled;
                                    managedCursor = null;
                                    highestSequencedPushed.clear();
                                    highestSequencedPersisted.clear();
                                    future.complete(null);
                                } else {
                                    log.warn("[{}] Failed to disable deduplication: {}", topic.getName(),
                                            exception.getMessage());
                                    status = Status.Failed;
                                    future.completeExceptionally(exception);
                                }
                            }
                        }, null);

                return future;
            } else if ((status == Status.Disabled || status == Status.Initialized) && shouldBeEnabled) {
                // Enable deduping
                final var future = openCursor(managedLedger, PersistentTopic.DEDUPLICATION_CURSOR_NAME)
                        .thenCompose(this::replayCursor);
                future.exceptionally(e -> {
                    status = Status.Failed;
                    log.error("[{}] Failed to enable deduplication", topic.getName(), e);
                    future.completeExceptionally(e);
                    return null;
                });
                return future;
            } else {
                // Nothing to do, we are in the correct state
                return CompletableFuture.completedFuture(null);
            }
        }
    }

    private CompletableFuture<Void> replayCursor(ManagedCursor cursor) {
        managedCursor = cursor;
        // Load the sequence ids from the snapshot in the cursor properties
        managedCursor.getProperties().forEach((k, v) -> {
            producerRemoved(k);
            highestSequencedPushed.put(k, v);
            highestSequencedPersisted.put(k, v);
        });
        // Replay all the entries and apply all the sequence ids updates
        log.info("[{}] Replaying {} entries for deduplication", topic.getName(),
                managedCursor.getNumberOfEntries());
        return replayTask.replay(cursor, (__, buffer) -> {
            final var metadata = Commands.parseMessageMetadata(buffer);
            final var producerName = metadata.getProducerName();
            final var sequenceId = Math.max(metadata.getHighestSequenceId(), metadata.getSequenceId());
            highestSequencedPushed.put(producerName, sequenceId);
            highestSequencedPersisted.put(producerName, sequenceId);
            producerRemoved(producerName);
        }).thenCompose(optPosition -> {
            if (optPosition.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            snapshotCounter = replayTask.getNumEntriesProcessed();
            if (snapshotCounter >= snapshotInterval) {
                return takeSnapshot(optPosition.get());
            } else {
                return CompletableFuture.completedFuture(null);
            }
        }).thenRun(() -> {
            status = Status.Enabled;
            log.info("[{}] Enabled deduplication", topic.getName());
        });
    }

    public boolean isEnabled() {
        return status == Status.Enabled;
    }

    /**
     * Assess whether the message was already stored in the topic.
     *
     * @return true if the message should be published or false if it was recognized as a duplicate
     */
    public MessageDupStatus isDuplicate(PublishContext publishContext, ByteBuf headersAndPayload) {
        setContextPropsIfRepl(publishContext, headersAndPayload);
        if (!isEnabled() || publishContext.isMarkerMessage()) {
            return MessageDupStatus.NotDup;
        }
        if (Producer.isRemoteOrShadow(publishContext.getProducerName(), replicatorPrefix)) {
            if (!publishContext.supportsReplDedupByLidAndEid()){
                return isDuplicateReplV1(publishContext, headersAndPayload);
            } else {
                return isDuplicateReplV2(publishContext, headersAndPayload);
            }
        }
        return isDuplicateNormal(publishContext, headersAndPayload, false);
    }

    public MessageDupStatus isDuplicateReplV1(PublishContext publishContext, ByteBuf headersAndPayload) {
        // Message is coming from replication, we need to use the original producer name and sequence id
        // for the purpose of deduplication and not rely on the "replicator" name.
        int readerIndex = headersAndPayload.readerIndex();
        MessageMetadata md = Commands.parseMessageMetadata(headersAndPayload);
        headersAndPayload.readerIndex(readerIndex);

        String producerName = md.getProducerName();
        long sequenceId = md.getSequenceId();
        long highestSequenceId = Math.max(md.getHighestSequenceId(), sequenceId);
        publishContext.setOriginalProducerName(producerName);
        publishContext.setOriginalSequenceId(sequenceId);
        publishContext.setOriginalHighestSequenceId(highestSequenceId);
        return isDuplicateNormal(publishContext, headersAndPayload, true);
    }

    private void setContextPropsIfRepl(PublishContext publishContext, ByteBuf headersAndPayload) {
        // Case-1: is a replication marker.
        if (publishContext.isMarkerMessage()) {
            // Message is coming from replication, we need to use the replication's producer name, ledger id and entry
            // id for the purpose of deduplication.
            MessageMetadata md = Commands.peekMessageMetadata(headersAndPayload, "Check-Deduplicate", -1);
            if (md != null && md.hasMarkerType() && Markers.isReplicationMarker(md.getMarkerType())) {
                publishContext.setProperty(MSG_PROP_IS_REPL_MARKER, "");
            }
            return;
        }

        // Case-2: is a replicated message.
        if (Producer.isRemoteOrShadow(publishContext.getProducerName(), replicatorPrefix)) {
            // Message is coming from replication, we need to use the replication's producer name, source cluster's
            // ledger id and entry id for the purpose of deduplication.
            int readerIndex = headersAndPayload.readerIndex();
            MessageMetadata md = Commands.parseMessageMetadata(headersAndPayload);
            headersAndPayload.readerIndex(readerIndex);

            List<KeyValue> kvPairList = md.getPropertiesList();
            for (KeyValue kvPair : kvPairList) {
                if (kvPair.getKey().equals(MSG_PROP_REPL_SOURCE_POSITION)) {
                    if (!kvPair.getValue().contains(":")) {
                        log.warn("[{}] Unexpected {}: {}", publishContext.getProducerName(),
                                MSG_PROP_REPL_SOURCE_POSITION,
                                kvPair.getValue());
                        break;
                    }
                    String[] ledgerIdAndEntryId = kvPair.getValue().split(":");
                    if (ledgerIdAndEntryId.length != 2 || !StringUtils.isNumeric(ledgerIdAndEntryId[0])
                            || !StringUtils.isNumeric(ledgerIdAndEntryId[1])) {
                        log.warn("[{}] Unexpected {}: {}", publishContext.getProducerName(),
                                MSG_PROP_REPL_SOURCE_POSITION,
                                kvPair.getValue());
                        break;
                    }
                    long[] positionPair = new long[]{Long.valueOf(ledgerIdAndEntryId[0]).longValue(),
                            Long.valueOf(ledgerIdAndEntryId[1]).longValue()};
                    publishContext.setProperty(MSG_PROP_REPL_SOURCE_POSITION, positionPair);
                    break;
                }
            }
        }
    }

    public MessageDupStatus isDuplicateReplV2(PublishContext publishContext, ByteBuf headersAndPayload) {
        Object positionPairObj = publishContext.getProperty(MSG_PROP_REPL_SOURCE_POSITION);
        if (positionPairObj == null || !(positionPairObj instanceof long[])) {
            log.error("[{}] Message can not determine whether the message is duplicated due to the acquired messages"
                            + " props were are invalid. producer={}. supportsReplDedupByLidAndEid: {}, sequence-id {},"
                            + " prop-{}: not in expected format",
                    topic.getName(), publishContext.getProducerName(),
                    publishContext.supportsReplDedupByLidAndEid(), publishContext.getSequenceId(),
                    MSG_PROP_REPL_SOURCE_POSITION);
            return MessageDupStatus.Unknown;
        }

        long[] positionPair = (long[]) positionPairObj;
        long replSequenceLId = positionPair[0];
        long replSequenceEId = positionPair[1];

        String lastSequenceLIdKey = publishContext.getProducerName() + "_LID";
        String lastSequenceEIdKey = publishContext.getProducerName() + "_EID";
        synchronized (highestSequencedPushed) {
            Long lastSequenceLIdPushed = highestSequencedPushed.get(lastSequenceLIdKey);
            Long lastSequenceEIdPushed = highestSequencedPushed.get(lastSequenceEIdKey);
            if (lastSequenceLIdPushed != null && lastSequenceEIdPushed != null
                && (replSequenceLId < lastSequenceLIdPushed.longValue()
                        || (replSequenceLId == lastSequenceLIdPushed.longValue()
                        && replSequenceEId <= lastSequenceEIdPushed.longValue()))) {
                if (log.isDebugEnabled()) {
                    log.debug("[{}] Message identified as duplicated producer={}. publishing {}:{}, latest publishing"
                            + " in-progress {}:{}",
                            topic.getName(), publishContext.getProducerName(), lastSequenceLIdPushed,
                            lastSequenceEIdPushed, lastSequenceLIdPushed, lastSequenceEIdPushed);
                }

                // Also need to check sequence ids that has been persisted.
                // If current message's seq id is smaller or equals to the
                // "lastSequenceLIdPersisted:lastSequenceEIdPersisted" than its definitely a dup
                // If current message's seq id is between "lastSequenceLIdPushed:lastSequenceEIdPushed" and
                // "lastSequenceLIdPersisted:lastSequenceEIdPersisted", then we cannot be sure whether the message
                // is a dup or not we should return an error to the producer for the latter case so that it can retry
                // at a future time
                Long lastSequenceLIdPersisted = highestSequencedPersisted.get(lastSequenceLIdKey);
                Long lastSequenceEIdPersisted = highestSequencedPersisted.get(lastSequenceEIdKey);
                if (log.isDebugEnabled()) {
                    log.debug("[{}] Message identified as duplicated producer={}. publishing {}:{}, latest"
                                    + " persisted {}:{}",
                            topic.getName(), publishContext.getProducerName(), replSequenceLId,
                            replSequenceEId, lastSequenceLIdPersisted, lastSequenceEIdPersisted);
                }
                if (lastSequenceLIdPersisted != null && lastSequenceEIdPersisted != null
                    && (replSequenceLId < lastSequenceLIdPersisted.longValue()
                        || (replSequenceLId == lastSequenceLIdPersisted.longValue()
                            && replSequenceEId <= lastSequenceEIdPersisted))) {
                    return MessageDupStatus.Dup;
                } else {
                    return MessageDupStatus.Unknown;
                }
            }
            highestSequencedPushed.put(lastSequenceLIdKey, replSequenceLId);
            highestSequencedPushed.put(lastSequenceEIdKey, replSequenceEId);
        }
        if (log.isDebugEnabled()) {
            log.debug("[{}] Message identified as non-duplicated producer={}. publishing {}:{}",
                    topic.getName(), publishContext.getProducerName(), replSequenceLId, replSequenceEId);
        }
        return MessageDupStatus.NotDup;
    }

    public MessageDupStatus isDuplicateNormal(PublishContext publishContext, ByteBuf headersAndPayload,
                                              boolean useOriginalProducerName) {
        String producerName = publishContext.getProducerName();
        if (useOriginalProducerName) {
            producerName = publishContext.getOriginalProducerName();
        }
        long sequenceId = publishContext.getSequenceId();
        long highestSequenceId = Math.max(publishContext.getHighestSequenceId(), sequenceId);
        long chunkID = -1;
        long totalChunk = -1;
        if (publishContext.isChunked()) {
            int readerIndex = headersAndPayload.readerIndex();
            MessageMetadata md = Commands.parseMessageMetadata(headersAndPayload);
            headersAndPayload.readerIndex(readerIndex);
            chunkID = md.getChunkId();
            totalChunk = md.getNumChunksFromMsg();
        }
        // All chunks of a message use the same message metadata and sequence ID,
        // so we only need to check the sequence ID for the last chunk in a chunk message.
        if (chunkID != -1 && chunkID != totalChunk - 1) {
            publishContext.setProperty(IS_LAST_CHUNK, Boolean.FALSE);
            return MessageDupStatus.NotDup;
        }
        // Synchronize the get() and subsequent put() on the map. This would only be relevant if the producer
        // disconnects and re-connects very quickly. At that point the call can be coming from a different thread
        synchronized (highestSequencedPushed) {
            Long lastSequenceIdPushed = highestSequencedPushed.get(producerName);
            if (lastSequenceIdPushed != null && sequenceId <= lastSequenceIdPushed) {
                if (log.isDebugEnabled()) {
                    log.debug("[{}] Message identified as duplicated producer={} seq-id={} -- highest-seq-id={}",
                            topic.getName(), producerName, sequenceId, lastSequenceIdPushed);
                }

                // Also need to check sequence ids that has been persisted.
                // If current message's seq id is smaller or equals to the
                // lastSequenceIdPersisted than its definitely a dup
                // If current message's seq id is between lastSequenceIdPersisted and
                // lastSequenceIdPushed, then we cannot be sure whether the message is a dup or not
                // we should return an error to the producer for the latter case so that it can retry at a future time
                Long lastSequenceIdPersisted = highestSequencedPersisted.get(producerName);
                if (lastSequenceIdPersisted != null && sequenceId <= lastSequenceIdPersisted) {
                    return MessageDupStatus.Dup;
                } else {
                    return MessageDupStatus.Unknown;
                }
            }
            highestSequencedPushed.put(producerName, highestSequenceId);
        }
        // Only put sequence ID into highestSequencedPushed and
        // highestSequencedPersisted until receive and persistent the last chunk.
        if (chunkID != -1 && chunkID == totalChunk - 1) {
            publishContext.setProperty(IS_LAST_CHUNK, Boolean.TRUE);
        }
        return MessageDupStatus.NotDup;
    }

    /**
     * Call this method whenever a message is persisted to get the chance to trigger a snapshot.
     */
    public void recordMessagePersisted(PublishContext publishContext, Position position) {
        if (!isEnabled() || publishContext.isMarkerMessage()) {
            return;
        }
        if (publishContext.getProducerName().startsWith(replicatorPrefix)
                && publishContext.supportsReplDedupByLidAndEid()) {
            recordMessagePersistedRepl(publishContext, position);
        } else {
            recordMessagePersistedNormal(publishContext, position);
        }
    }

    public void recordMessagePersistedRepl(PublishContext publishContext, Position position) {
        Object positionPairObj = publishContext.getProperty(MSG_PROP_REPL_SOURCE_POSITION);
        if (positionPairObj == null || !(positionPairObj instanceof long[])) {
            log.error("[{}] Can not persist highest sequence-id due to the acquired messages"
                            + " props are invalid. producer={}. supportsReplDedupByLidAndEid: {}, sequence-id {},"
                            + " prop-{}: not in expected format",
                    topic.getName(), publishContext.getProducerName(),
                    publishContext.supportsReplDedupByLidAndEid(), publishContext.getSequenceId(),
                    MSG_PROP_REPL_SOURCE_POSITION);
            recordMessagePersistedNormal(publishContext, position);
            return;
        }
        long[] positionPair = (long[]) positionPairObj;
        long replSequenceLId = positionPair[0];
        long replSequenceEId = positionPair[1];
        String lastSequenceLIdKey = publishContext.getProducerName() + "_LID";
        String lastSequenceEIdKey = publishContext.getProducerName() + "_EID";
        highestSequencedPersisted.put(lastSequenceLIdKey, replSequenceLId);
        highestSequencedPersisted.put(lastSequenceEIdKey, replSequenceEId);
        increaseSnapshotCounterAndTakeSnapshotIfNeeded(position);
    }

    public void recordMessagePersistedNormal(PublishContext publishContext, Position position) {
        String producerName = publishContext.getProducerName();
        long sequenceId = publishContext.getSequenceId();
        long highestSequenceId = publishContext.getHighestSequenceId();
        if (publishContext.getOriginalProducerName() != null) {
            // In case of replicated messages, this will be different from the current replicator producer name
            producerName = publishContext.getOriginalProducerName();
            sequenceId = publishContext.getOriginalSequenceId();
            highestSequenceId = publishContext.getOriginalHighestSequenceId();
        }
        Boolean isLastChunk = (Boolean) publishContext.getProperty(IS_LAST_CHUNK);
        if (isLastChunk == null || isLastChunk) {
            highestSequencedPersisted.put(producerName, Math.max(highestSequenceId, sequenceId));
        }
        increaseSnapshotCounterAndTakeSnapshotIfNeeded(position);
    }

    private void increaseSnapshotCounterAndTakeSnapshotIfNeeded(Position position) {
        if (++snapshotCounter >= snapshotInterval) {
            snapshotCounter = 0;
            takeSnapshot(position);
        } else {
            if (log.isDebugEnabled()) {
                log.debug("[{}] Waiting for sequence-id snapshot {}/{}", topic.getName(), snapshotCounter,
                        snapshotInterval);
            }
        }
    }

    public void resetHighestSequenceIdPushed() {
        if (!isEnabled()) {
            return;
        }

        highestSequencedPushed.clear();
        for (String producer : highestSequencedPersisted.keySet()) {
            highestSequencedPushed.put(producer, highestSequencedPersisted.get(producer));
        }
    }

    private CompletableFuture<Void> takeSnapshot(Position position) {
        if (log.isDebugEnabled()) {
            log.debug("[{}] Taking snapshot of sequence ids map", topic.getName());
        }

        if (!snapshotTaking.compareAndSet(false, true)) {
            log.warn("[{}] There is a pending snapshot when taking snapshot for {}", topic.getName(), position);
            return CompletableFuture.completedFuture(null);
        }

        Map<String, Long> snapshot = new TreeMap<>();
        highestSequencedPersisted.forEach((producerName, sequenceId) -> {
            if (snapshot.size() < maxNumberOfProducers) {
                snapshot.put(producerName, sequenceId);
            }
        });

        final var cursor = managedCursor;
        if (cursor == null) {
            log.warn("[{}] Cursor is null when taking snapshot for {}", topic.getName(), position);
            return CompletableFuture.completedFuture(null);
        }
        final var future = markDelete(cursor, position, snapshot).thenRun(() -> {
            if (log.isDebugEnabled()) {
                log.debug("[{}] Stored new deduplication snapshot at {}", topic.getName(), position);
            }
            lastSnapshotTimestamp = System.currentTimeMillis();
            snapshotTaking.set(false);
        });
        future.exceptionally(e -> {
            log.warn("[{}] Failed to store new deduplication snapshot at {}", topic.getName(), position, e);
            snapshotTaking.set(false);
            return null;
        });
        return future;
    }

    /**
     * Topic will call this method whenever a producer connects.
     */
    public void producerAdded(String producerName) {
        if (!isEnabled()) {
            return;
        }

        // Producer is no-longer inactive
        inactiveProducers.remove(producerName);
    }

    /**
     * Topic will call this method whenever a producer disconnects.
     */
    public void producerRemoved(String producerName) {
        if (!isEnabled()) {
            return;
        }

        // Producer is no-longer active
        inactiveProducers.put(producerName, System.currentTimeMillis());
    }

    /**
     * Remove from hash maps all the producers that were inactive for more than the configured amount of time.
     */
    public synchronized void purgeInactiveProducers() {
        long minimumActiveTimestamp = System.currentTimeMillis() - TimeUnit.MINUTES
                .toMillis(pulsar.getConfiguration().getBrokerDeduplicationProducerInactivityTimeoutMinutes());

        // if not enabled just clear all inactive producer record.
        if (!isEnabled()) {
            if (!inactiveProducers.isEmpty()) {
                inactiveProducers.clear();
            }
            return;
        }

        Iterator<Map.Entry<String, Long>> mapIterator = inactiveProducers.entrySet().iterator();
        boolean hasInactive = false;
        while (mapIterator.hasNext()) {
            java.util.Map.Entry<String, Long> entry = mapIterator.next();
            String producerName = entry.getKey();
            long lastActiveTimestamp = entry.getValue();

            if (lastActiveTimestamp < minimumActiveTimestamp) {
                log.info("[{}] Purging dedup information for producer {}", topic.getName(), producerName);
                mapIterator.remove();
                highestSequencedPushed.remove(producerName);
                highestSequencedPersisted.remove(producerName);
                hasInactive = true;
            }
        }
        if (hasInactive && isEnabled()) {
            takeSnapshot(getManagedCursor().getMarkDeletedPosition());
        }
    }

    public long getLastPublishedSequenceId(String producerName) {
        Long sequenceId = highestSequencedPushed.get(producerName);
        return sequenceId != null ? sequenceId : -1;
    }

    public void takeSnapshot() {
        if (!isEnabled()) {
            return;
        }

        Integer interval = topic.getHierarchyTopicPolicies().getDeduplicationSnapshotIntervalSeconds().get();
        long currentTimeStamp = System.currentTimeMillis();
        if (interval == null || interval <= 0
                || currentTimeStamp - lastSnapshotTimestamp < TimeUnit.SECONDS.toMillis(interval)) {
            return;
        }
        Position position = managedLedger.getLastConfirmedEntry();
        if (position == null) {
            return;
        }
        Position markDeletedPosition = managedCursor.getMarkDeletedPosition();
        if (markDeletedPosition != null && position.compareTo(markDeletedPosition) <= 0) {
            return;
        }
        takeSnapshot(position);
    }

    @VisibleForTesting
    ManagedCursor getManagedCursor() {
        return managedCursor;
    }

    @VisibleForTesting
    Map<String, Long> getInactiveProducers() {
        return inactiveProducers;
    }

    private static final Logger log = LoggerFactory.getLogger(MessageDeduplication.class);
}
