package com.baotin.notification.application;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Transactional event consumer body. Inbox insert, watches updates, notification
 * creation and outbox writes commit together; a retry is a no-op after commit.
 */
@Service
public class EventIngestor {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final WatchlistService watchlist;
    private final NotificationStore notifications;
    private final NotificationService notifier;

    public EventIngestor(JdbcTemplate jdbc, JsonMapper json, WatchlistService watchlist,
                         NotificationStore notifications, NotificationService notifier) {
        this.jdbc = jdbc;
        this.json = json;
        this.watchlist = watchlist;
        this.notifications = notifications;
        this.notifier = notifier;
    }

    @Transactional
    public void ingest(String raw) {
        JsonNode envelope = parse(raw);
        UUID eventId = uuid(text(envelope, "eventId"), "eventId");
        String eventType = text(envelope, "eventType");
        if (eventType == null || eventType.isBlank()) throw new IllegalArgumentException("eventType is required");
        JsonNode payload = envelope.path("payload");
        if (payload.isMissingNode() || payload.isNull()) payload = json.createObjectNode();

        int inserted = jdbc.update(
            "INSERT INTO notification.inbox_events(event_id,event_type) VALUES (?,?) ON CONFLICT (event_id) DO NOTHING",
            eventId, truncate(eventType, 150));
        if (inserted == 0) return;

        switch (eventType) {
            case "reputation.updated", "reputation.score.changed" -> reputation(payload);
            case "report.verified" -> report(payload, "VERIFIED");
            case "report.rejected" -> report(payload, "REJECTED");
            case "report.need_more_info" -> report(payload, "NEED_MORE_INFO");
            case "report.status_changed" -> report(payload, text(payload, "status"));
            default -> {
                // Forward-compatible: unknown internal events are acknowledged and ignored.
            }
        }
        jdbc.update("UPDATE notification.inbox_events SET processed_at=now() WHERE event_id=?", eventId);
    }

    private void reputation(JsonNode payload) {
        UUID targetId = optionalUuid(text(payload, "threatTargetId", "targetId", "urlTargetId"));
        String canonicalUrl = text(payload, "canonicalUrl", "url");
        BigDecimal score = decimal(payload, "newScore", "score", "riskScore");
        String level = text(payload, "newLevel", "riskLevel", "level");
        String label = text(payload, "reason", "riskLabel", "label");
        watchlist.applyReputation(targetId, canonicalUrl, score, level, label);
    }

    private void report(JsonNode payload, String status) {
        if (status == null || status.isBlank()) return;
        UUID userId = optionalUuid(text(payload, "reporterUserId", "userId"));
        if (userId == null) return;
        if (!notifications.preferences(userId).reportStatusEnabled()) return;
        UUID reportId = optionalUuid(text(payload, "reportId", "aggregateId"));
        String reportTitle = text(payload, "title", "reportTitle");
        String note = text(payload, "moderatorNote", "note");
        String normalized = status.trim().toUpperCase(java.util.Locale.ROOT);

        String title;
        String body;
        switch (normalized) {
            case "VERIFIED" -> {
                title = "Báo cáo đã được xác minh";
                body = "Báo cáo \"" + safe(reportTitle) + "\" đã được xác minh.";
            }
            case "REJECTED" -> {
                title = "Báo cáo đã bị từ chối";
                body = "Báo cáo \"" + safe(reportTitle) + "\" đã bị từ chối.";
            }
            case "NEED_MORE_INFO" -> {
                title = "Cần bổ sung thông tin";
                body = "Báo cáo \"" + safe(reportTitle) + "\" cần thêm thông tin để tiếp tục xử lý.";
            }
            default -> {
                return;
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        if (reportId != null) data.put("reportId", reportId.toString());
        data.put("status", normalized);
        if (note != null) data.put("moderatorNote", note);
        if (reportTitle != null) data.put("reportTitle", reportTitle);
        if (note != null && !note.isBlank() && !"REJECTED".equals(normalized)) {
            body = body + " Ghi chú: " + note;
        }
        notifier.create(userId, "REPORT_STATUS_CHANGED", title, truncate(body, 1000), data, true);
    }

    private JsonNode parse(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("Empty event");
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("Event is not valid JSON", e);
        }
    }

    private static String text(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && !value.isNull()) {
                String text = value.asText();
                if (text != null && !text.isBlank()) return text;
            }
        }
        return null;
    }

    private static BigDecimal decimal(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isNumber()) return value.decimalValue();
            if (value.isTextual()) {
                try {
                    return new BigDecimal(value.asText());
                } catch (NumberFormatException ignored) {
                    // try next field
                }
            }
        }
        return null;
    }

    private static UUID uuid(String value, String field) {
        if (value == null) throw new IllegalArgumentException(field + " is required");
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + " is not a UUID");
        }
    }

    private static UUID optionalUuid(String value) {
        if (value == null) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "không có tiêu đề" : value;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
