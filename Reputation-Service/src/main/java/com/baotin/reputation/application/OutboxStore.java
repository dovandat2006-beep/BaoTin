package com.baotin.reputation.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class OutboxStore {
    public record Row(UUID id, UUID leaseId, UUID aggregateId, String topic, String messageKey,
                      String payload, int attempts) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public OutboxStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void enqueue(UUID eventId, String aggregateType, UUID aggregateId, String eventType,
                        int eventVersion, String topic, String messageKey, String payload,
                        java.time.Instant occurredAt) {
        jdbc.update("""
            INSERT INTO reputation.outbox_events
                (id,aggregate_type,aggregate_id,event_type,event_version,topic,message_key,payload,occurred_at)
            VALUES (?,?,?,?,?,?,?,?::jsonb,?)
            """, eventId, aggregateType, aggregateId, eventType, eventVersion, topic, messageKey,
            payload, Timestamp.from(occurredAt));
    }

    @Transactional
    public List<Row> claim(int batch) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT * FROM reputation.outbox_events
            WHERE status IN ('PENDING','PROCESSING') AND next_attempt_at<=?
              AND (lease_until IS NULL OR lease_until<?)
            ORDER BY created_at
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """, Timestamp.from(clock.instant()), Timestamp.from(clock.instant()), batch);
        return rows.stream().map(row -> {
            UUID id = (UUID) row.get("id");
            UUID lease = UUID.randomUUID();
            int attempts = ((Number) row.get("attempts")).intValue() + 1;
            jdbc.update("""
                UPDATE reputation.outbox_events
                SET status='PROCESSING',lease_id=?,lease_until=?,attempts=?
                WHERE id=?
                """, lease, Timestamp.from(clock.instant().plusSeconds(300)), attempts, id);
            return new Row(id, lease, (UUID) row.get("aggregate_id"), (String) row.get("topic"),
                (String) row.get("message_key"), row.get("payload").toString(), attempts);
        }).toList();
    }

    public boolean published(UUID id, UUID leaseId) {
        return jdbc.update("""
            UPDATE reputation.outbox_events
            SET status='PUBLISHED',published_at=now(),lease_id=NULL,lease_until=NULL,last_error=NULL
            WHERE id=? AND lease_id=?
            """, id, leaseId) == 1;
    }

    public boolean failed(UUID id, UUID leaseId, int attempts, String error) {
        boolean finalFailure = attempts >= 5;
        long delay = Math.min(3600, 15L * (1L << Math.min(attempts - 1, 6)));
        return jdbc.update("""
            UPDATE reputation.outbox_events
            SET status=?,next_attempt_at=?,lease_id=NULL,lease_until=NULL,last_error=?
            WHERE id=? AND lease_id=?
            """, finalFailure ? "FAILED" : "PENDING",
            Timestamp.from(clock.instant().plusSeconds(delay)), error, id, leaseId) == 1;
    }
}
