-- Daily scan counters for the admin dashboard. No scan identity, target, input
-- or raw content is retained here; guest scans are counted without persistence.
CREATE TABLE threat.scan_metrics_daily (
    metric_date DATE PRIMARY KEY,
    scans BIGINT NOT NULL DEFAULT 0 CHECK (scans >= 0),
    high_risk_scans BIGINT NOT NULL DEFAULT 0 CHECK (high_risk_scans >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
