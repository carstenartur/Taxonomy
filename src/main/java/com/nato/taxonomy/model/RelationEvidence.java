package com.nato.taxonomy.model;

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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hypothesis_id", nullable = false)
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
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public RelationHypothesis getHypothesis() { return hypothesis; }
    public void setHypothesis(RelationHypothesis hypothesis) { this.hypothesis = hypothesis; }

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
