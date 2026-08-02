package com.taxonomy.portfolio.model;

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

/** Evidence-backed coverage of a taxonomy element by a reusable solution. */
@Entity
@Table(name = "solution_taxonomy", indexes = {
        @Index(name = "idx_soltax_solution", columnList = "solution_id"),
        @Index(name = "idx_soltax_node", columnList = "node_code")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uq_soltax_node", columnNames = {"solution_id", "node_code"})
})
public class SolutionTaxonomyCoverage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "solution_id", nullable = false)
    private SolutionDefinition solution;

    @Column(name = "node_code", nullable = false, length = 80)
    private String nodeCode;

    @Column(name = "coverage_percent", nullable = false)
    private int coveragePercent;

    @Column(length = 2000)
    private String evidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 32)
    private ReviewStatus reviewStatus = ReviewStatus.PROPOSED;

    @Column(name = "updated_by", nullable = false, length = 160)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected SolutionTaxonomyCoverage() {
    }

    public SolutionTaxonomyCoverage(SolutionDefinition solution,
                                    String nodeCode,
                                    int coveragePercent,
                                    String evidence,
                                    ReviewStatus reviewStatus,
                                    String updatedBy,
                                    Instant updatedAt) {
        this.solution = solution;
        this.nodeCode = nodeCode;
        this.coveragePercent = coveragePercent;
        this.evidence = evidence;
        this.reviewStatus = reviewStatus != null ? reviewStatus : ReviewStatus.PROPOSED;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }

    public void update(int coveragePercent,
                       String evidence,
                       ReviewStatus reviewStatus,
                       String updatedBy,
                       Instant updatedAt) {
        this.coveragePercent = coveragePercent;
        this.evidence = evidence;
        if (reviewStatus != null) this.reviewStatus = reviewStatus;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }

    public Long getId() { return id; }
    public SolutionDefinition getSolution() { return solution; }
    public String getNodeCode() { return nodeCode; }
    public int getCoveragePercent() { return coveragePercent; }
    public String getEvidence() { return evidence; }
    public ReviewStatus getReviewStatus() { return reviewStatus; }
    public String getUpdatedBy() { return updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getRowVersion() { return rowVersion; }
}
