CREATE TABLE analysis_continuation (
    id VARCHAR(36) PRIMARY KEY,
    username VARCHAR(160) NOT NULL,
    workspace_id VARCHAR(320) NOT NULL,
    branch_name VARCHAR(512) NOT NULL,
    repository_id VARCHAR(320) NOT NULL,
    input_hash VARCHAR(64) NOT NULL,
    request_json TEXT NOT NULL,
    result_json TEXT,
    state VARCHAR(32) NOT NULL,
    claim_token VARCHAR(36),
    claim_until BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    payload_characters BIGINT NOT NULL,
    current_node VARCHAR(320),
    row_version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_analysis_cont_scope ON analysis_continuation(workspace_id, username);
CREATE TABLE analysis_question_checkpoint (
    id VARCHAR(101) PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL,
    question_key VARCHAR(64) NOT NULL,
    input_hash VARCHAR(64) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    node_codes TEXT NOT NULL,
    detail_json TEXT,
    prompt_text TEXT,
    state VARCHAR(24) NOT NULL,
    attempts INTEGER NOT NULL,
    started_at BIGINT NOT NULL,
    error_text VARCHAR(2048),
    CONSTRAINT uq_analysis_question UNIQUE (run_id, question_key),
    CONSTRAINT fk_analysis_question_run FOREIGN KEY (run_id) REFERENCES analysis_continuation(id)
);
CREATE INDEX idx_analysis_question_run ON analysis_question_checkpoint(run_id,state);
