-- An optional verified reference; Threat owns the source scan and authorization.
-- No foreign key or join crosses service schema boundaries.
ALTER TABLE report.reports ADD COLUMN scan_id UUID;
