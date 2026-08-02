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

/** Queryable relation contained in an immutable requirement analysis snapshot. */
@Entity
@Table(name = "req_relation_mapping", indexes = {
        @Index(name = "idx_relmap_snapshot", columnList = "snapshot_id"),
        @Index(name = "idx_relmap_source", columnList = "source_code"),
        @Index(name = "idx_relmap_target", columnList = "target_code")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uq_relmap_signature",
                columnNames = {"snapshot_id", "source_code", "target_code", "relation_type", "relation_origin"})
})
public class RequirementRelationMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "snapshot_id", nullable = false)
    private RequirementAnalysisSnapshot snapshot;

    @Column(name = "source_code", nullable = false, length = 80)
    private String sourceCode;

    @Column(name = "target_code", nullable = false, length = 80)
    private String targetCode;

    @Column(name = "relation_type", nullable = false, length = 64)
    private String relationType;

    @Column(name = "relation_origin", nullable = false, length = 64)
    private String relationOrigin;

    @Column(name = "relation_category", length = 32)
    private String relationCategory;

    @Column(nullable = false)
    private double relevance;

    @Column(nullable = false)
    private double confidence;

    @Column(name = "presence_reason", length = 2000)
    private String presenceReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 32)
    private ReviewStatus reviewStatus = ReviewStatus.PROPOSED;

    @Column(name = "decision_by", length = 160)
    private String decisionBy;

    @Column(name = "decision_at")
    private Instant decisionAt;

    @Column(name = "decision_comment", length = 2000)
    private String decisionComment;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected RequirementRelationMapping() {
    }

    public RequirementRelationMapping(RequirementAnalysisSnapshot snapshot,
                                      String sourceCode,
                                      String targetCode,
                                      String relationType,
                                      String relationOrigin,
                                      String relationCategory,
                                      double relevance,
                                      double confidence,
                                      String presenceReason) {
        this.snapshot = snapshot;
        this.sourceCode = sourceCode;
        this.targetCode = targetCode;
        this.relationType = relationType;
        this.relationOrigin = relationOrigin;
        this.relationCategory = relationCategory;
        this.relevance = relevance;
        this.confidence = confidence;
        this.presenceReason = presenceReason;
    }

    public void review(ReviewStatus status, String user, String comment, Instant now) {
        if (status != null) this.reviewStatus = status;
        this.decisionBy = user;
        this.decisionComment = comment;
        this.decisionAt = now;
    }

    public Long getId() { return id; }
    public RequirementAnalysisSnapshot getSnapshot() { return snapshot; }
    public String getSourceCode() { return sourceCode; }
    public String getTargetCode() { return targetCode; }
    public String getRelationType() { return relationType; }
    public String getRelationOrigin() { return relationOrigin; }
    public String getRelationCategory() { return relationCategory; }
    public double getRelevance() { return relevance; }
    public double getConfidence() { return confidence; }
    public String getPresenceReason() { return presenceReason; }
    public ReviewStatus getReviewStatus() { return reviewStatus; }
    public String getDecisionBy() { return decisionBy; }
    public Instant getDecisionAt() { return decisionAt; }
    public String getDecisionComment() { return decisionComment; }
    public long getRowVersion() { return rowVersion; }
}
