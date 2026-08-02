package com.taxonomy.portfolio.model;

import com.taxonomy.portfolio.model.PortfolioTypes.ProjectStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Project boundary for requirements, analyses, solution decisions and products.
 *
 * <p>{@code scopeKey} is a normalized workspace/user scope populated by the
 * application service. It avoids database-specific NULL uniqueness semantics
 * and is the authoritative isolation discriminator.</p>
 */
@Entity
@Table(name = "arch_project", indexes = {
        @Index(name = "idx_proj_scope", columnList = "scope_key"),
        @Index(name = "idx_proj_status", columnList = "scope_key,status")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uq_proj_scope_key", columnNames = {"scope_key", "project_key"})
})
public class ArchitectureProject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scope_key", nullable = false, length = 160)
    private String scopeKey;

    @Column(name = "workspace_id", length = 120)
    private String workspaceId;

    @Column(name = "owner_username", nullable = false, length = 160)
    private String ownerUsername;

    @Column(name = "project_key", nullable = false, length = 64)
    private String projectKey;

    @Column(nullable = false, length = 240)
    private String title;

    @Column(length = 4000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ProjectStatus status = ProjectStatus.PLANNING;

    @Column(name = "target_architecture", length = 4000)
    private String targetArchitecture;

    @Column(name = "target_date")
    private LocalDate targetDate;

    @Column(name = "budget_amount", precision = 19, scale = 2)
    private BigDecimal budgetAmount;

    @Column(name = "budget_currency", length = 3)
    private String budgetCurrency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected ArchitectureProject() {
    }

    public ArchitectureProject(String scopeKey,
                               String workspaceId,
                               String ownerUsername,
                               String projectKey,
                               String title,
                               String description,
                               ProjectStatus status,
                               Instant now) {
        this.scopeKey = scopeKey;
        this.workspaceId = workspaceId;
        this.ownerUsername = ownerUsername;
        this.projectKey = projectKey;
        this.title = title;
        this.description = description;
        this.status = status != null ? status : ProjectStatus.PLANNING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(String title,
                       String description,
                       ProjectStatus status,
                       String targetArchitecture,
                       LocalDate targetDate,
                       BigDecimal budgetAmount,
                       String budgetCurrency,
                       Instant now) {
        if (title != null) this.title = title;
        if (description != null) this.description = description;
        if (status != null) this.status = status;
        if (targetArchitecture != null) this.targetArchitecture = targetArchitecture;
        if (targetDate != null) this.targetDate = targetDate;
        if (budgetAmount != null) this.budgetAmount = budgetAmount;
        if (budgetCurrency != null) this.budgetCurrency = budgetCurrency;
        this.updatedAt = now;
    }

    public Long getId() { return id; }
    public String getScopeKey() { return scopeKey; }
    public String getWorkspaceId() { return workspaceId; }
    public String getOwnerUsername() { return ownerUsername; }
    public String getProjectKey() { return projectKey; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public ProjectStatus getStatus() { return status; }
    public String getTargetArchitecture() { return targetArchitecture; }
    public LocalDate getTargetDate() { return targetDate; }
    public BigDecimal getBudgetAmount() { return budgetAmount; }
    public String getBudgetCurrency() { return budgetCurrency; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getRowVersion() { return rowVersion; }
}
