package com.taxonomy.workspace.service;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** A central fork owns this receipt independently of every personal working copy. */
@Entity
@Table(name = "central_fetch_operation", indexes = {
        @Index(name = "ix_cf_fetch_recovery", columnList = "phase,next_attempt_at"),
        @Index(name = "ix_cf_fetch_retention", columnList = "archived_at,completed_at")})
public class CentralFetchOperation extends RepositoryFetchOperation {
    @Column(name = "target_repository_id", nullable = false, length = 255) String targetRepositoryId;
    @Column(name = "source_repository_id", nullable = false, length = 255) String sourceRepositoryId;

    protected CentralFetchOperation() { }

    @Override RepositoryContext context() {
        return RepositoryContext.centralWrite(targetRepositoryId, branch, username);
    }

    @Override String sourceRepositoryId() { return sourceRepositoryId; }
}
