package com.baotin.report.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class EvidenceCleanup {
    private final JdbcTemplate jdbc;
    private final EvidenceStorage storage;
    public EvidenceCleanup(JdbcTemplate jdbc,EvidenceStorage storage) {this.jdbc=jdbc;this.storage=storage;}
    @Scheduled(fixedDelayString="${report.cleanup-delay-ms:60000}")
    public void cleanup() {
        var rows=jdbc.queryForList("SELECT id,object_key FROM report.evidence WHERE deleted_at IS NOT NULL AND purged_at IS NULL LIMIT 20");
        for(var row:rows) try {
            storage.remove(row.get("object_key").toString());
            jdbc.update("UPDATE report.evidence SET purged_at=CURRENT_TIMESTAMP WHERE id=?",row.get("id"));
        } catch(com.baotin.report.api.ReportFailure e) { break; }
    }
}
