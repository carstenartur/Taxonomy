package com.taxonomy.relations.model;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.model.ProposalStatus;
import com.taxonomy.model.RelationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Nationalized;

import java.time.Instant;

/** A proposed relation awaiting human review in one repository tenant. */
@Entity
@Table(name = "relation_proposal",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_relation_proposal_scope",
                columnNames = {
                        "repository_id",
                        "source_node_id",
                        "target_node_id",
                        "relation_type",
                        "workspace_scope_key"
                }),
        indexes = {
                @Index(name = "idx_proposal_repository", columnList = "repository_id"),
                @Index(
                        name = "idx_proposal_repository_workspace",
                        columnList = "repository_id, workspace_id"),
                @Index(name = "idx_proposal_owner", columnList = "owner_username"),
                @Index(
                        name = "idx_proposal_review_commit",
                        columnList = "repository_id, review_commit_id")
        })
public class RelationProposal {

    public static final String SHARED_SCOPE_KEY = "__shared__";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Mandatory logical repository tenant. */
    @Column(name = "repository_id", nullable = false, length = 255)
    private String repositoryId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_node_id", nullable = false)
    private TaxonomyNode sourceNode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_node_id", nullable = false)
    private TaxonomyNode targetNode;

    @Enumerated(EnumType.STRING)
    @Column(name = "relation_type", nullable = false)
    private RelationType relationType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProposalStatus status = ProposalStatus.PENDING;

    @Column(nullable = false)
    private double confidence;

    @Nationalized
    @Column(length = 2000)
    private String rationale;

    @Nationalized
    private String provenance;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    /** Exact branch whose Git commit made the review decision authoritative. */
    @Column(name = "review_branch", length = 255)
    private String reviewBranch;

    /** Full immutable Git commit that is authoritative for the review decision. */
    @Column(name = "review_commit_id", length = 40)
    private String reviewCommitId;

    /** Idempotency/causation token embedded in the authoritative commit metadata. */
    @Column(name = "review_causation_id", length = 255)
    private String reviewCausationId;

    @Column(name = "workspace_id")
    private String workspaceId;

    /**
     * Non-null uniqueness key for central and personal workspace proposal rows.
     */
    @Column(name = "workspace_scope_key", nullable = false, length = 255)
    private String workspaceScopeKey = SHARED_SCOPE_KEY;

    @Column(name = "owner_username")
    private String ownerUsername;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        synchronizeTenantKeys();
    }

    @PreUpdate
    void onUpdate() {
        synchronizeTenantKeys();
    }

    private void synchronizeTenantKeys() {
        repositoryId = requireText(repositoryId, "repositoryId");
        workspaceId = normalizeOptional(workspaceId);
        workspaceScopeKey = scopeKeyFor(workspaceId);
        ownerUsername = normalizeOptional(ownerUsername);
        reviewBranch = normalizeOptional(reviewBranch);
        reviewCommitId = normalizeOptional(reviewCommitId);
        reviewCausationId = normalizeOptional(reviewCausationId);
    }

    public static String scopeKeyFor(String workspaceId) {
        String normalized = normalizeOptional(workspaceId);
        return normalized == null ? SHARED_SCOPE_KEY : normalized;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRepositoryId() { return repositoryId; }
    public void setRepositoryId(String repositoryId) {
        this.repositoryId = requireText(repositoryId, "repositoryId");
    }

    public TaxonomyNode getSourceNode() { return sourceNode; }
    public void setSourceNode(TaxonomyNode sourceNode) { this.sourceNode = sourceNode; }

    public TaxonomyNode getTargetNode() { return targetNode; }
    public void setTargetNode(TaxonomyNode targetNode) { this.targetNode = targetNode; }

    public RelationType getRelationType() { return relationType; }
    public void setRelationType(RelationType relationType) { this.relationType = relationType; }

    public ProposalStatus getStatus() { return status; }
    public void setStatus(ProposalStatus status) { this.status = status; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    public String getRationale() { return rationale; }
    public void setRationale(String rationale) { this.rationale = rationale; }

    public String getProvenance() { return provenance; }
    public void setProvenance(String provenance) { this.provenance = provenance; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }

    public String getReviewBranch() { return reviewBranch; }
    public void setReviewBranch(String reviewBranch) {
        this.reviewBranch = normalizeOptional(reviewBranch);
    }

    public String getReviewCommitId() { return reviewCommitId; }
    public void setReviewCommitId(String reviewCommitId) {
        this.reviewCommitId = normalizeOptional(reviewCommitId);
    }

    public String getReviewCausationId() { return reviewCausationId; }
    public void setReviewCausationId(String reviewCausationId) {
        this.reviewCausationId = normalizeOptional(reviewCausationId);
    }

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) {
        this.workspaceId = normalizeOptional(workspaceId);
        this.workspaceScopeKey = scopeKeyFor(this.workspaceId);
    }

    public String getWorkspaceScopeKey() { return workspaceScopeKey; }

    public String getOwnerUsername() { return ownerUsername; }
    public void setOwnerUsername(String ownerUsername) {
        this.ownerUsername = normalizeOptional(ownerUsername);
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }
}
