package com.baotin.notification.infrastructure;

import com.baotin.notification.application.DeliveryStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Pulls delivery attempts and sends FCM push outside the request transaction. */
@Component
@ConditionalOnProperty(name = "notification.push.enabled", havingValue = "true")
public class PushDispatcher {
    private static final Logger log = LoggerFactory.getLogger(PushDispatcher.class);

    private final DeliveryStore deliveries;
    private final PushSender sender;
    private final JsonMapper json;
    private final int batchSize;

    public PushDispatcher(DeliveryStore deliveries, PushSender sender, JsonMapper json,
                          @Value("${notification.push.batch-size:20}") int batchSize) {
        this.deliveries = deliveries;
        this.sender = sender;
        this.json = json;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${notification.push.interval-ms:2000}")
    public void dispatch() {
        List<DeliveryStore.PushJob> jobs;
        try {
            jobs = deliveries.claim(batchSize);
        } catch (Exception e) {
            log.warn("Could not claim push deliveries: {}", e.getMessage());
            return;
        }
        for (DeliveryStore.PushJob job : jobs) {
            try {
                PushSender.PushResult result = sender.send(job.token(),
                    new PushSender.PushMessage(job.title(), job.body(), data(job)));
                if (result.delivered()) {
                    deliveries.sent(job.id());
                } else {
                    deliveries.failed(job.id(), job.attempt(), result.error(), !result.invalidToken(), result.invalidToken());
                }
            } catch (Exception e) {
                log.warn("Push delivery {} failed: {}", job.id(), e.getClass().getSimpleName());
                deliveries.failed(job.id(), job.attempt(), "PUSH_DISPATCH_ERROR", true, false);
            }
        }
    }

    private Map<String, String> data(DeliveryStore.PushJob job) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("notificationId", job.notificationId().toString());
        result.put("type", "NOTIFICATION");
        if (job.dataJson() == null || job.dataJson().isBlank()) return result;
        try {
            Map<String, Object> stored = json.readValue(job.dataJson(), new TypeReference<Map<String, Object>>() {});
            stored.forEach((key, value) -> {
                if (key != null && value != null) result.put(key, String.valueOf(value));
            });
        } catch (Exception ignored) {
            // The notification itself is still deliverable; only deep-link data is lost.
        }
        return result;
    }
}
