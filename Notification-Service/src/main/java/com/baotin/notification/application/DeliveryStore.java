package com.baotin.notification.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class DeliveryStore {
    public record PushJob(UUID id, UUID notificationId, UUID deviceId, String token, UUID userId,
                          String title, String body, String dataJson, int attempt) {}

    private static final RowMapper<PushJob> PUSH_JOB = (rs, n) -> new PushJob(
        rs.getObject("id", UUID.class), rs.getObject("notification_id", UUID.class),
        rs.getObject("device_token_id", UUID.class), rs.getString("token"),
        rs.getObject("user_id", UUID.class), rs.getString("title"), rs.getString("body"),
        rs.getString("data"), rs.getInt("attempt"));

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public DeliveryStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void inApp(UUID notificationId, boolean delivered, String error) {
        jdbc.update("INSERT INTO notification.delivery_attempts(id,notification_id,channel,status,attempt,error,updated_at) VALUES (?,?,'IN_APP',?,?,?,now())",
            UUID.randomUUID(), notificationId, delivered ? "SENT" : "SKIPPED", delivered ? 1 : 0, error);
    }

    public void pushForDevice(UUID notificationId, UUID deviceId) {
        jdbc.update("INSERT INTO notification.delivery_attempts(id,notification_id,channel,status,device_token_id,next_attempt_at) VALUES (?,?,'PUSH','PENDING',?,?)",
            UUID.randomUUID(), notificationId, deviceId, Timestamp.from(clock.instant()));
    }

    public void pushSkipped(UUID notificationId, String reason) {
        jdbc.update("INSERT INTO notification.delivery_attempts(id,notification_id,channel,status,attempt,error,updated_at) VALUES (?,?,'PUSH','SKIPPED',0,?,now())",
            UUID.randomUUID(), notificationId, reason);
    }

    @Transactional
    public List<PushJob> claim(int batch) {
        jdbc.update("UPDATE notification.delivery_attempts SET status='PENDING',updated_at=now()"
            + " WHERE channel='PUSH' AND status='PROCESSING' AND updated_at < now() - interval '5 minutes'");
        List<PushJob> jobs = jdbc.query("""
            SELECT da.id,da.notification_id,da.device_token_id,da.attempt,n.user_id,n.title,n.body,n.data::text AS data,dt.token
            FROM notification.delivery_attempts da
            JOIN notification.notifications n ON n.id=da.notification_id
            JOIN notification.device_tokens dt ON dt.id=da.device_token_id
            WHERE da.channel='PUSH' AND da.status='PENDING' AND (da.next_attempt_at IS NULL OR da.next_attempt_at<=?)
            ORDER BY da.created_at LIMIT ? FOR UPDATE OF da SKIP LOCKED
            """, PUSH_JOB, Timestamp.from(clock.instant()), batch);
        for (PushJob job : jobs) {
            jdbc.update("UPDATE notification.delivery_attempts SET status='PROCESSING',attempt=attempt+1,updated_at=now() WHERE id=?",
                job.id());
        }
        return jobs;
    }

    public void sent(UUID id) {
        jdbc.update("UPDATE notification.delivery_attempts SET status='SENT',error=NULL,updated_at=now() WHERE id=?", id);
    }

    public void failed(UUID id, int attempt, String error, boolean retry, boolean invalidToken) {
        if (invalidToken) {
            jdbc.update("UPDATE notification.delivery_attempts SET status='FAILED',error=?,updated_at=now() WHERE id=?", error, id);
            jdbc.update("UPDATE notification.device_tokens SET active=FALSE WHERE id=(SELECT device_token_id FROM notification.delivery_attempts WHERE id=?)", id);
            return;
        }
        if (!retry || attempt >= 3) {
            jdbc.update("UPDATE notification.delivery_attempts SET status='FAILED',error=?,updated_at=now() WHERE id=?", error, id);
            return;
        }
        Instant next = clock.instant().plusSeconds(30L * (1L << Math.min(attempt - 1, 5)));
        jdbc.update("UPDATE notification.delivery_attempts SET status='PENDING',error=?,next_attempt_at=?,updated_at=now() WHERE id=?",
            error, Timestamp.from(next), id);
    }
}
