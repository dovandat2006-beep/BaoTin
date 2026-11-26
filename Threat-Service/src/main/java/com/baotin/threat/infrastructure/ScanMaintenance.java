package com.baotin.threat.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ScanMaintenance {
    private final JdbcTemplate jdbc;
    public ScanMaintenance(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Scheduled(fixedDelayString = "${scan.retention-interval-ms:60000}")
    public void expireContent() {
        jdbc.update("UPDATE threat.scan_history SET encrypted_content=NULL WHERE encrypted_content IS NOT NULL AND content_expires_at<=now()");
        jdbc.update("DELETE FROM threat.rate_limits WHERE window_id < ?", System.currentTimeMillis() / 300000 - 2);
    }
}
