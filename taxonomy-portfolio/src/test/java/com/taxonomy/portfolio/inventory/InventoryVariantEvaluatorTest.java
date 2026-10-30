package com.taxonomy.portfolio.inventory;
import com.taxonomy.extension.api.inventory.InventoryComparisonContracts.*;
import com.taxonomy.portfolio.dto.PortfolioDtos.*;
import com.taxonomy.portfolio.model.PortfolioTypes.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class InventoryVariantEvaluatorTest {
    ProjectSolutionView solution(int coverage) {
        var definition=new SolutionView(3L,"S","Existing solution",null,SolutionType.APPLICATION,OperatingModel.UNSPECIFIED,LifecycleStatus.ACTIVE,1,"alice","north",null,null,null,null,Map.of(),Instant.EPOCH,Instant.EPOCH,List.of(new TaxonomyCoverageView(1L,"BP",coverage,"Approved mapping",ReviewStatus.CONFIRMED,"alice",Instant.EPOCH)));
        return new ProjectSolutionView(7L,1L,definition,ProjectSolutionStatus.PROPOSED,ActionStatus.REUSE,50,"Existing","alice",Instant.EPOCH,Instant.EPOCH,List.of(),List.of());
    }
    final Demand demand=new Demand("repo","scope","draft",1L,2L,3L,"snapshot","taxonomy","snapshot-fp",List.of("BP"),new Question(TimeMode.EXACT_DATE,LocalDate.parse("2030-01-01"),null,"north",null));
    VariantAssessment evaluate(int coverage,VariantInput input,List<Finding> findings) {return new InventoryVariantEvaluator().evaluate(solution(coverage),"fp","{}",input,demand,findings,List.of());}
    VariantInput input(List<String> candidates) {return new VariantInput(7L,VariantAction.REUSE,candidates,List.of(),List.of(),List.of());}
    @Test void ownConfirmedZeroRemainsBlockingWithAdditionalPositiveCandidate() {
        var positive=new Finding("product:1",Criterion.CAPABILITY,"BP",Outcome.SUPPORTED,List.of("product-evidence"),Set.of(),Set.of(),"",false);
        for(var candidateIds:List.of(List.<String>of(),List.of("product:1"))) {
            var variant=evaluate(0,input(candidateIds),List.of(positive));
            assertFalse(variant.hardExclusions().isEmpty());assertTrue(variant.findings().stream().anyMatch(f->f.candidateId().equals("solution:3")&&f.outcome()==Outcome.CONTRADICTED));
        }
    }
    @Test void availabilityRemainsUnknownEvenForConfirmedFullSemanticCoverage() {
        var variant=evaluate(100,input(List.of()),List.of());
        assertTrue(variant.hardExclusions().isEmpty());assertFalse(variant.openQuestions().isEmpty());
        assertTrue(variant.findings().stream().anyMatch(f->f.criterion()==Criterion.AVAILABILITY&&f.outcome()==Outcome.UNKNOWN));
    }
    @Test void citedLongBlockingNotesRemainFullyFrozenAndDoNotDisappear() {
        String reason="blocking "+"x".repeat(1700);
        var input=new VariantInput(7L,VariantAction.CHANGE,List.of(),List.of(new AssessmentNote(reason,AssessmentStatus.BLOCKING,"Owner"),new AssessmentNote(reason,AssessmentStatus.BLOCKING,"Owner2"),new AssessmentNote(reason,AssessmentStatus.BLOCKING,"Owner3")),List.of(),List.of(new CostAssumption("9007199254740993","EUR","one-time","Budget")));
        var variant=evaluate(100,input,List.of());
        assertEquals(input,variant.input());assertTrue(String.join(";",variant.hardExclusions()).length()>4000);
    }
}
