-- Mirrors taxonomy V35 (cluster call diagnostics) and V36 (distributed continuation ownership).
-- NULL observation_json preserves unresolved START; no recovery infers success or zero usage.
create table analysis_cluster_call (id varchar(64) not null, operation_id varchar(128) not null, task_id varchar(1200) not null, call_number bigint not null, execution_id varchar(36) not null, logical_ordinal integer not null, delivery_attempt integer not null, provider varchar(128) not null, node varchar(512), state varchar(32) not null, started_at bigint not null, duration_millis bigint not null, event_revision bigint not null, prompt_text clob, response_text clob, error_text varchar(1024), prompt_length integer not null, response_length integer not null, primary key (id), constraint uq_analysis_cluster_call_number unique (operation_id, call_number));
create index idx_analysis_cluster_call_run on analysis_cluster_call (operation_id, call_number);
create table analysis_cluster_attempt (id varchar(36) not null, operation_id varchar(128) not null, call_id varchar(64) not null, invocation_id varchar(36) not null, provider varchar(128) not null, source varchar(32) not null, retry_index integer not null, started_at bigint not null, observation_json clob, primary key (id));
create index idx_analysis_cluster_attempt_call on analysis_cluster_attempt (operation_id, call_id, started_at);
alter table analysis_continuation add column cluster_operation_id varchar(160);
alter table analysis_continuation add column cluster_source_json clob;
alter table analysis_continuation add column cluster_catalogue_hash varchar(64);
alter table analysis_question_checkpoint add column attempt_owner varchar(36);
alter table analysis_question_checkpoint add column cluster_operation_id varchar(160);
alter table analysis_question_checkpoint add column cluster_task_id varchar(640);
alter table analysis_question_checkpoint add column cluster_delivery integer;
