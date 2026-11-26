package com.baotin.threat.infrastructure;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import com.baotin.threat.domain.TargetIdentity;

@Repository
public class TargetStore {
    private final JdbcTemplate jdbc;
    private final TargetSearchEvents searchEvents;
    public TargetStore(JdbcTemplate jdbc,TargetSearchEvents searchEvents) { this.jdbc=jdbc;this.searchEvents=searchEvents; }
    @Transactional
    public UUID resolve(TargetIdentity identity) {
        String display=identity.type().equals("URL")?identity.canonical().substring(0,Math.min(500,identity.canonical().length())):"Message template #"+identity.hash().substring(0,12);
        var row=jdbc.queryForMap("INSERT INTO threat.targets(id,type,canonical_hash,normalized_value,display_preview,first_seen_at,last_seen_at,created_at,updated_at) VALUES(?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) ON CONFLICT(type,canonical_hash) DO UPDATE SET last_seen_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP RETURNING id,(xmax=0) AS inserted",UUID.randomUUID(),identity.type(),identity.hash(),identity.type().equals("URL")?identity.canonical():null,display);
        UUID id=(UUID)row.get("id");
        searchEvents.publish(Boolean.TRUE.equals(row.get("inserted"))?"threat.created":"threat.updated",id,identity.type(),
            identity.type().equals("URL")?identity.canonical():identity.hash(),Instant.now(),UUID.randomUUID());
        return id;
    }
}
