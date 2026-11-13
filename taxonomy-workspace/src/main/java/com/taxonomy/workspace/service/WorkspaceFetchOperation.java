package com.taxonomy.workspace.service;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** Durable authority for fetching an exact Git commit into one working copy. */
@Entity
@Table(name = "workspace_fetch_operation", indexes = {
        @Index(name = "ix_ws_fetch_recovery", columnList = "phase,next_attempt_at"),
        @Index(name = "ix_ws_fetch_retention", columnList = "archived_at,completed_at")})
public class WorkspaceFetchOperation extends RepositoryFetchOperation {
    @Column(name = "repository_id", nullable = false, length = 255) String repositoryId;
    @Column(name = "workspace_id", nullable = false, length = 255) String workspaceId;

    protected WorkspaceFetchOperation() { }

    @Override RepositoryContext context() {
        return RepositoryContext.workspace(repositoryId, workspaceId, branch, username);
    }

    @Override String sourceRepositoryId() { return repositoryId; }
}
