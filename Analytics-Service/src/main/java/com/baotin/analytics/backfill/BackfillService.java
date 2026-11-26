package com.baotin.analytics.backfill;

import com.baotin.analytics.events.AnalyticsEventIngestor;
import com.baotin.analytics.events.AnalyticsInvalidEventException;
import com.baotin.analytics.events.IngestOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class BackfillService {

    static final int MIN_BATCH_SIZE = 1;
    static final int MAX_BATCH_SIZE = 1000;
    static final int DEFAULT_BATCH_SIZE = 200;

    private static final String DEFAULT_SCAN_TOPIC = "baotin.scan.events.v1";
    private static final String DEFAULT_REPORT_TOPIC = "baotin.report.events.v1";
    private static final String DEFAULT_REPUTATION_TOPIC = "baotin.reputation.events.v1";
    private static final String SOURCE_FILE_NDJSON = "FILE_NDJSON";
    private static final String SOURCE_KAFKA_REPLAY = "KAFKA_REPLAY";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_FAILED = "FAILED";
    private static final int MAX_SOURCE_REF_LENGTH = 256;
    private static final int MAX_CONSECUTIVE_EMPTY_POLLS = 10;

    private static final Logger log = LoggerFactory.getLogger(BackfillService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate txTemplate;
    private final TransactionTemplate requiresNewTxTemplate;
    private final AnalyticsEventIngestor ingestor;
    private final String scanTopic;
    private final String reportTopic;
    private final String reputationTopic;
    private final MeterRegistry meterRegistry;

    public BackfillService(JdbcTemplate jdbc) {
        this(
                jdbc,
                new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource(), "dataSource must not be null")),
                new AnalyticsEventIngestor(jdbc),
                DEFAULT_SCAN_TOPIC,
                DEFAULT_REPORT_TOPIC,
                DEFAULT_REPUTATION_TOPIC,
                (MeterRegistry) null);
    }

    @Autowired
    public BackfillService(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            AnalyticsEventIngestor ingestor,
            @Value("${analytics.events.scan-topic:" + DEFAULT_SCAN_TOPIC + "}") String scanTopic,
            @Value("${analytics.events.report-topic:" + DEFAULT_REPORT_TOPIC + "}") String reportTopic,
            @Value("${analytics.events.reputation-topic:" + DEFAULT_REPUTATION_TOPIC + "}") String reputationTopic,
            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this(
                jdbc,
                transactionManager,
                ingestor,
                scanTopic,
                reportTopic,
                reputationTopic,
                meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable());
    }

    BackfillService(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            AnalyticsEventIngestor ingestor,
            String scanTopic,
            String reportTopic,
            String reputationTopic,
            MeterRegistry meterRegistry) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        Objects.requireNonNull(transactionManager, "transactionManager must not be null");
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTxTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.ingestor = Objects.requireNonNull(ingestor, "ingestor must not be null");
        this.scanTopic = requireTopic(scanTopic, "scanTopic");
        this.reportTopic = requireTopic(reportTopic, "reportTopic");
        this.reputationTopic = requireTopic(reputationTopic, "reputationTopic");
        this.meterRegistry = meterRegistry;
    }

    public BackfillRunSummary startFileRun(Path file, int batchSize) {
        Path validFile = validateReadableRegularFile(file);
        validateBatchSize(batchSize);

        UUID runId = UUID.randomUUID();
        String sourceRef = truncateSourceRef(validFile.toString());
        txTemplate.executeWithoutResult(status -> insertRun(runId, SOURCE_FILE_NDJSON, sourceRef, batchSize));

        return executeFileRun(runId, validFile, new RunCounters(0L, 0L, 0L, 0L), batchSize);
    }

    public BackfillRunSummary resumeFileRun(UUID runId, Path file, Integer batchSizeOverride) {
        Objects.requireNonNull(runId, "runId must not be null");
        Path validFile = validateReadableRegularFile(file);
        if (batchSizeOverride != null) {
            validateBatchSize(batchSizeOverride);
        }

        BackfillRunSummary resumed = transitionFailedToRunning(runId, SOURCE_FILE_NDJSON, batchSizeOverride);
        RunCounters counters = new RunCounters(
                resumed.lastCursor(),
                resumed.processedCount(),
                resumed.appliedCount(),
                resumed.skippedCount());
        return executeFileRun(runId, validFile, counters, resumed.batchSize());
    }

    public BackfillRunSummary startKafkaReplayRun(
            Properties consumerProps,
            List<String> topics,
            int batchSize,
            Duration pollTimeout) {
        validateBatchSize(batchSize);
        validatePollTimeout(pollTimeout);
        List<String> resolvedTopics = resolveTopics(topics);

        try (Consumer<String, String> consumer = createEphemeralConsumer(consumerProps)) {
            return startKafkaReplayRun(consumer, resolvedTopics, batchSize, pollTimeout);
        }
    }

    public BackfillRunSummary resumeKafkaReplayRun(
            UUID runId,
            Properties consumerProps,
            int batchSize,
            Duration pollTimeout) {
        Objects.requireNonNull(runId, "runId must not be null");
        validateBatchSize(batchSize);
        validatePollTimeout(pollTimeout);

        try (Consumer<String, String> consumer = createEphemeralConsumer(consumerProps)) {
            return resumeKafkaReplayRun(runId, consumer, batchSize, pollTimeout);
        }
    }

    BackfillRunSummary startKafkaReplayRun(
            Consumer<String, String> consumer,
            List<String> topics,
            int batchSize,
            Duration pollTimeout) {
        Objects.requireNonNull(consumer, "consumer must not be null");
        validateBatchSize(batchSize);
        validatePollTimeout(pollTimeout);
        List<String> resolvedTopics = resolveTopics(topics);

        List<TopicPartition> partitions = discoverPartitions(consumer, resolvedTopics);
        Map<TopicPartition, Long> beginningOffsets = partitions.isEmpty()
                ? Map.of()
                : consumer.beginningOffsets(partitions);
        Map<TopicPartition, Long> endOffsets = partitions.isEmpty()
                ? Map.of()
                : consumer.endOffsets(partitions);

        UUID runId = UUID.randomUUID();
        String sourceRef = truncateSourceRef(String.join(",", resolvedTopics));
        Map<TopicPartition, PartitionState> partitionStates = new LinkedHashMap<>();

        txTemplate.executeWithoutResult(status -> {
            insertRun(runId, SOURCE_KAFKA_REPLAY, sourceRef, batchSize);
            for (TopicPartition tp : partitions) {
                long nextOffset = Math.max(0L, beginningOffsets.getOrDefault(tp, 0L));
                long endOffset = Math.max(nextOffset, endOffsets.getOrDefault(tp, nextOffset));
                jdbc.update("""
                        INSERT INTO analytics.backfill_partition_checkpoints
                            (run_id, topic, partition_id, next_offset, end_offset)
                        VALUES (?, ?, ?, ?, ?)
                        """, runId, tp.topic(), tp.partition(), nextOffset, endOffset);
                partitionStates.put(tp, new PartitionState(tp, nextOffset, endOffset));
            }
        });

        return executeKafkaReplay(runId, consumer, partitionStates, new RunCounters(0L, 0L, 0L, 0L), batchSize, pollTimeout);
    }

    BackfillRunSummary resumeKafkaReplayRun(
            UUID runId,
            Consumer<String, String> consumer,
            int batchSize,
            Duration pollTimeout) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(consumer, "consumer must not be null");
        validateBatchSize(batchSize);
        validatePollTimeout(pollTimeout);

        BackfillRunSummary resumed = transitionFailedToRunning(runId, SOURCE_KAFKA_REPLAY, batchSize);
        Map<TopicPartition, PartitionState> partitionStates = loadPartitionStates(runId);
        RunCounters counters = new RunCounters(
                resumed.lastCursor(),
                resumed.processedCount(),
                resumed.appliedCount(),
                resumed.skippedCount());
        return executeKafkaReplay(runId, consumer, partitionStates, counters, batchSize, pollTimeout);
    }

    public BackfillRunSummary status(UUID runId) {
        Objects.requireNonNull(runId, "runId must not be null");
        return findRun(runId, false);
    }

    private BackfillRunSummary executeFileRun(
            UUID runId,
            Path file,
            RunCounters initialCounters,
            int batchSize) {
        RunCounters counters = initialCounters;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            long lineNumber = 0L;
            List<FileLine> batch = new ArrayList<>(batchSize);
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (lineNumber <= initialCounters.lastCursor() || line.isBlank()) {
                    continue;
                }
                batch.add(new FileLine(lineNumber, line));
                if (batch.size() == batchSize) {
                    counters = commitFileBatch(runId, batch, counters);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                counters = commitFileBatch(runId, batch, counters);
            }
            return markCompleted(runId);
        } catch (IOException exception) {
            markFailedSafely(runId, exception);
            throw new UncheckedIOException("Failed reading backfill NDJSON file", exception);
        } catch (RuntimeException exception) {
            markFailedSafely(runId, exception);
            throw exception;
        }
    }

    private RunCounters commitFileBatch(UUID runId, List<FileLine> batch, RunCounters before) {
        return txTemplate.execute(status -> {
            long appliedDelta = 0L;
            long skippedDelta = 0L;
            long lastLineNumber = before.lastCursor();
            for (FileLine entry : batch) {
                ResolvedEvent event = parseNdjsonLine(entry.rawLine());
                IngestOutcome outcome = ingestor.ingest(event.topic(), event.rawJson());
                if (outcome == IngestOutcome.APPLIED) {
                    appliedDelta++;
                } else {
                    skippedDelta++;
                }
                lastLineNumber = entry.lineNumber();
            }
            RunCounters next = new RunCounters(
                    lastLineNumber,
                    before.processedCount() + batch.size(),
                    before.appliedCount() + appliedDelta,
                    before.skippedCount() + skippedDelta);
            updateCheckpointTotals(runId, next);
            return next;
        });
    }

    private BackfillRunSummary executeKafkaReplay(
            UUID runId,
            Consumer<String, String> consumer,
            Map<TopicPartition, PartitionState> partitionStates,
            RunCounters initialCounters,
            int batchSize,
            Duration pollTimeout) {
        RunCounters counters = initialCounters;
        try {
            List<TopicPartition> active = partitionStates.values().stream()
                    .filter(state -> !state.isCommittedComplete())
                    .map(PartitionState::topicPartition)
                    .toList();
            if (active.isEmpty()) {
                return markCompleted(runId);
            }

            consumer.assign(active);
            for (TopicPartition tp : active) {
                consumer.seek(tp, partitionStates.get(tp).committedNextOffset);
            }

            List<ConsumerRecord<String, String>> batch = new ArrayList<>(batchSize);
            int emptyPolls = 0;

            while (hasRemainingCommittedWork(partitionStates)) {
                ConsumerRecords<String, String> records = consumer.poll(pollTimeout);
                if (records.isEmpty()) {
                    if (!batch.isEmpty() && anyPartitionAtEndPosition(consumer, partitionStates)) {
                        counters = commitKafkaBatch(runId, batch, partitionStates, counters);
                        batch.clear();
                        pauseCompletedPartitions(consumer, partitionStates);
                    }
                    boolean advanced = advanceCompletedPartitionsFromPosition(consumer, partitionStates, batch);
                    if (advanced) {
                        persistPendingPartitionOffsets(partitionStates, runId, batch);
                        pauseCompletedPartitions(consumer, partitionStates);
                    }
                    if (!batch.isEmpty() && (!hasRemainingEffectiveWork(partitionStates) || emptyPolls + 1 >= MAX_CONSECUTIVE_EMPTY_POLLS)) {
                        counters = commitKafkaBatch(runId, batch, partitionStates, counters);
                        batch.clear();
                        pauseCompletedPartitions(consumer, partitionStates);
                    }
                    if (!hasRemainingCommittedWork(partitionStates)) {
                        break;
                    }
                    emptyPolls++;
                    if (emptyPolls >= MAX_CONSECUTIVE_EMPTY_POLLS) {
                        throw new IllegalStateException("Kafka replay stalled before reaching snapshot endOffset");
                    }
                    continue;
                }

                emptyPolls = 0;
                boolean jumpedToEnd = false;

                for (ConsumerRecord<String, String> record : records) {
                    TopicPartition tp = new TopicPartition(record.topic(), record.partition());
                    PartitionState state = partitionStates.get(tp);
                    if (state == null || record.offset() < state.effectiveNextOffset()) {
                        continue;
                    }
                    if (record.offset() >= state.endOffset) {
                        if (state.pendingNextOffset < state.endOffset) {
                            state.pendingNextOffset = state.endOffset;
                            jumpedToEnd = true;
                        }
                        continue;
                    }
                    batch.add(record);
                    state.pendingNextOffset = record.offset() + 1;
                    if (batch.size() >= batchSize) {
                        counters = commitKafkaBatch(runId, batch, partitionStates, counters);
                        batch.clear();
                        pauseCompletedPartitions(consumer, partitionStates);
                    }
                }

                if (!batch.isEmpty() && !hasRemainingEffectiveWork(partitionStates)) {
                    counters = commitKafkaBatch(runId, batch, partitionStates, counters);
                    batch.clear();
                }
                if (jumpedToEnd) {
                    persistPendingPartitionOffsets(partitionStates, runId, batch);
                }
                pauseCompletedPartitions(consumer, partitionStates);
            }

            return markCompleted(runId);
        } catch (RuntimeException exception) {
            markFailedSafely(runId, exception);
            throw exception;
        }
    }

    private RunCounters commitKafkaBatch(
            UUID runId,
            List<ConsumerRecord<String, String>> batch,
            Map<TopicPartition, PartitionState> partitionStates,
            RunCounters before) {
        List<RejectionTag> rejections = new ArrayList<>();
        RunCounters next = txTemplate.execute(status -> {
            long appliedDelta = 0L;
            long skippedDelta = 0L;
            Map<TopicPartition, Long> batchOffsets = new LinkedHashMap<>();

            for (ConsumerRecord<String, String> record : batch) {
                TopicPartition tp = new TopicPartition(record.topic(), record.partition());
                try {
                    IngestOutcome outcome = ingestor.ingest(record.topic(), record.value());
                    if (outcome == IngestOutcome.APPLIED) {
                        appliedDelta++;
                    } else {
                        skippedDelta++;
                    }
                } catch (AnalyticsInvalidEventException exception) {
                    skippedDelta++;
                    String topic = safeTag(exception.topic(), safeTag(record.topic(), "unknown"));
                    String reason = safeTag(exception.reason(), "invalid_event");
                    log.warn("Skipped invalid analytics replay event: topic={} partition={} offset={} eventId={} reason={}",
                            topic, record.partition(), record.offset(), exception.eventId(), reason);
                    rejections.add(new RejectionTag(topic, reason));
                }
                batchOffsets.merge(tp, record.offset() + 1, Math::max);
            }

            for (Map.Entry<TopicPartition, Long> entry : batchOffsets.entrySet()) {
                TopicPartition tp = entry.getKey();
                PartitionState state = partitionStates.get(tp);
                long nextOffset = Math.min(Math.max(entry.getValue(), state.pendingNextOffset), state.endOffset);
                updatePartitionOffset(runId, tp, nextOffset);
            }

            long processed = before.processedCount() + batch.size();
            RunCounters updated = new RunCounters(
                    processed,
                    processed,
                    before.appliedCount() + appliedDelta,
                    before.skippedCount() + skippedDelta);
            updateCheckpointTotals(runId, updated);
            return updated;
        });

        for (ConsumerRecord<String, String> record : batch) {
            TopicPartition tp = new TopicPartition(record.topic(), record.partition());
            PartitionState state = partitionStates.get(tp);
            long candidate = Math.min(Math.max(record.offset() + 1, state.pendingNextOffset), state.endOffset);
            state.committedNextOffset = Math.max(state.committedNextOffset, candidate);
            state.pendingNextOffset = Math.max(state.pendingNextOffset, state.committedNextOffset);
        }
        for (RejectionTag tag : rejections) {
            incrementRejected(tag.topic(), tag.reason());
        }
        return next;
    }

    private void persistPendingPartitionOffsets(
            Map<TopicPartition, PartitionState> partitionStates,
            UUID runId,
            List<ConsumerRecord<String, String>> batch) {
        List<PartitionState> dirty = partitionStates.values().stream()
                .filter(state -> state.pendingNextOffset > state.committedNextOffset)
                .filter(state -> !hasPendingRecordsFor(batch, state.topicPartition))
                .toList();
        if (dirty.isEmpty()) {
            return;
        }
        txTemplate.executeWithoutResult(status -> {
            for (PartitionState state : dirty) {
                updatePartitionOffset(runId, state.topicPartition, state.pendingNextOffset);
            }
        });
        for (PartitionState state : dirty) {
            state.committedNextOffset = state.pendingNextOffset;
        }
    }

    private boolean advanceCompletedPartitionsFromPosition(
            Consumer<String, String> consumer,
            Map<TopicPartition, PartitionState> partitionStates,
            List<ConsumerRecord<String, String>> batch) {
        boolean advanced = false;
        for (PartitionState state : partitionStates.values()) {
            if (state.isEffectiveComplete()) {
                continue;
            }
            long position = consumer.position(state.topicPartition);
            if (position >= state.endOffset && !hasPendingRecordsFor(batch, state.topicPartition)) {
                state.pendingNextOffset = state.endOffset;
                advanced = true;
            }
        }
        return advanced;
    }

    private static boolean anyPartitionAtEndPosition(
            Consumer<String, String> consumer,
            Map<TopicPartition, PartitionState> partitionStates) {
        for (PartitionState state : partitionStates.values()) {
            if (!state.isCommittedComplete() && consumer.position(state.topicPartition) >= state.endOffset) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasPendingRecordsFor(List<ConsumerRecord<String, String>> batch, TopicPartition tp) {
        for (ConsumerRecord<String, String> record : batch) {
            if (record.topic().equals(tp.topic()) && record.partition() == tp.partition()) {
                return true;
            }
        }
        return false;
    }

    private static void pauseCompletedPartitions(
            Consumer<String, String> consumer,
            Map<TopicPartition, PartitionState> partitionStates) {
        List<TopicPartition> completed = new ArrayList<>();
        for (PartitionState state : partitionStates.values()) {
            if (!state.paused && state.isCommittedComplete()) {
                completed.add(state.topicPartition);
                state.paused = true;
            }
        }
        if (!completed.isEmpty()) {
            consumer.pause(completed);
        }
    }

    private static boolean hasRemainingCommittedWork(Map<TopicPartition, PartitionState> partitionStates) {
        for (PartitionState state : partitionStates.values()) {
            if (!state.isCommittedComplete()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasRemainingEffectiveWork(Map<TopicPartition, PartitionState> partitionStates) {
        for (PartitionState state : partitionStates.values()) {
            if (!state.isEffectiveComplete()) {
                return true;
            }
        }
        return false;
    }

    private void insertRun(UUID runId, String sourceType, String sourceRef, int batchSize) {
        jdbc.update("""
                INSERT INTO analytics.backfill_checkpoints (
                    run_id, source_type, source_ref, status,
                    last_cursor, processed_count, applied_count, skipped_count, batch_size
                ) VALUES (?, ?, ?, ?, 0, 0, 0, 0, ?)
                """, runId, sourceType, sourceRef, STATUS_RUNNING, batchSize);
    }

    private BackfillRunSummary transitionFailedToRunning(UUID runId, String expectedSourceType, Integer batchSizeOverride) {
        return txTemplate.execute(status -> {
            BackfillRunSummary existing = findRun(runId, true);
            if (!expectedSourceType.equals(existing.sourceType())) {
                throw new IllegalStateException(
                        "Backfill run " + runId + " has sourceType " + existing.sourceType() + ", expected " + expectedSourceType);
            }
            if (!STATUS_FAILED.equals(existing.status())) {
                throw new IllegalStateException(
                        "Backfill run " + runId + " cannot be resumed in status " + existing.status());
            }
            int effectiveBatchSize = batchSizeOverride != null ? batchSizeOverride : existing.batchSize();
            int updated = jdbc.update("""
                    UPDATE analytics.backfill_checkpoints
                    SET status = ?,
                        batch_size = ?,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE run_id = ? AND status = ?
                    """, STATUS_RUNNING, effectiveBatchSize, runId, STATUS_FAILED);
            if (updated != 1) {
                throw new IllegalStateException("Concurrent state change while resuming backfill run " + runId);
            }
            return findRun(runId, false);
        });
    }

    private void updateCheckpointTotals(UUID runId, RunCounters counters) {
        jdbc.update("""
                UPDATE analytics.backfill_checkpoints
                SET last_cursor = ?,
                    processed_count = ?,
                    applied_count = ?,
                    skipped_count = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE run_id = ?
                """,
                counters.lastCursor(),
                counters.processedCount(),
                counters.appliedCount(),
                counters.skippedCount(),
                runId);
    }

    private void updatePartitionOffset(UUID runId, TopicPartition tp, long nextOffset) {
        jdbc.update("""
                UPDATE analytics.backfill_partition_checkpoints
                SET next_offset = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE run_id = ? AND topic = ? AND partition_id = ?
                """, nextOffset, runId, tp.topic(), tp.partition());
    }

    private BackfillRunSummary markCompleted(UUID runId) {
        return txTemplate.execute(status -> {
            jdbc.update("""
                    UPDATE analytics.backfill_checkpoints
                    SET status = ?,
                        completed_at = COALESCE(completed_at, CURRENT_TIMESTAMP),
                        updated_at = CURRENT_TIMESTAMP
                    WHERE run_id = ? AND status = ?
                    """, STATUS_COMPLETED, runId, STATUS_RUNNING);
            return findRun(runId, false);
        });
    }

    private void markFailedSafely(UUID runId, Exception primaryException) {
        try {
            requiresNewTxTemplate.executeWithoutResult(status -> jdbc.update("""
                    UPDATE analytics.backfill_checkpoints
                    SET status = ?,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE run_id = ? AND status = ?
                    """, STATUS_FAILED, runId, STATUS_RUNNING));
        } catch (RuntimeException secondaryException) {
            primaryException.addSuppressed(secondaryException);
        }
    }

    private BackfillRunSummary findRun(UUID runId, boolean forUpdate) {
        String sql = """
                SELECT run_id, source_type, source_ref, status, last_cursor,
                       processed_count, applied_count, skipped_count, batch_size
                FROM analytics.backfill_checkpoints
                WHERE run_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        return jdbc.query(sql, BackfillService::mapSummary, runId).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Backfill run not found: " + runId));
    }

    private Map<TopicPartition, PartitionState> loadPartitionStates(UUID runId) {
        List<PartitionState> rows = jdbc.query("""
                SELECT topic, partition_id, next_offset, end_offset
                FROM analytics.backfill_partition_checkpoints
                WHERE run_id = ?
                ORDER BY topic, partition_id
                """,
                (rs, rowNum) -> new PartitionState(
                        new TopicPartition(rs.getString("topic"), rs.getInt("partition_id")),
                        rs.getLong("next_offset"),
                        rs.getLong("end_offset")),
                runId);
        Map<TopicPartition, PartitionState> states = new LinkedHashMap<>();
        for (PartitionState state : rows) {
            states.put(state.topicPartition, state);
        }
        return states;
    }

    private static BackfillRunSummary mapSummary(ResultSet rs, int rowNum) throws SQLException {
        return new BackfillRunSummary(
                rs.getObject("run_id", UUID.class),
                rs.getString("source_type"),
                rs.getString("source_ref"),
                rs.getString("status"),
                rs.getLong("last_cursor"),
                rs.getLong("processed_count"),
                rs.getLong("applied_count"),
                rs.getLong("skipped_count"),
                rs.getInt("batch_size"));
    }

    private ResolvedEvent parseNdjsonLine(String rawLine) {
        JsonNode root;
        try {
            root = JSON.readTree(rawLine);
        } catch (JacksonException ignored) {
            throw new AnalyticsInvalidEventException("unknown", "malformed_json");
        }
        if (root == null || !root.isObject()) {
            throw new AnalyticsInvalidEventException("unknown", "malformed_json");
        }
        if (root.has("topic") || root.has("event")) {
            JsonNode topicNode = root.path("topic");
            JsonNode eventNode = root.path("event");
            if (!topicNode.isString() || topicNode.asString().isBlank()) {
                throw new AnalyticsInvalidEventException("unknown", "invalid_wrapper_topic");
            }
            String topic = topicNode.asString().trim();
            if (!eventNode.isObject()) {
                throw new AnalyticsInvalidEventException(topic, "missing_wrapped_event");
            }
            try {
                return new ResolvedEvent(topic, JSON.writeValueAsString(eventNode));
            } catch (JacksonException ignored) {
                throw new AnalyticsInvalidEventException(topic, "malformed_json");
            }
        }
        String topic = AnalyticsEventIngestor.resolveTopicForEnvelope(
                rawLine, scanTopic, reportTopic, reputationTopic);
        return new ResolvedEvent(topic, rawLine);
    }

    private List<TopicPartition> discoverPartitions(Consumer<String, String> consumer, List<String> topics) {
        List<TopicPartition> partitions = new ArrayList<>();
        for (String topic : topics) {
            List<PartitionInfo> infos = consumer.partitionsFor(topic);
            if (infos == null) {
                continue;
            }
            for (PartitionInfo info : infos) {
                partitions.add(new TopicPartition(info.topic(), info.partition()));
            }
        }
        return partitions;
    }

    private List<String> resolveTopics(Collection<String> requestedTopics) {
        if (requestedTopics == null || requestedTopics.isEmpty()) {
            return List.of(scanTopic, reportTopic, reputationTopic);
        }
        Set<String> resolved = new LinkedHashSet<>();
        for (String topic : requestedTopics) {
            resolved.add(requireTopic(topic, "topic"));
        }
        return List.copyOf(resolved);
    }

    private static Consumer<String, String> createEphemeralConsumer(Properties inputProps) {
        return new KafkaConsumer<>(sanitizeConsumerProperties(inputProps));
    }

    static Properties sanitizeConsumerProperties(Properties inputProps) {
        if (inputProps == null) {
            throw new IllegalArgumentException("consumerProps must not be null");
        }
        Properties safeProps = new Properties();
        safeProps.putAll(inputProps);
        Object bootstrap = safeProps.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG);
        if (bootstrap == null || bootstrap.toString().isBlank()) {
            throw new IllegalArgumentException("bootstrap.servers must not be blank");
        }
        // Ephemeral manual-assign consumer must never touch live analytics-service-v1 group offsets.
        safeProps.remove(ConsumerConfig.GROUP_ID_CONFIG);
        safeProps.remove(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG);
        safeProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        safeProps.putIfAbsent(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        safeProps.putIfAbsent(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return safeProps;
    }

    private void incrementRejected(String topic, String reason) {
        if (meterRegistry != null) {
            meterRegistry.counter("analytics.events.rejected", "topic", topic, "reason", reason).increment();
        }
    }

    private static Path validateReadableRegularFile(Path file) {
        if (file == null) {
            throw new IllegalArgumentException("Input file path must not be null");
        }
        Path normalized = file.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(file)
                || Files.isSymbolicLink(normalized)
                || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)
                || !Files.isReadable(normalized)) {
            throw new IllegalArgumentException("Input file must be a readable regular non-symlink file");
        }
        return normalized;
    }

    private static void validateBatchSize(int batchSize) {
        if (batchSize < MIN_BATCH_SIZE || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "batchSize must be between " + MIN_BATCH_SIZE + " and " + MAX_BATCH_SIZE);
        }
    }

    private static void validatePollTimeout(Duration pollTimeout) {
        if (pollTimeout == null || pollTimeout.isNegative() || pollTimeout.isZero()) {
            throw new IllegalArgumentException("pollTimeout must be positive");
        }
    }

    private static String requireTopic(String topic, String label) {
        if (topic == null || topic.isBlank() || topic.length() > 128) {
            throw new IllegalArgumentException(label + " must be non-blank and <= 128 chars");
        }
        return topic.trim();
    }

    private static String truncateSourceRef(String value) {
        if (value.length() <= MAX_SOURCE_REF_LENGTH) {
            return value;
        }
        return value.substring(value.length() - MAX_SOURCE_REF_LENGTH);
    }

    private static String safeTag(String candidate, String fallback) {
        return (candidate == null || candidate.isBlank()) ? fallback : candidate;
    }

    private record FileLine(long lineNumber, String rawLine) {
    }

    private record ResolvedEvent(String topic, String rawJson) {
    }

    private record RunCounters(long lastCursor, long processedCount, long appliedCount, long skippedCount) {
    }

    private record RejectionTag(String topic, String reason) {
    }

    private static final class PartitionState {
        final TopicPartition topicPartition;
        long committedNextOffset;
        long pendingNextOffset;
        final long endOffset;
        boolean paused;

        PartitionState(TopicPartition topicPartition, long nextOffset, long endOffset) {
            this.topicPartition = topicPartition;
            this.committedNextOffset = nextOffset;
            this.pendingNextOffset = nextOffset;
            this.endOffset = endOffset;
            this.paused = nextOffset >= endOffset;
        }

        TopicPartition topicPartition() {
            return topicPartition;
        }

        boolean isCommittedComplete() {
            return committedNextOffset >= endOffset;
        }

        long effectiveNextOffset() {
            return Math.max(committedNextOffset, pendingNextOffset);
        }

        boolean isEffectiveComplete() {
            return effectiveNextOffset() >= endOffset;
        }
    }
}
