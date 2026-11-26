package com.baotin.threat.infrastructure;

import com.baotin.threat.domain.*;
import com.baotin.threat.domain.ScanResult.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class ScanStore {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final ContentProtector privacy;
    private final ScanMetricsStore metrics;
    private final TargetSearchEvents searchEvents;
    private final PublicMessages publicMessages;
    public ScanStore(JdbcTemplate jdbc, JsonMapper json, ContentProtector privacy, ScanMetricsStore metrics, TargetSearchEvents searchEvents, PublicMessages publicMessages) {
        this.jdbc = jdbc; this.json = json; this.privacy = privacy; this.metrics = metrics; this.searchEvents=searchEvents; this.publicMessages=publicMessages;
    }

    /** Network/model calls finish before this transaction. Targets, history and events commit together. */
    @Transactional
    public ScanResult save(ScanResult provisional, UUID userId, String input, UUID correlation) {
        // Consistent lock order prevents deadlocks when concurrent texts contain the same URLs in reverse order.
        Map<String, UUID> resolved = new HashMap<>();
        for (String canonical : provisional.urls().stream().map(UrlResult::url).distinct().sorted().toList()) resolved.put(canonical, target(canonical, provisional.scannedAt(), correlation));
        List<UrlResult> urls = new ArrayList<>();
        for (UrlResult url : provisional.urls()) {
            UUID id = resolved.get(url.url());
            urls.add(new UrlResult(url.url(), id, url.model(), url.rules(), url.reputationScore(), url.risk(), url.aiAvailable()));
            Map<String, Object> signals = new LinkedHashMap<>();
            signals.put("threatTargetId", id); signals.put("targetType", "URL"); signals.put("ruleSignal", url.rules().score());
            if (url.model() != null) signals.put("aiSignal", url.model().riskProbability() * 100);
            event("threat.updated", "ThreatTarget", id, signals, provisional.scannedAt(), correlation);
            // Publish only the public DNS host, never URL paths, credentials, query or SMS.
            String host = java.net.URI.create(url.url()).getHost();
            if (host != null && publicSearchHost(host)) event("search.target.updated", "ThreatTarget", id,
                Map.of("targetId",id,"targetType","URL","displayValue",host), provisional.scannedAt(), correlation);
            searchEvents.publish("threat.updated",id,"URL",url.url(),provisional.scannedAt(),correlation);
        }
        UUID targetId = provisional.type().equals("URL") ? urls.getFirst().targetId() : null;
        ScanResult result = new ScanResult(provisional.scanId(), provisional.type(), targetId, provisional.preview(), provisional.risk(),
                provisional.textModel(), provisional.urlModel(), provisional.rules(), List.copyOf(urls), provisional.aiAvailable(), provisional.degraded(), provisional.warnings(), provisional.scannedAt());
        if (userId != null) {
            ModelSignal model = result.type().equals("TEXT") ? result.textModel() : result.urlModel();
            byte[] encrypted = result.type().equals("TEXT") ? privacy.encrypt(input, result.scanId()) : null;
            jdbc.update("""
                INSERT INTO threat.scan_history(id,user_id,scan_type,target_id,input_hash,content_preview,encrypted_content,
                    model_label,model_score,model_name,model_version,rule_score,reputation_score,final_score,risk_level,
                    embedded_url_count,result_snapshot,created_at,content_expires_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?)
                """, result.scanId(), userId, result.type(), targetId, privacy.hash("input:" + input), result.preview(), encrypted,
                    model == null ? null : model.label(), model == null ? null : model.riskProbability(), model == null ? null : model.modelName(), model == null ? null : model.modelVersion(),
                    result.rules().score(), result.type().equals("URL") ? urls.getFirst().reputationScore() : null, result.risk().score(), result.risk().level(),
                    result.type().equals("TEXT") ? urls.size() : 0, json.writeValueAsString(result), Timestamp.from(result.scannedAt()),
                    encrypted == null ? null : Timestamp.from(result.scannedAt().plus(Duration.ofDays(30))));
            if (result.type().equals("TEXT")) for (UrlResult url : urls) {
                jdbc.update("INSERT INTO threat.scan_urls(id,scan_id,url_target_id,raw_url_hash,model_label,model_score,final_score,created_at) VALUES (?,?,?,?,?,?,?,?)",
                        UUID.randomUUID(), result.scanId(), url.targetId(), privacy.hash("url:" + url.url()), url.model() == null ? null : url.model().label(),
                        url.model() == null ? null : url.model().riskProbability(), url.risk().score(), Timestamp.from(result.scannedAt()));
            }
        }
        if(result.type().equals("TEXT") && Set.of("HIGH","CRITICAL").contains(result.risk().level()))
            publicMessages.record(input,"SCAN","user-submitted","SUSPICIOUS",result.scannedAt());
        metrics.record(result.scannedAt(), result.risk().level());
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("scanId", result.scanId()); event.put("scanType", result.type()); event.put("targetId", targetId);
        event.put("authenticated", userId != null); event.put("finalScore", result.risk().score()); event.put("riskLevel", result.risk().level());
        event.put("aiAvailable", result.aiAvailable()); event.put("embeddedUrlCount", result.type().equals("TEXT") ? urls.size() : 0);
        ModelSignal mainModel = result.type().equals("TEXT") ? result.textModel() : result.urlModel();
        event.put("modelName", mainModel == null ? null : mainModel.modelName()); event.put("modelVersion", mainModel == null ? null : mainModel.modelVersion());
        event.put("modelLabel", mainModel == null ? null : mainModel.label()); event.put("modelLatencyMs", mainModel == null ? null : mainModel.latencyMs());
        event("scan.completed", "Scan", result.scanId(), event, result.scannedAt(), correlation);
        return result;
    }

    static boolean publicSearchHost(String host) {
        if (host == null || host.length() > 253 || !host.equals(host.toLowerCase(java.util.Locale.ROOT))) return false;
        String[] labels = host.split("\\.", -1);
        if (labels.length < 2) return false;
        for (String label : labels) {
            if (!validDnsLabel(label)) return false;
        }
        String suffix = labels[labels.length - 1];
        return suffix.length() >= 2 && suffix.chars().allMatch(c -> c >= 'a' && c <= 'z')
            && !java.util.Set.of("localhost","local","internal","test","invalid").contains(suffix);
    }
    private static boolean validDnsLabel(String label) {
        if (label.isEmpty() || label.length() > 63 || !dnsAlphaNumeric(label.charAt(0))
                || !dnsAlphaNumeric(label.charAt(label.length() - 1))) return false;
        return label.chars().allMatch(c -> dnsAlphaNumeric(c) || c == '-');
    }
    private static boolean dnsAlphaNumeric(int c) {
        return c >= 'a' && c <= 'z' || c >= '0' && c <= '9';
    }

    private UUID target(String canonical, Instant now, UUID correlation) {
        var row = jdbc.queryForMap("""
            INSERT INTO threat.targets(id,type,canonical_hash,normalized_value,display_preview,first_seen_at,last_seen_at,created_at,updated_at)
            VALUES (?,'URL',?,?,?, ?,?,?,?) ON CONFLICT(type,canonical_hash)
            DO UPDATE SET last_seen_at=EXCLUDED.last_seen_at,updated_at=EXCLUDED.updated_at,version=threat.targets.version+1
            RETURNING id,(xmax=0) AS inserted
            """, UUID.randomUUID(), privacy.urlIdentity(canonical), canonical, truncate(canonical, 500),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        UUID id = (UUID) row.get("id");
        event(Boolean.TRUE.equals(row.get("inserted")) ? "target.created" : "target.updated", "Target", id, Map.of("targetId", id, "targetType", "URL", "canonicalHash", privacy.urlIdentity(canonical)), now, correlation);
        if(Boolean.TRUE.equals(row.get("inserted"))) searchEvents.publish("threat.created",id,"URL",canonical,now,correlation);
        return id;
    }

    private void event(String type, String aggregateType, UUID aggregateId, Map<String, ?> payload, Instant now, UUID correlation) {
        UUID id = UUID.randomUUID();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", id); envelope.put("eventType", type); envelope.put("eventVersion", 1);
        envelope.put("occurredAt", now); envelope.put("producer", "threat-service"); envelope.put("aggregateId", aggregateId);
        envelope.put("aggregateType", aggregateType); envelope.put("payload", payload);
        envelope.put("correlationId", correlation); envelope.put("causationId", null);
        jdbc.update("INSERT INTO threat.outbox_events(id,aggregate_type,aggregate_id,event_type,topic,message_key,payload,occurred_at,created_at) VALUES (?,?,?,?,?,?,?::jsonb,?,?)",
                id, aggregateType, aggregateId, type, type.equals("search.target.updated") ? "baotin.search.targets.v1" : type.equals("threat.updated") ? "baotin.threat.events.v1" : "baotin.scan.events.v1", aggregateId.toString(), json.writeValueAsString(envelope), Timestamp.from(now), Timestamp.from(now));
    }

    public UUID existingUrl(String canonicalHash) {
        var targets = jdbc.query("SELECT id FROM threat.targets WHERE type='URL' AND canonical_hash=?", (row, index) -> row.getObject(1, UUID.class), canonicalHash);
        return targets.isEmpty() ? null : targets.getFirst();
    }

    public record HistoryPage(List<ScanResult> items, int page, int size, long totalItems, long totalPages) {}
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public HistoryPage history(UUID user, String type, String level, int page, int size, boolean ascending) {
        String where = "user_id=?"; List<Object> parameters = new ArrayList<>(); parameters.add(user);
        if (type != null) { where += " AND scan_type=?"; parameters.add(type); }
        if (level != null) { where += " AND risk_level=?"; parameters.add(level); }
        long count = jdbc.queryForObject("SELECT count(*) FROM threat.scan_history WHERE " + where, Long.class, parameters.toArray());
        parameters.add(size); parameters.add((long) page * size);
        List<ScanResult> items = jdbc.query("SELECT result_snapshot::text FROM threat.scan_history WHERE " + where + " ORDER BY created_at " + (ascending ? "ASC, id ASC" : "DESC, id DESC") + " LIMIT ? OFFSET ?",
                (row, index) -> json.readValue(row.getString(1), ScanResult.class), parameters.toArray());
        return new HistoryPage(items, page, size, count, (count + size - 1) / size);
    }
    public ScanResult detail(UUID user, UUID id) {
        List<ScanResult> rows = jdbc.query("SELECT result_snapshot::text FROM threat.scan_history WHERE user_id=? AND id=?",
                (row, index) -> json.readValue(row.getString(1), ScanResult.class), user, id);
        if (rows.isEmpty()) throw new NoSuchElementException("SCAN_NOT_FOUND");
        return rows.getFirst();
    }
    public void delete(UUID user, UUID id) {
        if (jdbc.update("DELETE FROM threat.scan_history WHERE user_id=? AND id=?", user, id) == 0) throw new NoSuchElementException("SCAN_NOT_FOUND");
    }
    public void deleteAll(UUID user) { jdbc.update("DELETE FROM threat.scan_history WHERE user_id=?", user); }
    public static String truncate(String value, int size) { return value.codePointCount(0, value.length()) <= size ? value : value.substring(0, value.offsetByCodePoints(0, size)); }
}
