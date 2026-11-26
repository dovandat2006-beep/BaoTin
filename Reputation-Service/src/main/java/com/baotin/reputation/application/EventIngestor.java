package com.baotin.reputation.application;

import com.baotin.reputation.domain.ReportContributionStatus;
import com.baotin.reputation.domain.SignalOverrides;
import com.baotin.reputation.domain.TargetType;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class EventIngestor {
    private static final Logger log = LoggerFactory.getLogger(EventIngestor.class);
    private static final BigDecimal MAX_SIGNAL = new BigDecimal("100.00");
    private static final Set<String> SUPPORTED_EVENTS = Set.of(
        "report.verified", "report.rejected", "report.revoked", "threat.updated", "campaign.updated");

    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final ReputationStore store;
    private final ReputationService service;

    public EventIngestor(JdbcTemplate jdbc, JsonMapper json, ReputationStore store,
                         ReputationService service) {
        this.jdbc = jdbc;
        this.json = json;
        this.store = store;
        this.service = service;
    }

    @Transactional
    public void ingest(String raw) {
        JsonNode root;
        try {
            root = json.readTree(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid event JSON", e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Event must be a JSON object");
        }

        UUID eventId = uuid(root, "eventId");
        String eventType = text(root, "eventType");
        if (!SUPPORTED_EVENTS.contains(eventType)) {
            log.debug("Ignoring unsupported event type {}", eventType);
            return;
        }
        JsonNode versionNode = root.get("eventVersion");
        if (versionNode == null || !versionNode.isIntegralNumber() || versionNode.asInt() != 1) {
            throw new IllegalArgumentException("Unsupported eventVersion "
                + (versionNode == null ? "missing" : versionNode.asText()));
        }
        Instant occurredAt = instant(root, "occurredAt");
        String correlationId = optionalText(root, "correlationId");
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = eventId.toString();
        }
        JsonNode payload = root.path("payload");
        if (!payload.isObject()) {
            throw new IllegalArgumentException("Event payload must be an object");
        }

        int inserted = jdbc.update("""
            INSERT INTO reputation.inbox_events(event_id,event_type,payload_hash)
            VALUES (?,?,?)
            ON CONFLICT (event_id) DO NOTHING
            """, eventId, eventType, sha256(raw));
        if (inserted == 0) {
            log.debug("Duplicate event {} ignored", eventId);
            return;
        }

        switch (eventType) {
            case "report.verified" -> report(payload, ReportContributionStatus.VERIFIED, eventType,
                eventId, correlationId, occurredAt);
            case "report.rejected", "report.revoked" -> report(payload, ReportContributionStatus.REVOKED,
                eventType, eventId, correlationId, occurredAt);
            case "threat.updated" -> threat(payload, eventType, eventId, correlationId, occurredAt);
            case "campaign.updated" -> campaign(payload, eventType, eventId, correlationId, occurredAt);
            default -> log.debug("Ignoring unsupported event type {}", eventType);
        }

        jdbc.update("UPDATE reputation.inbox_events SET processed_at=now() WHERE event_id=?", eventId);
    }

    private void report(JsonNode payload, ReportContributionStatus status, String eventType,
                        UUID eventId, String correlationId, Instant occurredAt) {
        UUID reportId = uuid(payload, "reportId");
        boolean canonical = payload.has("targetId");
        UUID targetId = uuid(payload, canonical ? "targetId" : "threatTargetId");
        TargetType targetType;
        BigDecimal severity;
        if (canonical) {
            String type = text(payload, "targetType");
            if (!Set.of("URL", "MESSAGE_TEMPLATE").contains(type))
                throw new IllegalArgumentException("Unsupported canonical report targetType");
            String expectedStatus = eventType.equals("report.verified") ? "VERIFIED" : "REJECTED";
            if (!text(payload, "status").equals(expectedStatus) || eventType.equals("report.revoked"))
                throw new IllegalArgumentException("Report status does not match eventType");
            uuid(payload, "reporterUserId");
            JsonNode version = payload.get("version");
            if (version == null || !version.isIntegralNumber() || !version.canConvertToLong() || version.asLong() < 0)
                throw new IllegalArgumentException("Invalid report version");
            targetType = type.equals("MESSAGE_TEMPLATE") ? TargetType.OTHER : TargetType.URL;
            // Binary moderator verification policy v1; legacy severity remains unchanged.
            severity = MAX_SIGNAL;
        } else {
            targetType = targetType(payload);
            severity = decimal(payload, "severity", true);
        }

        boolean applied = store.upsertReportContribution(reportId, targetId, severity, status,
            occurredAt, occurredAt);
        if (!applied) {
            log.debug("Stale report event {} for report {} ignored", eventId, reportId);
            return;
        }
        service.recalculateFromEvent(targetId, targetType, SignalOverrides.empty(), eventType,
            correlationId, eventId.toString(), occurredAt);
    }

    private void threat(JsonNode payload, String eventType, UUID eventId, String correlationId,
                        Instant occurredAt) {
        UUID targetId = uuid(payload, "threatTargetId");
        TargetType targetType = targetType(payload);
        BigDecimal ruleSignal = decimal(payload, "ruleSignal", false);
        BigDecimal aiSignal = decimal(payload, "aiSignal", false);
        if (ruleSignal == null && aiSignal == null) {
            throw new IllegalArgumentException("threat.updated requires ruleSignal or aiSignal");
        }
        service.recalculateFromEvent(targetId, targetType,
            new SignalOverrides(null, ruleSignal, aiSignal, null, null, null), eventType,
            correlationId, eventId.toString(), occurredAt);
    }

    private void campaign(JsonNode payload, String eventType, UUID eventId, String correlationId,
                          Instant occurredAt) {
        UUID targetId = uuid(payload, "threatTargetId");
        TargetType targetType = targetType(payload);
        BigDecimal campaignSignal = decimal(payload, "campaignSignal", true);
        service.recalculateFromEvent(targetId, targetType,
            new SignalOverrides(null, null, null, campaignSignal, null, null), eventType,
            correlationId, eventId.toString(), occurredAt);
    }

    private static TargetType targetType(JsonNode payload) {
        String value = text(payload, "targetType");
        try {
            return TargetType.parse(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported targetType", e);
        }
    }

    private static UUID uuid(JsonNode node, String field) {
        String value = text(node, field);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid UUID in " + field, e);
        }
    }

    private static BigDecimal decimal(JsonNode node, String field, boolean required) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            if (required) {
                throw new IllegalArgumentException("Missing " + field);
            }
            return null;
        }
        if (!value.isNumber()) {
            throw new IllegalArgumentException(field + " must be a number");
        }
        BigDecimal decimal = value.decimalValue();
        if (decimal.signum() < 0 || decimal.compareTo(MAX_SIGNAL) > 0) {
            throw new IllegalArgumentException(field + " must be between 0 and 100");
        }
        return decimal;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Missing " + field);
        }
        return value.asText();
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()) {
            return null;
        }
        return value.asText();
    }

    private static Instant instant(JsonNode node, String field) {
        String value = text(node, field);
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(value).toInstant();
            } catch (DateTimeParseException ex) {
                throw new IllegalArgumentException("Invalid date-time in " + field, ex);
            }
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
