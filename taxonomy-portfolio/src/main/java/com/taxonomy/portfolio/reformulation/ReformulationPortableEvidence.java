package com.taxonomy.portfolio.reformulation;

import com.taxonomy.portfolio.model.PortfolioTenantIdentity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Portable reformulation evidence imported from Git.
 *
 * <p>The row deliberately contains business keys and content hashes rather than
 * source database identifiers. Source-native adoption receipts remain the
 * authority in their own workspace; this table retains only evidence that must
 * survive materialization into a different repository/workspace.</p>
 */
@Entity
@Table(name = "reformulation_portable_evidence",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_reform_portable_scope_hash",
                columnNames = {"scope_key", "evidence_hash"}),
        indexes = @Index(
                name = "idx_reform_portable_identity",
                columnList = "scope_key,project_key,requirement_key,target_version_number"))
public class ReformulationPortableEvidence {

    @Id
    @Column(length = 64, updatable = false)
    private String id;

    @Column(name = "scope_key", nullable = false, updatable = false,
            length = PortfolioTenantIdentity.MAX_SCOPE_KEY_LENGTH)
    private String scopeKey;

    @Column(name = "project_key", nullable = false, updatable = false, length = 64)
    private String projectKey;

    @Column(name = "requirement_key", nullable = false, updatable = false, length = 64)
    private String requirementKey;

    @Column(name = "target_version_number", nullable = false, updatable = false)
    private int targetVersionNumber;

    @Column(name = "schema_version", nullable = false, updatable = false, length = 64)
    private String schemaVersion;

    @Column(name = "evidence_hash", nullable = false, updatable = false, length = 64)
    private String evidenceHash;

    @Column(name = "target_text_hash", nullable = false, updatable = false, length = 64)
    private String targetTextHash;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(name = "evidence_payload", nullable = false, updatable = false)
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ReformulationPortableEvidence() {
    }

    public ReformulationPortableEvidence(
            String id,
            String scopeKey,
            String projectKey,
            String requirementKey,
            int targetVersionNumber,
            String schemaVersion,
            String evidenceHash,
            String targetTextHash,
            String payload,
            Instant createdAt) {
        this.id = id;
        this.scopeKey = scopeKey;
        this.projectKey = projectKey;
        this.requirementKey = requirementKey;
        this.targetVersionNumber = targetVersionNumber;
        this.schemaVersion = schemaVersion;
        this.evidenceHash = evidenceHash;
        this.targetTextHash = targetTextHash;
        this.payload = payload;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getScopeKey() { return scopeKey; }
    public String getProjectKey() { return projectKey; }
    public String getRequirementKey() { return requirementKey; }
    public int getTargetVersionNumber() { return targetVersionNumber; }
    public String getSchemaVersion() { return schemaVersion; }
    public String getEvidenceHash() { return evidenceHash; }
    public String getTargetTextHash() { return targetTextHash; }
    public String getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
}
