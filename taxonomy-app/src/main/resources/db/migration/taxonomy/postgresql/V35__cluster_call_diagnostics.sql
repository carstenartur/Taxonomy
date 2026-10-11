-- Logical evaluations and physical transport admissions remain separate durable evidence.
-- NULL observation_json preserves unresolved START; no recovery infers success or zero usage.
CREATE TABLE analysis_cluster_call (
    id VARCHAR(64) PRIMARY KEY,
    operation_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(1200) NOT NULL,
    call_number BIGINT NOT NULL,
    execution_id VARCHAR(36) NOT NULL,
    logical_ordinal INTEGER NOT NULL,
    delivery_attempt INTEGER NOT NULL,
    provider VARCHAR(128) NOT NULL,
    node VARCHAR(512),
    state VARCHAR(32) NOT NULL,
    started_at BIGINT NOT NULL,
    duration_millis BIGINT NOT NULL,
    event_revision BIGINT NOT NULL,
    prompt_text TEXT,
    response_text TEXT,
    error_text VARCHAR(1024),
    prompt_length INTEGER NOT NULL,
    response_length INTEGER NOT NULL,
    CONSTRAINT uq_analysis_cluster_call_number UNIQUE (operation_id, call_number)
);
CREATE INDEX idx_analysis_cluster_call_run ON analysis_cluster_call (operation_id, call_number);

CREATE TABLE analysis_cluster_attempt (
    id VARCHAR(36) PRIMARY KEY,
    operation_id VARCHAR(128) NOT NULL,
    call_id VARCHAR(64) NOT NULL,
    invocation_id VARCHAR(36) NOT NULL,
    provider VARCHAR(128) NOT NULL,
    source VARCHAR(32) NOT NULL,
    retry_index INTEGER NOT NULL,
    started_at BIGINT NOT NULL,
    observation_json TEXT
);
CREATE INDEX idx_analysis_cluster_attempt_call ON analysis_cluster_attempt (operation_id, call_id, started_at);
