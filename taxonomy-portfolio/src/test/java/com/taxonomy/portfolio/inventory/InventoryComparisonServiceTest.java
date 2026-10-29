package com.taxonomy.portfolio.inventory;
import com.taxonomy.extension.api.inventory.InventoryComparisonContracts.*;
import com.taxonomy.extension.api.inventory.InventoryEvidencePort;
import com.taxonomy.portfolio.dto.PortfolioDtos.*;
import com.taxonomy.portfolio.model.PortfolioTypes.*;
import com.taxonomy.portfolio.service.*;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class InventoryComparisonServiceTest {
    final ProductCatalogService products=mock(ProductCatalogService.class);
    final PortfolioAnalysisPersistenceService snapshots=mock(PortfolioAnalysisPersistenceService.class);
    final ProjectPortfolioService projects=mock(ProjectPortfolioService.class);
    final SolutionPortfolioService solutions=mock(SolutionPortfolioService.class);
    final InventoryEvidencePort evidence=mock(InventoryEvidencePort.class);
    final WorkspaceContext context=new WorkspaceContext("alice","ws","draft","repo");
    final InventoryComparisonService service=new InventoryComparisonService(products,snapshots,projects,solutions,evidence,new PortfolioJsonCodec(JsonMapper.builder().build()));
    @org.junit.jupiter.api.BeforeEach void fingerprints() {when(evidence.fingerprint(any())).thenAnswer(call->Integer.toHexString(call.getArgument(0).hashCode()));}
    @Test void comparisonRequiresExactRecordedSelectionAndRetainsUnknowns() {
        var summary=mock(SnapshotSummary.class);when(summary.id()).thenReturn("snapshot");when(summary.requirementId()).thenReturn(2L);when(summary.requirementVersionId()).thenReturn(3L);when(summary.taxonomyFingerprint()).thenReturn("catalog");
        var mapping=mock(ElementMappingView.class);when(mapping.nodeCode()).thenReturn("fixture:node");
        var detail=new SnapshotDetail(summary,null,null,null,null,List.of(mapping),List.of());
        when(snapshots.getSnapshot(1L,"snapshot","alice",context)).thenReturn(detail);
        when(evidence.readSources(any(),any(),any())).thenReturn(new InventoryEvidencePort.SourceRead(List.of(),List.of()));
        when(products.listProducts("alice",context)).thenReturn(List.of());
        var current=mock(RequirementView.class);when(current.currentVersionId()).thenReturn(5L);when(projects.getRequirement(1L,2L,"alice",context)).thenReturn(current);
        when(projects.listRequirementVersions(1L,2L,"alice",context)).thenReturn(List.of(new RequirementVersionView(3L,1,"Exact original demand","hash",null,"alice",Instant.EPOCH,null)));
        var question=new Question(TimeMode.EXACT_DATE,LocalDate.parse("2030-01-01"),null,"north",null);
        var result=service.compare(1L,"snapshot",new InventoryComparisonService.CreateComparisonRequest(List.of("fixture:node"),question,List.of()),"alice",context);
        assertEquals(List.of("fixture:node"),result.demand().selectedNodes());assertEquals(3L,result.demand().requirementVersionId());assertEquals(5L,result.demand().observedRequirementVersionId());
        verify(evidence).save(any(),eq(result));
        assertThrows(PortfolioException.class,()->service.compare(1L,"snapshot",new InventoryComparisonService.CreateComparisonRequest(List.of("outside-selection"),question,List.of()),"alice",context));
    }
    @Test void hardContradictionPreventsSelectionRegardlessOfBrowserPayload() {
        var demand=new Demand("repo",PortfolioScope.key("alice",context),"draft",1L,2L,3L,"snapshot","catalog","demand",List.of("fixture:node"),new Question(TimeMode.EXACT_DATE,LocalDate.parse("2030-01-01"),null,"north",null));
        var candidate=new Candidate("product:1",1L,"Product","TECHNOLOGY_PRODUCT","product-v1",Map.of(),List.of());
        var finding=new Finding(candidate.id(),Criterion.AVAILABILITY,null,Outcome.CONTRADICTED,List.of(),Set.of(),Set.of(),"clarify",true);
        var comparison=new Comparison(UUID.randomUUID(),demand,InventoryCriterionEvaluator.VERSION,Instant.now(),List.of(candidate),List.of(finding),List.of(),"fingerprint");
        when(evidence.read(any(),eq(comparison.id()))).thenReturn(comparison);
        assertThrows(PortfolioException.class,()->service.review(comparison.id(),new Review(UUID.randomUUID(),"fingerprint",candidate.id(),"Choose existing product",7L,true),"alice",context));
        verifyNoInteractions(solutions);verify(products,never()).upsertCandidate(any(),any(),any(),any(),any());
    }
    @Test void positiveStartWindowWithPartialExclusionCannotBeSelected() {
        var question=new Question(TimeMode.START_WINDOW,LocalDate.parse("2030-01-01"),LocalDate.parse("2031-01-01"),"north",null);
        var claims=List.of(new Assertion("yes","product:1",Criterion.AVAILABILITY,null,true,new Boundary("2029",Precision.YEAR),new Boundary("2032",Precision.YEAR),"north",null,com.taxonomy.extension.api.inventory.InventoryReadContracts.AssertionKind.DECLARED,"Positive claim",Instant.EPOCH),
                new Assertion("no","product:1",Criterion.AVAILABILITY,null,false,new Boundary("2030-06-01",Precision.DATE),new Boundary("2030-07-01",Precision.DATE),"north",null,com.taxonomy.extension.api.inventory.InventoryReadContracts.AssertionKind.DECLARED,"Explicit exclusion",Instant.EPOCH));
        var demand=new Demand("repo",PortfolioScope.key("alice",context),"draft",1L,2L,3L,"snapshot","catalog","demand",List.of("BP"),question);
        var candidate=new Candidate("product:1",1L,"Product","TECHNOLOGY_PRODUCT","product-v1",Map.of(),claims);
        var finding=new InventoryCriterionEvaluator().evaluate(candidate.id(),Criterion.AVAILABILITY,null,question,claims,true);
        var comparison=new Comparison(UUID.randomUUID(),demand,InventoryCriterionEvaluator.VERSION,Instant.now(),List.of(candidate),List.of(finding),List.of(),"fingerprint");
        when(evidence.read(any(),eq(comparison.id()))).thenReturn(comparison);
        when(evidence.review(any(),any(),any(),any())).thenAnswer(c->new Reviewed(c.getArgument(2),Instant.now(),"alice"));
        assertThrows(PortfolioException.class,()->service.review(comparison.id(),new Review(UUID.randomUUID(),"fingerprint",candidate.id(),"Select despite conflicting interval",7L,true),"alice",context));
    }

    @Test void retainedComparisonWithOlderEvaluationRulesRequiresNewComparison() {
        var demand=new Demand("repo",PortfolioScope.key("alice",context),"draft",1L,2L,3L,"snapshot","catalog","demand",List.of("BP"),new Question(TimeMode.START_WINDOW,LocalDate.parse("2030-01-01"),LocalDate.parse("2031-01-01"),"north",null));
        var candidate=new Candidate("product:1",1L,"Product","TECHNOLOGY_PRODUCT","product-v1",Map.of(),List.of());
        var comparison=new Comparison(UUID.randomUUID(),demand,"inventory-criteria/1",Instant.now(),List.of(candidate),List.of(),List.of(),"fingerprint");
        when(evidence.read(any(),eq(comparison.id()))).thenReturn(comparison);
        when(evidence.review(any(),eq(comparison.id()),any(),any())).thenAnswer(call->{((java.util.function.Supplier<?>)call.getArgument(3)).get();return null;});
        var failure=assertThrows(PortfolioException.class,()->service.review(comparison.id(),new Review(UUID.randomUUID(),"fingerprint",candidate.id(),"Old supported preview",7L,true),"alice",context));
        assertEquals(PortfolioException.Kind.CONFLICT,failure.getKind());assertTrue(failure.getMessage().contains("evaluation rules"));
        verify(products,never()).requireProductForUpdate(any(),any(),any());
        verify(projects,never()).requireProjectForUpdate(any(),any(),any());
    }

    @Test void identicalRetainedReviewReplaysAcrossEvaluatorUpgradeWithoutApplication() {
        var demand=new Demand("repo",PortfolioScope.key("alice",context),"draft",1L,2L,3L,"snapshot","catalog","demand",List.of("BP"),new Question(TimeMode.EXACT_DATE,LocalDate.parse("2030-01-01"),null,"north",null));
        var candidate=new Candidate("product:1",1L,"Product","TECHNOLOGY_PRODUCT","product-v1",Map.of(),List.of());
        var comparison=new Comparison(UUID.randomUUID(),demand,"inventory-criteria/1",Instant.EPOCH,List.of(candidate),List.of(),List.of(),"fingerprint");
        var review=new Review(UUID.randomUUID(),"fingerprint",candidate.id(),"Historical selection",7L,true,100);
        var retained=new Reviewed(review,Instant.EPOCH,"alice");
        when(evidence.read(any(),eq(comparison.id()))).thenReturn(comparison);
        when(evidence.review(any(),eq(comparison.id()),eq(review),any())).thenReturn(retained);
        assertEquals(retained,service.review(comparison.id(),review,"alice",context));
        verify(products,never()).requireProductForUpdate(any(),any(),any());
        verify(projects,never()).requireProjectForUpdate(any(),any(),any());
    }

}
