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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;

/** Status and retry boundary for one requirement inside a batch analysis job. */
@Entity
@Table(name = "req_analysis_item", indexes = {
        @Index(name = "idx_item_job", columnList = "job_id"),
        @Index(name = "idx_item_req", columnList = "requirement_id")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uq_item_job_req", columnNames = {"job_id", "requirement_id"})
})
public class RequirementAnalysisJobItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private RequirementAnalysisJob job;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requirement_id", nullable = false)
    private ProjectRequirement requirement;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requirement_version_id", nullable = false)
    private ProjectRequirementVersion requirementVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AnalysisStatus status = AnalysisStatus.PENDING;

    @Column(name = "snapshot_id", length = 36)
    private String snapshotId;

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
        this.status = AnalysisStatus.PENDING;
        this.snapshotId = null;
        this.errorMessage = null;
        this.startedAt = null;
        this.completedAt = null;
        this.attempt++;
    }

    public Long getId() { return id; }
    public RequirementAnalysisJob getJob() { return job; }
    public ProjectRequirement getRequirement() { return requirement; }
    public ProjectRequirementVersion getRequirementVersion() { return requirementVersion; }
    public AnalysisStatus getStatus() { return status; }
    public String getSnapshotId() { return snapshotId; }
    public int getAttempt() { return attempt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public String getErrorMessage() { return errorMessage; }
    public long getRowVersion() { return rowVersion; }
}
