package com.taxonomy.workspace.service;

import jakarta.persistence.*;
import java.util.UUID;

/** Append-only source/target outcome; previous user decisions survive later polls and toggles. */
@Entity
@Table(name = "branch_adoption_event", uniqueConstraints = @UniqueConstraint(columnNames = { "rule_id", "revision" }))
public class BranchAdoptionEvent {
    @Id
    @Column(length = 36)
    String id;
    @Column(name = "rule_id", length = 64, nullable = false)
    String ruleId;
    @Column(nullable = false)
    long revision;
    @Column(name = "recorded_at", nullable = false)
    long recordedAt;
    @Column(name = "actor_id", length = 64, nullable = false)
    String actor;
    @Column(name = "source_branch", nullable = false)
    String sourceBranch;
    @Column(name = "source_commit", length = 40)
    String sourceCommit;
    @Column(name = "target_before", length = 40)
    String targetBefore;
    @Column(name = "target_after", length = 40)
    String targetAfter;
    @Column(nullable = false, length = 24)
    String status;
    @Column(length = 255)
    String detail;

    protected BranchAdoptionEvent() {
    }

    BranchAdoptionEvent(BranchAdoptionRule rule, String actor) {
        id = UUID.randomUUID().toString();
        ruleId = rule.id;
        revision = rule.revision;
        recordedAt = System.currentTimeMillis();
        this.actor = actor;
        sourceBranch = rule.sourceBranch;
        sourceCommit = rule.sourceCommit;
        targetBefore = rule.targetBefore;
        targetAfter = rule.targetAfter;
        status = rule.status;
        detail = rule.detail;
    }
}
