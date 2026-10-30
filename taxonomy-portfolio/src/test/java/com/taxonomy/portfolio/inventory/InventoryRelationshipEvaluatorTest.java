package com.taxonomy.portfolio.inventory;
import com.taxonomy.extension.api.inventory.InventoryComparisonContracts.*;
import com.taxonomy.extension.api.inventory.InventoryReadContracts.AssertionKind;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class InventoryRelationshipEvaluatorTest {
    final Question question=new Question(TimeMode.CONTINUOUS_PERIOD,LocalDate.parse("2030-01-01"),LocalDate.parse("2031-01-01"),"north","prod");
    final InventoryRelationshipEvaluator evaluator=new InventoryRelationshipEvaluator();
    RelationshipRequirement required(RelationshipKind kind) {return new RelationshipRequirement("r","product:1","product:2",kind,"api",true);}
    Assertion edge(String id,boolean positive,RelationshipKind kind,String end) {return new Assertion(id,"product:1",Criterion.RELATIONSHIPS,null,positive,new Boundary("2029",Precision.YEAR),new Boundary(end,Precision.YEAR),"north","prod",AssertionKind.DECLARED,"Owner record",Instant.EPOCH,new Relationship("product:2",kind,"api"));}
    @Test void exactDirectedEvidenceSupportsButWrongTargetDirectionKindOrQualifierDoesNot() {
        var assertion=edge("yes",true,RelationshipKind.INTERFACE,"2032");
        assertEquals(Outcome.SUPPORTED,evaluator.evaluate(required(RelationshipKind.INTERFACE),question,List.of(assertion)).finding().outcome());
        for(var wrong:List.of(new RelationshipRequirement("r","product:2","product:1",RelationshipKind.INTERFACE,"api",true),new RelationshipRequirement("r","product:1","product:3",RelationshipKind.INTERFACE,"api",true),new RelationshipRequirement("r","product:1","product:2",RelationshipKind.DEPENDENCY,"api",true),new RelationshipRequirement("r","product:1","product:2",RelationshipKind.INTERFACE,"other",true)))
            assertEquals(Outcome.UNKNOWN,evaluator.evaluate(wrong,question,List.of(assertion)).finding().outcome());
    }
    @Test void conflictRetainsBothEvidenceIdsAndBlocksRequiredEdge() {
        var result=evaluator.evaluate(required(RelationshipKind.DEPENDENCY),question,List.of(edge("yes",true,RelationshipKind.DEPENDENCY,"2032"),edge("no",false,RelationshipKind.DEPENDENCY,"2032"))).finding();
        assertEquals(Outcome.CONFLICT,result.outcome());assertTrue(result.hardExclusion());assertEquals(Set.of("yes","no"),new HashSet<>(result.evidenceIds()));
    }
    @Test void absentOrExpiredPositiveEvidenceCannotFabricateNegativeEdge() {
        assertEquals(Outcome.UNKNOWN,evaluator.evaluate(required(RelationshipKind.ALTERNATIVE),question,List.of()).finding().outcome());
        assertEquals(Outcome.UNKNOWN,evaluator.evaluate(required(RelationshipKind.ALTERNATIVE),question,List.of(edge("old",true,RelationshipKind.ALTERNATIVE,"2029"))).finding().outcome());
    }
    @Test void confirmedHardExclusionBlocksButAbsentBanDoesNotEstablishCompatibility() {
        var requirement=required(RelationshipKind.HARD_EXCLUSION);
        assertTrue(evaluator.evaluate(requirement,question,List.of(edge("ban",true,RelationshipKind.HARD_EXCLUSION,"2032"))).finding().hardExclusion());
        assertEquals(Outcome.UNKNOWN,evaluator.evaluate(requirement,question,List.of()).finding().outcome());
    }
    @Test void informationalNegativeIsVisibleWithoutBlockingSelection() {
        var required=required(RelationshipKind.INTERFACE);
        var optional=new RelationshipRequirement(required.id(),required.sourceCandidateId(),required.targetCandidateId(),required.kind(),required.qualifier(),false);
        var finding=evaluator.evaluate(optional,question,List.of(edge("no",false,RelationshipKind.INTERFACE,"2032"))).finding();
        assertEquals(Outcome.CONTRADICTED,finding.outcome());assertFalse(finding.hardExclusion());
    }
    Assertion datedEdge(String id,boolean positive,RelationshipKind kind,String from,String until) {
        return new Assertion(id,"product:1",Criterion.RELATIONSHIPS,null,positive,new Boundary(from,Precision.DATE),new Boundary(until,Precision.DATE),"north","prod",AssertionKind.DECLARED,"Owner",Instant.EPOCH,new Relationship("product:2",kind,"api"));
    }
    @Test void partialNegativeConflictsWithPositiveCoverageInStartWindow() {
        var window=new Question(TimeMode.START_WINDOW,question.start(),question.endExclusive(),"north","prod");
        var result=evaluator.evaluate(required(RelationshipKind.INTERFACE),window,List.of(edge("yes",true,RelationshipKind.INTERFACE,"2032"),datedEdge("no",false,RelationshipKind.INTERFACE,"2030-06-01","2030-07-01"))).finding();
        assertEquals(Outcome.CONFLICT,result.outcome());assertTrue(result.hardExclusion());
    }
    @Test void unionOfNegativeIntervalsExcludesAllStartsButPartialBanAloneRemainsUnknown() {
        var window=new Question(TimeMode.START_WINDOW,question.start(),question.endExclusive(),"north","prod");
        var result=evaluator.evaluate(required(RelationshipKind.DEPENDENCY),window,List.of(datedEdge("first",false,RelationshipKind.DEPENDENCY,"2030-01-01","2030-07-01"),datedEdge("last",false,RelationshipKind.DEPENDENCY,"2030-07-01","2031-01-01"))).finding();
        assertEquals(Outcome.CONTRADICTED,result.outcome());assertTrue(result.hardExclusion());
        assertEquals(Outcome.UNKNOWN,evaluator.evaluate(required(RelationshipKind.HARD_EXCLUSION),window,List.of(datedEdge("part",true,RelationshipKind.HARD_EXCLUSION,"2030-06-01","2030-07-01"))).finding().outcome());
    }
    @Test void absentContractQualificationCannotEstablishInterfaceSuitability() {
        var requirement=new RelationshipRequirement("r","product:1","product:2",RelationshipKind.INTERFACE,null,true);
        var assertion=new Assertion("unqualified","product:1",Criterion.RELATIONSHIPS,null,true,new Boundary("2029",Precision.YEAR),new Boundary("2032",Precision.YEAR),"north","prod",AssertionKind.DECLARED,"Owner",Instant.EPOCH,new Relationship("product:2",RelationshipKind.INTERFACE,null));
        assertEquals(Outcome.UNKNOWN,evaluator.evaluate(requirement,question,List.of(assertion)).finding().outcome());
    }

}
