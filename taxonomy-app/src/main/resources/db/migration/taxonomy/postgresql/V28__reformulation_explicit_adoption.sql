-- Immutable review material and a separately confirmed version transition receipt.
-- Depends on the existing proposal/version tables; never updates requirement text.
CREATE TABLE reformulation_adoption_preview (
    id VARCHAR(36) PRIMARY KEY,
    proposal_id VARCHAR(36) NOT NULL,
    scope_key VARCHAR(1024) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    preview_payload TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_reform_adopt_preview_scope UNIQUE (id, proposal_id, scope_key),
    CONSTRAINT fk_reform_adopt_preview_offer FOREIGN KEY (proposal_id, scope_key)
        REFERENCES reformulation_proposal (id, scope_key)
);
CREATE INDEX idx_reform_adopt_preview_offer ON reformulation_adoption_preview (proposal_id, scope_key);
CREATE TABLE reformulation_adoption (
    id VARCHAR(64) PRIMARY KEY,
    proposal_id VARCHAR(36) NOT NULL,
    preview_id VARCHAR(36) NOT NULL,
    scope_key VARCHAR(1024) NOT NULL,
    command_hash VARCHAR(64) NOT NULL,
    requirement_id BIGINT NOT NULL,
    target_version_id BIGINT NOT NULL,
    receipt_payload TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_reform_adopt_preview UNIQUE (preview_id),
    CONSTRAINT fk_reform_adopt_preview FOREIGN KEY (preview_id, proposal_id, scope_key)
        REFERENCES reformulation_adoption_preview (id, proposal_id, scope_key),
    CONSTRAINT fk_reform_adopt_version FOREIGN KEY (target_version_id, requirement_id, scope_key)
        REFERENCES project_req_version (id, requirement_id, scope_key)
);
CREATE INDEX idx_reform_adopt_offer ON reformulation_adoption (proposal_id, scope_key, created_at);
