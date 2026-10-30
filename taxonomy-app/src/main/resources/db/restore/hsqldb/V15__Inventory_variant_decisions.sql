-- Existing comparison rows coordinate current evidence; business JSON remains immutable.
alter table inventory_comparison add column row_version bigint default 0 not null;
alter table inventory_comparison add column scope_guard_key varchar(240);
update inventory_comparison set scope_guard_key='comparison:' || id;
alter table inventory_comparison alter column scope_guard_key set not null;
alter table inventory_comparison add constraint uq_inventory_scope_guard unique(scope_guard_key);
alter table project_solution add column inventory_comparison_id varchar(36);
alter table project_solution add column inventory_fingerprint varchar(128);
alter table project_solution add column inventory_hard_exclusions varchar(4000);
