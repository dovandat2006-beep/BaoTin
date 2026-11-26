package com.baotin.notification.application;

import com.baotin.notification.api.ApiFailure;
import com.baotin.notification.api.DeviceResource;
import com.baotin.notification.api.NotificationResource;
import com.baotin.notification.api.PageResponse;
import com.baotin.notification.api.PreferencesResource;
import com.baotin.notification.application.NotificationStore.Device;
import com.baotin.notification.application.NotificationStore.Preferences;
import com.baotin.notification.infrastructure.PushSender;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class NotificationService {
    private final NotificationStore store;
    private final DeliveryStore deliveries;
    private final OutboxStore outbox;
    private final PushSender push;
    private final JsonMapper json;
    private final Clock clock;
    private final String notificationTopic;

    public NotificationService(NotificationStore store, DeliveryStore deliveries, OutboxStore outbox, PushSender push,
                               JsonMapper json, Clock clock,
                               @Value("${notification.events.notification-topic}") String notificationTopic) {
        this.store = store;
        this.deliveries = deliveries;
        this.outbox = outbox;
        this.push = push;
        this.json = json;
        this.clock = clock;
        this.notificationTopic = notificationTopic;
    }

    /**
     * Creates one logical notification and queues delivery channels in the same
     * transaction as its outbox event. Push is never attempted inline with the
     * request: the dispatcher owns retries and invalid-token cleanup.
     */
    @Transactional
    public UUID create(UUID userId, String type, String title, String body, Map<String, Object> data, boolean allowPush) {
        Preferences preferences = store.preferences(userId);
        boolean inApp = preferences.inAppEnabled();
        boolean pushRequested = allowPush && preferences.pushEnabled();
        if (!inApp && !pushRequested) return null;
        if (!inApp && pushRequested && !push.available()) return null;

        UUID id = UUID.randomUUID();
        store.insertNotification(id, userId, type, truncate(title, 200), truncate(body, 1000), data, inApp);
        deliveries.inApp(id, inApp, inApp ? null : "IN_APP_DISABLED");
        if (pushRequested) {
            if (!push.available()) {
                deliveries.pushSkipped(id, "PUSH_PROVIDER_UNAVAILABLE");
            } else {
                List<Device> devices = store.activeDevices(userId);
                if (devices.isEmpty()) deliveries.pushSkipped(id, "NO_ACTIVE_DEVICE");
                else devices.forEach(device -> deliveries.pushForDevice(id, device.id()));
            }
        }

        Map<String, Object> channels = new LinkedHashMap<>();
        channels.put("inApp", inApp);
        channels.put("push", pushRequested);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("notificationId", id.toString());
        payload.put("userId", userId.toString());
        payload.put("type", type);
        payload.put("channels", channels);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("eventType", "notification.sent");
        envelope.put("eventVersion", 1);
        envelope.put("occurredAt", clock.instant().toString());
        envelope.put("producer", "notification-service");
        envelope.put("aggregateType", "Notification");
        envelope.put("aggregateId", id.toString());
        envelope.put("correlationId", UUID.randomUUID().toString());
        envelope.put("causationId", null);
        envelope.put("payload", payload);
        outbox.enqueue("Notification", id, "notification.sent", 1, notificationTopic, id.toString(), json.writeValueAsString(envelope));
        return id;
    }

    public PageResponse<NotificationResource> list(UUID userId, int page, int size, boolean unreadOnly) {
        List<NotificationResource> items = store.list(userId, page, size, unreadOnly).stream().map(store::resource).toList();
        return PageResponse.of(items, page, size, store.count(userId, unreadOnly));
    }

    public long unreadCount(UUID userId) { return store.unreadCount(userId); }

    public void markRead(UUID userId, UUID id) {
        if (!store.markRead(userId, id)) throw ApiFailure.notFound();
    }

    public int markAllRead(UUID userId) { return store.markAllRead(userId); }

    public void delete(UUID userId, UUID id) {
        if (!store.deleteNotification(userId, id)) throw ApiFailure.notFound();
    }

    public PreferencesResource preferences(UUID userId) { return store.resource(store.preferences(userId)); }

    public PreferencesResource updatePreferences(UUID userId, Boolean riskChange, Boolean reportStatus, Boolean pushEnabled, Boolean inApp) {
        if (riskChange == null && reportStatus == null && pushEnabled == null && inApp == null) {
            throw new ApiFailure(400, "INVALID_INPUT", "At least one preference is required");
        }
        return store.resource(store.updatePreferences(userId, riskChange, reportStatus, pushEnabled, inApp));
    }

    public List<DeviceResource> devices(UUID userId) {
        return store.devices(userId).stream().map(store::resource).toList();
    }

    @Transactional
    public DeviceResource registerDevice(UUID userId, String platform, String token, String deviceId) {
        String normalized = platform == null ? "" : platform.trim().toUpperCase(java.util.Locale.ROOT);
        if (!List.of("WEB", "ANDROID", "IOS").contains(normalized)) {
            throw new ApiFailure(400, "INVALID_PLATFORM", "Platform must be WEB, ANDROID or IOS");
        }
        if (token == null || token.isBlank() || token.length() > 512) {
            throw new ApiFailure(400, "INVALID_TOKEN", "Device token is invalid");
        }
        if (deviceId == null || deviceId.isBlank() || deviceId.length() > 128) {
            throw new ApiFailure(400, "INVALID_DEVICE", "Device id is invalid");
        }
        return store.resource(store.registerDevice(userId, normalized, token.trim(), deviceId.trim()));
    }

    public void removeDevice(UUID userId, UUID id) {
        if (!store.removeDevice(userId, id)) throw ApiFailure.notFound();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
