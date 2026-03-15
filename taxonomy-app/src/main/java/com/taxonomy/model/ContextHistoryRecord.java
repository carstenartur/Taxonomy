package com.taxonomy.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Persists navigation history with origin tracking and return-to-origin support.
 *
 * <p>Each record captures a single navigation event — the context and branch
 * the user moved away from, the context and branch they moved to, and the
 * reason for the navigation (e.g. {@code "SWITCH_BRANCH"},
 * {@code "OPEN_HISTORY"}, {@code "COMPARE"}, {@code "SEARCH"}).
 *
 * <p>The {@code originContextId} field supports "return-to-origin"
 * navigation: when a user drills into history or a comparison, the
 * original context is preserved so they can jump back to where they
 * started.
 *
 * <p>Note: a DTO called {@code ContextHistoryEntry} already exists in
 * {@code taxonomy-domain}; this entity uses the name
 * {@code ContextHistoryRecord} to avoid ambiguity.
 */
@Entity
@Table(name = "context_history_record", indexes = {
    @Index(name = "idx_history_username", columnList = "username"),
    @Index(name = "idx_history_created", columnList = "created_at")
})
public class ContextHistoryRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String username;

    @Column(name = "from_context_id")
    private String fromContextId;

    @Column(name = "to_context_id")
    private String toContextId;

    @Column(name = "from_branch")
    private String fromBranch;

    @Column(name = "to_branch")
    private String toBranch;

    @Column(name = "from_commit_id")
    private String fromCommitId;

    @Column(name = "to_commit_id")
    private String toCommitId;

    @Column
    private String reason;

    @Column(name = "origin_context_id")
    private String originContextId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public ContextHistoryRecord() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getFromContextId() {
        return fromContextId;
    }

    public void setFromContextId(String fromContextId) {
        this.fromContextId = fromContextId;
    }

    public String getToContextId() {
        return toContextId;
    }

    public void setToContextId(String toContextId) {
        this.toContextId = toContextId;
    }

    public String getFromBranch() {
        return fromBranch;
    }

    public void setFromBranch(String fromBranch) {
        this.fromBranch = fromBranch;
    }

    public String getToBranch() {
        return toBranch;
    }

    public void setToBranch(String toBranch) {
        this.toBranch = toBranch;
    }

    public String getFromCommitId() {
        return fromCommitId;
    }

    public void setFromCommitId(String fromCommitId) {
        this.fromCommitId = fromCommitId;
    }

    public String getToCommitId() {
        return toCommitId;
    }

    public void setToCommitId(String toCommitId) {
        this.toCommitId = toCommitId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getOriginContextId() {
        return originContextId;
    }

    public void setOriginContextId(String originContextId) {
        this.originContextId = originContextId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
