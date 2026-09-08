-- Durable, tenant-scoped integration operations. Remote delivery and Git are recoverable separate phases.

CREATE TABLE interop_connection (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    scope_id VARCHAR(64) NOT NULL,
    repository_id VARCHAR(320) NOT NULL,
    organization_id VARCHAR(400) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    connector_id VARCHAR(64) NOT NULL,
    profile_version VARCHAR(32) NOT NULL,
    authority_mode VARCHAR(32) NOT NULL,
    remote_profile VARCHAR(100),
    project_id BIGINT,
    external_scope TEXT NOT NULL,
    created_by VARCHAR(160) NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    checkpoint_id VARCHAR(36),
    active_operation_id VARCHAR(36),
    connection_revision BIGINT NOT NULL,
    row_version BIGINT NOT NULL
);

CREATE INDEX ix_interop_connection_scope ON interop_connection (scope_id);

CREATE TABLE interop_operation (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    scope_id VARCHAR(64) NOT NULL,
    connection_id VARCHAR(36) NOT NULL,
    actor VARCHAR(160) NOT NULL,
    status VARCHAR(32) NOT NULL,
    direction VARCHAR(16) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    review_fingerprint VARCHAR(64),
    connection_revision BIGINT NOT NULL,
    context_json TEXT NOT NULL,
    document_json TEXT NOT NULL,
    changes_json TEXT NOT NULL,
    review_json TEXT,
    result_json TEXT,
    result_state_json TEXT,
    result_file_json TEXT,
    result_commit VARCHAR(40),
    result_revision BIGINT,
    failure_code VARCHAR(64),
    created_at VARCHAR(40) NOT NULL,
    updated_at VARCHAR(40) NOT NULL,
    row_version BIGINT NOT NULL
);

CREATE INDEX ix_interop_operation_scope ON interop_operation (scope_id,connection_id,created_at);

CREATE TABLE interop_identity (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    scope_id VARCHAR(64) NOT NULL,
    connection_id VARCHAR(36) NOT NULL,
    external_id VARCHAR(2048) NOT NULL,
    business_identity VARCHAR(256) NOT NULL,
    requirement_id BIGINT,
    external_version VARCHAR(2048),
    fingerprint VARCHAR(64) NOT NULL,
    external_json TEXT NOT NULL,
    internal_json TEXT NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    removed BOOLEAN NOT NULL,
    row_version BIGINT NOT NULL
);

CREATE INDEX ix_interop_identity_scope ON interop_identity (scope_id,connection_id);

CREATE TABLE interop_checkpoint (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    scope_id VARCHAR(64) NOT NULL,
    connection_id VARCHAR(36) NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    git_commit VARCHAR(40),
    external_version VARCHAR(2048),
    fingerprint VARCHAR(64) NOT NULL,
    context_json TEXT NOT NULL,
    created_at VARCHAR(40) NOT NULL
);

CREATE INDEX ix_interop_checkpoint_scope ON interop_checkpoint (scope_id,connection_id);

CREATE TABLE interop_event (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    scope_id VARCHAR(64) NOT NULL,
    connection_id VARCHAR(36) NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    actor VARCHAR(160) NOT NULL,
    occurred_at VARCHAR(40) NOT NULL,
    rationale VARCHAR(1000),
    failure_code VARCHAR(64)
);

CREATE INDEX ix_interop_event_operation ON interop_event (scope_id,operation_id,occurred_at);
