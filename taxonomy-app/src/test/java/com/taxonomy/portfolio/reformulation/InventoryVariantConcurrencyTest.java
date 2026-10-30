package com.taxonomy.portfolio.reformulation;
import com.taxonomy.extension.api.inventory.InventoryComparisonContracts.*;
import com.taxonomy.portfolio.dto.PortfolioDtos.*;
import com.taxonomy.portfolio.model.PortfolioTypes.*;
import com.taxonomy.portfolio.inventory.InventoryComparisonService;
import com.taxonomy.portfolio.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real service writers must not commit between frozen variant validation and its application. */
@SpringBootTest @AutoConfigureMockMvc @WithMockUser(username="architect",roles="ARCHITECT")
class InventoryVariantConcurrencyTest extends ReformulationWorkflowFixture {
    @Autowired InventoryComparisonService comparisons;
    @Autowired ProductCatalogService products;
    @MockitoSpyBean SolutionPortfolioService solutions;
    @Autowired com.taxonomy.catalog.api.TaxonomyNodeLookup nodes;
    @Autowired PortfolioFingerprintService fingerprints;
    @Override String snapshot(RequirementView req) {
        var official=nodes.findByCode("BP").orElseThrow();var node=new com.taxonomy.dto.TaxonomyNodeDto();node.setCode(official.getCode());node.setNameEn(official.getNameEn());
        var result=new com.taxonomy.dto.AnalysisResult(Map.of(official.getCode(),60),List.of(node));result.setStatus("SUCCESS");
        var job=analyses.createOrReuseJob(project.id(),List.of(req.id()),null,25,UUID.randomUUID().toString(),"architect",context);String id=UUID.randomUUID().toString();
        analyses.persistSnapshot(job.items().getFirst().id(),job.id(),project.id(),PortfolioScope.key("architect",context),id,"session-"+id,result,null,null,null,null,null,"fixture-prompt",fingerprints.taxonomyFingerprint(),"architect",context,1);return id;
    }
    enum Writer { FIELDS, COVERAGE, LINK, MEMBERSHIP, PROJECT_SOLUTION }
    @ParameterizedTest @EnumSource(Writer.class)
    void writersRemainOutsideTheValidatedApplicationWindow(Writer writer) throws Exception {
        var solution=solutions.createSolution(new CreateSolutionRequest("RACE-"+UUID.randomUUID(),"Existing race solution",null,null,null,null,null,"north",null,null,null,null,Map.of()),"architect",context);
        var decision=solutions.addProjectSolution(project.id(),new AddProjectSolutionRequest(solution.id(),null,null,50,"Existing"),"architect",context);
        var product=products.createProduct(new CreateProductRequest("RACE-P-"+UUID.randomUUID(),"Manufacturer",null,"Existing product",null,ProductStatus.ACTIVE,null,null,OperatingModel.UNSPECIFIED,null,null,null,null,null,null,"Source",Instant.EPOCH),"architect",context);
        var request=new InventoryComparisonService.CreateComparisonRequest(List.of("BP"),new Question(TimeMode.EXACT_DATE,LocalDate.parse("2030-01-01"),null,"north",null),List.of(),List.of(),List.of(new VariantInput(decision.id(),VariantAction.REUSE,List.of(),List.of(),List.of(),List.of())));
        var comparison=comparisons.compare(project.id(),snapshot,request,"architect",context);
        var review=new Review(UUID.randomUUID(),comparison.fingerprint(),null,"Reasoned evaluation",decision.id(),false,60,new VariantDecision(decision.id(),VariantOutcome.EVALUATED,true));
        var ready=new CountDownLatch(1);var release=new CountDownLatch(1);var attempted=new CountDownLatch(1);
        doAnswer(call->{ready.countDown();if(!release.await(15,TimeUnit.SECONDS))throw new IllegalStateException("Review pause not released");return call.callRealMethod();}).when(solutions).applyInventoryVariant(eq(project.id()),eq(decision.id()),any(),any(),anyString(),anyString(),anyString(),anyString(),eq("architect"),eq(context));
        Runnable mutate=()->{attempted.countDown();switch(writer) {
            case FIELDS -> solutions.updateSolution(solution.id(),json.readValue("{\"title\":\"Changed solution\"}",UpdateSolutionRequest.class),"architect",context);
            case COVERAGE -> solutions.upsertTaxonomyCoverage(solution.id(),new UpsertTaxonomyCoverageRequest("BP",0,"Explicit zero mapping",ReviewStatus.CONFIRMED),"architect",context);
            case LINK -> solutions.linkRequirement(project.id(),decision.id(),new LinkRequirementSolutionRequest(requirement.id(),snapshot,20,RequirementSolutionRole.USES,ReviewStatus.CONFIRMED,"Changed link"),"architect",context);
            case MEMBERSHIP -> products.upsertCandidate(project.id(),decision.id(),new UpsertProductCandidateRequest(product.id(),70,null,null,null,"Evidence",0,ReviewStatus.CONFIRMED,ProductSelectionStatus.CANDIDATE),"architect",context);
            case PROJECT_SOLUTION -> solutions.updateProjectSolution(project.id(),decision.id(),new UpdateProjectSolutionRequest(null,null,75,"Changed decision"),"architect",context);
        }};
        try(var executor=Executors.newFixedThreadPool(2)) {
            var applied=executor.submit(()->comparisons.review(comparison.id(),review,"architect",context));
            assertTrue(ready.await(15,TimeUnit.SECONDS),"Real application reached after input validation");
            var changed=executor.submit(mutate);assertTrue(attempted.await(5,TimeUnit.SECONDS));
            boolean committed;
            try {changed.get(500,TimeUnit.MILLISECONDS);committed=true;}catch(TimeoutException blocked){committed=false;}finally{release.countDown();}
            applied.get(15,TimeUnit.SECONDS);
            try {changed.get(15,TimeUnit.SECONDS);}catch(ExecutionException contention) {
                var conflict=assertInstanceOf(PortfolioException.class,contention.getCause());assertEquals(PortfolioException.Kind.CONFLICT,conflict.getKind());mutate.run();
            }
            assertFalse(committed,"Writer committed inside reviewed application window: "+writer);
            assertEquals(1,comparisons.reviews(comparison.id(),"architect",context).size());
            var stale=new Review(UUID.randomUUID(),comparison.fingerprint(),null,"Reapply stale comparison",decision.id(),false,60,new VariantDecision(decision.id(),VariantOutcome.EVALUATED,true));
            assertThrows(PortfolioException.class,()->comparisons.review(comparison.id(),stale,"architect",context));
        } finally {release.countDown();reset(solutions);}
    }
}
