package com.taxonomy.portfolio.inventory;
import com.taxonomy.portfolio.service.*;
import com.taxonomy.portfolio.repository.*;
import com.taxonomy.portfolio.model.*;
import com.taxonomy.portfolio.model.PortfolioTypes.*;
import com.taxonomy.portfolio.dto.PortfolioDtos.*;
import com.taxonomy.catalog.api.TaxonomyNodeLookup;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class InventoryRetainedExclusionsTest {
    @Test void browserCannotClearRetainedHardExclusions() {
        var pr=mock(ProductCatalogEntryRepository.class);var cr=mock(SolutionProductCandidateRepository.class);var sr=mock(ProjectSolutionRepository.class);
        var service=new ProductCatalogService(pr,mock(ProductTaxonomyCoverageRepository.class),cr,sr,mock(TaxonomyNodeLookup.class),mock(ProjectPortfolioService.class));
        var context=new WorkspaceContext("alice","ws","draft","repo");var product=mock(ProductCatalogEntry.class);when(product.getId()).thenReturn(9L);
        when(pr.findByIdAndScopeKey(eq(9L),anyString())).thenReturn(Optional.of(product));var solution=mock(ProjectSolution.class);when(sr.findByIdAndProjectId(7L,1L)).thenReturn(Optional.of(solution));
        var candidate=new SolutionProductCandidate(solution,product,100,"availability contradicted",null,null,null,1,ReviewStatus.CONFIRMED,ProductSelectionStatus.REJECTED,"alice",Instant.now());
        ReflectionTestUtils.setField(candidate,"id",8L);candidate.bindInventoryEvidence("comparison","fingerprint","availability contradicted");when(cr.findByProjectSolutionIdAndProductId(7L,9L)).thenReturn(Optional.of(candidate));
        assertThrows(PortfolioException.class,()->service.upsertCandidate(1L,7L,new UpsertProductCandidateRequest(9L,100,null,null,null,null,1,ReviewStatus.CONFIRMED,ProductSelectionStatus.SELECTED),"alice",context));
        assertEquals("availability contradicted",candidate.getInventoryHardExclusions());verify(cr,never()).save(any());
    }
}
