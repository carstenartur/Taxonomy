package com.taxonomy.relations.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * Durable recovery record for a Git-authoritative relation commit whose
 * database projection did not complete.
 *
 * <p>The authoritative commit is immutable. Rebuild reconciliation may only
 * complete or supersede the record after the selected branch has been rebuilt
 * from a commit that contains that authority.</p>
 */
@Entity
@Table(name = "relation_projection_recovery",
        indexes = {
                @Index(
                        name = "idx_rel_projection_recovery_repository",
                        columnList = "repository_id"),
                @Index(
                        name = "idx_rel_projection_recovery_pending",
                        columnList = "repository_id, workspace_scope_key, branch, status, id")
        },
        uniqueConstraints = @UniqueConstraint(
                name = "uq_rel_projection_recovery_authority",
                columnNames = {
                        "repository_id",
                        "workspace_scope_key",
                        "branch",
                        "authoritative_commit_id"
                }))
public class RelationProjectionRecovery {

    private static final int FAILURE_TYPE_LIMIT = 255;
    private static final int FAILURE_MESSAGE_LIMIT = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repository_id", nullable = false, length = 255)
    private String repositoryId;

    @Column(name = "workspace_id", length = 255)
    private String workspaceId;

    @Column(name = "workspace_scope_key", nullable = false, length = 255)
    private String workspaceScopeKey = RelationDecisionProjection.CENTRAL_SCOPE_KEY;

    @Column(name = "branch", nullable = false, length = 255)
    private String branch;

    @Column(name = "previous_head_commit", length = 40)
    private String previousHeadCommit;

    @Column(name = "authoritative_commit_id", nullable = false, length = 40)
    private String authoritativeCommitId;

    @Column(name = "causation_id", nullable = false, length = 255)
    private String causationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private RecoveryStatus status = RecoveryStatus.PENDING;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "failure_type", nullable = false, length = FAILURE_TYPE_LIMIT)
    private String failureType;

    @Column(name = "failure_message", nullable = false, length = FAILURE_MESSAGE_LIMIT)
    private String failureMessage;

    @Column(name = "first_observed_at", nullable = false)
    private Instant firstObservedAt;

    @Column(name = "last_observed_at", nullable = false)
    private Instant lastObservedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @PrePersist
    void onCreate() {
        synchronize();
        Instant now = Instant.now();
        if (firstObservedAt == null) {
            firstObservedAt = now;
        }
        if (lastObservedAt == null) {
            lastObservedAt = firstObservedAt;
        }
    }

    @PreUpdate
    void onUpdate() {
        synchronize();
    }

    private void synchronize() {
        repositoryId = requireText(repositoryId, "repositoryId");
        workspaceId = normalizeOptional(workspaceId);
        workspaceScopeKey = RelationDecisionProjection.scopeKeyFor(workspaceId);
        branch = requireText(branch, "branch");
        previousHeadCommit = normalizeCommitId(
                previousHeadCommit, "previousHeadCommit", true);
        authoritativeCommitId = normalizeCommitId(
                authoritativeCommitId, "authoritativeCommitId", false);
        causationId = requireText(causationId, "causationId");
        status = Objects.requireNonNull(status, "status");
        failureType = limit(requireText(failureType, "failureType"), FAILURE_TYPE_LIMIT);
        failureMessage = limit(
                requireText(failureMessage, "failureMessage"),
                FAILURE_MESSAGE_LIMIT);
        if (attemptCount < 1) {
            throw new IllegalArgumentException("attemptCount must be positive");
        }
        if (status == RecoveryStatus.PENDING && completedAt != null) {
            throw new IllegalArgumentException(
                    "pending recovery must not have completedAt");
        }
        if (status != RecoveryStatus.PENDING && completedAt == null) {
            throw new IllegalArgumentException(
                    "completed recovery requires completedAt");
        }
    }

    public void recordFailure(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        Instant now = Instant.now();
        if (firstObservedAt == null) {
            firstObservedAt = now;
        }
        lastObservedAt = now;
        status = RecoveryStatus.PENDING;
        completedAt = null;
        attemptCount = Math.addExact(attemptCount, 1);
        failureType = limit(failure.getClass().getName(), FAILURE_TYPE_LIMIT);
        String message = normalizeOptional(failure.getMessage());
        failureMessage = limit(
                message == null ? failure.getClass().getSimpleName() : message,
                FAILURE_MESSAGE_LIMIT);
    }

    public void complete(RecoveryStatus completion) {
        if (completion == null || completion == RecoveryStatus.PENDING) {
            throw new IllegalArgumentException(
                    "completion must be RECOVERED or SUPERSEDED");
        }
        status = completion;
        completedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRepositoryId() {
        return repositoryId;
    }

    public void setRepositoryId(String repositoryId) {
        this.repositoryId = requireText(repositoryId, "repositoryId");
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(String workspaceId) {
        this.workspaceId = normalizeOptional(workspaceId);
        this.workspaceScopeKey = RelationDecisionProjection.scopeKeyFor(
                this.workspaceId);
    }

    public String getWorkspaceScopeKey() {
        return workspaceScopeKey;
    }

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = requireText(branch, "branch");
    }

    public String getPreviousHeadCommit() {
        return previousHeadCommit;
    }

    public void setPreviousHeadCommit(String previousHeadCommit) {
        this.previousHeadCommit = normalizeCommitId(
                previousHeadCommit, "previousHeadCommit", true);
    }

    public String getAuthoritativeCommitId() {
        return authoritativeCommitId;
    }

    public void setAuthoritativeCommitId(String authoritativeCommitId) {
        this.authoritativeCommitId = normalizeCommitId(
                authoritativeCommitId, "authoritativeCommitId", false);
    }

    public String getCausationId() {
        return causationId;
    }

    public void setCausationId(String causationId) {
        this.causationId = requireText(causationId, "causationId");
    }

    public RecoveryStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public String getFailureType() {
        return failureType;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public Instant getFirstObservedAt() {
        return firstObservedAt;
    }

    public Instant getLastObservedAt() {
        return lastObservedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public long getVersion() {
        return version;
    }

    private static String normalizeCommitId(
            String value,
            String field,
            boolean optional) {
        String normalized = normalizeOptional(value);
        if (normalized == null && optional) {
            return null;
        }
        if (normalized == null
                || normalized.length() != 40
                || !normalized.chars().allMatch(RelationProjectionRecovery::isHex)) {
            throw new IllegalArgumentException(
                    field + " must be a full Git object ID");
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static boolean isHex(int value) {
        return value >= '0' && value <= '9'
                || value >= 'a' && value <= 'f'
                || value >= 'A' && value <= 'F';
    }

    private static String limit(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }

    public enum RecoveryStatus {
        PENDING,
        RECOVERED,
        SUPERSEDED
    }
}
