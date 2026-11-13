-- Rebuildable branch-local native vector generations. A removed relation checkpoint owns no durable index.
create table branch_graph_vector_generation (
    checkpoint_id bigint primary key references relation_decision_projection_checkpoint(id) on delete cascade,
    generation_id varchar(36) not null,
    started_at timestamp(6) not null,
    started_by varchar(255) not null,
    published_at timestamp(6),
    state varchar(16) not null,
    source_commit varchar(40) not null,
    source_key varchar(64) not null,
    catalogue_revision varchar(255) not null,
    definition_key varchar(64) not null,
    model_key varchar(64) not null,
    model_identity clob not null,
    text_format varchar(255) not null,
    index_format varchar(64) not null,
    relations_key varchar(64) not null,
    node_count integer not null,
    relation_count integer not null,
    index_sha256 varchar(64),
    index_bytes blob
);
