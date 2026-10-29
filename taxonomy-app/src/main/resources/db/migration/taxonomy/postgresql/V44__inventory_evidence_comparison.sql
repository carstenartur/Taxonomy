-- Typed inventory evidence is distinct from ExchangeDocument integration operations.
create table inventory_comparison (
 id varchar(200) primary key, repository_id varchar(320) not null, scope_key varchar(1024) not null,
 branch_name varchar(400) not null, project_id bigint not null, snapshot_id varchar(100) not null,
 actor varchar(160) not null, fingerprint varchar(128) not null, comparison_json text not null
);
create index ix_inventory_comparison_scope on inventory_comparison(scope_key);
create table inventory_assertion (
 id varchar(200) primary key, repository_id varchar(320) not null, scope_key varchar(1024) not null,
 branch_name varchar(400) not null, candidate_id varchar(3000) not null, assertion_json text not null
);
create index ix_inventory_assertion_scope on inventory_assertion(scope_key);
create table inventory_observation (
 id varchar(200) primary key, repository_id varchar(320) not null, scope_key varchar(1024) not null,
 branch_name varchar(400) not null, profile_id varchar(100) not null, profile_revision varchar(100) not null,
 actor varchar(160) not null, organization_id varchar(400) not null, status varchar(30) not null,
 failure_code varchar(80), created_at varchar(40) not null, observation_json text
);
create index ix_inventory_observation_scope on inventory_observation(scope_key);
create table inventory_review (
 id varchar(200) primary key, repository_id varchar(320) not null, scope_key varchar(1024) not null,
 branch_name varchar(400) not null, comparison_id varchar(36) not null, review_fingerprint varchar(128) not null,
 review_json text not null
);
create index ix_inventory_review_scope on inventory_review(scope_key);
create table inventory_mapping (
 id varchar(200) primary key, repository_id varchar(320) not null, scope_key varchar(1024) not null,
 branch_name varchar(400) not null, candidate_id varchar(3000) not null, mapping_json text not null
);
create index ix_inventory_mapping_scope on inventory_mapping(scope_key);
alter table solution_product add column inventory_comparison_id varchar(36);
alter table solution_product add column inventory_fingerprint varchar(128);
alter table solution_product add column inventory_hard_exclusions varchar(4000);
