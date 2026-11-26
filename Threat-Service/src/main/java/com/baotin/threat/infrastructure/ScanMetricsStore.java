package com.baotin.threat.infrastructure;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Daily operational counters only; no scan or user identity is stored. */
@Repository
public class ScanMetricsStore {
    private final JdbcTemplate jdbc;

    public ScanMetricsStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(Instant scannedAt, String riskLevel) {
        LocalDate date = scannedAt.atZone(ZoneOffset.UTC).toLocalDate();
        boolean highRisk = "HIGH".equals(riskLevel) || "CRITICAL".equals(riskLevel);
        jdbc.update("""
            INSERT INTO threat.scan_metrics_daily(metric_date,scans,high_risk_scans,updated_at)
            VALUES (?,?,?,now())
            ON CONFLICT(metric_date) DO UPDATE SET
                scans=threat.scan_metrics_daily.scans+1,
                high_risk_scans=threat.scan_metrics_daily.high_risk_scans+EXCLUDED.high_risk_scans,
                updated_at=now()
            """, date, 1L, highRisk ? 1L : 0L);
    }

    public ScanStats today() {
        LocalDate date = LocalDate.now(ZoneOffset.UTC);
        return jdbc.query("""
            SELECT scans,high_risk_scans FROM threat.scan_metrics_daily WHERE metric_date=?
            """, rs -> rs.next()
                ? new ScanStats(rs.getLong("scans"), rs.getLong("high_risk_scans"))
                : new ScanStats(0, 0), date);
    }

    public record ScanStats(long scansToday, long highRiskScansToday) {}
}
