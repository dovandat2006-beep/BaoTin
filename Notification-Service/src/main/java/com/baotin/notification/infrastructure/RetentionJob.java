package com.baotin.notification.infrastructure;

import com.baotin.notification.application.RetentionStore;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RetentionJob {
    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final RetentionStore store;
    private final Clock clock;
    private final boolean enabled;

    public RetentionJob(RetentionStore store, Clock clock,
                        @Value("${notification.retention.enabled:true}") boolean enabled) {
        this.store = store;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${notification.retention.cleanup-cron:0 15 3 * * *}", zone = "UTC")
    public void purgeExpiredData() {
        if (!enabled) return;
        RetentionStore.PurgeResult result = store.purge(clock.instant());
        log.info("notification retention removed notifications={} deliveryAttempts={} inboxEvents={} "
                + "outboxEvents={} deviceTokens={}",
            result.notifications(), result.deliveryAttempts(), result.inboxEvents(),
            result.outboxEvents(), result.deviceTokens());
    }
}
