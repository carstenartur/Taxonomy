package com.taxonomy.portfolio.inventory;

import com.taxonomy.extension.api.inventory.InventoryComparisonContracts.*;
import com.taxonomy.extension.api.inventory.InventoryReadContracts.AssertionKind;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class InventoryCriterionEvaluatorTest {
    private final InventoryCriterionEvaluator evaluator = new InventoryCriterionEvaluator();
    private final Question period = new Question(TimeMode.CONTINUOUS_PERIOD, LocalDate.parse("2030-01-01"), LocalDate.parse("2031-01-01"), "north", "production");
    private Assertion assertion(boolean positive, String start, String end, AssertionKind kind) {
        return new Assertion("evidence-" + positive + start + end + kind, "product:1", Criterion.AVAILABILITY, null, positive,
                start == null ? null : new Boundary(start, start.length() == 4 ? Precision.YEAR : Precision.DATE),
                end == null ? null : new Boundary(end, end.length() == 4 ? Precision.YEAR : Precision.DATE),
                "north", "production", kind, "approved operational plan", Instant.parse("2026-10-01T00:00:00Z"));
    }
    private Finding evaluate(Question q, Assertion... assertions) {
        return evaluator.evaluate("product:1", Criterion.AVAILABILITY, null, q, List.of(assertions), true);
    }
    @Test void wholeCoverageSupportsAndMissingEndDoesNotMeanInfinity() {
        assertEquals(Outcome.SUPPORTED, evaluate(period, assertion(true,"2029-01-01","2032-01-01", AssertionKind.OBSERVED)).outcome());
        assertEquals(Outcome.UNKNOWN, evaluate(period, assertion(true,"2029-01-01",null, AssertionKind.OBSERVED)).outcome());
    }
    @Test void documentedEndWithinContinuousUseContradicts() {
        var finding = evaluate(period, assertion(true,"2029-01-01","2030-07-01", AssertionKind.DECLARED));
        assertEquals(Outcome.CONTRADICTED, finding.outcome());
        assertTrue(finding.hardExclusion());
    }
    @Test void partialStartWindowRemainsUnknownButAllStartsExcludedContradicts() {
        var window = new Question(TimeMode.START_WINDOW, period.start(), period.endExclusive(), "north", "production");
        assertEquals(Outcome.UNKNOWN, evaluate(window, assertion(true,"2030-07-01","2032-01-01", AssertionKind.PLANNED)).outcome());
        assertEquals(Outcome.CONTRADICTED, evaluate(window, assertion(true,"2028-01-01","2029-01-01", AssertionKind.DECLARED)).outcome());
    }
    @Test void yearPrecisionDoesNotInventAnExactStart() {
        var exact = new Question(TimeMode.EXACT_DATE, LocalDate.parse("2030-06-01"), null,"north","production");
        assertEquals(Outcome.UNKNOWN, evaluate(exact, assertion(true,"2030","2032", AssertionKind.PLANNED)).outcome());
        assertEquals(Outcome.SUPPORTED, evaluate(period, assertion(true,"2029","2032", AssertionKind.PLANNED)).outcome());
    }
    @Test void conflictingAdmissibleEvidenceIsRetainedAndExcludesSelection() {
        var result = evaluate(period, assertion(true,"2029-01-01","2032-01-01", AssertionKind.OBSERVED), assertion(false,"2029-01-01","2032-01-01", AssertionKind.DECLARED));
        assertEquals(Outcome.CONFLICT,result.outcome()); assertEquals(2,result.evidenceIds().size()); assertTrue(result.hardExclusion());
    }
    @Test void differentContextDoesNotEstablishAvailabilityAndUnexaminedIsSeparate() {
        var wrong = new Question(TimeMode.CONTINUOUS_PERIOD,period.start(),period.endExclusive(),"south","test");
        assertEquals(Outcome.UNKNOWN,evaluate(wrong,assertion(true,"2029-01-01","2032-01-01",AssertionKind.OBSERVED)).outcome());
        assertEquals(Outcome.UNEXAMINED,evaluator.evaluate("product:1",Criterion.RELATIONSHIPS,null,period,List.of(),false).outcome());
    }
    @Test void plannedAndActualRemainExplicitInFinding() {
        var result = evaluate(period,assertion(true,"2029-01-01","2032-01-01",AssertionKind.PLANNED));
        assertEquals(Set.of(AssertionKind.PLANNED),result.assertionKinds());
        assertTrue(result.limitations().contains("PLANNED_NOT_ACTUAL"));
    }
    @Test void exactDateUsesHalfOpenEnd() {
        var exact = new Question(TimeMode.EXACT_DATE,LocalDate.parse("2030-01-01"),null,"north","production");
        assertEquals(Outcome.CONTRADICTED,evaluate(exact,assertion(true,"2029-01-01","2030-01-01",AssertionKind.OBSERVED)).outcome());
    }
    @Test void explicitNegativeInsideContinuousUseContradictsWholePeriod() {
        assertEquals(Outcome.CONTRADICTED,evaluate(period,assertion(false,"2030-06-01","2030-07-01",AssertionKind.DECLARED)).outcome());
    }
    @Test void documentedStartInsideContinuousUseContradictsRequiredEarlierUse() {
        assertEquals(Outcome.CONTRADICTED,evaluate(period,assertion(true,"2030-06-01","2032-01-01",AssertionKind.DECLARED)).outcome());
    }
    @Test void timelessConfirmedCapabilityNeverManufacturesOrganizationalDates() {
        var mapping=new Assertion("mapping:1","product:1",Criterion.CAPABILITY,"fixture:node",true,null,null,null,null,AssertionKind.DECLARED,"Confirmed mapping",Instant.EPOCH);
        assertEquals(Outcome.SUPPORTED,evaluator.evaluate("product:1",Criterion.CAPABILITY,"fixture:node",period,List.of(mapping),true).outcome());
    }

    @Test void multipleExplicitExclusionsCanExcludeEveryPermittedStart() {
        var window=new Question(TimeMode.START_WINDOW,period.start(),period.endExclusive(),"north","production");
        var result=evaluate(window,assertion(false,"2029-01-01","2030-07-01",AssertionKind.DECLARED),assertion(false,"2030-07-01","2032-01-01",AssertionKind.DECLARED));
        assertEquals(Outcome.CONTRADICTED,result.outcome());assertTrue(result.hardExclusion());
    }

    @Test void partialExplicitExclusionConflictsWithPositiveStartWindowCoverage() {
        var window=new Question(TimeMode.START_WINDOW,period.start(),period.endExclusive(),"north","production");
        var result=evaluate(window,assertion(true,"2029-01-01","2032-01-01",AssertionKind.OBSERVED),assertion(false,"2030-06-01","2030-07-01",AssertionKind.DECLARED));
        assertEquals(Outcome.CONFLICT,result.outcome());assertTrue(result.hardExclusion());assertEquals(2,result.evidenceIds().size());
    }

}
