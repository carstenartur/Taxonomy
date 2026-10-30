-- Existing comparison rows coordinate current evidence; business JSON remains immutable.
alter table inventory_comparison add column if not exists row_version bigint default 0 not null;
alter table inventory_comparison add column if not exists scope_guard_key varchar(240);
update inventory_comparison set scope_guard_key='comparison:' || id where scope_guard_key is null;
update inventory_comparison set row_version=0 where row_version is null;
alter table inventory_comparison alter column row_version set default 0;
alter table inventory_comparison alter column row_version set not null;
alter table inventory_comparison alter column scope_guard_key set not null;
do $$ begin
 if not exists (select 1 from pg_constraint where conname='uq_inventory_scope_guard' and conrelid='inventory_comparison'::regclass) then
  alter table inventory_comparison add constraint uq_inventory_scope_guard unique(scope_guard_key);
 end if;
end $$;
alter table project_solution add column if not exists inventory_comparison_id varchar(36);
alter table project_solution add column if not exists inventory_fingerprint varchar(128);
alter table project_solution add column if not exists inventory_hard_exclusions varchar(4000);
