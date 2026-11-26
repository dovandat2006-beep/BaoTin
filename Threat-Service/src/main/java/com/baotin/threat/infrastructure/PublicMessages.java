package com.baotin.threat.infrastructure;

import com.baotin.threat.domain.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.json.JsonMapper;

/** Stores only masked message wording; identity/account/session never enters public events. */
@Component
public class PublicMessages {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    public PublicMessages(JdbcTemplate jdbc,JsonMapper json) { this.jdbc=jdbc;this.json=json; }
    @Transactional(propagation=Propagation.MANDATORY)
    public UUID record(String input,String source,String sourceName,String label,Instant at) {
        String text=SensitiveText.redact(input);if(text.isBlank())throw new IllegalArgumentException("Empty masked message");
        String fingerprint=new TextNormalizer().fingerprint(text);
        var identity=TargetIdentity.of("MESSAGE_TEMPLATE",fingerprint);

        var row=jdbc.queryForMap("INSERT INTO threat.targets(id,type,canonical_hash,normalized_value,display_preview,first_seen_at,last_seen_at,created_at,updated_at) VALUES(?,'MESSAGE_TEMPLATE',?,NULL,?,?,?, ?,?) ON CONFLICT(type,canonical_hash) DO UPDATE SET last_seen_at=EXCLUDED.last_seen_at,updated_at=EXCLUDED.updated_at RETURNING id",UUID.randomUUID(),identity.hash(),ScanStore.truncate(text,500),Timestamp.from(at),Timestamp.from(at),Timestamp.from(at),Timestamp.from(at));
        UUID id=(UUID)row.get("id");
        jdbc.update("INSERT INTO threat.public_message_templates(target_id,redacted_text,source_type,source_name,dataset_label,updated_at) VALUES(?,?,?,?,?,?) ON CONFLICT(target_id) DO UPDATE SET redacted_text=EXCLUDED.redacted_text,updated_at=EXCLUDED.updated_at,dataset_label=CASE WHEN threat.public_message_templates.dataset_label='SUSPICIOUS' OR EXCLUDED.dataset_label='SUSPICIOUS' THEN 'SUSPICIOUS' WHEN threat.public_message_templates.dataset_label='BENIGN' OR EXCLUDED.dataset_label='BENIGN' THEN 'BENIGN' ELSE 'UNKNOWN' END",id,text,source,sourceName,label,Timestamp.from(at));
        String storedLabel=jdbc.queryForObject("SELECT dataset_label FROM threat.public_message_templates WHERE target_id=?",String.class,id);
        Map<String,Object> publication=snapshot(id,text,storedLabel);
        UUID event=UUID.randomUUID();Map<String,Object> envelope=new LinkedHashMap<>();
        envelope.put("eventId",event);envelope.put("eventVersion",1);envelope.put("eventType","threat.updated");envelope.put("producer","threat-service");
        envelope.put("aggregateType","ThreatTarget");envelope.put("aggregateId",id);envelope.put("occurredAt",at);envelope.put("correlationId",UUID.randomUUID());envelope.put("causationId",null);
        envelope.put("payload",Map.of("threatTargetId",id,"targetType","MESSAGE_TEMPLATE","publicSearch",publication));
        jdbc.update("INSERT INTO threat.outbox_events(id,aggregate_type,aggregate_id,event_type,topic,message_key,payload,occurred_at,created_at) VALUES(?,'ThreatTarget',?,'threat.updated','baotin.threat.search.events.v1',?,?::jsonb,?,?)",event,id,id.toString(),json.writeValueAsString(envelope),Timestamp.from(at),Timestamp.from(at));
        return id;
    }
    public static Map<String,Object> snapshot(UUID id,String content,String label) {
        String text=SensitiveText.redact(content);
        return Map.of("visibility","PUBLIC","documentType","OTHER","referenceId",id,
            "title",ScanStore.truncate(text,200),"displayValue",ScanStore.truncate(text,253),"content",text,"status","ACTIVE",
            "metadata",Map.of("referenceType","MESSAGE_TEMPLATE","contentWarning",Set.of("SUSPICIOUS","BENIGN").contains(label==null?"UNKNOWN":label)?label:"UNKNOWN"));
    }

}
