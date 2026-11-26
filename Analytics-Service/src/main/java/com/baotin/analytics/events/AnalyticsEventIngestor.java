package com.baotin.analytics.events;

import com.baotin.analytics.application.ModerationStatus;
import com.baotin.analytics.application.RiskLevel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class AnalyticsEventIngestor {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsEventIngestor.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_TOPIC_LENGTH = 128;
    private static final int MAX_EVENT_TYPE_LENGTH = 64;

    private final JdbcTemplate jdbc;
    private final String scanTopic;
    private final String reportTopic;
    private final String reputationTopic;

    public AnalyticsEventIngestor(JdbcTemplate jdbc) {
        this(jdbc, "baotin.scan.events.v1", "baotin.report.events.v1", "baotin.reputation.events.v1");
    }

    @Autowired
    public AnalyticsEventIngestor(
            JdbcTemplate jdbc,
            @Value("${analytics.events.scan-topic:baotin.scan.events.v1}") String scanTopic,
            @Value("${analytics.events.report-topic:baotin.report.events.v1}") String reportTopic,
            @Value("${analytics.events.reputation-topic:baotin.reputation.events.v1}") String reputationTopic) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.scanTopic = requireTopicConfig(scanTopic, "scanTopic");
        this.reportTopic = requireTopicConfig(reportTopic, "reportTopic");
        this.reputationTopic = requireTopicConfig(reputationTopic, "reputationTopic");
    }

    public static String resolveTopicForEnvelope(
            String rawJson,
            String scanTopic,
            String reportTopic,
            String reputationTopic) {
        requireTopicConfig(scanTopic, "scanTopic");
        requireTopicConfig(reportTopic, "reportTopic");
        requireTopicConfig(reputationTopic, "reputationTopic");

        JsonNode root = parseJsonObject(rawJson, "unknown");
        UUID maybeEventId = tryParseUuid(root.path("eventId"));
        String eventType = requireText(root, "eventType", "unknown", maybeEventId, "missing_event_type");
        if (eventType.startsWith("scan.") || eventType.startsWith("target.")) {
            return scanTopic;
        }
        if (eventType.startsWith("report.")) {
            return reportTopic;
        }
        if (eventType.startsWith("reputation.")) {
            return reputationTopic;
        }
        throw new AnalyticsInvalidEventException("unknown", "unrecognized_event_type", maybeEventId);
    }

    @Transactional(noRollbackFor = AnalyticsInvalidEventException.class)
    public IngestOutcome ingest(String topic, String rawJson) {
        if (topic == null || topic.isBlank() || topic.length() > MAX_TOPIC_LENGTH) {
            throw new AnalyticsInvalidEventException("unknown", "invalid_topic");
        }
        if (!topic.equals(scanTopic) && !topic.equals(reportTopic) && !topic.equals(reputationTopic)) {
            throw new AnalyticsInvalidEventException(topic, "unknown_topic");
        }

        JsonNode root = parseJsonObject(rawJson, topic);
        Envelope envelope = parseEnvelope(topic, root);
        ValidatedPayload validated = validateKnownPayload(topic, envelope);

        String payloadHash = sha256Hex(canonicalBytes(root));
        int inserted = jdbc.update("""
                INSERT INTO analytics.inbox_events
                    (event_id, topic, event_type, aggregate_id, occurred_at, payload_hash)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                envelope.eventId(),
                topic,
                envelope.eventType(),
                envelope.aggregateId(),
                OffsetDateTime.ofInstant(envelope.occurredAt(), ZoneOffset.UTC),
                payloadHash);
        if (inserted == 0) {
            String existingHash = jdbc.query(
                    "SELECT payload_hash FROM analytics.inbox_events WHERE event_id = ?",
                    (rs, rowNum) -> rs.getString(1),
                    envelope.eventId()).stream().findFirst().orElse(null);
            if (payloadHash.equals(existingHash)) {
                return IngestOutcome.DUPLICATE;
            }
            log.warn("Analytics event conflict ignored: topic={} eventId={} reason=payload_hash_mismatch",
                    topic, envelope.eventId());
            return IngestOutcome.CONFLICT_IGNORED;
        }

        return switch (validated) {
            case ScanCompleted scan -> applyScanCompleted(envelope.occurredAt(), scan);
            case TargetLifecycle target -> applyTargetLifecycle(envelope.occurredAt(), target);
            case ReportLifecycle report -> applyReportLifecycle(envelope, report);
            case ReputationScoreChanged rep -> applyReputationChange(rep);
            case UnknownType ignored -> {
                log.debug("Ignoring unknown event type on subscribed topic: topic={} eventType={} eventId={}",
                        topic, envelope.eventType(), envelope.eventId());
                yield IngestOutcome.IGNORED_EVENT_TYPE;
            }
        };
    }

    private ValidatedPayload validateKnownPayload(String topic, Envelope envelope) {
        JsonNode p = envelope.payload();
        UUID eventId = envelope.eventId();
        String eventType = envelope.eventType();
        if (topic.equals(scanTopic)) {
            return switch (eventType) {
                case "scan.completed" -> parseScanCompleted(topic, eventId, p);
                case "target.created" -> new TargetLifecycle(
                        parseUuid(p, "targetId", topic, eventId, "invalid_target_payload"), true);
                case "target.updated" -> new TargetLifecycle(
                        parseUuid(p, "targetId", topic, eventId, "invalid_target_payload"), false);
                default -> new UnknownType();
            };
        }
        if (topic.equals(reportTopic)) {
            return switch (eventType) {
                case "report.submitted", "report.verified", "report.rejected", "report.need_more_info" ->
                        parseReportLifecycle(topic, eventId, p);
                default -> new UnknownType();
            };
        }
        if ("reputation.score.changed".equals(eventType)) {
            return parseReputationChange(topic, eventId, envelope.occurredAt(), p);
        }
        return new UnknownType();
    }

    private IngestOutcome applyScanCompleted(Instant occurredAt, ScanCompleted scan) {
        LocalDate metricDate = utcDate(occurredAt);
        int inserted = jdbc.update(
                "INSERT INTO analytics.processed_scans (scan_id, metric_date) VALUES (?, ?) ON CONFLICT DO NOTHING",
                scan.scanId(),
                metricDate);
        if (inserted == 0) {
            return IngestOutcome.DUPLICATE;
        }
        addDailyMetrics(metricDate, DailyDelta.ofScan(scan.scanType(), scan.riskLevel()));
        return IngestOutcome.APPLIED;
    }

    private IngestOutcome applyTargetLifecycle(Instant occurredAt, TargetLifecycle target) {
        if (!target.created()) {
            return IngestOutcome.APPLIED;
        }
        LocalDate createdDate = utcDate(occurredAt);
        int inserted = jdbc.update("""
                INSERT INTO analytics.known_targets (target_id, created_date, created_at)
                VALUES (?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                target.targetId(),
                createdDate,
                OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC));
        if (inserted == 0) {
            return IngestOutcome.DUPLICATE;
        }
        addDailyMetrics(createdDate, DailyDelta.newThreat());
        return IngestOutcome.APPLIED;
    }

    private IngestOutcome applyReportLifecycle(Envelope envelope, ReportLifecycle report) {
        int inserted = jdbc.update("""
                INSERT INTO analytics.report_lifecycle_state (
                    report_id, target_id, last_version, last_status, last_event_at,
                    created_recorded, verified_recorded, rejected_recorded, need_more_info_recorded
                ) VALUES (?, ?, ?, ?, ?, FALSE, FALSE, FALSE, FALSE)
                ON CONFLICT DO NOTHING
                """,
                report.reportId(),
                report.targetId(),
                report.version(),
                report.status().name(),
                OffsetDateTime.ofInstant(envelope.occurredAt(), ZoneOffset.UTC));

        ReportState state = jdbc.queryForObject("""
                SELECT target_id, last_version, last_status, last_event_at,
                       created_recorded, created_date,
                       verified_recorded, verified_date,
                       rejected_recorded, rejected_date,
                       need_more_info_recorded, need_more_info_date
                FROM analytics.report_lifecycle_state
                WHERE report_id = ?
                FOR UPDATE
                """, (rs, rowNum) -> new ReportState(
                rs.getObject("target_id", UUID.class),
                rs.getInt("last_version"),
                rs.getString("last_status"),
                rs.getObject("last_event_at", OffsetDateTime.class).toInstant(),
                rs.getBoolean("created_recorded"),
                rs.getObject("created_date", LocalDate.class),
                rs.getBoolean("verified_recorded"),
                rs.getObject("verified_date", LocalDate.class),
                rs.getBoolean("rejected_recorded"),
                rs.getObject("rejected_date", LocalDate.class),
                rs.getBoolean("need_more_info_recorded"),
                rs.getObject("need_more_info_date", LocalDate.class)),
                report.reportId());

        boolean advanceState = report.version() > state.lastVersion()
                || (report.version() == state.lastVersion() && envelope.occurredAt().isAfter(state.lastEventAt()));
        UUID nextTargetId = state.targetId() != null ? state.targetId() : report.targetId();
        boolean targetBackfilled = state.targetId() == null && nextTargetId != null;

        LocalDate metricDate = utcDate(envelope.occurredAt());
        boolean createdRecorded = state.createdRecorded();
        LocalDate createdDate = state.createdDate();
        boolean verifiedRecorded = state.verifiedRecorded();
        LocalDate verifiedDate = state.verifiedDate();
        boolean rejectedRecorded = state.rejectedRecorded();
        LocalDate rejectedDate = state.rejectedDate();
        boolean needMoreInfoRecorded = state.needMoreInfoRecorded();
        LocalDate needMoreInfoDate = state.needMoreInfoDate();

        DailyDelta milestoneDelta = null;
        switch (envelope.eventType()) {
            case "report.submitted" -> {
                if (!createdRecorded) {
                    createdRecorded = true;
                    createdDate = metricDate;
                    milestoneDelta = DailyDelta.createdReport();
                }
            }
            case "report.verified" -> {
                if (!verifiedRecorded) {
                    verifiedRecorded = true;
                    verifiedDate = metricDate;
                    milestoneDelta = DailyDelta.verifiedReport();
                }
            }
            case "report.rejected" -> {
                if (!rejectedRecorded) {
                    rejectedRecorded = true;
                    rejectedDate = metricDate;
                    milestoneDelta = DailyDelta.rejectedReport();
                }
            }
            case "report.need_more_info" -> {
                if (!needMoreInfoRecorded) {
                    needMoreInfoRecorded = true;
                    needMoreInfoDate = metricDate;
                    milestoneDelta = DailyDelta.needMoreInfoReport();
                }
            }
            default -> {
            }
        }

        if (milestoneDelta != null) {
            addDailyMetrics(metricDate, milestoneDelta);
        }

        boolean milestoneRecorded = milestoneDelta != null;
        if (advanceState || milestoneRecorded || targetBackfilled) {
            jdbc.update("""
                    UPDATE analytics.report_lifecycle_state
                    SET target_id = ?,
                        last_version = ?,
                        last_status = ?,
                        last_event_at = ?,
                        created_recorded = ?,
                        created_date = ?,
                        verified_recorded = ?,
                        verified_date = ?,
                        rejected_recorded = ?,
                        rejected_date = ?,
                        need_more_info_recorded = ?,
                        need_more_info_date = ?,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE report_id = ?
                    """,
                    nextTargetId,
                    advanceState ? report.version() : state.lastVersion(),
                    advanceState ? report.status().name() : state.lastStatus(),
                    OffsetDateTime.ofInstant(
                            advanceState ? envelope.occurredAt() : state.lastEventAt(),
                            ZoneOffset.UTC),
                    createdRecorded,
                    createdDate,
                    verifiedRecorded,
                    verifiedDate,
                    rejectedRecorded,
                    rejectedDate,
                    needMoreInfoRecorded,
                    needMoreInfoDate,
                    report.reportId());
        }

        return (inserted == 1 || advanceState || milestoneRecorded || targetBackfilled)
                ? IngestOutcome.APPLIED
                : IngestOutcome.OUT_OF_ORDER_IGNORED;
    }

    private IngestOutcome applyReputationChange(ReputationScoreChanged rep) {
        OffsetDateTime effectiveAt = OffsetDateTime.ofInstant(rep.changedAt(), ZoneOffset.UTC);
        int inserted = jdbc.update("""
                INSERT INTO analytics.target_reputation_view (target_id, risk_level, last_event_at)
                VALUES (?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                rep.threatTargetId(),
                rep.newLevel().name(),
                effectiveAt);
        if (inserted == 1) {
            return IngestOutcome.APPLIED;
        }

        Instant existingAt = jdbc.queryForObject("""
                SELECT last_event_at
                FROM analytics.target_reputation_view
                WHERE target_id = ?
                FOR UPDATE
                """,
                (rs, rowNum) -> rs.getObject("last_event_at", OffsetDateTime.class).toInstant(),
                rep.threatTargetId());
        if (rep.changedAt().isAfter(existingAt)) {
            jdbc.update("""
                    UPDATE analytics.target_reputation_view
                    SET risk_level = ?,
                        last_event_at = ?,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE target_id = ?
                    """,
                    rep.newLevel().name(),
                    effectiveAt,
                    rep.threatTargetId());
            return IngestOutcome.APPLIED;
        }
        return IngestOutcome.OUT_OF_ORDER_IGNORED;
    }

    private void addDailyMetrics(LocalDate metricDate, DailyDelta delta) {
        jdbc.update(
                "INSERT INTO analytics.daily_metrics (metric_date) VALUES (?) ON CONFLICT DO NOTHING",
                metricDate);
        jdbc.queryForObject(
                "SELECT metric_date FROM analytics.daily_metrics WHERE metric_date = ? FOR UPDATE",
                LocalDate.class,
                metricDate);
        jdbc.update("""
                UPDATE analytics.daily_metrics
                SET total_scans = total_scans + ?,
                    sms_scans = sms_scans + ?,
                    url_scans = url_scans + ?,
                    high_risk_scans = high_risk_scans + ?,
                    risk_low_scans = risk_low_scans + ?,
                    risk_medium_scans = risk_medium_scans + ?,
                    risk_high_scans = risk_high_scans + ?,
                    risk_critical_scans = risk_critical_scans + ?,
                    reports_created = reports_created + ?,
                    reports_verified = reports_verified + ?,
                    reports_rejected = reports_rejected + ?,
                    reports_need_more_info = reports_need_more_info + ?,
                    new_threats = new_threats + ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE metric_date = ?
                """,
                delta.totalScans(),
                delta.textScans(),
                delta.urlScans(),
                delta.highRiskScans(),
                delta.riskLowScans(),
                delta.riskMediumScans(),
                delta.riskHighScans(),
                delta.riskCriticalScans(),
                delta.reportsCreated(),
                delta.reportsVerified(),
                delta.reportsRejected(),
                delta.reportsNeedMoreInfo(),
                delta.newThreats(),
                metricDate);
    }

    private static Envelope parseEnvelope(String topic, JsonNode root) {
        UUID eventId = parseUuid(root, "eventId", topic, null, "invalid_event_id");
        String eventType = requireText(root, "eventType", topic, eventId, "invalid_event_type");
        if (eventType.length() > MAX_EVENT_TYPE_LENGTH) {
            throw new AnalyticsInvalidEventException(topic, "invalid_event_type", eventId);
        }
        JsonNode versionNode = root.path("eventVersion");
        if (!versionNode.isIntegralNumber() || !versionNode.canConvertToInt() || versionNode.asInt() != 1) {
            throw new AnalyticsInvalidEventException(topic, "invalid_event_version", eventId);
        }
        Instant occurredAt = parseInstant(root, "occurredAt", topic, eventId, "invalid_occurred_at");
        UUID aggregateId = parseUuid(root, "aggregateId", topic, eventId, "invalid_aggregate_id");
        JsonNode payload = root.path("payload");
        if (!payload.isObject()) {
            throw new AnalyticsInvalidEventException(topic, "missing_payload", eventId);
        }
        return new Envelope(eventId, eventType, occurredAt, aggregateId, payload);
    }

    private static ScanCompleted parseScanCompleted(String topic, UUID eventId, JsonNode payload) {
        UUID scanId = parseUuid(payload, "scanId", topic, eventId, "invalid_scan_payload");
        String scanType = requireText(payload, "scanType", topic, eventId, "invalid_scan_payload");
        if (!"TEXT".equals(scanType) && !"URL".equals(scanType)) {
            throw new AnalyticsInvalidEventException(topic, "invalid_scan_payload", eventId);
        }
        RiskLevel riskLevel = parseEnum(
                payload, "riskLevel", RiskLevel.class, topic, eventId, "invalid_scan_payload");
        return new ScanCompleted(scanId, scanType, riskLevel);
    }

    private static ReportLifecycle parseReportLifecycle(String topic, UUID eventId, JsonNode payload) {
        UUID reportId = parseUuid(payload, "reportId", topic, eventId, "invalid_report_payload");
        JsonNode targetNode = payload.path("targetId");
        UUID targetId = (targetNode.isMissingNode() || targetNode.isNull())
                ? null
                : parseUuid(payload, "targetId", topic, eventId, "invalid_report_payload");
        ModerationStatus status = parseEnum(
                payload, "status", ModerationStatus.class, topic, eventId, "invalid_report_payload");
        JsonNode versionNode = payload.path("version");
        if (!versionNode.isIntegralNumber() || !versionNode.canConvertToInt() || versionNode.asInt() < 0) {
            throw new AnalyticsInvalidEventException(topic, "invalid_report_payload", eventId);
        }
        return new ReportLifecycle(reportId, targetId, status, versionNode.asInt());
    }

    private static ReputationScoreChanged parseReputationChange(
            String topic,
            UUID eventId,
            Instant envelopeOccurredAt,
            JsonNode payload) {
        UUID threatTargetId = parseUuid(payload, "threatTargetId", topic, eventId, "invalid_reputation_payload");
        RiskLevel newLevel = parseEnum(
                payload, "newLevel", RiskLevel.class, topic, eventId, "invalid_reputation_payload");
        JsonNode changedAtNode = payload.path("changedAt");
        Instant changedAt = (changedAtNode.isMissingNode() || changedAtNode.isNull())
                ? envelopeOccurredAt
                : parseInstant(payload, "changedAt", topic, eventId, "invalid_reputation_payload");
        return new ReputationScoreChanged(threatTargetId, newLevel, changedAt);
    }

    private static JsonNode parseJsonObject(String rawJson, String topic) {
        if (rawJson == null || rawJson.isBlank()) {
            throw new AnalyticsInvalidEventException(topic, "blank_payload");
        }
        try {
            JsonNode root = JSON.readTree(rawJson);
            if (root == null || !root.isObject()) {
                throw new AnalyticsInvalidEventException(topic, "malformed_json");
            }
            return root;
        } catch (JacksonException ignored) {
            throw new AnalyticsInvalidEventException(topic, "malformed_json");
        }
    }

    private static String requireText(
            JsonNode node,
            String field,
            String topic,
            UUID eventId,
            String reason) {
        JsonNode value = node.path(field);
        if (!value.isString()) {
            throw new AnalyticsInvalidEventException(topic, reason, eventId);
        }
        String text = value.asString();
        if (text.isBlank()) {
            throw new AnalyticsInvalidEventException(topic, reason, eventId);
        }
        return text;
    }

    private static UUID parseUuid(
            JsonNode node,
            String field,
            String topic,
            UUID eventId,
            String reason) {
        String text = requireText(node, field, topic, eventId, reason);
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException ignored) {
            throw new AnalyticsInvalidEventException(topic, reason, eventId);
        }
    }

    private static UUID tryParseUuid(JsonNode node) {
        if (!node.isString()) {
            return null;
        }
        try {
            return UUID.fromString(node.asString());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static Instant parseInstant(
            JsonNode node,
            String field,
            String topic,
            UUID eventId,
            String reason) {
        String text = requireText(node, field, topic, eventId, reason);
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException ignored) {
            throw new AnalyticsInvalidEventException(topic, reason, eventId);
        }
    }

    private static <E extends Enum<E>> E parseEnum(
            JsonNode node,
            String field,
            Class<E> enumType,
            String topic,
            UUID eventId,
            String reason) {
        String text = requireText(node, field, topic, eventId, reason);
        try {
            return Enum.valueOf(enumType, text);
        } catch (IllegalArgumentException ignored) {
            throw new AnalyticsInvalidEventException(topic, reason, eventId);
        }
    }

    private static LocalDate utcDate(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC).toLocalDate();
    }

    private static byte[] canonicalBytes(JsonNode root) {
        try {
            return JSON.writeValueAsBytes(canonicalize(root));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Failed to canonicalize validated JSON tree", exception);
        }
    }

    private static JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = JSON.createObjectNode();
            List<String> keys = new ArrayList<>(node.propertyNames());
            Collections.sort(keys);
            for (String key : keys) {
                sorted.set(key, canonicalize(node.get(key)));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = JSON.createArrayNode();
            for (JsonNode element : node) {
                array.add(canonicalize(element));
            }
            return array;
        }
        return node;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String requireTopicConfig(String topic, String name) {
        if (topic == null || topic.isBlank() || topic.length() > MAX_TOPIC_LENGTH) {
            throw new IllegalArgumentException(name + " must be a non-blank topic within " + MAX_TOPIC_LENGTH + " chars");
        }
        return topic.trim();
    }

    private record Envelope(UUID eventId, String eventType, Instant occurredAt, UUID aggregateId, JsonNode payload) {
    }

    private sealed interface ValidatedPayload
            permits ScanCompleted, TargetLifecycle, ReportLifecycle, ReputationScoreChanged, UnknownType {
    }

    private record ScanCompleted(UUID scanId, String scanType, RiskLevel riskLevel) implements ValidatedPayload {
    }

    private record TargetLifecycle(UUID targetId, boolean created) implements ValidatedPayload {
    }

    private record ReportLifecycle(UUID reportId, UUID targetId, ModerationStatus status, int version)
            implements ValidatedPayload {
    }

    private record ReputationScoreChanged(UUID threatTargetId, RiskLevel newLevel, Instant changedAt)
            implements ValidatedPayload {
    }

    private record UnknownType() implements ValidatedPayload {
    }

    private record ReportState(
            UUID targetId,
            int lastVersion,
            String lastStatus,
            Instant lastEventAt,
            boolean createdRecorded,
            LocalDate createdDate,
            boolean verifiedRecorded,
            LocalDate verifiedDate,
            boolean rejectedRecorded,
            LocalDate rejectedDate,
            boolean needMoreInfoRecorded,
            LocalDate needMoreInfoDate) {
    }

    private record DailyDelta(
            long totalScans,
            long textScans,
            long urlScans,
            long highRiskScans,
            long riskLowScans,
            long riskMediumScans,
            long riskHighScans,
            long riskCriticalScans,
            long reportsCreated,
            long reportsVerified,
            long reportsRejected,
            long reportsNeedMoreInfo,
            long newThreats) {

        static DailyDelta ofScan(String scanType, RiskLevel riskLevel) {
            boolean highRisk = riskLevel == RiskLevel.HIGH || riskLevel == RiskLevel.CRITICAL;
            return new DailyDelta(
                    1L,
                    "TEXT".equals(scanType) ? 1L : 0L,
                    "URL".equals(scanType) ? 1L : 0L,
                    highRisk ? 1L : 0L,
                    riskLevel == RiskLevel.LOW ? 1L : 0L,
                    riskLevel == RiskLevel.MEDIUM ? 1L : 0L,
                    riskLevel == RiskLevel.HIGH ? 1L : 0L,
                    riskLevel == RiskLevel.CRITICAL ? 1L : 0L,
                    0L, 0L, 0L, 0L, 0L);
        }

        static DailyDelta newThreat() {
            return new DailyDelta(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 1L);
        }

        static DailyDelta createdReport() {
            return new DailyDelta(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 1L, 0L, 0L, 0L, 0L);
        }

        static DailyDelta verifiedReport() {
            return new DailyDelta(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 1L, 0L, 0L, 0L);
        }

        static DailyDelta rejectedReport() {
            return new DailyDelta(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 1L, 0L, 0L);
        }

        static DailyDelta needMoreInfoReport() {
            return new DailyDelta(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 1L, 0L);
        }
    }
}
