package com.taxonomy.editor.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Append-only reconstructible semantic revision; never updated or deleted by the editor. */
@Entity
@org.hibernate.annotations.Immutable
@Table(name = "editor_operation", uniqueConstraints = @UniqueConstraint(name = "uq_editor_operation_revision", columnNames = {"scope_id", "semantic_revision"}))
public class EditorOperation {
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

    @Column(name = "correlation_id", nullable = false, length = 36)
    String correlationId;

    @Column(name = "causation_id", nullable = false, length = 36)
    String causationId;

    @Column(name = "kind", nullable = false, length = 80)
    String kind;

    @Column(name = "target_operation_id", nullable = true, length = 36)
    String targetOperationId;

    @Column(name = "previous_revision", nullable = false)
    long previousRevision;

    @Column(name = "semantic_revision", nullable = false)
    long revision;

    @Column(name = "fingerprint", nullable = false, length = 64)
    String fingerprint;

    @Column(name = "body_version", nullable = false)
    int bodyVersion;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(name = "before_dsl", nullable = false)
    String beforeDsl;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(name = "after_dsl", nullable = false)
    String afterDsl;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(name = "affected_ids", nullable = false)
    String affectedIds;

    protected EditorOperation() {}
}
