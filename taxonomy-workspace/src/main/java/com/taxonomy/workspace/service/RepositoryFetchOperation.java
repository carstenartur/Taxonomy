package com.taxonomy.workspace.service;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Shared admission, publication and retention fields for typed Git transfer receipts. */
@MappedSuperclass
abstract class RepositoryFetchOperation extends LeasedRepositoryOperation {
    @Column(name = "fingerprint", nullable = false, length = 64) String fingerprint;
    @Column(name = "cause_id", nullable = false, length = 64) String causeId;
    @Column(name = "actor_id", nullable = false, length = 64) String actor;
    @Column(name = "login_kind", nullable = false, length = 12) String loginKind;
    @Column(name = "binding_key", nullable = false, length = 64) String bindingKey;
    @Column(name = "policy_code", nullable = false, length = 40) String policy;
    @Column(name = "actor_scope", nullable = false, length = 255) String username;
    @Column(name = "local_branch", nullable = false, length = 255) String branch;
    @Column(name = "source_branch", nullable = false, length = 255) String sourceBranch;
    @Column(name = "fork_point", length = 40) String forkPoint;
    @Column(name = "requested_commit", nullable = false, length = 40) String requested;
    @Column(name = "expected_commit", length = 40) String expected;
    @Column(name = "observed_commit", length = 40) String observed;
    @Column(name = "result_commit", length = 40) String result;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR) @Column(name = "selected_tag") String selectedTag;
    @Column(name = "requested_tag_object", length = 40) String requestedTagObject;
    @Column(name = "result_tag_object", length = 40) String resultTagObject;
    @Column(name = "created_at", nullable = false) long createdAt;
    @Column(name = "archived_by", length = 64) String archivedBy;

    abstract RepositoryContext context();
    abstract String sourceRepositoryId();

    boolean ownedBy(RepositoryContext selected, String stableActor) {
        var owner = context();
        return actor.equals(stableActor) && owner.scope() == selected.scope()
                && owner.username().equals(selected.username()) && owner.branch().equals(selected.branch())
                && owner.repositoryId().equals(selected.repositoryId())
                && java.util.Objects.equals(owner.workspaceId(), selected.workspaceId());
    }
}
