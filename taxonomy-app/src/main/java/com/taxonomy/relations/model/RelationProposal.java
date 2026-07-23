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

/** A proposed relation awaiting human review. */
@Entity
@Table(name = "relation_proposal",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_relation_proposal_scope",
                columnNames = {
                        "source_node_id", "target_node_id", "relation_type", "workspace_scope_key"
                }),
        indexes = {
                @Index(name = "idx_proposal_workspace", columnList = "workspace_id"),
                @Index(name = "idx_proposal_owner", columnList = "owner_username")
        })
public class RelationProposal {

    public static final String SHARED_SCOPE_KEY = "__shared__";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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

    @Column(name = "workspace_id")
    private String workspaceId;

    /**
     * Non-null database uniqueness key. SQL UNIQUE permits repeated NULL values,
     * so nullable workspace_id cannot by itself protect shared-scope proposals.
     */
    @Column(name = "workspace_scope_key", length = 255)
    private String workspaceScopeKey = SHARED_SCOPE_KEY;

    @Column(name = "owner_username")
    private String ownerUsername;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        synchronizeWorkspaceScopeKey();
    }

    @PreUpdate
    void onUpdate() {
        synchronizeWorkspaceScopeKey();
    }

    private void synchronizeWorkspaceScopeKey() {
        workspaceScopeKey = scopeKeyFor(workspaceId);
    }

    public static String scopeKeyFor(String workspaceId) {
        return workspaceId == null || workspaceId.isBlank()
                ? SHARED_SCOPE_KEY : workspaceId;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

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

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) {
        this.workspaceId = workspaceId;
        synchronizeWorkspaceScopeKey();
    }

    public String getWorkspaceScopeKey() { return workspaceScopeKey; }

    public String getOwnerUsername() { return ownerUsername; }
    public void setOwnerUsername(String ownerUsername) { this.ownerUsername = ownerUsername; }
}