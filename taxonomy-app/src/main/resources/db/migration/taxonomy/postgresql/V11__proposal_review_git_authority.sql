-- Proposal review status is a rebuildable projection of an exact Git decision.
-- Legacy reviewed proposals remain valid with null authority metadata; every new
-- Git-first review writes branch, commit and causation together.

alter table relation_proposal
    add column review_branch varchar(255),
    add column review_commit_id varchar(40),
    add column review_causation_id varchar(255);

alter table relation_proposal
    add constraint ck_relation_proposal_review_authority_complete
        check (
            (review_branch is null
                and review_commit_id is null
                and review_causation_id is null)
            or
            (review_branch is not null
                and review_commit_id is not null
                and review_causation_id is not null)
        ),
    add constraint ck_relation_proposal_review_commit_format
        check (
            review_commit_id is null
            or review_commit_id ~ '^[0-9a-f]{40}$'
        );

create index idx_proposal_review_commit
    on relation_proposal (repository_id, review_commit_id);
