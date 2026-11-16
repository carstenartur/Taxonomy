package com.taxonomy.versioning.service;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** Maps the JDBC journal for Hibernate-managed dialects and validated restore schemas. */
@Entity
@Table(name = "history_index_decision", indexes = {
        @Index(name = "ix_hist_decision_operation", columnList = "operation_id,occurred_at")})
public class HistoryIndexDecisionRow {
    @Id
    @Column(name = "decision_id", length = 36, nullable = false)
    String decisionId;

    @Column(name = "operation_id", length = 64, nullable = false)
    String operationId;

    @Column(name = "actor_id", length = 64, nullable = false)
    String actorId;

    @Column(name = "decision_code", length = 40, nullable = false)
    String decisionCode;

    @Column(name = "occurred_at", nullable = false)
    long occurredAt;
}
