package com.taxonomy.workspace.service;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** Append-only phase and authorization outcomes. No DSL, error messages or login credentials are retained. */
@Entity
@Table(name = "workspace_publication_decision", indexes = @Index(name = "ix_ws_publish_decision", columnList = "operation_id,id"))
public class WorkspacePublicationDecision {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @Column(name = "operation_id", nullable = false, updatable = false, length = 64) String operation;
    @Column(name = "executor_id", nullable = false, updatable = false, length = 64) String executor;
    @Column(name = "original_actor", nullable = false, updatable = false, length = 64) String actor;
    @Column(name = "decision_at", nullable = false, updatable = false) long at;
    @Column(name = "phase_code", nullable = false, updatable = false, length = 28) String phase;
    @Column(name = "decision_code", nullable = false, updatable = false, length = 48) String decision;
    protected WorkspacePublicationDecision() { }
    WorkspacePublicationDecision(WorkspacePublicationOperation row,String by,long at,String decision) {
        this.operation=row.id; this.actor=row.actor; this.phase=row.step.name();
        this.executor=by; this.at=at; this.decision=decision;
    }
}
