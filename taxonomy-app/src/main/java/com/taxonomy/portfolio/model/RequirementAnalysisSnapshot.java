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
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable, reproducible result of analyzing one exact requirement version.
 *
 * <p>The complete typed analysis result and its derived intelligence views are
 * serialized as JSON so old snapshots remain displayable after the live
 * taxonomy, prompt templates or relation graph have changed. Queryable element
 * and relation mappings are stored in dedicated tenant-bound tables.</p>
 */
@Entity
@Table(name = "req_analysis_snapshot", indexes = {
        @Index(name = "idx_snap_project", columnList = "scope_key,project_id,created_at"),
        @Index(name = "idx_snap_req", columnList = "scope_key,requirement_id,created_at"),
        @Index(name = "idx_snap_job", columnList = "scope_key,project_id,job_id"),
        @Index(name = "idx_snap_scope", columnList = "scope_key")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uq_snap_id_scope", columnNames = {"id", "scope_key"}),
        @UniqueConstraint(name = "uq_snap_req_proj_scope",
                columnNames = {"id", "requirement_id", "project_id", "scope_key"}),
        @UniqueConstraint(name = "uq_snap_item_scope",
                columnNames = {"id", "job_id", "requirement_id",
                        "requirement_version_id", "project_id", "scope_key"})
})
public class RequirementAnalysisSnapshot {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "scope_key", nullable = false,
            length = PortfolioTenantIdentity.MAX_SCOPE_KEY_LENGTH)
    private String scopeKey;

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

    @Column(name = "requirement_id", nullable = false)
    private Long requirementId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({
            @JoinColumn(name = "requirement_id", referencedColumnName = "id",
                    nullable = false, insertable = false, updatable = false),
            @JoinColumn(name = "project_id", referencedColumnName = "project_id",
                    nullable = false, insertable = false, updatable = false),
            @JoinColumn(name = "scope_key", referencedColumnName = "scope_key",
                    nullable = false, insertable = false, updatable = false)
    })
    private ProjectRequirement requirement;

    @Column(name = "requirement_version_id", nullable = false)
    private Long requirementVersionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({
            @JoinColumn(name = "requirement_version_id", referencedColumnName = "id",
                    nullable = false, insertable = false, updatable = false),
            @JoinColumn(name = "requirement_id", referencedColumnName = "requirement_id",
                    nullable = false, insertable = false, updatable = false),
            @JoinColumn(name = "scope_key", referencedColumnName = "scope_key",
                    nullable = false, insertable = false, updatable = false)
    })
    private ProjectRequirementVersion requirementVersion;

    @Column(name = "job_id", nullable = false)
    private String jobId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({
            @JoinColumn(name = "job_id", referencedColumnName = "id",
                    nullable = false, insertable = false, updatable = false),
            @JoinColumn(name = "project_id", referencedColumnName = "project_id",
                    nullable = false, insertable = false, updatable = false),
            @JoinColumn(name = "scope_key", referencedColumnName = "scope_key",
                    nullable = false, insertable = false, updatable = false)
    })
    private RequirementAnalysisJob job;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AnalysisStatus status;

    @Column(name = "analysis_session_id", nullable = false, length = 64)
    private String analysisSessionId;

    @Column(length = 40)
    private String provider;

    @Column(name = "model_name", length = 160)
    private String modelName;

    @Column(name = "prompt_fingerprint", length = 64)
    private String promptFingerprint;

    @Column(name = "taxonomy_fingerprint", length = 64)
    private String taxonomyFingerprint;

    /** Retained as immutable provenance; {@link #scopeKey} is the tenant authority. */
    @Column(name = "workspace_id", length = 120)
    private String workspaceId;

    /** Retained as immutable provenance and included in the encoded tenant scope. */
    @Column(name = "branch_name", length = 240)
    private String branchName;

    @Column(name = "commit_sha", length = 64)
    private String commitSha;

    @Column(name = "created_by", nullable = false, length = 160)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "warning_count", nullable = false)
    private int warningCount;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Lob
    @Column(name = "analysis_payload", nullable = false)
    private String analysisPayload;

    @Lob
    @Column(name = "gap_payload")
    private String gapAnalysisPayload;

    @Lob
    @Column(name = "pattern_payload")
    private String patternDetectionPayload;

    @Lob
    @Column(name = "recommendation_payload")
    private String recommendationPayload;

    protected RequirementAnalysisSnapshot() {
    }

    public RequirementAnalysisSnapshot(String id,
                                       ArchitectureProject project,
                                       ProjectRequirement requirement,
                                       ProjectRequirementVersion requirementVersion,
                                       RequirementAnalysisJob job,
                                       AnalysisStatus status,
                                       String analysisSessionId,
                                       String provider,
                                       String modelName,
                                       String promptFingerprint,
                                       String taxonomyFingerprint,
                                       String workspaceId,
                                       String branchName,
                                       String commitSha,
                                       String createdBy,
                                       Instant createdAt,
                                       long durationMs,
                                       int warningCount,
                                       String errorMessage,
                                       String analysisPayload,
                                       String gapAnalysisPayload,
                                       String patternDetectionPayload,
                                       String recommendationPayload) {
        if (status != AnalysisStatus.SUCCESS && status != AnalysisStatus.PARTIAL) {
            throw new IllegalArgumentException("Snapshot status must be SUCCESS or PARTIAL");
        }
        this.id = id;
        this.project = project;
        this.requirement = requirement;
        this.requirementVersion = requirementVersion;
        this.job = job;
        this.status = status;
        this.analysisSessionId = analysisSessionId;
        this.provider = provider;
        this.modelName = modelName;
        this.promptFingerprint = promptFingerprint;
        this.taxonomyFingerprint = taxonomyFingerprint;
        this.workspaceId = workspaceId;
        this.branchName = branchName;
        this.commitSha = commitSha;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.durationMs = durationMs;
        this.warningCount = warningCount;
        this.errorMessage = errorMessage;
        this.analysisPayload = analysisPayload;
        this.gapAnalysisPayload = gapAnalysisPayload;
        this.patternDetectionPayload = patternDetectionPayload;
        this.recommendationPayload = recommendationPayload;
        synchronizeTenantAuthority(false);
    }

    @PrePersist
    @PreUpdate
    private void synchronizeTenantAuthority() {
        synchronizeTenantAuthority(true);
    }

    private void synchronizeTenantAuthority(boolean requirePersistentParents) {
        if (project == null || requirement == null || requirementVersion == null || job == null) {
            throw new IllegalArgumentException(
                    "Analysis snapshot must reference project, requirement, version and job");
        }
        String projectScope = exactScope(project.getScopeKey(), "Snapshot project");
        String requirementScope = exactScope(requirement.getScopeKey(), "Snapshot requirement");
        String versionScope = exactScope(
                requirementVersion.getScopeKey(), "Snapshot requirement version");
        String jobScope = exactScope(job.getScopeKey(), "Snapshot analysis job");
        if (!projectScope.equals(requirementScope)
                || !projectScope.equals(versionScope)
                || !projectScope.equals(jobScope)) {
            throw new IllegalArgumentException(
                    "Analysis snapshot parents do not belong to the same tenant scope");
        }
        if (scopeKey != null && !scopeKey.isBlank()
                && !projectScope.equals(scopeKey.strip())) {
            throw new IllegalArgumentException(
                    "Analysis snapshot tenant scope does not match its parents");
        }
        scopeKey = projectScope;

        Long persistentProjectId = project.getId();
        Long requirementProjectId = requirement.getProjectId();
        Long jobProjectId = job.getProjectId();
        Long persistentRequirementId = requirement.getId();
        Long versionRequirementId = requirementVersion.getRequirementId();
        if (persistentProjectId == null || requirementProjectId == null
                || jobProjectId == null || persistentRequirementId == null
                || versionRequirementId == null || requirementVersion.getId() == null
                || job.getId() == null) {
            if (requirePersistentParents) {
                throw new IllegalArgumentException(
                        "Analysis snapshot parents must be persisted before the snapshot");
            }
            return;
        }
        if (!Objects.equals(persistentProjectId, requirementProjectId)
                || !Objects.equals(persistentProjectId, jobProjectId)) {
            throw new IllegalArgumentException(
                    "Analysis snapshot project, requirement and job do not match");
        }
        if (!Objects.equals(persistentRequirementId, versionRequirementId)) {
            throw new IllegalArgumentException(
                    "Analysis snapshot version belongs to another requirement");
        }
        projectId = persistentProjectId;
        requirementId = persistentRequirementId;
        requirementVersionId = requirementVersion.getId();
        jobId = job.getId();
    }

    private static String exactScope(String scope, String parent) {
        if (scope == null || scope.isBlank()) {
            throw new IllegalArgumentException(parent + " must expose an exact tenant scope");
        }
        return scope.strip();
    }

    public String getId() { return id; }
    public String getScopeKey() { return scopeKey; }
    public Long getProjectId() { return projectId; }
    public ArchitectureProject getProject() { return project; }
    public Long getRequirementId() { return requirementId; }
    public ProjectRequirement getRequirement() { return requirement; }
    public Long getRequirementVersionId() { return requirementVersionId; }
    public ProjectRequirementVersion getRequirementVersion() { return requirementVersion; }
    public String getJobId() { return jobId; }
    public RequirementAnalysisJob getJob() { return job; }
    public AnalysisStatus getStatus() { return status; }
    public String getAnalysisSessionId() { return analysisSessionId; }
    public String getProvider() { return provider; }
    public String getModelName() { return modelName; }
    public String getPromptFingerprint() { return promptFingerprint; }
    public String getTaxonomyFingerprint() { return taxonomyFingerprint; }
    public String getWorkspaceId() { return workspaceId; }
    public String getBranchName() { return branchName; }
    public String getCommitSha() { return commitSha; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public long getDurationMs() { return durationMs; }
    public int getWarningCount() { return warningCount; }
    public String getErrorMessage() { return errorMessage; }
    public String getAnalysisPayload() { return analysisPayload; }
    public String getGapAnalysisPayload() { return gapAnalysisPayload; }
    public String getPatternDetectionPayload() { return patternDetectionPayload; }
    public String getRecommendationPayload() { return recommendationPayload; }
}
