package com.taxonomy.workspace.service;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;

/** Execution ownership and scheduling shared by durable Git operations. */
@MappedSuperclass
abstract class LeasedRepositoryOperation {
    @Id @Column(name = "operation_id", length = 64) String id;
    @Column(name = "phase", nullable = false, length = 24) String phase;
    @Column(name = "attempts", nullable = false) int attempts;
    @Column(name = "last_attempt_at") Long lastAttemptAt;
    @Column(name = "next_attempt_at") Long nextAttemptAt;
    @Column(name = "completed_at") Long completedAt;
    @Column(name = "archived_at") Long archivedAt;
    @Column(name = "executor_id", length = 64) String executor;
    @Column(name = "lease_id", length = 36) String lease;
    @Column(name = "decision_code", length = 40) String decision;

    void finish(String outcome, String decisionCode, long now) {
        phase = outcome;
        decision = decisionCode;
        completedAt = now;
        nextAttemptAt = null;
        lease = null;
    }
}
