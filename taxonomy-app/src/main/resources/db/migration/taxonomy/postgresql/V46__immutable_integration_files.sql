-- Binary exchange bases and reviewed output are independent of bounded JSON evidence.
CREATE TABLE interop_file (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    scope_id VARCHAR(64) NOT NULL,
    connection_id VARCHAR(36) NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    actor VARCHAR(160) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    profile VARCHAR(64) NOT NULL,
    profile_version VARCHAR(32) NOT NULL,
    binding_json TEXT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    byte_count BIGINT NOT NULL,
    media_type VARCHAR(200) NOT NULL,
    filename VARCHAR(240) NOT NULL,
    model_identifier VARCHAR(2048),
    review_fingerprint VARCHAR(64),
    losses_json TEXT NOT NULL,
    content BYTEA NOT NULL,
    created_at VARCHAR(40) NOT NULL
);
CREATE INDEX ix_interop_file_scope ON interop_file(scope_id,connection_id,actor,kind);
ALTER TABLE interop_operation ADD COLUMN file_export_json TEXT;
ALTER TABLE interop_operation ADD COLUMN base_file_id VARCHAR(36);
ALTER TABLE interop_operation ADD COLUMN result_file_id VARCHAR(36);
