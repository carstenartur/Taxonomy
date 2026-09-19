package com.taxonomy.portfolio.service;

import com.taxonomy.catalog.api.TaxonomyNodeLookup;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.portfolio.dto.PortfolioDtos.*;
import com.taxonomy.portfolio.model.*;
import com.taxonomy.portfolio.model.PortfolioTypes.*;
import com.taxonomy.portfolio.repository.*;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProductCatalogPolicyContractTest {
    final ProductCatalogEntryRepository products=mock(ProductCatalogEntryRepository.class);
    final ProductTaxonomyCoverageRepository coverage=mock(ProductTaxonomyCoverageRepository.class);
    final SolutionProductCandidateRepository candidates=mock(SolutionProductCandidateRepository.class);
    final ProjectSolutionRepository decisions=mock(ProjectSolutionRepository.class);
    final TaxonomyNodeLookup nodes=mock(TaxonomyNodeLookup.class);
    final ProjectPortfolioService projects=mock(ProjectPortfolioService.class);
    final WorkspaceContext context=new WorkspaceContext("alice","ws-a","draft","repo-a");
    final ProductCatalogService service=new ProductCatalogService(products,coverage,candidates,decisions,nodes,projects);
    ProductCatalogEntry product;
    ProjectSolution decision;
    @BeforeEach void setUp() {
        when(products.save(any())).thenAnswer(call->{ product=call.getArgument(0);ReflectionTestUtils.setField(product,"id",9L);return product; });
        var request=mock(CreateProductRequest.class);
        when(request.productKey()).thenReturn(" prd-one ");when(request.manufacturer()).thenReturn("Vendor");
        when(request.productName()).thenReturn("Product");when(request.sourceReference()).thenReturn("Reviewed source");
        when(request.verifiedAt()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        service.createProduct(request,"alice",context);
        when(products.findByIdAndScopeKey(9L,PortfolioScope.key("alice",context))).thenReturn(Optional.of(product));
        when(nodes.findByCode("CP-1")).thenReturn(Optional.of(mock(TaxonomyNode.class)));
        decision=mock(ProjectSolution.class);when(decision.getId()).thenReturn(3L);
        when(decisions.findByIdAndProjectId(3L,1L)).thenReturn(Optional.of(decision));
        clearInvocations(products,coverage,candidates,decisions,projects);
    }
    @Test void creationDefaultsAndExplicitUpdatesRemainSourcedAndScoped() {
        assertThat(product.getProductKey()).isEqualTo("PRD-ONE");
        assertThat(product.getProductStatus()).isEqualTo(ProductStatus.CANDIDATE);
        assertThat(product.getOperatingModel()).isEqualTo(OperatingModel.UNSPECIFIED);
        var update=mock(UpdateProductRequest.class);
        when(update.manufacturer()).thenReturn(" New Vendor ");when(update.productName()).thenReturn(" New Product ");
        when(update.sourceReference()).thenReturn(" New evidence ");when(update.costAmount()).thenReturn(new BigDecimal("12.50"));
        when(update.costCurrency()).thenReturn("eur");when(update.verifiedAt()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        service.updateProduct(9L,update,"alice",context);
        service.updateProduct(9L,mock(UpdateProductRequest.class),"alice",context);
        assertThat(product.getManufacturer()).isEqualTo("New Vendor");assertThat(product.getProductName()).isEqualTo("New Product");
        assertThat(product.getCostAmount()).isEqualByComparingTo("12.50");assertThat(product.getCostCurrency()).isEqualTo("EUR");
        assertThat(product.getSourceReference()).isEqualTo("New evidence");
        verify(products,times(2)).findByIdAndScopeKey(9L,PortfolioScope.key("alice",context));
    }
    @Test void invalidRequestsAndUnknownIdentityNeverSave() {
        assertThatThrownBy(()->service.createProduct(null,"alice",context)).isInstanceOf(PortfolioException.class);
        assertThatThrownBy(()->service.updateProduct(9L,null,"alice",context)).isInstanceOf(PortfolioException.class);
        assertThatThrownBy(()->service.upsertTaxonomyCoverage(9L,null,"alice",context)).isInstanceOf(PortfolioException.class);
        assertThatThrownBy(()->service.upsertCandidate(1L,3L,null,"alice",context)).isInstanceOf(PortfolioException.class);
        assertThatThrownBy(()->service.requireProduct(null,"alice",context)).hasMessageContaining("productId is required");
        assertThatThrownBy(()->service.requireProduct(999L,"alice",context)).hasMessageContaining("Product not found");
        var invalid=mock(CreateProductRequest.class);when(invalid.productKey()).thenReturn("bad/key");
        assertThatThrownBy(()->service.createProduct(invalid,"alice",context)).hasMessageContaining("unsupported characters");
        var duplicate=mock(CreateProductRequest.class);when(duplicate.productKey()).thenReturn("prd-one");
        when(products.findByScopeKeyAndProductKeyIgnoreCase(anyString(),eq("PRD-ONE"))).thenReturn(Optional.of(product));
        assertThatThrownBy(()->service.createProduct(duplicate,"alice",context)).hasMessageContaining("already exists");
        verify(products,never()).save(any());verifyNoInteractions(coverage,candidates);
    }
    @ParameterizedTest @ValueSource(ints={-1,101})
    void outOfRangeCoverageIsRejectedBeforeSaving(int percent) {
        var request=mock(UpsertTaxonomyCoverageRequest.class);when(request.nodeCode()).thenReturn("cp-1");when(request.coveragePercent()).thenReturn(percent);
        assertThatThrownBy(()->service.upsertTaxonomyCoverage(9L,request,"alice",context)).hasMessageContaining("between 0 and 100");
        verify(coverage,never()).save(any());
    }
    @ParameterizedTest @ValueSource(doubles={-0.1,1.1,Double.NaN,Double.POSITIVE_INFINITY})
    void nonFiniteAndOutOfRangeConfidenceCannotCreateCandidates(double confidence) {
        var request=candidate();when(request.confidence()).thenReturn(confidence);
        assertThatThrownBy(()->service.upsertCandidate(1L,3L,request,"alice",context)).hasMessageContaining("confidence must be");
        verify(candidates,never()).save(any());
    }
    @Test void selectionRequiresConfirmedReviewAndNoHardExclusions() {
        var request=candidate();when(request.selectionStatus()).thenReturn(ProductSelectionStatus.SELECTED);
        assertThatThrownBy(()->service.upsertCandidate(1L,3L,request,"alice",context)).hasMessageContaining("CONFIRMED");
        when(request.reviewStatus()).thenReturn(ReviewStatus.CONFIRMED);when(request.hardExclusions()).thenReturn("unsupported jurisdiction");
        assertThatThrownBy(()->service.upsertCandidate(1L,3L,request,"alice",context)).hasMessageContaining("hard exclusions");
        verify(candidates,never()).save(any());
    }
    @Test void existingCandidateIsUpdatedWithoutChangingItsIdentity() {
        var candidate=new SolutionProductCandidate(decision,product,10,null,null,null,null,0.1,null,null,"alice",Instant.EPOCH);
        ReflectionTestUtils.setField(candidate,"id",17L);
        when(candidates.findByProjectSolutionIdAndProductId(3L,9L)).thenReturn(Optional.of(candidate));
        var result=service.upsertCandidate(1L,3L,candidate(),"alice",context);
        assertThat(result.id()).isEqualTo(17L);assertThat(candidate.getCoveragePercent()).isEqualTo(80);
        assertThat(candidate.getReviewStatus()).isEqualTo(ReviewStatus.PROPOSED);
        assertThat(candidate.getSelectionStatus()).isEqualTo(ProductSelectionStatus.CANDIDATE);
        verify(candidates).save(candidate);
    }
    @Test void unknownTaxonomyAndProjectSolutionStayErrors() {
        var request=mock(UpsertTaxonomyCoverageRequest.class);when(request.nodeCode()).thenReturn("missing");
        assertThatThrownBy(()->service.upsertTaxonomyCoverage(9L,request,"alice",context)).hasMessageContaining("Unknown taxonomy node");
        assertThatThrownBy(()->service.upsertCandidate(1L,999L,candidate(),"alice",context)).hasMessageContaining("Project solution not found");
        assertThatThrownBy(()->service.listCandidates(1L,999L,"alice",context)).hasMessageContaining("Project solution not found");
    }
    private UpsertProductCandidateRequest candidate() {
        var request=mock(UpsertProductCandidateRequest.class);when(request.productId()).thenReturn(9L);
        when(request.coveragePercent()).thenReturn(80);when(request.confidence()).thenReturn(0.8);return request;
    }
}
