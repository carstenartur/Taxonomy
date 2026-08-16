package com.taxonomy.portfolio.model;

import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
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

/** Status and retry boundary for one exact-tenant requirement inside an analysis job. */
@Entity
@Table(name = "req_analysis_item", indexes = {
        @Index(name = "idx_item_job", columnList = "scope_key,project_id,job_id"),
        @Index(name = "idx_item_req", columnList = "scope_key,project_id,requirement_id"),
        @Index(name = "idx_item_scope", columnList = "scope_key")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uq_item_job_req",
                columnNames = {"scope_key", "job_id", "requirement_id"}),
        @UniqueConstraint(name = "uq_item_id_scope", columnNames = {"id", "scope_key"})
})
public class RequirementAnalysisJobItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scope_key", nullable = false,
            length = PortfolioTenantIdentity.MAX_SCOPE_KEY_LENGTH)
    private String scopeKey;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "job_id", nullable = false, insertable = false, updatable = false)
    private String jobId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({
            @JoinColumn(name = "job_id", referencedColumnName = "id", nullable = false),
            @JoinColumn(name = "project_id", referencedColumnName = "project_id",
                    nullable = false, insertable = false, updatable = false),
            @JoinColumn(name = "scope_key", referencedColumnName = "scope_key",
                    nullable = false, insertable = false, updatable = false)
    })
    private RequirementAnalysisJob job;

    @Column(name = "requirement_id", nullable = false, insertable = false, updatable = false)
    private Long requirementId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({
            @JoinColumn(name = "requirement_id", referencedColumnName = "id", nullable = false),
            @JoinColumn(name = "project_id", referencedColumnName = "project_id",
                    nullable = false, insertable = false, updatable = false),
            @JoinColumn(name = "scope_key", referencedColumnName = "scope_key",
                    nullable = false, insertable = false, updatable = false)
    })
    private ProjectRequirement requirement;

    @Column(name = "requirement_version_id", nullable = false,
            insertable = false, updatable = false)
    private Long requirementVersionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({
            @JoinColumn(name = "requirement_version_id", referencedColumnName = "id", nullable = false),
            @JoinColumn(name = "requirement_id", referencedColumnName = "requirement_id",
                    nullable = false, insertable = false, updatable = false),
            @JoinColumn(name = "scope_key", referencedColumnName = "scope_key",
                    nullable = false, insertable = false, updatable = false)
    })
    private ProjectRequirementVersion requirementVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AnalysisStatus status = AnalysisStatus.PENDING;

    @Column(name = "snapshot_id", length = 36)
    private String snapshotId;

    /**
     * Read-only full work-identity association. A result can only point to a
     * snapshot produced for this same job, project, requirement, version and tenant.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns({
            @JoinColumn(name = "snapshot_id", referencedColumnName = "id",
                    insertable = false, updatable = false),
            @JoinColumn(name = "job_id", referencedColumnName = "job_id",
                    insertable = false, updatable = false),
            @JoinColumn(name = "requirement_id", referencedColumnName = "requirement_id",
                    insertable = false, updatable = false),
            @JoinColumn(name = "requirement_version_id",
                    referencedColumnName = "requirement_version_id",
                    insertable = false, updatable = false),
            @JoinColumn(name = "project_id", referencedColumnName = "project_id",
                    insertable = false, updatable = false),
            @JoinColumn(name = "scope_key", referencedColumnName = "scope_key",
                    insertable = false, updatable = false)
    })
    private RequirementAnalysisSnapshot snapshot;

    @Column(nullable = false)
    private int attempt = 1;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected RequirementAnalysisJobItem() {
    }

    public RequirementAnalysisJobItem(RequirementAnalysisJob job,
                                      ProjectRequirement requirement,
                                      ProjectRequirementVersion requirementVersion) {
        this.job = job;
        this.requirement = requirement;
        this.requirementVersion = requirementVersion;
        synchronizeTenantAuthority(false);
    }

    public void markRunning(Instant now) {
        this.status = AnalysisStatus.RUNNING;
        this.startedAt = now;
        this.completedAt = null;
        this.errorMessage = null;
    }

    public void complete(AnalysisStatus status, String snapshotId, Instant now) {
        if (status != AnalysisStatus.SUCCESS && status != AnalysisStatus.PARTIAL) {
            throw new IllegalArgumentException("Completed item status must be SUCCESS or PARTIAL");
        }
        this.status = status;
        this.snapshotId = snapshotId;
        this.snapshot = null;
        this.completedAt = now;
        this.errorMessage = null;
    }

    public void fail(String errorMessage, Instant now) {
        this.status = AnalysisStatus.FAILED;
        this.errorMessage = errorMessage;
        this.completedAt = now;
    }

    public void prepareRetry(ProjectRequirementVersion currentVersion) {
        this.requirementVersion = currentVersion;
        synchronizeTenantAuthority(true);
        this.status = AnalysisStatus.PENDING;
        this.snapshotId = null;
        this.snapshot = null;
        this.errorMessage = null;
        this.startedAt = null;
        this.completedAt = null;
        this.attempt++;
    }

    @PrePersist
    @PreUpdate
    private void synchronizeTenantAuthority() {
        synchronizeTenantAuthority(true);
    }

    private void synchronizeTenantAuthority(boolean requirePersistentParents) {
        if (job == null || requirement == null || requirementVersion == null) {
            throw new IllegalArgumentException(
                    "Analysis item must reference job, requirement and immutable version");
        }
        String jobScope = exactScope(job.getScopeKey(), "Analysis item job");
        String requirementScope = exactScope(
                requirement.getScopeKey(), "Analysis item requirement");
        String versionScope = exactScope(
                requirementVersion.getScopeKey(), "Analysis item requirement version");
        if (!jobScope.equals(requirementScope) || !jobScope.equals(versionScope)) {
            throw new IllegalArgumentException(
                    "Analysis item parents do not belong to the same tenant scope");
        }
        if (scopeKey != null && !scopeKey.isBlank()
                && !jobScope.equals(scopeKey.strip())) {
            throw new IllegalArgumentException(
                    "Analysis item tenant scope does not match its parents");
        }
        scopeKey = jobScope;

        Long jobProjectId = job.getProjectId();
        Long requirementProjectId = requirement.getProjectId();
        Long persistentRequirementId = requirement.getId();
        Long versionRequirementId = requirementVersion.getRequirementId();
        if (jobProjectId == null || requirementProjectId == null
                || persistentRequirementId == null || versionRequirementId == null
                || job.getId() == null || requirementVersion.getId() == null) {
            if (requirePersistentParents) {
                throw new IllegalArgumentException(
                        "Analysis item parents must be persisted before the item");
            }
            return;
        }
        if (!Objects.equals(jobProjectId, requirementProjectId)) {
            throw new IllegalArgumentException(
                    "Analysis item job and requirement belong to different projects");
        }
        if (!Objects.equals(persistentRequirementId, versionRequirementId)) {
            throw new IllegalArgumentException(
                    "Analysis item version belongs to another requirement");
        }
        if (projectId != null && !Objects.equals(projectId, jobProjectId)) {
            throw new IllegalArgumentException(
                    "Analysis item project ID does not match its parents");
        }
        projectId = jobProjectId;
        jobId = job.getId();
        requirementId = persistentRequirementId;
        requirementVersionId = requirementVersion.getId();
    }

    private static String exactScope(String scope, String parent) {
        if (scope == null || scope.isBlank()) {
            throw new IllegalArgumentException(parent + " must expose an exact tenant scope");
        }
        return scope.strip();
    }

    public Long getId() { return id; }
    public String getScopeKey() { return scopeKey; }
    public Long getProjectId() { return projectId; }
    public String getJobId() { return jobId; }
    public RequirementAnalysisJob getJob() { return job; }
    public Long getRequirementId() { return requirementId; }
    public ProjectRequirement getRequirement() { return requirement; }
    public Long getRequirementVersionId() { return requirementVersionId; }
    public ProjectRequirementVersion getRequirementVersion() { return requirementVersion; }
    public AnalysisStatus getStatus() { return status; }
    public String getSnapshotId() { return snapshotId; }
    public RequirementAnalysisSnapshot getSnapshot() { return snapshot; }
    public int getAttempt() { return attempt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public String getErrorMessage() { return errorMessage; }
    public long getRowVersion() { return rowVersion; }
}
