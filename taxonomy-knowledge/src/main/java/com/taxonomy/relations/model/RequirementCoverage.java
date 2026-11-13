package com.taxonomy.relations.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Persists the mapping between a requirement identifier and a taxonomy node that
 * the requirement covers (score ≥ configured threshold).
 */
@Entity
@Table(name = "scoped_requirement_coverage", uniqueConstraints = @UniqueConstraint(name = "uq_scoped_requirement_coverage",
        columnNames = {"repository_id", "workspace_scope", "branch_name", "source_commit", "requirement_id", "node_code"}))
public class RequirementCoverage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repository_id", nullable = false) private String repositoryId;
    @Column(name = "workspace_id") private String workspaceId;
    @Column(name = "workspace_scope", length = 257, nullable = false) private String workspaceScope;
    @Column(name = "branch_name", nullable = false) private String branch;
    @Column(name = "source_commit", nullable = false, length = 40) private String sourceCommit;
    @Column(name = "recorded_by", nullable = false) private String recordedBy;

    @Column(name = "requirement_id", nullable = false)
    private String requirementId;

    @Column(name = "requirement_text", length = 2000)
    private String requirementText;

    @Column(name = "node_code", nullable = false)
    private String nodeCode;

    @Column(name = "score")
    private double score;

    @Column(name = "analyzed_at")
    private Instant analyzedAt;

    protected RequirementCoverage() {
    }

    /** Source and actor of one admitted coverage recording, shared by all its node rows. */
    public record Source(String repositoryId, String workspaceId, String branch, String commit, String actor) {}

    public RequirementCoverage(Source source, String requirementId, String requirementText,
                               String nodeCode, double score, Instant analyzedAt) {
        this.repositoryId = source.repositoryId();
        this.workspaceId = source.workspaceId();
        this.workspaceScope = scope(source.workspaceId());
        this.branch = source.branch();
        this.sourceCommit = source.commit();
        this.recordedBy = source.actor();
        this.requirementId = requirementId;
        this.requirementText = requirementText;
        this.nodeCode = nodeCode;
        this.score = score;
        this.analyzedAt = analyzedAt;
    }

    /** Used together with repository, branch and commit; central and named workspaces occupy disjoint keys. */
    public static String scope(String workspaceId) {
        return workspaceId == null ? "C" : "W:" + workspaceId;
    }

    public String getRepositoryId() { return repositoryId; }
    public String getWorkspaceId() { return workspaceId; }
    public String getBranch() { return branch; }
    public String getSourceCommit() { return sourceCommit; }
    public String getRecordedBy() { return recordedBy; }

    public Long getId() { return id; }

    public String getRequirementId() { return requirementId; }

    public String getRequirementText() { return requirementText; }

    public String getNodeCode() { return nodeCode; }

    public double getScore() { return score; }

    public Instant getAnalyzedAt() { return analyzedAt; }
}
