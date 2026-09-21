ALTER TABLE reformulation_run ADD COLUMN cancelled_by VARCHAR(255);
ALTER TABLE reformulation_run ADD COLUMN cancelled_at TIMESTAMP(6) WITH TIME ZONE;

CREATE TABLE reformulation_node_checkpoint (
    id VARCHAR(64) PRIMARY KEY,
    proposal_id VARCHAR(36) NOT NULL,
    scope_key VARCHAR(1024) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    task_kind VARCHAR(16) NOT NULL,
    input_fingerprint VARCHAR(64) NOT NULL,
    result_payload TEXT NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_reform_checkpoint_proposal FOREIGN KEY (proposal_id, scope_key)
        REFERENCES reformulation_proposal (id, scope_key),
    CONSTRAINT fk_reform_checkpoint_run FOREIGN KEY (run_id) REFERENCES reformulation_run (id)
);
CREATE INDEX idx_reform_checkpoint_proposal ON reformulation_node_checkpoint (proposal_id, scope_key);
