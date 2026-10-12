package com.taxonomy.workspace.model;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Immutable original Git-event evidence; names and addresses confer no target identity or rights. */
@Entity
@org.hibernate.annotations.Immutable
@Table(name = "workspace_git_reflog_provenance", uniqueConstraints = @UniqueConstraint(
        name = "uq_workspace_reflog_origin", columnNames = {"repository_key", "origin_id", "event_ordinal"}))
public class GitReflogProvenanceRecord {
    @Id @Column(name = "id", nullable = false, length = 64) String id;
    @Column(name = "repository_key", nullable = false, length = 64) String repositoryKey;
    @Column(name = "repository_id", nullable = false, length = 255) String repositoryId;
    @Column(name = "workspace_id", length = 255) String workspaceId;
    @Column(name = "storage_repository_name", nullable = false, length = 512) String storageRepositoryName;
    @Column(name = "origin_id", nullable = false, length = 64) String originId;
    @Column(name = "event_ordinal", nullable = false) int eventOrdinal;
    @Column(name = "body_version", nullable = false) int bodyVersion;
    @Column(name = "sha256", nullable = false, length = 64) String sha256;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR) @Column(name = "body", nullable = false) String body;
    protected GitReflogProvenanceRecord() { }
}
