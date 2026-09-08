package com.taxonomy.editor.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Durable editor persistence; mutations occur only under the workspace transaction lock. */
@Entity
@Table(name = "editor_checkpoint")
public class EditorCheckpoint {
    @Id
    @Column(name = "id", nullable = false, length = 101)
    String id;

    @Column(name = "scope_id", nullable = false, length = 64)
    String scopeId;

    @Column(name = "command_id", nullable = false, length = 36)
    String commandId;

    @Column(name = "actor", nullable = false, length = 320)
    String actor;

    @Column(name = "occurred_at", nullable = false, length = 40)
    String occurredAt;

    @Column(name = "rationale", nullable = false, length = 1000)
    String rationale;

    @Column(name = "fingerprint", nullable = false, length = 64)
    String fingerprint;

    @Column(name = "from_revision", nullable = false)
    long fromRevision;

    @Column(name = "semantic_revision", nullable = false)
    long revision;

    @Column(name = "expected_commit", nullable = true, length = 40)
    String expectedCommit;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(name = "dsl", nullable = false)
    String dsl;

    @Column(name = "commit_id", nullable = true, length = 40)
    String commitId;

    @Column(name = "completed", nullable = false)
    boolean completed;

    @Column(name = "commit_created", nullable = false)
    boolean commitCreated;

    @Column(name = "failure_code", length = 40)
    String failureCode;

    protected EditorCheckpoint() {}
}
