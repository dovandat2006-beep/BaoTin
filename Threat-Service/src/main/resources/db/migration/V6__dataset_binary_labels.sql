-- Preserve original meaning: SMS 0=legitimate and URL benign were seeded as UNKNOWN.
ALTER TABLE threat.dataset_import_rows DROP CONSTRAINT dataset_import_rows_dataset_label_check;
ALTER TABLE threat.dataset_import_rows ADD CONSTRAINT dataset_import_rows_dataset_label_check
 CHECK(dataset_label IN ('SUSPICIOUS','BENIGN','UNKNOWN'));
UPDATE threat.dataset_import_rows SET dataset_label='BENIGN'
 WHERE dataset_name IN ('sms-v1','url-v1') AND dataset_label='UNKNOWN';
UPDATE threat.public_message_templates m SET dataset_label=CASE
 WHEN m.dataset_label='SUSPICIOUS' OR EXISTS(SELECT 1 FROM threat.dataset_import_rows d WHERE d.target_id=m.target_id AND d.dataset_label='SUSPICIOUS') THEN 'SUSPICIOUS'
 WHEN EXISTS(SELECT 1 FROM threat.dataset_import_rows d WHERE d.target_id=m.target_id AND d.dataset_label='BENIGN') THEN 'BENIGN'
 ELSE m.dataset_label END;
