-- Durable execution ownership; original requirements and run history are unchanged.
CREATE TABLE reformulation_recovery_lease (
    run_id VARCHAR(36) PRIMARY KEY,
    dispatch_payload TEXT NOT NULL,
    owner_id VARCHAR(36),
    lease_epoch BIGINT NOT NULL DEFAULT 0,
    lease_until TIMESTAMP WITH TIME ZONE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_reform_recovery_run FOREIGN KEY (run_id) REFERENCES reformulation_run(id)
);
CREATE INDEX idx_reform_recovery_due ON reformulation_recovery_lease(active, lease_until);
