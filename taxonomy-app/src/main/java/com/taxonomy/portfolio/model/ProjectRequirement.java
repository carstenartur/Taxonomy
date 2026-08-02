package com.taxonomy.portfolio.model;

import com.taxonomy.portfolio.model.PortfolioTypes.Criticality;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementType;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
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

/** Stable requirement identity within a project. Text changes create versions. */
@Entity
@Table(name = "project_requirement", indexes = {
        @Index(name = "idx_req_project", columnList = "project_id"),
        @Index(name = "idx_req_status", columnList = "project_id,status")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uq_req_project_key", columnNames = {"project_id", "requirement_key"})
})
public class ProjectRequirement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private ArchitectureProject project;

    @Column(name = "requirement_key", nullable = false, length = 64)
    private String requirementKey;

    @Column(nullable = false, length = 240)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RequirementStatus status = RequirementStatus.DRAFT;

    @Column(nullable = false)
    private int priority = 50;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Criticality criticality = Criticality.MEDIUM;

    @Enumerated(EnumType.STRING)
    @Column(name = "requirement_type", nullable = false, length = 32)
    private RequirementType requirementType = RequirementType.FUNCTIONAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 32)
    private ReviewStatus reviewStatus = ReviewStatus.PROPOSED;

    @Column(name = "owner_username", nullable = false, length = 160)
    private String ownerUsername;

    /** Points at the immutable current text version without creating a circular FK. */
    @Column(name = "current_version_id")
    private Long currentVersionId;

    /** Points at the immutable current analysis snapshot. */
    @Column(name = "current_snapshot_id", length = 36)
    private String currentAnalysisSnapshotId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected ProjectRequirement() {
    }

    public ProjectRequirement(ArchitectureProject project,
                              String requirementKey,
                              String title,
                              RequirementStatus status,
                              int priority,
                              Criticality criticality,
                              RequirementType requirementType,
                              ReviewStatus reviewStatus,
                              String ownerUsername,
                              Instant now) {
        this.project = project;
        this.requirementKey = requirementKey;
        this.title = title;
        this.status = status != null ? status : RequirementStatus.DRAFT;
        this.priority = priority;
        this.criticality = criticality != null ? criticality : Criticality.MEDIUM;
        this.requirementType = requirementType != null ? requirementType : RequirementType.FUNCTIONAL;
        this.reviewStatus = reviewStatus != null ? reviewStatus : ReviewStatus.PROPOSED;
        this.ownerUsername = ownerUsername;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void updateMetadata(String title,
                               RequirementStatus status,
                               Integer priority,
                               Criticality criticality,
                               RequirementType requirementType,
                               ReviewStatus reviewStatus,
                               String ownerUsername,
                               Instant now) {
        if (title != null) this.title = title;
        if (status != null) this.status = status;
        if (priority != null) this.priority = priority;
        if (criticality != null) this.criticality = criticality;
        if (requirementType != null) this.requirementType = requirementType;
        if (reviewStatus != null) this.reviewStatus = reviewStatus;
        if (ownerUsername != null) this.ownerUsername = ownerUsername;
        this.updatedAt = now;
    }

    public void pointToVersion(Long versionId, Instant now) {
        this.currentVersionId = versionId;
        this.updatedAt = now;
    }

    public void pointToAnalysis(String snapshotId, Instant now) {
        this.currentAnalysisSnapshotId = snapshotId;
        this.updatedAt = now;
    }

    public Long getId() { return id; }
    public ArchitectureProject getProject() { return project; }
    public String getRequirementKey() { return requirementKey; }
    public String getTitle() { return title; }
    public RequirementStatus getStatus() { return status; }
    public int getPriority() { return priority; }
    public Criticality getCriticality() { return criticality; }
    public RequirementType getRequirementType() { return requirementType; }
    public ReviewStatus getReviewStatus() { return reviewStatus; }
    public String getOwnerUsername() { return ownerUsername; }
    public Long getCurrentVersionId() { return currentVersionId; }
    public String getCurrentAnalysisSnapshotId() { return currentAnalysisSnapshotId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getRowVersion() { return rowVersion; }
}
