-- Primary workspace evidence, retained across activation and every later history backup.
-- Physical storage routing is target-owned; the body contains only original event provenance.
CREATE TABLE workspace_git_reflog_provenance (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    repository_key VARCHAR(64) NOT NULL,
    repository_id VARCHAR(255) NOT NULL,
    workspace_id VARCHAR(255),
    storage_repository_name VARCHAR(512) NOT NULL,
    origin_id VARCHAR(64) NOT NULL,
    event_ordinal INTEGER NOT NULL,
    body_version INTEGER NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    body CLOB NOT NULL,
    CONSTRAINT uq_workspace_reflog_origin UNIQUE (repository_key, origin_id, event_ordinal)
);
