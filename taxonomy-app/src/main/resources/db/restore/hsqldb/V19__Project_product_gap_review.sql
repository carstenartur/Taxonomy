create table project_product_gap_review (
    id varchar(36) primary key,
    project_id bigint not null,
    scope_key varchar(1024) not null,
    gap_key varchar(64) not null,
    review_revision integer not null,
    evidence_fingerprint varchar(64) not null,
    classification varchar(16) not null,
    rationale varchar(2000) not null,
    reviewer varchar(160) not null,
    reviewed_at timestamp(6) not null,
    constraint fk_product_gap_review_project foreign key (project_id, scope_key) references arch_project(id, scope_key),
    constraint uq_product_gap_review_revision unique(project_id, gap_key, review_revision),
    constraint ck_product_gap_review_class check (classification in ('CREATE','PROCURE','CHANGE','UNDECIDED')),
    constraint ck_product_gap_review_revision check (review_revision > 0)
);
