package com.baotin.notification.application;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Deletes operational data after its retention window. Each table is purged in
 * bounded batches so a cleanup run never holds a long transaction or unbounded
 * lock set. Unprocessed inbox rows and unpublished outbox rows are always kept:
 * they are evidence of a delivery/consumer failure and need operator action.
 */
@Repository
public class RetentionStore {
    public record PurgeResult(int deliveryAttempts, int notifications, int inboxEvents,
                              int outboxEvents, int deviceTokens) {
        public int total() {
            return deliveryAttempts + notifications + inboxEvents + outboxEvents + deviceTokens;
        }
    }

    private final JdbcTemplate jdbc;
    private final Duration notificationAge;
    private final Duration deliveryAge;
    private final Duration inboxAge;
    private final Duration outboxAge;
    private final Duration inactiveDeviceAge;
    private final int batchSize;
    private final int maxBatches;

    public RetentionStore(JdbcTemplate jdbc,
                          @Value("${notification.retention.notifications-days:180}") long notificationDays,
                          @Value("${notification.retention.delivery-days:30}") long deliveryDays,
                          @Value("${notification.retention.inbox-days:30}") long inboxDays,
                          @Value("${notification.retention.outbox-days:30}") long outboxDays,
                          @Value("${notification.retention.inactive-device-days:180}") long inactiveDeviceDays,
                          @Value("${notification.retention.batch-size:2000}") int batchSize,
                          @Value("${notification.retention.max-batches:20}") int maxBatches) {
        this.jdbc = jdbc;
        this.notificationAge = days("notifications", notificationDays);
        this.deliveryAge = days("delivery", deliveryDays);
        this.inboxAge = days("inbox", inboxDays);
        this.outboxAge = days("outbox", outboxDays);
        this.inactiveDeviceAge = days("inactive-device", inactiveDeviceDays);
        if (batchSize < 1 || batchSize > 10_000) {
            throw new IllegalArgumentException("notification.retention.batch-size must be between 1 and 10000");
        }
        if (maxBatches < 1 || maxBatches > 100) {
            throw new IllegalArgumentException("notification.retention.max-batches must be between 1 and 100");
        }
        this.batchSize = batchSize;
        this.maxBatches = maxBatches;
    }

    public PurgeResult purge(Instant now) {
        int deliveries = deleteBatches("""
            DELETE FROM notification.delivery_attempts
            WHERE id IN (
                SELECT id FROM notification.delivery_attempts
                WHERE status IN ('SENT','FAILED','SKIPPED') AND updated_at < ?
                ORDER BY updated_at
                LIMIT ?
            )
            """, now.minus(deliveryAge));
        int notifications = deleteBatches("""
            DELETE FROM notification.notifications
            WHERE id IN (
                SELECT id FROM notification.notifications
                WHERE created_at < ?
                ORDER BY created_at
                LIMIT ?
            )
            """, now.minus(notificationAge));
        int inbox = deleteBatches("""
            DELETE FROM notification.inbox_events
            WHERE event_id IN (
                SELECT event_id FROM notification.inbox_events
                WHERE processed_at IS NOT NULL AND processed_at < ?
                ORDER BY processed_at
                LIMIT ?
            )
            """, now.minus(inboxAge));
        int outbox = deleteBatches("""
            DELETE FROM notification.outbox_events
            WHERE id IN (
                SELECT id FROM notification.outbox_events
                WHERE status = 'PUBLISHED' AND published_at < ?
                ORDER BY published_at
                LIMIT ?
            )
            """, now.minus(outboxAge));
        int devices = deleteBatches("""
            DELETE FROM notification.device_tokens
            WHERE id IN (
                SELECT id FROM notification.device_tokens
                WHERE active = FALSE AND last_seen_at < ?
                ORDER BY last_seen_at
                LIMIT ?
            )
            """, now.minus(inactiveDeviceAge));
        return new PurgeResult(deliveries, notifications, inbox, outbox, devices);
    }

    private int deleteBatches(String sql, Instant cutoff) {
        int removed = 0;
        Timestamp timestamp = Timestamp.from(cutoff);
        for (int batch = 0; batch < maxBatches; batch++) {
            int count = jdbc.update(sql, timestamp, batchSize);
            removed += count;
            if (count < batchSize) break;
        }
        return removed;
    }

    private static Duration days(String name, long value) {
        if (value < 1 || value > 3650) {
            throw new IllegalArgumentException("notification.retention." + name + "-days must be between 1 and 3650");
        }
        return Duration.ofDays(value);
    }
}
