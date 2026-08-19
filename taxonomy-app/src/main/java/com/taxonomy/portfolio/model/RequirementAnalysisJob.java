package com.taxonomy.portfolio.model;

import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;

/** Persisted batch job for independently analyzing project requirements in one exact tenant. */
@Entity
@Table(name = "req_analysis_job", indexes = {
        @Index(name = "idx_job_project", columnList = "scope_key,project_id,created_at"),
        @Index(name = "idx_job_status", columnList = "scope_key,project_id,status"),
        @Index(name = "idx_job_scope", columnList = "scope_key")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uq_job_idempotency",
                columnNames = {"scope_key", "project_id", "idempotency_key"}),
        @UniqueConstraint(name = "uq_job_id_scope", columnNames = {"id", "scope_key"}),
        @UniqueConstraint(name = "uq_job_id_proj_scope",
                columnNames = {"id", "project_id", "scope_key"})
})
public class RequirementAnalysisJob {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "scope_key", nullable = false,
            length = PortfolioTenantIdentity.MAX_SCOPE_KEY_LENGTH)
    private String scopeKey;

    /** Scalar parent identity is the write authority for the tenant-bound foreign key. */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({
            @JoinColumn(name = "project_id", referencedColumnName = "id",
                    nullable = false, insertable = false, updatable = false),
            @JoinColumn(name = "scope_key", referencedColumnName = "scope_key",
                    nullable = false, insertable = false, updatable = false)
    })
    private ArchitectureProject project;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AnalysisStatus status = AnalysisStatus.PENDING;

    /** Always populated; automatically generated keys keep SQL Server uniqueness portable. */
    @Column(name = "idempotency_key", nullable = false, length = 160)
    private String idempotencyKey;

    @Column(length = 40)
    private String provider;

    @Column(name = "max_architecture_nodes", nullable = false)
    private int maxArchitectureNodes;

    @Column(name = "requested_by", nullable = false, length = 160)
    private String requestedBy;

    /** Retained as display provenance; {@link #scopeKey} is the persistence authority. */
    @Column(name = "workspace_id", length = 120)
    private String workspaceId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "total_items", nullable = false)
    private int totalItems;

    @Column(name = "successful_items", nullable = false)
    private int successfulItems;

    @Column(name = "partial_items", nullable = false)
    private int partialItems;

    @Column(name = "failed_items", nullable = false)
    private int failedItems;

    @Column(name = "error_summary", length = 2000)
    private String errorSummary;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected RequirementAnalysisJob() {
    }

    public RequirementAnalysisJob(String id,
                                  ArchitectureProject project,
                                  String idempotencyKey,
                                  String provider,
                                  int maxArchitectureNodes,
                                  String requestedBy,
                                  String workspaceId,
                                  int totalItems,
                                  Instant createdAt) {
        this.id = id;
        this.project = project;
        this.idempotencyKey = idempotencyKey != null ? idempotencyKey : "auto:" + id;
        this.provider = provider;
        this.maxArchitectureNodes = maxArchitectureNodes;
        this.requestedBy = requestedBy;
        this.workspaceId = workspaceId;
        this.totalItems = totalItems;
        this.createdAt = createdAt;
        synchronizeTenantAuthority(false);
    }

    public void markPending() {
        this.status = AnalysisStatus.PENDING;
        this.startedAt = null;
        this.completedAt = null;
        this.errorSummary = null;
    }

    public void markRunning(Instant now) {
        this.status = AnalysisStatus.RUNNING;
        this.startedAt = now;
        this.completedAt = null;
        this.errorSummary = null;
    }

    public void complete(int successfulItems,
                         int partialItems,
                         int failedItems,
                         String errorSummary,
                         Instant now) {
        this.successfulItems = successfulItems;
        this.partialItems = partialItems;
        this.failedItems = failedItems;
        this.errorSummary = errorSummary;

        int completedItems = successfulItems + partialItems + failedItems;
        if (completedItems < totalItems) {
            // Another worker may still own RUNNING items. Never expose a
            // premature SUCCESS state merely because this request claimed no
            // additional work. Preserve an existing start time, but enforce
            // the invariant that every externally visible RUNNING job has one.
            this.status = AnalysisStatus.RUNNING;
            if (this.startedAt == null) {
                this.startedAt = now;
            }
            this.completedAt = null;
            return;
        }

        this.completedAt = now;
        if (failedItems == totalItems && totalItems > 0) {
            this.status = AnalysisStatus.FAILED;
        } else if (failedItems > 0 || partialItems > 0) {
            this.status = AnalysisStatus.PARTIAL;
        } else {
            this.status = AnalysisStatus.SUCCESS;
        }
    }

    public void cancel(Instant now) {
        this.status = AnalysisStatus.CANCELLED;
        this.completedAt = now;
    }

    @PrePersist
    @PreUpdate
    private void synchronizeTenantAuthority() {
        synchronizeTenantAuthority(true);
    }

    private void synchronizeTenantAuthority(boolean requirePersistentParent) {
        if (project == null || project.getScopeKey() == null
                || project.getScopeKey().isBlank()) {
            throw new IllegalArgumentException(
                    "Analysis job project must expose an exact tenant scope");
        }
        String projectScope = project.getScopeKey().strip();
        if (scopeKey != null && !scopeKey.isBlank()
                && !projectScope.equals(scopeKey.strip())) {
            throw new IllegalArgumentException(
                    "Analysis job tenant scope does not match its project");
        }
        scopeKey = projectScope;

        Long persistentProjectId = project.getId();
        if (persistentProjectId == null) {
            if (requirePersistentParent) {
                throw new IllegalArgumentException(
                        "Analysis job project must be persisted before the job");
            }
            return;
        }
        if (projectId != null && !Objects.equals(projectId, persistentProjectId)) {
            throw new IllegalArgumentException(
                    "Analysis job project ID does not match its association");
        }
        projectId = persistentProjectId;
    }

    public String getId() { return id; }
    public String getScopeKey() { return scopeKey; }
    public Long getProjectId() { return projectId; }
    public ArchitectureProject getProject() { return project; }
    public AnalysisStatus getStatus() { return status; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getProvider() { return provider; }
    public int getMaxArchitectureNodes() { return maxArchitectureNodes; }
    public String getRequestedBy() { return requestedBy; }
    public String getWorkspaceId() { return workspaceId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public int getTotalItems() { return totalItems; }
    public int getSuccessfulItems() { return successfulItems; }
    public int getPartialItems() { return partialItems; }
    public int getFailedItems() { return failedItems; }
    public String getErrorSummary() { return errorSummary; }
    public long getRowVersion() { return rowVersion; }
}
