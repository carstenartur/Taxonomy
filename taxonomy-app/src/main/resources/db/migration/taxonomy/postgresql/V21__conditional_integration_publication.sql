-- Observation history is deliberately never backfilled as COMMON.
ALTER TABLE interop_connection ADD COLUMN common_checkpoint_id VARCHAR(36);
ALTER TABLE interop_checkpoint ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'OBSERVATION';
ALTER TABLE interop_checkpoint ADD COLUMN baseline_json TEXT;
ALTER TABLE interop_checkpoint ADD COLUMN publication_completion_json TEXT;

CREATE TABLE interop_publication (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    scope_id VARCHAR(64) NOT NULL,
    connection_id VARCHAR(36) NOT NULL,
    schema_version INTEGER NOT NULL,
    request_json TEXT NOT NULL,
    config_fingerprint VARCHAR(64) NOT NULL,
    preview_json TEXT,
    plan_json TEXT,
    review_json TEXT,
    phase VARCHAR(40) NOT NULL,
    predecessor_id VARCHAR(36),
    common_checkpoint_id VARCHAR(36),
    local_state_json TEXT,
    bindings_json TEXT,
    completion_json TEXT,
    failure_code VARCHAR(64),
    reserved_revision BIGINT NOT NULL,
    lease_owner VARCHAR(36),
    lease_until VARCHAR(40),
    lease_epoch BIGINT NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    updated_at VARCHAR(40) NOT NULL,
    row_version BIGINT NOT NULL
);
CREATE INDEX ix_interop_publication_scope ON interop_publication (scope_id,connection_id);

CREATE TABLE interop_publish_item (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    scope_id VARCHAR(64) NOT NULL,
    connection_id VARCHAR(36) NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    item_key VARCHAR(64) NOT NULL,
    item_ordinal INTEGER NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    intent_json TEXT NOT NULL,
    request_json TEXT,
    request_fingerprint VARCHAR(64),
    state VARCHAR(32) NOT NULL,
    receipt_json TEXT,
    attempt_count INTEGER NOT NULL,
    resubmit_allowed BOOLEAN NOT NULL,
    failure_code VARCHAR(64),
    row_version BIGINT NOT NULL,
    CONSTRAINT uq_publish_item_key UNIQUE (operation_id,item_key),
    CONSTRAINT uq_publish_item_ordinal UNIQUE (operation_id,item_ordinal),
    CONSTRAINT uq_publish_item_idempotency UNIQUE (connection_id,idempotency_key)
);
CREATE INDEX ix_interop_publish_item_scope ON interop_publish_item (scope_id,connection_id);

CREATE TABLE interop_publish_attempt (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    scope_id VARCHAR(64) NOT NULL,
    connection_id VARCHAR(36) NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    item_id VARCHAR(64) NOT NULL,
    lease_epoch BIGINT NOT NULL,
    kind VARCHAR(16) NOT NULL,
    started_at VARCHAR(40) NOT NULL,
    ended_at VARCHAR(40),
    outcome VARCHAR(64),
    receipt_json TEXT
);
CREATE INDEX ix_interop_publish_attempt_scope ON interop_publish_attempt (scope_id,connection_id);
