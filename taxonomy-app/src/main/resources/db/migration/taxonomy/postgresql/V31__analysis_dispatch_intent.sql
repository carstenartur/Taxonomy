CREATE TABLE analysis_dispatch_intent (
    id VARCHAR(64) PRIMARY KEY,
    task_id VARCHAR(1200) NOT NULL,
    operation_id VARCHAR(128) NOT NULL,
    task_type VARCHAR(32) NOT NULL,
    routing_root VARCHAR(64),
    message_json TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    dispatch_attempts INTEGER NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    dispatched_at BIGINT,
    failure_kind VARCHAR(64),
    row_version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_analysis_dispatch_status ON analysis_dispatch_intent(status, created_at);
CREATE INDEX idx_analysis_dispatch_operation ON analysis_dispatch_intent(operation_id);
CREATE TABLE analysis_task_completion (
    id VARCHAR(64) PRIMARY KEY,
    task_id VARCHAR(1200) NOT NULL,
    operation_id VARCHAR(128) NOT NULL,
    message_type VARCHAR(64) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    completion_json TEXT NOT NULL,
    recorded_at BIGINT NOT NULL
);
CREATE INDEX idx_analysis_completion_operation ON analysis_task_completion(operation_id);
