package com.taxonomy.portfolio.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Immutable classification of a particular retained set of observations. */
@Entity
@Table(name = "project_product_gap_review", uniqueConstraints = @UniqueConstraint(
        name = "uq_product_gap_review_revision", columnNames = {"project_id", "gap_key", "review_revision"}))
public class ProductGapReview {
    public enum Classification { CREATE, PROCURE, CHANGE, UNDECIDED }

    @Id @Column(length = 36) private String id;
    @Column(name = "project_id", nullable = false) private Long projectId;
    @Column(name = "scope_key", nullable = false, length = PortfolioTenantIdentity.MAX_SCOPE_KEY_LENGTH) private String scopeKey;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns(value = {
            @JoinColumn(name = "project_id", referencedColumnName = "id", insertable = false, updatable = false),
            @JoinColumn(name = "scope_key", referencedColumnName = "scope_key", insertable = false, updatable = false)
    }, foreignKey = @ForeignKey(name = "fk_product_gap_review_project"))
    private ArchitectureProject project;
    @Column(name = "gap_key", nullable = false, length = 64) private String gapKey;
    @Column(name = "review_revision", nullable = false) private int revision;
    @Column(name = "evidence_fingerprint", nullable = false, length = 64) private String evidenceFingerprint;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private Classification classification;
    @Column(nullable = false, length = 2000) private String rationale;
    @Column(nullable = false, length = 160) private String reviewer;
    @Column(name = "reviewed_at", nullable = false) private Instant reviewedAt;

    protected ProductGapReview() { }

    public ProductGapReview(Long projectId, String scopeKey, String gapKey, int revision, String evidenceFingerprint,
                            Classification classification, String rationale, String reviewer, Instant reviewedAt) {
        this.id = UUID.randomUUID().toString();
        this.projectId = projectId;
        this.scopeKey = scopeKey;
        this.gapKey = gapKey;
        this.revision = revision;
        this.evidenceFingerprint = evidenceFingerprint;
        this.classification = classification;
        this.rationale = rationale;
        this.reviewer = reviewer;
        this.reviewedAt = reviewedAt;
    }

    public String getGapKey() { return gapKey; }
    public int getRevision() { return revision; }
    public String getEvidenceFingerprint() { return evidenceFingerprint; }
    public Classification getClassification() { return classification; }
    public String getRationale() { return rationale; }
    public String getReviewer() { return reviewer; }
    public Instant getReviewedAt() { return reviewedAt; }
}
