ALTER TABLE threat.scan_history ALTER COLUMN content_preview TYPE text;
CREATE TABLE threat.public_message_templates (
 target_id uuid PRIMARY KEY REFERENCES threat.targets(id),
 redacted_text text NOT NULL CHECK(length(redacted_text) BETWEEN 1 AND 10000),
 source_type varchar(20) NOT NULL CHECK(source_type IN ('SCAN','DATASET')),
 source_name varchar(80) NOT NULL, dataset_label varchar(30),
 updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE threat.dataset_import_rows (
 dataset_name varchar(80) NOT NULL, row_hash varchar(64) NOT NULL,
 target_id uuid NOT NULL REFERENCES threat.targets(id), dataset_label varchar(30) NOT NULL CHECK(dataset_label IN ('SUSPICIOUS','UNKNOWN')), imported_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(dataset_name,row_hash)
);
CREATE INDEX dataset_import_target_label ON threat.dataset_import_rows(target_id,dataset_label);
