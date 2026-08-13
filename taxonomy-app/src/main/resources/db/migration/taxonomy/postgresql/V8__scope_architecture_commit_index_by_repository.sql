-- Architecture commit-index rows are rebuildable projections of authoritative
-- JGit history. Historic rows contain neither repository nor workspace
-- provenance, so assigning them to the primary repository would silently leak
-- or misattribute workspace history. Purge the ambiguous projection and rebuild
-- it on demand with explicit RepositoryContext routing.

truncate table architecture_commit_index restart identity;

alter table architecture_commit_index
    add column repository_id varchar(255),
    add column workspace_id varchar(255),
    add column workspace_scope_key varchar(255);

alter table architecture_commit_index
    alter column repository_id set not null,
    alter column workspace_scope_key set not null,
    alter column branch set not null;

alter table architecture_commit_index
    drop constraint if exists architecture_commit_index_commit_id_key,
    add constraint fk_commit_index_repository
        foreign key (repository_id)
        references system_repository (repository_id),
    add constraint uq_commit_index_repository_workspace_branch_commit
        unique (
            repository_id,
            workspace_scope_key,
            branch,
            commit_id);

create index idx_commit_index_repository
    on architecture_commit_index (repository_id);

create index idx_commit_index_repository_workspace
    on architecture_commit_index (repository_id, workspace_id);

create index idx_commit_index_scope_branch
    on architecture_commit_index (
        repository_id,
        workspace_scope_key,
        branch);
