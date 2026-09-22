-- Committed client-attempt starts are not proof of provider receipt or billing.
CREATE TABLE reformulation_usage_session (
    run_id VARCHAR(36) PRIMARY KEY,
    proposal_id VARCHAR(36) NOT NULL,
    scope_key VARCHAR(1024) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    schema_version INTEGER NOT NULL,
    from_first_attempt BOOLEAN NOT NULL,
    CONSTRAINT fk_reform_usage_run FOREIGN KEY (run_id) REFERENCES reformulation_run(id)
);
CREATE TABLE reformulation_usage_attempt (
    id VARCHAR(36) PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL,
    owner_id VARCHAR(36) NOT NULL,
    lease_epoch BIGINT NOT NULL,
    invocation_id VARCHAR(36) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    source_kind VARCHAR(24) NOT NULL,
    retry_index INTEGER NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    status_code INTEGER,
    outcome VARCHAR(24),
    duration_millis BIGINT,
    input_tokens BIGINT,
    output_tokens BIGINT,
    total_tokens BIGINT,
    cached_input_tokens BIGINT,
    reasoning_tokens BIGINT,
    invalid_usage BOOLEAN,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_reform_usage_attempt_session FOREIGN KEY (run_id) REFERENCES reformulation_usage_session(run_id),
    CONSTRAINT uk_reform_usage_invocation UNIQUE (run_id, invocation_id, source_kind, retry_index)
);
CREATE INDEX idx_reform_usage_attempt_run ON reformulation_usage_attempt(run_id);
