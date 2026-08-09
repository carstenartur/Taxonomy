-- Make pre-multi-repository workspace provenance explicit without changing the
-- lifecycle of future, not-yet-provisioned workspace rows. Existing rows could
-- only have been created from the historic primary repository.

do $$
declare
    unbound_workspace_count bigint;
    primary_repository_count bigint;
begin
    select count(*)
    into unbound_workspace_count
    from user_workspace
    where source_repository_id is null;

    if unbound_workspace_count > 0 then
        select count(*)
        into primary_repository_count
        from system_repository
        where primary_repo = true;

        if primary_repository_count <> 1 then
            raise exception
                'Cannot bind % existing workspace(s): expected exactly one primary repository, found %',
                unbound_workspace_count,
                primary_repository_count;
        end if;

        update user_workspace
        set source_repository_id = (
            select repository_id
            from system_repository
            where primary_repo = true
        )
        where source_repository_id is null;
    end if;
end
$$;

alter table user_workspace
    add constraint fk_workspace_source_repository
        foreign key (source_repository_id)
        references system_repository (repository_id);
