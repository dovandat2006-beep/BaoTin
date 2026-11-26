package com.baotin.threat.infrastructure;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Search domain snapshots use the owning state transaction, never a second write/send. */
@Component
public class TargetSearchEvents {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    public TargetSearchEvents(JdbcTemplate jdbc,JsonMapper json) { this.jdbc=jdbc;this.json=json; }
    @Transactional(propagation=Propagation.MANDATORY)
    public void publish(String eventType,UUID target,String targetType,String publicInput,Instant at,UUID correlation) {
        if(!Set.of("threat.created","threat.updated").contains(eventType)) throw new IllegalArgumentException("Invalid target event type");
        Map<String,Object> snapshot=publicSnapshot(target,targetType,publicInput);
        if(snapshot==null)return;
        if(targetType.equals("MESSAGE_TEMPLATE")) {
            var masked=jdbc.queryForList("SELECT redacted_text,dataset_label FROM threat.public_message_templates WHERE target_id=?",target);
            if(!masked.isEmpty())snapshot=PublicMessages.snapshot(target,(String)masked.getFirst().get("redacted_text"),(String)masked.getFirst().get("dataset_label"));
        }
        if(targetType.equals("URL")) {
            boolean suspicious=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM threat.dataset_import_rows WHERE target_id=? AND dataset_label='SUSPICIOUS')",Boolean.class,target));
            boolean benign=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM threat.dataset_import_rows WHERE target_id=? AND dataset_label='BENIGN')",Boolean.class,target));
            snapshot=withDatasetLabel(snapshot,suspicious?"SUSPICIOUS":benign?"BENIGN":"UNKNOWN");
        }
        UUID event=UUID.randomUUID();Map<String,Object> envelope=new LinkedHashMap<>();
        envelope.put("eventId",event);envelope.put("eventVersion",1);envelope.put("eventType",eventType);
        envelope.put("producer","threat-service");envelope.put("aggregateType","ThreatTarget");envelope.put("aggregateId",target);
        envelope.put("occurredAt",at);envelope.put("correlationId",correlation);envelope.put("causationId",null);
        envelope.put("payload",Map.of("threatTargetId",target,"targetType",targetType,"publicSearch",snapshot));
        jdbc.update("INSERT INTO threat.outbox_events(id,aggregate_type,aggregate_id,event_type,topic,message_key,payload,occurred_at,created_at) VALUES (?,?,?,?,?,?,?::jsonb,?,?)",
            event,"ThreatTarget",target,eventType,"baotin.threat.search.events.v1",target.toString(),json.writeValueAsString(envelope),Timestamp.from(at),Timestamp.from(at));
    }
    public static Map<String,Object> withDatasetWarning(Map<String,Object> snapshot,boolean suspicious) {
        if(snapshot==null || !suspicious)return snapshot;
        var result=new LinkedHashMap<String,Object>(snapshot);
        result.put("metadata",Map.of("referenceType","URL","contentWarning","SUSPICIOUS"));return result;
    }
    public static Map<String,Object> withDatasetLabel(Map<String,Object> snapshot,String label) {
        if(snapshot==null || !Set.of("SUSPICIOUS","BENIGN").contains(label))return snapshot;
        var result=new LinkedHashMap<String,Object>(snapshot);
        result.put("metadata",Map.of("referenceType","URL","contentWarning",label));return result;
    }
    /** Pure owner policy shared by the transactional publisher and read-only exporter. */
    public static Map<String,Object> publicSnapshot(UUID target,String targetType,String publicInput) {
        String display,documentType;
        if(targetType.equals("URL")) {
            String host=URI.create(publicInput).getHost();
            if(host==null || !ScanStore.publicSearchHost(host)) return null;
            display=host;documentType="URL";
        } else if(targetType.equals("MESSAGE_TEMPLATE")) {
            if(publicInput.length()!=64 || !publicInput.chars().allMatch(c->c>='0'&&c<='9'||c>='a'&&c<='f')) throw new IllegalArgumentException("Only template fingerprint accepted");
            display="Mẫu tin nhắn #"+publicInput.substring(0,12);documentType="OTHER";
        } else throw new IllegalArgumentException("Unsupported target publication");
        return Map.of("visibility","PUBLIC","documentType",documentType,"referenceId",target,
            "title",display,"displayValue",display,"status","ACTIVE","metadata",Map.of("referenceType",targetType));
    }

}
