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
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;

/** Queryable relation contained in an immutable exact-tenant analysis snapshot. */
@Entity
@Table(name = "req_relation_mapping", indexes = {
        @Index(name = "idx_relmap_snapshot", columnList = "scope_key,snapshot_id"),
        @Index(name = "idx_relmap_source", columnList = "scope_key,source_code"),
        @Index(name = "idx_relmap_target", columnList = "scope_key,target_code"),
        @Index(name = "idx_relmap_scope", columnList = "scope_key")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uq_relmap_signature",
                columnNames = {"scope_key", "snapshot_id", "source_code", "target_code",
                        "relation_type", "relation_origin"}),
        @UniqueConstraint(name = "uq_relmap_id_scope", columnNames = {"id", "scope_key"})
})
public class RequirementRelationMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scope_key", nullable = false,
            length = PortfolioTenantIdentity.MAX_SCOPE_KEY_LENGTH)
    private String scopeKey;

    @Column(name = "snapshot_id", nullable = false, insertable = false, updatable = false)
    private String snapshotId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({
            @JoinColumn(name = "snapshot_id", referencedColumnName = "id", nullable = false),
            @JoinColumn(name = "scope_key", referencedColumnName = "scope_key",
                    nullable = false, insertable = false, updatable = false)
    })
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
        synchronizeTenantAuthority(false);
    }

    public void review(ReviewStatus status, String user, String comment, Instant now) {
        if (status != null) this.reviewStatus = status;
        this.decisionBy = user;
        this.decisionComment = comment;
        this.decisionAt = now;
    }

    @PrePersist
    @PreUpdate
    private void synchronizeTenantAuthority() {
        synchronizeTenantAuthority(true);
    }

    private void synchronizeTenantAuthority(boolean requirePersistentParent) {
        if (snapshot == null || snapshot.getScopeKey() == null
                || snapshot.getScopeKey().isBlank()) {
            throw new IllegalArgumentException(
                    "Relation mapping snapshot must expose an exact tenant scope");
        }
        String snapshotScope = snapshot.getScopeKey().strip();
        if (scopeKey != null && !scopeKey.isBlank()
                && !snapshotScope.equals(scopeKey.strip())) {
            throw new IllegalArgumentException(
                    "Relation mapping tenant scope does not match its snapshot");
        }
        scopeKey = snapshotScope;
        if (snapshot.getId() == null) {
            if (requirePersistentParent) {
                throw new IllegalArgumentException(
                        "Relation mapping snapshot must be persisted before the mapping");
            }
            return;
        }
        if (snapshotId != null && !Objects.equals(snapshotId, snapshot.getId())) {
            throw new IllegalArgumentException(
                    "Relation mapping snapshot ID does not match its association");
        }
        snapshotId = snapshot.getId();
    }

    public Long getId() { return id; }
    public String getScopeKey() { return scopeKey; }
    public String getSnapshotId() { return snapshotId; }
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
