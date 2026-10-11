package com.taxonomy.composition.demo;

import com.taxonomy.portfolio.model.PortfolioTenantIdentity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * Durable marker of an isolated, labelled product demo.
 *
 * <p>The marker binds exactly one demo project and requirement to one
 * versioned scenario. Analysis admission derives the replay provider from this
 * record and the requirement version, never from a browser flag, so a normal
 * project can never receive demo replies and a demo project can never reach a
 * live model.</p>
 */
@Entity
@Table(name = "product_demo_run", indexes = {
        @Index(name = "idx_demo_run_scope", columnList = "scope_key")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uq_demo_run_project", columnNames = "project_id")
})
public class ProductDemoRun {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "scope_key", nullable = false, length = PortfolioTenantIdentity.MAX_SCOPE_KEY_LENGTH)
    private String scopeKey;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "requirement_id", nullable = false)
    private Long requirementId;

    @Column(name = "scenario_id", nullable = false, length = 64)
    private String scenarioId;

    @Column(name = "scenario_version", nullable = false)
    private int scenarioVersion;

    @Column(name = "scenario_fingerprint", nullable = false, length = 64)
    private String scenarioFingerprint;

    @Column(name = "owner_username", nullable = false, length = 160)
    private String ownerUsername;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    protected ProductDemoRun() { }

    public ProductDemoRun(String id, String scopeKey, Long projectId, Long requirementId, String scenarioId,
                          int scenarioVersion, String scenarioFingerprint, String ownerUsername, Instant createdAt) {
        this.id = id;
        this.scopeKey = scopeKey;
        this.projectId = projectId;
        this.requirementId = requirementId;
        this.scenarioId = scenarioId;
        this.scenarioVersion = scenarioVersion;
        this.scenarioFingerprint = scenarioFingerprint;
        this.ownerUsername = ownerUsername;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getScopeKey() { return scopeKey; }
    public Long getProjectId() { return projectId; }
    public Long getRequirementId() { return requirementId; }
    public String getScenarioId() { return scenarioId; }
    public int getScenarioVersion() { return scenarioVersion; }
    public String getScenarioFingerprint() { return scenarioFingerprint; }
    public String getOwnerUsername() { return ownerUsername; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getClosedAt() { return closedAt; }
    public boolean isClosed() { return closedAt != null; }

    public void close(Instant now) {
        if (closedAt == null) closedAt = now;
    }
}
