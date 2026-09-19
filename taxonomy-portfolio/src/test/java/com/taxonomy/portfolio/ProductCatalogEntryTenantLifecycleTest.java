package com.taxonomy.portfolio;

import com.taxonomy.portfolio.model.PortfolioTenantIdentity;
import com.taxonomy.portfolio.model.ProductCatalogEntry;
import com.taxonomy.portfolio.model.PortfolioTypes.OperatingModel;
import com.taxonomy.portfolio.model.PortfolioTypes.ProductStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductCatalogEntryTenantLifecycleTest {

    @Test
    void encodedTenantAndDefaultVocabularyAreMaterializedAtConstruction() {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        String scopeKey = new PortfolioTenantIdentity(
                "repo-a", "WORKSPACE:workspace-a", "main").scopeKey();

        ProductCatalogEntry entry = entry(
                scopeKey, "workspace-a", null, null, now);

        assertThat(entry.getRepositoryId()).isEqualTo("repo-a");
        assertThat(entry.getWorkspaceScope()).isEqualTo("WORKSPACE:workspace-a");
        assertThat(entry.getBranchName()).isEqualTo("main");
        assertThat(entry.getProductStatus()).isEqualTo(ProductStatus.CANDIDATE);
        assertThat(entry.getOperatingModel()).isEqualTo(OperatingModel.UNSPECIFIED);
        assertThat(entry.getCreatedAt()).isEqualTo(now);
        assertThat(entry.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    void completeAndSparseUpdatesExerciseEveryOptionalFieldWithoutDataLoss() {
        Instant created = Instant.parse("2026-08-16T00:00:00Z");
        Instant verified = Instant.parse("2026-08-16T01:00:00Z");
        Instant updated = Instant.parse("2026-08-16T02:00:00Z");
        Instant touched = Instant.parse("2026-08-16T03:00:00Z");
        ProductCatalogEntry entry = entry(
                new PortfolioTenantIdentity("repo-a", "CENTRAL", "draft").scopeKey(),
                null,
                ProductStatus.ACTIVE,
                OperatingModel.ON_PREMISES,
                created);

        entry.update(
                "Updated manufacturer",
                "Updated family",
                "Updated product",
                "2026.2",
                ProductStatus.DEPRECATED,
                LocalDate.of(2030, 12, 31),
                "subscription",
                OperatingModel.SAAS,
                "Linux, Windows",
                "MFA, encryption",
                "BSI, ISO 27001",
                new BigDecimal("123.45"),
                "EUR",
                "per user/month",
                "catalogue-42",
                verified,
                updated);

        assertThat(entry.getManufacturer()).isEqualTo("Updated manufacturer");
        assertThat(entry.getProductFamily()).isEqualTo("Updated family");
        assertThat(entry.getProductName()).isEqualTo("Updated product");
        assertThat(entry.getEditionVersion()).isEqualTo("2026.2");
        assertThat(entry.getProductStatus()).isEqualTo(ProductStatus.DEPRECATED);
        assertThat(entry.getEndOfSupport()).isEqualTo(LocalDate.of(2030, 12, 31));
        assertThat(entry.getLicenseModel()).isEqualTo("subscription");
        assertThat(entry.getOperatingModel()).isEqualTo(OperatingModel.SAAS);
        assertThat(entry.getSupportedPlatforms()).isEqualTo("Linux, Windows");
        assertThat(entry.getSecurityFeatures()).isEqualTo("MFA, encryption");
        assertThat(entry.getComplianceFeatures()).isEqualTo("BSI, ISO 27001");
        assertThat(entry.getCostAmount()).isEqualByComparingTo("123.45");
        assertThat(entry.getCostCurrency()).isEqualTo("EUR");
        assertThat(entry.getCostBasis()).isEqualTo("per user/month");
        assertThat(entry.getSourceReference()).isEqualTo("catalogue-42");
        assertThat(entry.getVerifiedAt()).isEqualTo(verified);
        assertThat(entry.getCreatedBy()).isEqualTo("architect");
        assertThat(entry.getUpdatedAt()).isEqualTo(updated);

        entry.update(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, touched);

        assertThat(entry.getManufacturer()).isEqualTo("Updated manufacturer");
        assertThat(entry.getProductFamily()).isEqualTo("Updated family");
        assertThat(entry.getProductName()).isEqualTo("Updated product");
        assertThat(entry.getEditionVersion()).isEqualTo("2026.2");
        assertThat(entry.getProductStatus()).isEqualTo(ProductStatus.DEPRECATED);
        assertThat(entry.getOperatingModel()).isEqualTo(OperatingModel.SAAS);
        assertThat(entry.getUpdatedAt()).isEqualTo(touched);
    }

    @Test
    void mismatchedWorkspaceScopeFailsClosedAndLegacyScopeDoesNotPretendToBeExact() {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        String central = new PortfolioTenantIdentity(
                "repo-a", "CENTRAL", "main").scopeKey();

        assertThatThrownBy(() -> entry(
                central, "workspace-a", ProductStatus.ACTIVE,
                OperatingModel.HYBRID, now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match workspaceId");

        ProductCatalogEntry legacy = entry(
                "workspace:legacy", "legacy", ProductStatus.ACTIVE,
                OperatingModel.HYBRID, now);
        assertThat(legacy.getRepositoryId()).isNull();
        assertThat(legacy.getWorkspaceScope()).isNull();
        assertThat(legacy.getBranchName()).isNull();
    }

    private static ProductCatalogEntry entry(
            String scopeKey,
            String workspaceId,
            ProductStatus status,
            OperatingModel operatingModel,
            Instant now) {
        return new ProductCatalogEntry(
                scopeKey,
                workspaceId,
                "PRODUCT-1",
                "Initial manufacturer",
                "Initial family",
                "Initial product",
                "1.0",
                status,
                LocalDate.of(2029, 1, 1),
                "perpetual",
                operatingModel,
                "Linux",
                "encryption",
                "ISO 27001",
                new BigDecimal("10.00"),
                "EUR",
                "per instance",
                "catalogue-1",
                Instant.parse("2026-08-15T23:00:00Z"),
                "architect",
                now);
    }
}
