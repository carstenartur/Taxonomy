-- Isolated, labelled product demo (issue #1184). One demo run owns exactly one
-- project and requirement; analysis admission derives the scenario-replay
-- provider from this marker and never from a browser flag.
create table if not exists product_demo_run (
    id varchar(36) not null primary key,
    scope_key varchar(1024) not null,
    project_id bigint not null,
    requirement_id bigint not null,
    scenario_id varchar(64) not null,
    scenario_version integer not null,
    scenario_fingerprint varchar(64) not null,
    owner_username varchar(160) not null,
    created_at timestamp(6) with time zone not null,
    closed_at timestamp(6) with time zone,
    constraint uq_demo_run_project unique (project_id),
    constraint fk_demo_run_project foreign key (project_id) references arch_project (id) on delete cascade,
    constraint fk_demo_run_requirement foreign key (requirement_id) references project_requirement (id) on delete cascade
);
create index if not exists idx_demo_run_scope on product_demo_run (scope_key);
