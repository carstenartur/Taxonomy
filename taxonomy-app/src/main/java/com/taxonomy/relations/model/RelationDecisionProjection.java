package com.taxonomy.relations.model;

import com.taxonomy.model.RelationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;

/**
 * Rebuildable branch-local projection of one relation decision from authoritative Git.
 *
 * <p>This table is deliberately separate from the legacy relation read model. A
 * workspace or variant can therefore record a removal tombstone without making an
 * inherited central relation visible again, and independent branches can project the
 * same relation identity without overwriting each other.</p>
 */
@Entity
@Table(name = "relation_decision_projection",
        indexes = {
                @Index(
                        name = "idx_rel_decision_repository",
                        columnList = "repository_id"),
                @Index(
                        name = "idx_rel_decision_scope_branch",
                        columnList = "repository_id, workspace_scope_key, branch"),
                @Index(
                        name = "idx_rel_decision_commit",
                        columnList = "authoritative_commit_id")
        },
        uniqueConstraints = @UniqueConstraint(
                name = "uq_rel_decision_scope_identity",
                columnNames = {
                        "repository_id",
                        "workspace_scope_key",
                        "branch",
                        "source_code",
                        "relation_type",
                        "target_code"
                }))
public class RelationDecisionProjection {

    public static final String CENTRAL_SCOPE_KEY = "__shared__";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repository_id", nullable = false, length = 255)
    private String repositoryId;

    @Column(name = "workspace_id", length = 255)
    private String workspaceId;

    @Column(name = "workspace_scope_key", nullable = false, length = 255)
    private String workspaceScopeKey = CENTRAL_SCOPE_KEY;

    @Column(name = "branch", nullable = false, length = 255)
    private String branch;

    @Column(name = "source_code", nullable = false, length = 255)
    private String sourceCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "relation_type", nullable = false, length = 64)
    private RelationType relationType;

    @Column(name = "target_code", nullable = false, length = 255)
    private String targetCode;

    @Column(name = "relation_present", nullable = false)
    private boolean relationPresent;

    @Column(name = "status", length = 255)
    private String status;

    @Column(name = "confidence")
    private Double confidence;

    @Column(name = "provenance", length = 255)
    private String provenance;

    @Column(name = "authoritative_commit_id", nullable = false, length = 40)
    private String authoritativeCommitId;

    @Column(name = "causation_id", nullable = false, length = 255)
    private String causationId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @PrePersist
    void onCreate() {
        synchronize();
        if (updatedAt == null) {
            updatedAt = Instant.now();
        }
    }

    @PreUpdate
    void onUpdate() {
        synchronize();
        updatedAt = Instant.now();
    }

    private void synchronize() {
        repositoryId = requireText(repositoryId, "repositoryId");
        workspaceId = normalizeOptional(workspaceId);
        workspaceScopeKey = scopeKeyFor(workspaceId);
        branch = requireText(branch, "branch");
        sourceCode = requireText(sourceCode, "sourceCode");
        relationType = Objects.requireNonNull(relationType, "relationType");
        targetCode = requireText(targetCode, "targetCode");
        status = normalizeOptional(status);
        confidence = normalizeConfidence(confidence);
        provenance = normalizeOptional(provenance);
        authoritativeCommitId = requireText(
                authoritativeCommitId, "authoritativeCommitId");
        causationId = requireText(causationId, "causationId");
    }

    public static String scopeKeyFor(String workspaceId) {
        String normalized = normalizeOptional(workspaceId);
        return normalized == null ? CENTRAL_SCOPE_KEY : normalized;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRepositoryId() {
        return repositoryId;
    }

    public void setRepositoryId(String repositoryId) {
        this.repositoryId = requireText(repositoryId, "repositoryId");
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(String workspaceId) {
        this.workspaceId = normalizeOptional(workspaceId);
        this.workspaceScopeKey = scopeKeyFor(this.workspaceId);
    }

    public String getWorkspaceScopeKey() {
        return workspaceScopeKey;
    }

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = requireText(branch, "branch");
    }

    public String getSourceCode() {
        return sourceCode;
    }

    public void setSourceCode(String sourceCode) {
        this.sourceCode = requireText(sourceCode, "sourceCode");
    }

    public RelationType getRelationType() {
        return relationType;
    }

    public void setRelationType(RelationType relationType) {
        this.relationType = Objects.requireNonNull(relationType, "relationType");
    }

    public String getTargetCode() {
        return targetCode;
    }

    public void setTargetCode(String targetCode) {
        this.targetCode = requireText(targetCode, "targetCode");
    }

    public boolean isRelationPresent() {
        return relationPresent;
    }

    public void setRelationPresent(boolean relationPresent) {
        this.relationPresent = relationPresent;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = normalizeOptional(status);
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = normalizeConfidence(confidence);
    }

    public String getProvenance() {
        return provenance;
    }

    public void setProvenance(String provenance) {
        this.provenance = normalizeOptional(provenance);
    }

    public String getAuthoritativeCommitId() {
        return authoritativeCommitId;
    }

    public void setAuthoritativeCommitId(String authoritativeCommitId) {
        this.authoritativeCommitId = requireText(
                authoritativeCommitId, "authoritativeCommitId");
    }

    public String getCausationId() {
        return causationId;
    }

    public void setCausationId(String causationId) {
        this.causationId = requireText(causationId, "causationId");
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }

    private static Double normalizeConfidence(Double value) {
        if (value == null) {
            return null;
        }
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(
                    "confidence must be finite and between 0.0 and 1.0");
        }
        return value;
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
