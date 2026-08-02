package com.taxonomy.relations.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Nationalized;

import java.time.Instant;
import com.taxonomy.catalog.model.TaxonomyRelation;
import com.taxonomy.model.HypothesisStatus;
import com.taxonomy.model.RelationType;

/**
 * A provisional/proposed relation hypothesis derived from analysis.
 *
 * <p>Unlike {@link TaxonomyRelation} which represents confirmed relations,
 * a hypothesis is a candidate relation that can be accepted or rejected
 * through a review workflow.</p>
 */
@Entity
@Table(name = "relation_hypothesis",
       indexes = {
           @Index(name = "idx_hyp_project", columnList = "project_id"),
           @Index(name = "idx_hyp_requirement", columnList = "requirement_id"),
           @Index(name = "idx_hyp_snapshot", columnList = "analysis_snapshot_id")
       },
       uniqueConstraints = @UniqueConstraint(
               name = "uq_hypothesis_workspace_session_relation",
               columnNames = {"workspace_id", "source_node_id", "target_node_id",
                       "relation_type", "analysis_session_id"}))
public class RelationHypothesis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Nationalized
    @Column(name = "source_node_id", nullable = false)
    private String sourceNodeId;

    @Nationalized
    @Column(name = "target_node_id", nullable = false)
    private String targetNodeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "relation_type", nullable = false)
    private RelationType relationType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private HypothesisStatus status = HypothesisStatus.PROVISIONAL;

    @Column
    private Double confidence;

    @Nationalized
    @Column(name = "analysis_session_id")
    private String analysisSessionId;

    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "requirement_id")
    private Long requirementId;

    @Column(name = "analysis_snapshot_id", length = 36)
    private String analysisSnapshotId;

    @Column(name = "applied_in_current_analysis")
    private boolean appliedInCurrentAnalysis;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "workspace_id")
    private String workspaceId;

    @Column(name = "owner_username")
    private String ownerUsername;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSourceNodeId() { return sourceNodeId; }
    public void setSourceNodeId(String sourceNodeId) { this.sourceNodeId = sourceNodeId; }

    public String getTargetNodeId() { return targetNodeId; }
    public void setTargetNodeId(String targetNodeId) { this.targetNodeId = targetNodeId; }

    public RelationType getRelationType() { return relationType; }
    public void setRelationType(RelationType relationType) { this.relationType = relationType; }

    public HypothesisStatus getStatus() { return status; }
    public void setStatus(HypothesisStatus status) { this.status = status; }

    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }

    public String getAnalysisSessionId() { return analysisSessionId; }
    public void setAnalysisSessionId(String analysisSessionId) { this.analysisSessionId = analysisSessionId; }

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public Long getRequirementId() { return requirementId; }
    public void setRequirementId(Long requirementId) { this.requirementId = requirementId; }

    public String getAnalysisSnapshotId() { return analysisSnapshotId; }
    public void setAnalysisSnapshotId(String analysisSnapshotId) { this.analysisSnapshotId = analysisSnapshotId; }

    public boolean isAppliedInCurrentAnalysis() { return appliedInCurrentAnalysis; }
    public void setAppliedInCurrentAnalysis(boolean appliedInCurrentAnalysis) { this.appliedInCurrentAnalysis = appliedInCurrentAnalysis; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

    public String getOwnerUsername() { return ownerUsername; }
    public void setOwnerUsername(String ownerUsername) { this.ownerUsername = ownerUsername; }
}
