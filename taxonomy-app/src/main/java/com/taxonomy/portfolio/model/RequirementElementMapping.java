package com.taxonomy.portfolio.model;

import com.taxonomy.portfolio.model.PortfolioTypes.ActionStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.MappingOrigin;
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

/** Queryable requirement-snapshot to taxonomy-element mapping. */
@Entity
@Table(name = "req_element_mapping", indexes = {
        @Index(name = "idx_elmap_snapshot", columnList = "snapshot_id"),
        @Index(name = "idx_elmap_node", columnList = "node_code"),
        @Index(name = "idx_elmap_action", columnList = "action_status")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uq_elmap_snap_node", columnNames = {"snapshot_id", "node_code"})
})
public class RequirementElementMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "snapshot_id", nullable = false)
    private RequirementAnalysisSnapshot snapshot;

    @Column(name = "node_code", nullable = false, length = 80)
    private String nodeCode;

    @Column(name = "node_title", length = 500)
    private String nodeTitle;

    @Column(name = "taxonomy_root", nullable = false, length = 8)
    private String taxonomyRoot;

    @Column(name = "direct_score", nullable = false)
    private int directScore;

    @Column(nullable = false)
    private double relevance;

    @Column(nullable = false)
    private double confidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "mapping_origin", nullable = false, length = 32)
    private MappingOrigin mappingOrigin;

    @Column(name = "hierarchy_path", length = 2000)
    private String hierarchyPath;

    @Column(name = "presence_reason", length = 2000)
    private String presenceReason;

    @Column(name = "selected_for_impact", nullable = false)
    private boolean selectedForImpact;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 32)
    private ReviewStatus reviewStatus = ReviewStatus.PROPOSED;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_status", nullable = false, length = 32)
    private ActionStatus actionStatus = ActionStatus.UNDECIDED;

    @Column(name = "action_evidence", length = 2000)
    private String actionEvidence;

    @Column(name = "decision_by", length = 160)
    private String decisionBy;

    @Column(name = "decision_at")
    private Instant decisionAt;

    @Column(name = "decision_comment", length = 2000)
    private String decisionComment;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected RequirementElementMapping() {
    }

    public RequirementElementMapping(RequirementAnalysisSnapshot snapshot,
                                     String nodeCode,
                                     String nodeTitle,
                                     String taxonomyRoot,
                                     int directScore,
                                     double relevance,
                                     double confidence,
                                     MappingOrigin mappingOrigin,
                                     String hierarchyPath,
                                     String presenceReason,
                                     boolean selectedForImpact) {
        this.snapshot = snapshot;
        this.nodeCode = nodeCode;
        this.nodeTitle = nodeTitle;
        this.taxonomyRoot = taxonomyRoot;
        this.directScore = directScore;
        this.relevance = relevance;
        this.confidence = confidence;
        this.mappingOrigin = mappingOrigin;
        this.hierarchyPath = hierarchyPath;
        this.presenceReason = presenceReason;
        this.selectedForImpact = selectedForImpact;
    }

    public void review(ReviewStatus reviewStatus,
                       ActionStatus actionStatus,
                       String actionEvidence,
                       String decisionBy,
                       String decisionComment,
                       Instant decisionAt) {
        if (reviewStatus != null) this.reviewStatus = reviewStatus;
        if (actionStatus != null) this.actionStatus = actionStatus;
        this.actionEvidence = actionEvidence;
        this.decisionBy = decisionBy;
        this.decisionComment = decisionComment;
        this.decisionAt = decisionAt;
    }

    public Long getId() { return id; }
    public RequirementAnalysisSnapshot getSnapshot() { return snapshot; }
    public String getNodeCode() { return nodeCode; }
    public String getNodeTitle() { return nodeTitle; }
    public String getTaxonomyRoot() { return taxonomyRoot; }
    public int getDirectScore() { return directScore; }
    public double getRelevance() { return relevance; }
    public double getConfidence() { return confidence; }
    public MappingOrigin getMappingOrigin() { return mappingOrigin; }
    public String getHierarchyPath() { return hierarchyPath; }
    public String getPresenceReason() { return presenceReason; }
    public boolean isSelectedForImpact() { return selectedForImpact; }
    public ReviewStatus getReviewStatus() { return reviewStatus; }
    public ActionStatus getActionStatus() { return actionStatus; }
    public String getActionEvidence() { return actionEvidence; }
    public String getDecisionBy() { return decisionBy; }
    public Instant getDecisionAt() { return decisionAt; }
    public String getDecisionComment() { return decisionComment; }
    public long getRowVersion() { return rowVersion; }
}
