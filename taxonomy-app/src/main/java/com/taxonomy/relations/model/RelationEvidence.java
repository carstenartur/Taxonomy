package com.taxonomy.relations.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.Nationalized;

import java.time.Instant;
import java.util.Objects;

/**
 * Evidence supporting or challenging a {@link RelationHypothesis}.
 *
 * <p>Stores LLM, semantic, or rule-based justifications for relation hypotheses
 * to enable audit trails and re-validation. The redundant scalar tenant columns
 * are the sole write authority and make cross-repository, cross-workspace or
 * cross-branch evidence links impossible at the database boundary.</p>
 */
@Entity
@Table(name = "relation_evidence", indexes = {
        @Index(name = "idx_evidence_hyp_tenant",
                columnList = "hypothesis_id, repository_id, workspace_scope_key, branch_name"),
        @Index(name = "idx_evidence_tenant",
                columnList = "repository_id, workspace_scope_key, branch_name")
})
public class RelationEvidence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hypothesis_id", nullable = false)
    private Long hypothesisId;

    @Column(name = "repository_id", nullable = false, length = 255)
    private String repositoryId;

    @Column(name = "workspace_scope_key", nullable = false, length = 255)
    private String workspaceScopeKey;

    @Column(name = "branch_name", nullable = false, length = 255)
    private String branchName;

    /** Fully read-only association; the scalar columns above own every write. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({
            @JoinColumn(name = "hypothesis_id", referencedColumnName = "id",
                    nullable = false, insertable = false, updatable = false),
            @JoinColumn(name = "repository_id", referencedColumnName = "repository_id",
                    nullable = false, insertable = false, updatable = false),
            @JoinColumn(name = "workspace_scope_key",
                    referencedColumnName = "workspace_scope_key",
                    nullable = false, insertable = false, updatable = false),
            @JoinColumn(name = "branch_name", referencedColumnName = "branch_name",
                    nullable = false, insertable = false, updatable = false)
    })
    private RelationHypothesis hypothesis;

    @Nationalized
    @Column(name = "evidence_type", nullable = false)
    private String evidenceType;

    @Nationalized
    @Lob
    @Column(length = 2000)
    private String summary;

    @Nationalized
    @Lob
    @Column(name = "full_text", length = 10000)
    private String fullText;

    @Column
    private Double confidence;

    @Nationalized
    @Column(name = "model_name")
    private String modelName;

    @Nationalized
    @Column(name = "model_version")
    private String modelVersion;

    @Nationalized
    @Column(name = "prompt_version")
    private String promptVersion;

    @Nationalized
    @Lob
    @Column(name = "input_snapshot", length = 10000)
    private String inputSnapshot;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        synchronizeTenantAuthority();
    }

    @PreUpdate
    protected void onUpdate() {
        synchronizeTenantAuthority();
    }

    private void synchronizeTenantAuthority() {
        if (hypothesis == null) {
            throw new IllegalArgumentException("Relation evidence must reference a hypothesis");
        }
        Long parentId = hypothesis.getId();
        if (parentId == null) {
            throw new IllegalArgumentException(
                    "Relation evidence hypothesis must be persisted before its evidence");
        }
        String parentRepository = requireText(
                hypothesis.getRepositoryId(), "hypothesis.repositoryId");
        String parentWorkspaceScope = requireText(
                hypothesis.getWorkspaceScopeKey(), "hypothesis.workspaceScopeKey");
        String parentBranch = requireText(
                hypothesis.getBranchName(), "hypothesis.branchName");

        if (hypothesisId != null && !Objects.equals(hypothesisId, parentId)) {
            throw new IllegalArgumentException(
                    "Relation evidence hypothesis ID does not match its parent");
        }
        if (repositoryId != null
                && !parentRepository.equals(repositoryId.strip())) {
            throw new IllegalArgumentException(
                    "Relation evidence repository does not match its hypothesis");
        }
        if (workspaceScopeKey != null
                && !parentWorkspaceScope.equals(workspaceScopeKey.strip())) {
            throw new IllegalArgumentException(
                    "Relation evidence workspace does not match its hypothesis");
        }
        if (branchName != null && !parentBranch.equals(branchName.strip())) {
            throw new IllegalArgumentException(
                    "Relation evidence branch does not match its hypothesis");
        }

        hypothesisId = parentId;
        repositoryId = parentRepository;
        workspaceScopeKey = parentWorkspaceScope;
        branchName = parentBranch;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getHypothesisId() { return hypothesisId; }
    public String getRepositoryId() { return repositoryId; }
    public String getWorkspaceScopeKey() { return workspaceScopeKey; }
    public String getBranchName() { return branchName; }

    public RelationHypothesis getHypothesis() { return hypothesis; }
    public void setHypothesis(RelationHypothesis hypothesis) {
        this.hypothesis = Objects.requireNonNull(hypothesis, "hypothesis");
    }

    public String getEvidenceType() { return evidenceType; }
    public void setEvidenceType(String evidenceType) { this.evidenceType = evidenceType; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getFullText() { return fullText; }
    public void setFullText(String fullText) { this.fullText = fullText; }

    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public String getModelVersion() { return modelVersion; }
    public void setModelVersion(String modelVersion) { this.modelVersion = modelVersion; }

    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String promptVersion) { this.promptVersion = promptVersion; }

    public String getInputSnapshot() { return inputSnapshot; }
    public void setInputSnapshot(String inputSnapshot) { this.inputSnapshot = inputSnapshot; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }
}
