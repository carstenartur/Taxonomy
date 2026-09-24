-- Portable, content-addressed reformulation adoption evidence.
-- Business keys and hashes intentionally replace source-workspace database IDs.
CREATE TABLE reformulation_portable_evidence (
    id VARCHAR(64) PRIMARY KEY,
    scope_key VARCHAR(1024) NOT NULL,
    project_key VARCHAR(64) NOT NULL,
    requirement_key VARCHAR(64) NOT NULL,
    target_version_number INTEGER NOT NULL,
    schema_version VARCHAR(64) NOT NULL,
    evidence_hash VARCHAR(64) NOT NULL,
    target_text_hash VARCHAR(64) NOT NULL,
    evidence_payload TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_reform_portable_scope_hash UNIQUE (scope_key, evidence_hash)
);

CREATE INDEX idx_reform_portable_identity
    ON reformulation_portable_evidence
       (scope_key, project_key, requirement_key, target_version_number);
