package com.baotin.notification.application;

import com.baotin.notification.api.WatchlistResource;
import com.baotin.notification.domain.UrlCanonicalizer.CanonicalUrl;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class WatchlistStore {
    public record Row(UUID id, UUID userId, UUID urlTargetId, String canonicalUrl, String canonicalHash,
                      String displayUrl, String host, BigDecimal score, BigDecimal previousScore,
                      String level, String previousLevel, String label, Instant createdAt, Instant updatedAt) {}

    private static final RowMapper<Row> ROW = (rs, n) -> new Row(
        rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getObject("url_target_id", UUID.class),
        rs.getString("canonical_url"), rs.getString("canonical_hash"), rs.getString("display_url"), rs.getString("host"),
        rs.getBigDecimal("risk_score"), rs.getBigDecimal("previous_score"), rs.getString("risk_level"),
        rs.getString("previous_level"), rs.getString("risk_label"),
        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbc;

    public WatchlistStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns the row and whether it was newly created. Repeated watches are idempotent. */
    public record AddResult(Row row, boolean created) {}

    public AddResult add(UUID userId, CanonicalUrl url) {
        List<Row> inserted = jdbc.query("""
            INSERT INTO notification.watchlist(id,user_id,url_target_id,canonical_url,canonical_hash,display_url,host)
            VALUES (?,?,?,?,?,?,?)
            ON CONFLICT (user_id,url_target_id) DO NOTHING
            RETURNING *
            """, ROW, UUID.randomUUID(), userId, url.targetId(), url.value(), url.hash(),
            truncate(url.value(), 500), url.host());
        if (!inserted.isEmpty()) return new AddResult(inserted.getFirst(), true);
        return new AddResult(jdbc.query("SELECT * FROM notification.watchlist WHERE user_id=? AND url_target_id=?",
            ROW, userId, url.targetId()).getFirst(), false);
    }

    public List<Row> list(UUID userId, int page, int size) {
        return jdbc.query("SELECT * FROM notification.watchlist WHERE user_id=? ORDER BY updated_at DESC,id DESC LIMIT ? OFFSET ?",
            ROW, userId, size, (long) page * size);
    }

    public long count(UUID userId) {
        Long total = jdbc.queryForObject("SELECT count(*) FROM notification.watchlist WHERE user_id=?", Long.class, userId);
        return total == null ? 0 : total;
    }

    public void bindTarget(UUID watchId,UUID targetId) {
        jdbc.update("INSERT INTO notification.watchlist_targets(watchlist_id,threat_target_id) VALUES (?,?) ON CONFLICT DO NOTHING",watchId,targetId);
    }

    public boolean remove(UUID userId, UUID id) {
        return jdbc.update("DELETE FROM notification.watchlist WHERE id=? AND user_id=?", id, userId) > 0;
    }

    /**
     * Updates every watcher of the same canonical URL. Repeated events that do not
     * change score/level are ignored so they cannot create duplicate alerts.
     */
    public List<Row> applyReputation(UUID targetId, String canonicalHash, BigDecimal score, String level, String label) {
        return jdbc.query("""
            UPDATE notification.watchlist
            SET previous_score=risk_score,previous_level=risk_level,risk_score=?,risk_level=?,risk_label=?,updated_at=now()
            WHERE (url_target_id=? OR (?::text IS NOT NULL AND canonical_hash=?) OR EXISTS (
                SELECT 1 FROM notification.watchlist_targets b WHERE b.watchlist_id=notification.watchlist.id AND b.threat_target_id=?))
              AND (risk_score IS DISTINCT FROM ? OR risk_level IS DISTINCT FROM ?)
            RETURNING *
            """, ROW, score, level, truncate(label, 200), targetId, canonicalHash, canonicalHash, targetId, score, level);
    }

    public WatchlistResource resource(Row row) {
        return new WatchlistResource(row.id().toString(), row.urlTargetId(), row.displayUrl(), row.host(),
            row.score(), row.previousScore(), row.level(), row.previousLevel(), row.label(),
            row.createdAt(), row.updatedAt());
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
