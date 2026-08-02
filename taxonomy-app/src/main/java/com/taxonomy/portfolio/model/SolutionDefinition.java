package com.taxonomy.portfolio.model;

import com.taxonomy.portfolio.model.PortfolioTypes.LifecycleStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.OperatingModel;
import com.taxonomy.portfolio.model.PortfolioTypes.SolutionType;
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

/** Reusable, workspace-scoped solution definition independent of a project. */
@Entity
@Table(name = "solution_definition", indexes = {
        @Index(name = "idx_sol_scope", columnList = "scope_key"),
        @Index(name = "idx_sol_lifecycle", columnList = "scope_key,lifecycle_status")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uq_sol_scope_key", columnNames = {"scope_key", "solution_key"})
})
public class SolutionDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scope_key", nullable = false, length = 160)
    private String scopeKey;

    @Column(name = "workspace_id", length = 120)
    private String workspaceId;

    @Column(name = "solution_key", nullable = false, length = 64)
    private String solutionKey;

    @Column(nullable = false, length = 240)
    private String title;

    @Column(length = 4000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "solution_type", nullable = false, length = 32)
    private SolutionType solutionType = SolutionType.OTHER;

    @Enumerated(EnumType.STRING)
    @Column(name = "operating_model", nullable = false, length = 32)
    private OperatingModel operatingModel = OperatingModel.UNSPECIFIED;

    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_status", nullable = false, length = 32)
    private LifecycleStatus lifecycleStatus = LifecycleStatus.PLANNED;

    @Column(name = "maturity_level", nullable = false)
    private int maturityLevel;

    @Column(name = "owner_username", nullable = false, length = 160)
    private String ownerUsername;

    @Column(name = "responsible_organization", length = 240)
    private String responsibleOrganization;

    @Column(name = "cost_amount", precision = 19, scale = 2)
    private BigDecimal costAmount;

    @Column(name = "cost_currency", length = 3)
    private String costCurrency;

    @Column(name = "risk_notes", length = 2000)
    private String riskNotes;

    @Column(name = "lead_time_days")
    private Integer leadTimeDays;

    @Column(name = "extension_attributes", length = 4000)
    private String extensionAttributesJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected SolutionDefinition() {
    }

    public SolutionDefinition(String scopeKey,
                              String workspaceId,
                              String solutionKey,
                              String title,
                              String description,
                              SolutionType solutionType,
                              OperatingModel operatingModel,
                              LifecycleStatus lifecycleStatus,
                              int maturityLevel,
                              String ownerUsername,
                              String responsibleOrganization,
                              Instant now) {
        this.scopeKey = scopeKey;
        this.workspaceId = workspaceId;
        this.solutionKey = solutionKey;
        this.title = title;
        this.description = description;
        this.solutionType = solutionType != null ? solutionType : SolutionType.OTHER;
        this.operatingModel = operatingModel != null ? operatingModel : OperatingModel.UNSPECIFIED;
        this.lifecycleStatus = lifecycleStatus != null ? lifecycleStatus : LifecycleStatus.PLANNED;
        this.maturityLevel = maturityLevel;
        this.ownerUsername = ownerUsername;
        this.responsibleOrganization = responsibleOrganization;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(String title,
                       String description,
                       SolutionType solutionType,
                       OperatingModel operatingModel,
                       LifecycleStatus lifecycleStatus,
                       Integer maturityLevel,
                       String ownerUsername,
                       String responsibleOrganization,
                       BigDecimal costAmount,
                       String costCurrency,
                       String riskNotes,
                       Integer leadTimeDays,
                       String extensionAttributesJson,
                       Instant now) {
        if (title != null) this.title = title;
        if (description != null) this.description = description;
        if (solutionType != null) this.solutionType = solutionType;
        if (operatingModel != null) this.operatingModel = operatingModel;
        if (lifecycleStatus != null) this.lifecycleStatus = lifecycleStatus;
        if (maturityLevel != null) this.maturityLevel = maturityLevel;
        if (ownerUsername != null) this.ownerUsername = ownerUsername;
        if (responsibleOrganization != null) this.responsibleOrganization = responsibleOrganization;
        if (costAmount != null) this.costAmount = costAmount;
        if (costCurrency != null) this.costCurrency = costCurrency;
        if (riskNotes != null) this.riskNotes = riskNotes;
        if (leadTimeDays != null) this.leadTimeDays = leadTimeDays;
        if (extensionAttributesJson != null) this.extensionAttributesJson = extensionAttributesJson;
        this.updatedAt = now;
    }

    public Long getId() { return id; }
    public String getScopeKey() { return scopeKey; }
    public String getWorkspaceId() { return workspaceId; }
    public String getSolutionKey() { return solutionKey; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public SolutionType getSolutionType() { return solutionType; }
    public OperatingModel getOperatingModel() { return operatingModel; }
    public LifecycleStatus getLifecycleStatus() { return lifecycleStatus; }
    public int getMaturityLevel() { return maturityLevel; }
    public String getOwnerUsername() { return ownerUsername; }
    public String getResponsibleOrganization() { return responsibleOrganization; }
    public BigDecimal getCostAmount() { return costAmount; }
    public String getCostCurrency() { return costCurrency; }
    public String getRiskNotes() { return riskNotes; }
    public Integer getLeadTimeDays() { return leadTimeDays; }
    public String getExtensionAttributesJson() { return extensionAttributesJson; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getRowVersion() { return rowVersion; }
}
