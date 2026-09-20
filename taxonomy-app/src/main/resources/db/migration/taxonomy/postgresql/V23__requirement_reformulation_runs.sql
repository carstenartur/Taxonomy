CREATE TABLE reformulation_run (
    id VARCHAR(36) PRIMARY KEY,
    proposal_id VARCHAR(36) NOT NULL,
    scope_key VARCHAR(1024) NOT NULL,
    run_payload TEXT NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_reform_run_proposal FOREIGN KEY (proposal_id, scope_key)
        REFERENCES reformulation_proposal (id, scope_key)
);
CREATE INDEX idx_reform_run_proposal ON reformulation_run (proposal_id, scope_key);
