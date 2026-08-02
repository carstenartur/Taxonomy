package com.taxonomy.portfolio.model;

import com.taxonomy.portfolio.model.PortfolioTypes.RequirementSolutionRole;
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

/** Requirement-specific coverage by a project solution. */
@Entity
@Table(name = "req_solution_link", indexes = {
        @Index(name = "idx_rsl_req", columnList = "requirement_id"),
        @Index(name = "idx_rsl_psol", columnList = "project_solution_id")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uq_rsl_solution_req", columnNames = {"project_solution_id", "requirement_id"})
})
public class RequirementSolutionLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_solution_id", nullable = false)
    private ProjectSolution projectSolution;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requirement_id", nullable = false)
    private ProjectRequirement requirement;

    @Column(name = "snapshot_id", length = 36)
    private String snapshotId;

    @Column(name = "coverage_percent", nullable = false)
    private int coveragePercent;

    @Enumerated(EnumType.STRING)
    @Column(name = "solution_role", nullable = false, length = 32)
    private RequirementSolutionRole role = RequirementSolutionRole.USES;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 32)
    private ReviewStatus reviewStatus = ReviewStatus.PROPOSED;

    @Column(length = 2000)
    private String evidence;

    @Column(name = "updated_by", nullable = false, length = 160)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected RequirementSolutionLink() {
    }

    public RequirementSolutionLink(ProjectSolution projectSolution,
                                   ProjectRequirement requirement,
                                   String snapshotId,
                                   int coveragePercent,
                                   RequirementSolutionRole role,
                                   ReviewStatus reviewStatus,
                                   String evidence,
                                   String updatedBy,
                                   Instant updatedAt) {
        this.projectSolution = projectSolution;
        this.requirement = requirement;
        this.snapshotId = snapshotId;
        this.coveragePercent = coveragePercent;
        this.role = role != null ? role : RequirementSolutionRole.USES;
        this.reviewStatus = reviewStatus != null ? reviewStatus : ReviewStatus.PROPOSED;
        this.evidence = evidence;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }

    public void update(String snapshotId,
                       int coveragePercent,
                       RequirementSolutionRole role,
                       ReviewStatus reviewStatus,
                       String evidence,
                       String updatedBy,
                       Instant updatedAt) {
        this.snapshotId = snapshotId;
        this.coveragePercent = coveragePercent;
        if (role != null) this.role = role;
        if (reviewStatus != null) this.reviewStatus = reviewStatus;
        this.evidence = evidence;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }

    public Long getId() { return id; }
    public ProjectSolution getProjectSolution() { return projectSolution; }
    public ProjectRequirement getRequirement() { return requirement; }
    public String getSnapshotId() { return snapshotId; }
    public int getCoveragePercent() { return coveragePercent; }
    public RequirementSolutionRole getRole() { return role; }
    public ReviewStatus getReviewStatus() { return reviewStatus; }
    public String getEvidence() { return evidence; }
    public String getUpdatedBy() { return updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getRowVersion() { return rowVersion; }
}
