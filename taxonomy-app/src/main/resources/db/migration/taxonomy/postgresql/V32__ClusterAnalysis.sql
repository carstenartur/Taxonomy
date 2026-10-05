-- Durable operation authority, exact frozen inputs, committed task effects and replayable progress.
-- Broker deliveries, consumer ownership and provider permits remain outside these records.
CREATE TABLE analysis_cluster_run (
    id VARCHAR(128) PRIMARY KEY,
    username VARCHAR(160) NOT NULL,
    scope_key VARCHAR(64) NOT NULL,
    project_id BIGINT,
    requirement_id BIGINT,
    context_json TEXT NOT NULL,
    command_json TEXT NOT NULL,
    view_json TEXT,
    result_json TEXT,
    relation_plan_json TEXT,
    state VARCHAR(32) NOT NULL,
    total_roots INTEGER NOT NULL,
    completed_roots INTEGER NOT NULL,
    event_revision BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_analysis_cluster_owner ON analysis_cluster_run (username, created_at);
CREATE INDEX idx_analysis_cluster_scope ON analysis_cluster_run (username, scope_key, created_at);

CREATE TABLE analysis_cluster_work (
    id VARCHAR(64) PRIMARY KEY,
    operation_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(1200) NOT NULL,
    task_type VARCHAR(32) NOT NULL,
    root_code VARCHAR(64),
    ordinal_number INTEGER NOT NULL,
    message_json TEXT NOT NULL,
    input_json TEXT,
    result_json TEXT,
    failure_reason VARCHAR(64),
    state VARCHAR(32) NOT NULL,
    settled BOOLEAN NOT NULL,
    delivery_attempts INTEGER NOT NULL,
    started_at BIGINT,
    finished_at BIGINT
);
CREATE INDEX idx_analysis_cluster_work_run ON analysis_cluster_work (operation_id, ordinal_number);

CREATE TABLE analysis_cluster_input (
    id VARCHAR(64) PRIMARY KEY,
    operation_id VARCHAR(128) NOT NULL,
    root_code VARCHAR(64) NOT NULL,
    input_json TEXT NOT NULL
);
CREATE INDEX idx_analysis_cluster_input_run ON analysis_cluster_input (operation_id);

CREATE TABLE analysis_cluster_event (
    id VARCHAR(64) PRIMARY KEY,
    operation_id VARCHAR(128) NOT NULL,
    event_revision BIGINT NOT NULL,
    event_json TEXT NOT NULL,
    CONSTRAINT uq_analysis_cluster_event_revision UNIQUE (operation_id, event_revision)
);
CREATE INDEX idx_analysis_cluster_event_run ON analysis_cluster_event (operation_id, event_revision);
