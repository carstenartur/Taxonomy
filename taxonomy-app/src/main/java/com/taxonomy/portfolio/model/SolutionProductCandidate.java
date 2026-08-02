package com.taxonomy.portfolio.model;

import com.taxonomy.portfolio.model.PortfolioTypes.ProductSelectionStatus;
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

/** Reviewed product candidate for a project solution, never an autonomous purchase decision. */
@Entity
@Table(name = "solution_product", indexes = {
        @Index(name = "idx_solprod_psol", columnList = "project_solution_id"),
        @Index(name = "idx_solprod_product", columnList = "product_id")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uq_solprod_pair", columnNames = {"project_solution_id", "product_id"})
})
public class SolutionProductCandidate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_solution_id", nullable = false)
    private ProjectSolution projectSolution;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private ProductCatalogEntry product;

    @Column(name = "coverage_percent", nullable = false)
    private int coveragePercent;

    @Column(name = "hard_exclusions", length = 4000)
    private String hardExclusions;

    @Column(length = 4000)
    private String strengths;

    @Column(length = 4000)
    private String weaknesses;

    @Column(name = "open_evidence", length = 4000)
    private String openEvidence;

    @Column(nullable = false)
    private double confidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 32)
    private ReviewStatus reviewStatus = ReviewStatus.PROPOSED;

    @Enumerated(EnumType.STRING)
    @Column(name = "selection_status", nullable = false, length = 32)
    private ProductSelectionStatus selectionStatus = ProductSelectionStatus.CANDIDATE;

    @Column(name = "updated_by", nullable = false, length = 160)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected SolutionProductCandidate() {
    }

    public SolutionProductCandidate(ProjectSolution projectSolution,
                                    ProductCatalogEntry product,
                                    int coveragePercent,
                                    String hardExclusions,
                                    String strengths,
                                    String weaknesses,
                                    String openEvidence,
                                    double confidence,
                                    ReviewStatus reviewStatus,
                                    ProductSelectionStatus selectionStatus,
                                    String updatedBy,
                                    Instant updatedAt) {
        this.projectSolution = projectSolution;
        this.product = product;
        this.coveragePercent = coveragePercent;
        this.hardExclusions = hardExclusions;
        this.strengths = strengths;
        this.weaknesses = weaknesses;
        this.openEvidence = openEvidence;
        this.confidence = confidence;
        this.reviewStatus = reviewStatus != null ? reviewStatus : ReviewStatus.PROPOSED;
        this.selectionStatus = selectionStatus != null
                ? selectionStatus : ProductSelectionStatus.CANDIDATE;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }

    public void update(int coveragePercent,
                       String hardExclusions,
                       String strengths,
                       String weaknesses,
                       String openEvidence,
                       double confidence,
                       ReviewStatus reviewStatus,
                       ProductSelectionStatus selectionStatus,
                       String updatedBy,
                       Instant updatedAt) {
        this.coveragePercent = coveragePercent;
        this.hardExclusions = hardExclusions;
        this.strengths = strengths;
        this.weaknesses = weaknesses;
        this.openEvidence = openEvidence;
        this.confidence = confidence;
        if (reviewStatus != null) this.reviewStatus = reviewStatus;
        if (selectionStatus != null) this.selectionStatus = selectionStatus;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }

    public Long getId() { return id; }
    public ProjectSolution getProjectSolution() { return projectSolution; }
    public ProductCatalogEntry getProduct() { return product; }
    public int getCoveragePercent() { return coveragePercent; }
    public String getHardExclusions() { return hardExclusions; }
    public String getStrengths() { return strengths; }
    public String getWeaknesses() { return weaknesses; }
    public String getOpenEvidence() { return openEvidence; }
    public double getConfidence() { return confidence; }
    public ReviewStatus getReviewStatus() { return reviewStatus; }
    public ProductSelectionStatus getSelectionStatus() { return selectionStatus; }
    public String getUpdatedBy() { return updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getRowVersion() { return rowVersion; }
}
