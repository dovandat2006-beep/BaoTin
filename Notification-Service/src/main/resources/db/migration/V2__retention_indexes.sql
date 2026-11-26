-- Partial indexes keep the scheduled retention deletes bounded to the rows
-- that are actually eligible. The predicates mirror RetentionStore.
CREATE INDEX notification_notifications_retention_idx
    ON notification.notifications(created_at);

CREATE INDEX notification_delivery_retention_idx
    ON notification.delivery_attempts(updated_at)
    WHERE status IN ('SENT', 'FAILED', 'SKIPPED');

CREATE INDEX notification_inbox_retention_idx
    ON notification.inbox_events(processed_at)
    WHERE processed_at IS NOT NULL;

CREATE INDEX notification_outbox_retention_idx
    ON notification.outbox_events(published_at)
    WHERE status = 'PUBLISHED';

CREATE INDEX notification_device_retention_idx
    ON notification.device_tokens(last_seen_at)
    WHERE active = FALSE;
