package com.baotin.threat.application;

import com.baotin.threat.domain.TargetIdentity;
import com.baotin.threat.infrastructure.TargetStore;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TargetService {
    public record Target(UUID id,String type,String displayValue) {}
    private final TargetStore store;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final com.baotin.threat.infrastructure.ContentProtector privacy;
    public TargetService(TargetStore store,org.springframework.jdbc.core.JdbcTemplate jdbc,com.baotin.threat.infrastructure.ContentProtector privacy) { this.store=store;this.jdbc=jdbc;this.privacy=privacy; }
    @Transactional public Target resolve(String type,String value,UUID scanId,UUID reporter) {
        if(scanId==null && reporter==null) return resolve(type,value);
        if(scanId==null || reporter==null) throw new IllegalArgumentException("Scan and reporter must be provided together");
        var scans=jdbc.queryForList("SELECT scan_type,target_id,input_hash FROM threat.scan_history WHERE id=? AND user_id=?",scanId,reporter);
        if(scans.isEmpty()) throw new java.util.NoSuchElementException();
        var scan=scans.getFirst();
        if(!("URL".equals(type)?"URL":"TEXT").equals(scan.get("scan_type"))) throw new IllegalArgumentException("Scan type mismatch");
        var target=resolve(type,value);
        boolean matches="URL".equals(type)?target.id().equals(scan.get("target_id")):privacy.hash("input:"+value).equals(scan.get("input_hash"));
        if(!matches) throw new IllegalArgumentException("Scan input mismatch");
        return target;
    }
    @Transactional public Target resolve(String type,String value) {
        var identity=TargetIdentity.of(type,value);
        String display=type.equals("URL")?identity.canonical().substring(0,Math.min(500,identity.canonical().length())):"Mẫu tin nhắn #"+identity.hash().substring(0,12);
        return new Target(store.resolve(identity),type,display);
    }
}
