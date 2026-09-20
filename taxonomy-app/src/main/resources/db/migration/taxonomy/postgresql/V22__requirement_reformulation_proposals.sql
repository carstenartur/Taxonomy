-- Independent proposal journal. No requirement text/version/pointer is modified.
ALTER TABLE req_analysis_snapshot ADD CONSTRAINT uq_snap_source_scope
    UNIQUE (id, requirement_version_id, requirement_id, project_id, scope_key);
CREATE TABLE reformulation_proposal (
    id VARCHAR(36) PRIMARY KEY,
    scope_key VARCHAR(1024) NOT NULL,
    project_id BIGINT NOT NULL,
    requirement_id BIGINT NOT NULL,
    source_version_id BIGINT NOT NULL,
    snapshot_id VARCHAR(36) NOT NULL,
    baseline_payload TEXT NOT NULL,
    created_by VARCHAR(160) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    current_revision BIGINT NOT NULL,
    row_version BIGINT NOT NULL,
    CONSTRAINT uq_reform_proposal_scope UNIQUE (id, scope_key),
    CONSTRAINT fk_reform_source FOREIGN KEY (source_version_id, requirement_id, scope_key)
        REFERENCES project_req_version (id, requirement_id, scope_key),
    CONSTRAINT fk_reform_snapshot FOREIGN KEY (snapshot_id, source_version_id, requirement_id, project_id, scope_key)
        REFERENCES req_analysis_snapshot (id, requirement_version_id, requirement_id, project_id, scope_key)
);
CREATE INDEX idx_reform_req ON reformulation_proposal (scope_key, project_id, requirement_id);
CREATE TABLE reformulation_revision (
    id VARCHAR(80) PRIMARY KEY,
    proposal_id VARCHAR(36) NOT NULL,
    scope_key VARCHAR(1024) NOT NULL,
    revision_number BIGINT NOT NULL,
    revision_payload TEXT NOT NULL,
    CONSTRAINT uq_reform_revision UNIQUE (proposal_id, revision_number),
    CONSTRAINT fk_reform_revision_proposal FOREIGN KEY (proposal_id, scope_key)
        REFERENCES reformulation_proposal (id, scope_key)
);
