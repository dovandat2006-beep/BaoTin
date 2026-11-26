package com.baotin.notification.application;

import com.baotin.notification.api.DeviceResource;
import com.baotin.notification.api.NotificationResource;
import com.baotin.notification.api.PreferencesResource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class NotificationStore {
    public record Preferences(boolean riskChangeEnabled, boolean reportStatusEnabled,
                              boolean pushEnabled, boolean inAppEnabled, Instant updatedAt) {
        public static Preferences defaults() {
            return new Preferences(true, true, true, true, null);
        }
    }

    public record Device(UUID id, UUID userId, String platform, String token, String deviceId,
                         boolean active, Instant createdAt, Instant lastSeenAt) {}

    public record NotificationRow(UUID id, UUID userId, String type, String title, String body,
                                  String dataJson, boolean inAppDelivered, Instant createdAt, Instant readAt) {
        public boolean unread() { return readAt == null; }
    }

    private static final RowMapper<Preferences> PREFERENCES = (rs, n) -> new Preferences(
        rs.getBoolean("risk_change_enabled"), rs.getBoolean("report_status_enabled"),
        rs.getBoolean("push_enabled"), rs.getBoolean("in_app_enabled"),
        instant(rs.getTimestamp("updated_at")));

    private static final RowMapper<Device> DEVICE = (rs, n) -> new Device(
        rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("platform"),
        rs.getString("token"), rs.getString("device_id"), rs.getBoolean("active"),
        instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("last_seen_at")));

    private static final RowMapper<NotificationRow> NOTIFICATION = (rs, n) -> new NotificationRow(
        rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("type"),
        rs.getString("title"), rs.getString("body"), rs.getString("data"),
        rs.getBoolean("in_app_delivered"), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("read_at")));

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public NotificationStore(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    public Preferences preferences(UUID userId) {
        return jdbc.query("SELECT * FROM notification.preferences WHERE user_id=?", PREFERENCES, userId)
            .stream().findFirst().orElseGet(Preferences::defaults);
    }

    public Preferences updatePreferences(UUID userId, Boolean riskChange, Boolean reportStatus, Boolean push, Boolean inApp) {
        jdbc.update("""
            INSERT INTO notification.preferences(user_id,risk_change_enabled,report_status_enabled,push_enabled,in_app_enabled,updated_at)
            VALUES (?,COALESCE(?,TRUE),COALESCE(?,TRUE),COALESCE(?,TRUE),COALESCE(?,TRUE),now())
            ON CONFLICT (user_id) DO UPDATE SET
              risk_change_enabled=COALESCE(?,notification.preferences.risk_change_enabled),
              report_status_enabled=COALESCE(?,notification.preferences.report_status_enabled),
              push_enabled=COALESCE(?,notification.preferences.push_enabled),
              in_app_enabled=COALESCE(?,notification.preferences.in_app_enabled),
              updated_at=now()
            """, userId, riskChange, reportStatus, push, inApp, riskChange, reportStatus, push, inApp);
        return preferences(userId);
    }

    public void insertNotification(UUID id, UUID userId, String type, String title, String body,
                                   Map<String, Object> data, boolean inAppDelivered) {
        jdbc.update("INSERT INTO notification.notifications(id,user_id,type,title,body,data,in_app_delivered) VALUES (?,?,?,?,?,?::jsonb,?)",
            id, userId, type, title, body, json.writeValueAsString(data), inAppDelivered);
    }

    public List<NotificationRow> list(UUID userId, int page, int size, boolean unreadOnly) {
        String filter = unreadOnly ? " AND read_at IS NULL" : "";
        return jdbc.query("SELECT * FROM notification.notifications WHERE user_id=? AND in_app_delivered" + filter
                + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
            NOTIFICATION, userId, size, (long) page * size);
    }

    public long count(UUID userId, boolean unreadOnly) {
        String filter = unreadOnly ? " AND read_at IS NULL" : "";
        Long total = jdbc.queryForObject("SELECT count(*) FROM notification.notifications WHERE user_id=? AND in_app_delivered" + filter,
            Long.class, userId);
        return total == null ? 0 : total;
    }

    public long unreadCount(UUID userId) {
        Long total = jdbc.queryForObject("SELECT count(*) FROM notification.notifications WHERE user_id=? AND in_app_delivered AND read_at IS NULL",
            Long.class, userId);
        return total == null ? 0 : total;
    }

    public boolean markRead(UUID userId, UUID id) {
        return jdbc.update("UPDATE notification.notifications SET read_at=COALESCE(read_at,now()) WHERE id=? AND user_id=?",
            id, userId) > 0;
    }

    public int markAllRead(UUID userId) {
        return jdbc.update("UPDATE notification.notifications SET read_at=now() WHERE user_id=? AND read_at IS NULL", userId);
    }

    public boolean deleteNotification(UUID userId, UUID id) {
        return jdbc.update("DELETE FROM notification.notifications WHERE id=? AND user_id=?", id, userId) > 0;
    }

    public List<Device> activeDevices(UUID userId) {
        return jdbc.query("SELECT * FROM notification.device_tokens WHERE user_id=? AND active ORDER BY last_seen_at DESC",
            DEVICE, userId);
    }

    public List<Device> devices(UUID userId) {
        return jdbc.query("SELECT * FROM notification.device_tokens WHERE user_id=? ORDER BY last_seen_at DESC", DEVICE, userId);
    }

    public Optional<Device> device(UUID userId, UUID id) {
        return jdbc.query("SELECT * FROM notification.device_tokens WHERE id=? AND user_id=?", DEVICE, id, userId).stream().findFirst();
    }

    public Device registerDevice(UUID userId, String platform, String token, String deviceId) {
        jdbc.update("""
            INSERT INTO notification.device_tokens(id,user_id,platform,token,device_id,active,last_seen_at)
            VALUES (?,?,?,?,?,TRUE,now())
            ON CONFLICT (token) DO UPDATE SET user_id=EXCLUDED.user_id,platform=EXCLUDED.platform,
              device_id=EXCLUDED.device_id,active=TRUE,last_seen_at=now()
            """, UUID.randomUUID(), userId, platform, token, deviceId);
        return jdbc.query("SELECT * FROM notification.device_tokens WHERE token=?", DEVICE, token).getFirst();
    }

    public boolean deactivateDevice(UUID id) {
        return jdbc.update("UPDATE notification.device_tokens SET active=FALSE WHERE id=?", id) > 0;
    }

    public boolean removeDevice(UUID userId, UUID id) {
        return jdbc.update("DELETE FROM notification.device_tokens WHERE id=? AND user_id=?", id, userId) > 0;
    }

    public NotificationResource resource(NotificationRow row) {
        Map<String, Object> data = row.dataJson() == null || row.dataJson().isBlank()
            ? Map.of() : json.readValue(row.dataJson(), new TypeReference<Map<String, Object>>() {});
        return new NotificationResource(row.id().toString(), row.type(), row.title(), row.body(), data,
            row.createdAt(), row.readAt(), !row.unread());
    }

    public PreferencesResource resource(Preferences p) {
        return new PreferencesResource(p.riskChangeEnabled(), p.reportStatusEnabled(), p.pushEnabled(), p.inAppEnabled(), p.updatedAt());
    }

    public DeviceResource resource(Device d) {
        return new DeviceResource(d.id().toString(), d.platform(), d.deviceId(), d.active(), d.createdAt(), d.lastSeenAt());
    }
}
