CREATE TABLE restore_git_owner (
    owner_id INTEGER NOT NULL PRIMARY KEY,
    generation_id VARCHAR(36) NOT NULL,
    archive_sha256 VARCHAR(64) NOT NULL,
    allocation_sha256 VARCHAR(64) NOT NULL,
    actor_id VARCHAR(36) NOT NULL,
    repository_count INTEGER NOT NULL,
    CHECK (owner_id = 1),
    CHECK (repository_count >= 0)
);

CREATE TABLE restore_git_repository (
    source_key VARCHAR(64) NOT NULL PRIMARY KEY,
    source_repository_id VARCHAR(512) NOT NULL,
    source_workspace_id VARCHAR(512),
    archive_id VARCHAR(128) NOT NULL,
    target_repository_id VARCHAR(512) NOT NULL,
    target_workspace_id VARCHAR(512),
    storage_repository_name VARCHAR(255) NOT NULL UNIQUE,
    operation_id VARCHAR(36) NOT NULL UNIQUE,
    input_sha256 VARCHAR(64) NOT NULL,
    owner_state VARCHAR(16) NOT NULL,
    proof_sha256 VARCHAR(64),
    proof_body CLOB,
    CHECK (owner_state IN ('PREPARED', 'COMPLETE')),
    CHECK ((owner_state = 'PREPARED' AND proof_sha256 IS NULL AND proof_body IS NULL)
        OR (owner_state = 'COMPLETE' AND proof_sha256 IS NOT NULL AND proof_body IS NOT NULL))
);
