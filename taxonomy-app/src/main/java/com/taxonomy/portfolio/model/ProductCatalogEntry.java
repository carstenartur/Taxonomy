package com.taxonomy.portfolio.model;

import com.taxonomy.portfolio.model.PortfolioTypes.OperatingModel;
import com.taxonomy.portfolio.model.PortfolioTypes.ProductStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Sourced and dated catalogue entry for a concrete product/version.
 *
 * <p>A product claim is deliberately incomplete until {@code sourceReference}
 * and {@code verifiedAt} are populated. Consumers must expose that provenance
 * rather than presenting stale product metadata as current fact.</p>
 */
@Entity
@Table(name = "product_catalog", indexes = {
        @Index(name = "idx_prod_scope", columnList = "scope_key"),
        @Index(name = "idx_prod_status", columnList = "scope_key,product_status"),
        @Index(name = "idx_prod_eos", columnList = "end_of_support")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uq_prod_scope_key", columnNames = {"scope_key", "product_key"})
})
public class ProductCatalogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scope_key", nullable = false, length = 160)
    private String scopeKey;

    @Column(name = "workspace_id", length = 120)
    private String workspaceId;

    @Column(name = "product_key", nullable = false, length = 64)
    private String productKey;

    @Column(nullable = false, length = 240)
    private String manufacturer;

    @Column(name = "product_family", length = 240)
    private String productFamily;

    @Column(name = "product_name", nullable = false, length = 240)
    private String productName;

    @Column(name = "edition_version", length = 160)
    private String editionVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "product_status", nullable = false, length = 32)
    private ProductStatus productStatus = ProductStatus.CANDIDATE;

    @Column(name = "end_of_support")
    private LocalDate endOfSupport;

    @Column(name = "license_model", length = 500)
    private String licenseModel;

    @Enumerated(EnumType.STRING)
    @Column(name = "operating_model", nullable = false, length = 32)
    private OperatingModel operatingModel = OperatingModel.UNSPECIFIED;

    @Column(name = "supported_platforms", length = 2000)
    private String supportedPlatforms;

    @Column(name = "security_features", length = 4000)
    private String securityFeatures;

    @Column(name = "compliance_features", length = 4000)
    private String complianceFeatures;

    @Column(name = "cost_amount", precision = 19, scale = 2)
    private BigDecimal costAmount;

    @Column(name = "cost_currency", length = 3)
    private String costCurrency;

    @Column(name = "cost_basis", length = 500)
    private String costBasis;

    @Lob
    @Column(name = "source_reference", nullable = false)
    private String sourceReference;

    @Column(name = "verified_at", nullable = false)
    private Instant verifiedAt;

    @Column(name = "created_by", nullable = false, length = 160)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected ProductCatalogEntry() {
    }

    public ProductCatalogEntry(String scopeKey,
                               String workspaceId,
                               String productKey,
                               String manufacturer,
                               String productFamily,
                               String productName,
                               String editionVersion,
                               ProductStatus productStatus,
                               LocalDate endOfSupport,
                               String licenseModel,
                               OperatingModel operatingModel,
                               String supportedPlatforms,
                               String securityFeatures,
                               String complianceFeatures,
                               BigDecimal costAmount,
                               String costCurrency,
                               String costBasis,
                               String sourceReference,
                               Instant verifiedAt,
                               String createdBy,
                               Instant now) {
        this.scopeKey = scopeKey;
        this.workspaceId = workspaceId;
        this.productKey = productKey;
        this.manufacturer = manufacturer;
        this.productFamily = productFamily;
        this.productName = productName;
        this.editionVersion = editionVersion;
        this.productStatus = productStatus != null ? productStatus : ProductStatus.CANDIDATE;
        this.endOfSupport = endOfSupport;
        this.licenseModel = licenseModel;
        this.operatingModel = operatingModel != null ? operatingModel : OperatingModel.UNSPECIFIED;
        this.supportedPlatforms = supportedPlatforms;
        this.securityFeatures = securityFeatures;
        this.complianceFeatures = complianceFeatures;
        this.costAmount = costAmount;
        this.costCurrency = costCurrency;
        this.costBasis = costBasis;
        this.sourceReference = sourceReference;
        this.verifiedAt = verifiedAt;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(String manufacturer,
                       String productFamily,
                       String productName,
                       String editionVersion,
                       ProductStatus productStatus,
                       LocalDate endOfSupport,
                       String licenseModel,
                       OperatingModel operatingModel,
                       String supportedPlatforms,
                       String securityFeatures,
                       String complianceFeatures,
                       BigDecimal costAmount,
                       String costCurrency,
                       String costBasis,
                       String sourceReference,
                       Instant verifiedAt,
                       Instant now) {
        if (manufacturer != null) this.manufacturer = manufacturer;
        if (productFamily != null) this.productFamily = productFamily;
        if (productName != null) this.productName = productName;
        if (editionVersion != null) this.editionVersion = editionVersion;
        if (productStatus != null) this.productStatus = productStatus;
        if (endOfSupport != null) this.endOfSupport = endOfSupport;
        if (licenseModel != null) this.licenseModel = licenseModel;
        if (operatingModel != null) this.operatingModel = operatingModel;
        if (supportedPlatforms != null) this.supportedPlatforms = supportedPlatforms;
        if (securityFeatures != null) this.securityFeatures = securityFeatures;
        if (complianceFeatures != null) this.complianceFeatures = complianceFeatures;
        if (costAmount != null) this.costAmount = costAmount;
        if (costCurrency != null) this.costCurrency = costCurrency;
        if (costBasis != null) this.costBasis = costBasis;
        if (sourceReference != null) this.sourceReference = sourceReference;
        if (verifiedAt != null) this.verifiedAt = verifiedAt;
        this.updatedAt = now;
    }

    public Long getId() { return id; }
    public String getScopeKey() { return scopeKey; }
    public String getWorkspaceId() { return workspaceId; }
    public String getProductKey() { return productKey; }
    public String getManufacturer() { return manufacturer; }
    public String getProductFamily() { return productFamily; }
    public String getProductName() { return productName; }
    public String getEditionVersion() { return editionVersion; }
    public ProductStatus getProductStatus() { return productStatus; }
    public LocalDate getEndOfSupport() { return endOfSupport; }
    public String getLicenseModel() { return licenseModel; }
    public OperatingModel getOperatingModel() { return operatingModel; }
    public String getSupportedPlatforms() { return supportedPlatforms; }
    public String getSecurityFeatures() { return securityFeatures; }
    public String getComplianceFeatures() { return complianceFeatures; }
    public BigDecimal getCostAmount() { return costAmount; }
    public String getCostCurrency() { return costCurrency; }
    public String getCostBasis() { return costBasis; }
    public String getSourceReference() { return sourceReference; }
    public Instant getVerifiedAt() { return verifiedAt; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getRowVersion() { return rowVersion; }
}
