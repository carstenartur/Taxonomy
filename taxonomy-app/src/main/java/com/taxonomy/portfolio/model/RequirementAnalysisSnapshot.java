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
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Immutable, reproducible result of analyzing one exact requirement version.
 *
 * <p>The complete typed analysis result and its derived intelligence views are
 * serialized as JSON so old snapshots remain displayable after the live
 * taxonomy, prompt templates or relation graph have changed. Queryable element
 * and relation mappings are stored in dedicated tables.</p>
 */
@Entity
@Table(name = "req_analysis_snapshot", indexes = {
        @Index(name = "idx_snap_project", columnList = "project_id,created_at"),
        @Index(name = "idx_snap_req", columnList = "requirement_id,created_at"),
        @Index(name = "idx_snap_job", columnList = "job_id")
})
public class RequirementAnalysisSnapshot {

    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private ArchitectureProject project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requirement_id", nullable = false)
    private ProjectRequirement requirement;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requirement_version_id", nullable = false)
    private ProjectRequirementVersion requirementVersion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
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

    @Column(name = "workspace_id", length = 120)
    private String workspaceId;

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
    }

    public String getId() { return id; }
    public ArchitectureProject getProject() { return project; }
    public ProjectRequirement getRequirement() { return requirement; }
    public ProjectRequirementVersion getRequirementVersion() { return requirementVersion; }
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
