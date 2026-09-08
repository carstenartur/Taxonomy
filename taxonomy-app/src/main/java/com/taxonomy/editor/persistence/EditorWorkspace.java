package com.taxonomy.editor.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Durable editor persistence; mutations occur only under the workspace transaction lock. */
@Entity
@Table(name = "editor_workspace")
public class EditorWorkspace {
    @Id
    @Column(name = "scope_id", nullable = false, length = 64)
    String scopeId;

    @Column(name = "repository_id", nullable = false, length = 320)
    String repositoryId;

    @Column(name = "workspace_id", nullable = false, length = 320)
    String workspaceId;

    @Column(name = "branch", nullable = false, length = 255)
    String branch;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(name = "dsl", nullable = false)
    String dsl;

    @Column(name = "semantic_revision", nullable = false)
    long revision;

    @Column(name = "checkpoint_commit", nullable = true, length = 40)
    String checkpointCommit;

    @Column(name = "checkpoint_revision", nullable = false)
    long checkpointRevision;

    @Column(name = "pending_checkpoint", nullable = true, length = 36)
    String pendingCheckpoint;

    @Version
    @Column(name = "row_version", nullable = false)
    long rowVersion;

    protected EditorWorkspace() {}
}
