package com.taxonomy.portfolio.inventory;
import com.taxonomy.portfolio.dto.PortfolioDtos.*;
import com.taxonomy.portfolio.model.PortfolioTypes.*;
import com.taxonomy.extension.api.inventory.InventoryComparisonContracts.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class CuratedInventoryProjectionTest {
    @Test void activeProductAndSupportDateNeverEstablishOrganizationalAvailability() {
        ProductView product=mock(ProductView.class);
        when(product.id()).thenReturn(1L);when(product.productName()).thenReturn("Same name");
        when(product.productStatus()).thenReturn(ProductStatus.ACTIVE);
        when(product.endOfSupport()).thenReturn(LocalDate.parse("2032-01-01"));
        when(product.verifiedAt()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        when(product.taxonomyCoverage()).thenReturn(List.of(
                new TaxonomyCoverageView(1L,"fixture:node",100,"reviewed",ReviewStatus.CONFIRMED,"alice",Instant.EPOCH),
                new TaxonomyCoverageView(2L,"fixture:other",100,"proposal",ReviewStatus.PROPOSED,"alice",Instant.EPOCH)));
        var candidate=new CuratedInventoryProjection().project(product,"fingerprint",List.of(),new Question(TimeMode.EXACT_DATE,LocalDate.parse("2030-01-01"),null,"north","production"));
        assertEquals("product:1",candidate.id());
        assertTrue(candidate.assertions().stream().noneMatch(a->a.criterion()==Criterion.AVAILABILITY));
        assertTrue(candidate.assertions().stream().noneMatch(a->"fixture:other".equals(a.nodeCode())));
        assertTrue(candidate.assertions().stream().anyMatch(a->a.criterion()==Criterion.SUPPORT));
        assertEquals("2026-01-01T00:00:00Z",candidate.metadata().get("verifiedAt"));
    }
}
