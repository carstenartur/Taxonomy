-- Semantic editing authority is independent from version/checkpoint Git storage.

CREATE TABLE editor_workspace (
    scope_id VARCHAR(64) NOT NULL PRIMARY KEY,
    repository_id VARCHAR(320) NOT NULL,
    workspace_id VARCHAR(320) NOT NULL,
    branch VARCHAR(255) NOT NULL,
    dsl TEXT NOT NULL,
    semantic_revision BIGINT NOT NULL,
    checkpoint_commit VARCHAR(40),
    checkpoint_revision BIGINT NOT NULL,
    pending_checkpoint VARCHAR(36),
    row_version BIGINT NOT NULL
);

CREATE TABLE editor_operation (
    id VARCHAR(101) NOT NULL PRIMARY KEY,
    scope_id VARCHAR(64) NOT NULL,
    command_id VARCHAR(36) NOT NULL,
    actor VARCHAR(320) NOT NULL,
    occurred_at VARCHAR(40) NOT NULL,
    rationale VARCHAR(1000) NOT NULL,
    correlation_id VARCHAR(36) NOT NULL,
    causation_id VARCHAR(36) NOT NULL,
    kind VARCHAR(80) NOT NULL,
    target_operation_id VARCHAR(36),
    previous_revision BIGINT NOT NULL,
    semantic_revision BIGINT NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    body_version INTEGER NOT NULL,
    before_dsl TEXT NOT NULL,
    after_dsl TEXT NOT NULL,
    affected_ids TEXT NOT NULL,
    CONSTRAINT uq_editor_operation_revision UNIQUE (scope_id, semantic_revision)
);

CREATE TABLE editor_checkpoint (
    id VARCHAR(101) NOT NULL PRIMARY KEY,
    scope_id VARCHAR(64) NOT NULL,
    command_id VARCHAR(36) NOT NULL,
    actor VARCHAR(320) NOT NULL,
    occurred_at VARCHAR(40) NOT NULL,
    rationale VARCHAR(1000) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    from_revision BIGINT NOT NULL,
    semantic_revision BIGINT NOT NULL,
    expected_commit VARCHAR(40),
    dsl TEXT NOT NULL,
    commit_id VARCHAR(40),
    completed BOOLEAN NOT NULL,
    commit_created BOOLEAN NOT NULL,
    failure_code VARCHAR(40)
);
