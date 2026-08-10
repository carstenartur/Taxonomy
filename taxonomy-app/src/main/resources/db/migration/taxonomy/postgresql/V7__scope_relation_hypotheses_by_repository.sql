-- Relation hypotheses were historically partitioned only by workspace_id and
-- an optional analysis session. Repository identity and non-null scope keys are
-- required to prevent cross-repository reads and SQL NULL uniqueness gaps.

alter table relation_hypothesis
    add column repository_id varchar(255),
    add column workspace_scope_key varchar(255),
    add column analysis_session_scope_key varchar(255);

-- Persist the same canonical identities used by RepositoryContext and the
-- entity lifecycle callbacks before deriving tenant keys or matching workspace
-- provenance. Otherwise rows containing surrounding whitespace would become
-- invisible after application-level normalization.
update relation_hypothesis
set workspace_id = btrim(workspace_id)
where workspace_id is not null;

update relation_hypothesis
set workspace_id = null
where workspace_id = '';

update relation_hypothesis
set analysis_session_id = btrim(analysis_session_id)
where analysis_session_id is not null;

update relation_hypothesis
set analysis_session_id = null
where analysis_session_id = '';

do $$
declare
    unbound_workspace_hypothesis_count bigint;
    unbound_central_hypothesis_count bigint;
    primary_repository_count bigint;
    duplicate_group_count bigint;
begin
    with unambiguous_workspace_source as (
        select
            btrim(workspace_id) as workspace_id,
            max(source_repository_id) as source_repository_id
        from user_workspace
        where workspace_id is not null
          and btrim(workspace_id) <> ''
        group by btrim(workspace_id)
        having count(*) = 1
           and count(source_repository_id) = 1
    )
    update relation_hypothesis hypothesis
    set repository_id = workspace.source_repository_id
    from unambiguous_workspace_source workspace
    where hypothesis.repository_id is null
      and hypothesis.workspace_id is not null
      and workspace.workspace_id = hypothesis.workspace_id;

    select count(*)
    into unbound_workspace_hypothesis_count
    from relation_hypothesis
    where repository_id is null
      and workspace_id is not null;

    if unbound_workspace_hypothesis_count > 0 then
        raise exception
            'Cannot bind % existing workspace relation hypothesis/hypotheses: workspace source repository provenance is missing or ambiguous',
            unbound_workspace_hypothesis_count;
    end if;

    select count(*)
    into unbound_central_hypothesis_count
    from relation_hypothesis
    where repository_id is null
      and workspace_id is null;

    if unbound_central_hypothesis_count > 0 then
        select count(*)
        into primary_repository_count
        from system_repository
        where primary_repo = true;

        if primary_repository_count <> 1 then
            raise exception
                'Cannot bind % existing central relation hypothesis/hypotheses: expected exactly one primary repository, found %',
                unbound_central_hypothesis_count,
                primary_repository_count;
        end if;

        update relation_hypothesis
        set repository_id = (
            select repository_id
            from system_repository
            where primary_repo = true
        )
        where repository_id is null
          and workspace_id is null;
    end if;

    select count(*)
    into duplicate_group_count
    from (
        select
            repository_id,
            coalesce(workspace_id, '__shared__') as workspace_key,
            source_node_id,
            target_node_id,
            relation_type,
            coalesce(analysis_session_id, '__unspecified__') as session_key,
            count(*)
        from relation_hypothesis
        group by
            repository_id,
            coalesce(workspace_id, '__shared__'),
            source_node_id,
            target_node_id,
            relation_type,
            coalesce(analysis_session_id, '__unspecified__')
        having count(*) > 1
    ) duplicates;

    if duplicate_group_count > 0 then
        raise exception
            'Cannot enforce relation hypothesis tenant uniqueness: found % duplicate repository/workspace/session relation group(s)',
            duplicate_group_count;
    end if;
end
$$;

update relation_hypothesis
set workspace_scope_key = coalesce(workspace_id, '__shared__'),
    analysis_session_scope_key = coalesce(analysis_session_id, '__unspecified__');

alter table relation_hypothesis
    alter column repository_id set not null,
    alter column workspace_scope_key set not null,
    alter column analysis_session_scope_key set not null;

alter table relation_hypothesis
    drop constraint uq_hypothesis_workspace_session_relation,
    add constraint fk_relation_hypothesis_repository
        foreign key (repository_id)
        references system_repository (repository_id),
    add constraint uq_hypothesis_repository_workspace_session_relation
        unique (
            repository_id,
            workspace_scope_key,
            source_node_id,
            target_node_id,
            relation_type,
            analysis_session_scope_key);

create index idx_hyp_repository
    on relation_hypothesis (repository_id);

create index idx_hyp_repository_workspace
    on relation_hypothesis (repository_id, workspace_id);
