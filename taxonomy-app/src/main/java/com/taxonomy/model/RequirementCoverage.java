package com.taxonomy.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Persists the mapping between a requirement identifier and a taxonomy node that
 * the requirement covers (score ≥ configured threshold).
 */
@Entity
@Table(name = "requirement_coverage", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"requirement_id", "node_code"})
})
public class RequirementCoverage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "requirement_id", nullable = false)
    private String requirementId;

    @Column(name = "requirement_text", length = 2000)
    private String requirementText;

    @Column(name = "node_code", nullable = false)
    private String nodeCode;

    @Column(name = "score")
    private int score;

    @Column(name = "analyzed_at")
    private Instant analyzedAt;

    protected RequirementCoverage() {
    }

    public RequirementCoverage(String requirementId, String requirementText,
                               String nodeCode, int score, Instant analyzedAt) {
        this.requirementId = requirementId;
        this.requirementText = requirementText;
        this.nodeCode = nodeCode;
        this.score = score;
        this.analyzedAt = analyzedAt;
    }

    public Long getId() { return id; }

    public String getRequirementId() { return requirementId; }

    public String getRequirementText() { return requirementText; }

    public String getNodeCode() { return nodeCode; }

    public int getScore() { return score; }

    public Instant getAnalyzedAt() { return analyzedAt; }
}
