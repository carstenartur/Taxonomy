package com.taxonomy.relations.model;

import jakarta.persistence.*;
import org.hibernate.annotations.Nationalized;

import java.time.Instant;

/**
 * Evidence supporting or challenging a {@link RelationHypothesis}.
 *
 * <p>Stores LLM, semantic, or rule-based justifications for relation hypotheses
 * to enable audit trails and re-validation.
 */
@Entity
@Table(name = "relation_evidence")
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
    @Nationalized
    @Column(name = "branch_name", nullable = false, length = 255)
    private String branchName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns(value = {
        @JoinColumn(name = "hypothesis_id", referencedColumnName = "id", insertable = false, updatable = false),
        @JoinColumn(name = "repository_id", referencedColumnName = "repository_id", insertable = false, updatable = false),
        @JoinColumn(name = "workspace_scope_key", referencedColumnName = "workspace_scope_key", insertable = false, updatable = false),
        @JoinColumn(name = "branch_name", referencedColumnName = "branch_name", insertable = false, updatable = false)
    }, foreignKey = @ForeignKey(name = "fk_evidence_hypothesis_scope"))
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
    @PreUpdate
    protected void onCreate() {
        if (hypothesisId == null || repositoryId == null || workspaceScopeKey == null || branchName == null
                || repositoryId.isBlank() || workspaceScopeKey.isBlank() || branchName.isBlank()) {
            throw new IllegalArgumentException("Evidence requires exact parent authority");
        }
        if (hypothesis != null && (!hypothesisId.equals(hypothesis.getId())
                || !repositoryId.equals(hypothesis.getRepositoryId())
                || !workspaceScopeKey.equals(hypothesis.getWorkspaceScopeKey())
                || !branchName.equals(hypothesis.getBranchName()))) {
            throw new IllegalArgumentException("Evidence parent authority mismatch");
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public RelationHypothesis getHypothesis() { return hypothesis; }
    public void setHypothesis(RelationHypothesis hypothesis) {
        this.hypothesis = java.util.Objects.requireNonNull(hypothesis, "hypothesis");
        this.hypothesisId = hypothesis.getId();
        this.repositoryId = hypothesis.getRepositoryId();
        this.workspaceScopeKey = hypothesis.getWorkspaceScopeKey();
        this.branchName = hypothesis.getBranchName();
    }
    public Long getHypothesisId() { return hypothesisId; }
    public String getRepositoryId() { return repositoryId; }
    public String getWorkspaceScopeKey() { return workspaceScopeKey; }
    public String getBranchName() { return branchName; }

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
}
