package com.taxonomy.acceptance;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.taxonomy.acceptance.DirectedRelationMetrics.*;
import static org.junit.jupiter.api.Assertions.*;

class DirectedRelationMetricsTest {
    private static final Relation READ = new Relation("UA-1580", "CONSUMES", "IP-1116");
    private static final Relation SUPPORT = new Relation("CI-1052", "SUPPORTS", "BP-1017");
    private static final Relation WRITE = new Relation("UA-1580", "PRODUCES", "IP-1116");
    private static final Reference REQUIRED = new Reference(Set.of(READ, SUPPORT), Set.of(), Set.of());

    @Test void exactDirectedRelationsPass() {
        var result = evaluate(REQUIRED, List.of(SUPPORT, READ), RunState.SUCCESS);
        assertEquals(Verdict.PASS, result.verdict());
        assertEquals(1.0, result.precision());
        assertEquals(1.0, result.recall());
    }

    @Test void reversedDirectionIsFalsePositiveAndMissesTheRequiredRelation() {
        var reverse = new Relation(READ.target(), READ.type(), READ.source());
        var result = evaluate(REQUIRED, List.of(SUPPORT, reverse), RunState.SUCCESS);
        assertEquals(Verdict.FAIL, result.verdict());
        assertEquals(List.of(reverse), result.falsePositives());
        assertEquals(List.of(READ), result.notObservedRequired());
        assertEquals(0.5, result.precision());
        assertEquals(0.5, result.recall());
    }

    @Test void readingDoesNotAuthorizeWriting() {
        var result = evaluate(REQUIRED, List.of(READ, SUPPORT, WRITE), RunState.SUCCESS);
        assertEquals(List.of(WRITE), result.falsePositives());
        assertEquals(2.0 / 3.0, result.precision());
        assertEquals(1.0, result.recall());
        assertEquals(Verdict.FAIL, result.verdict());
    }

    @Test void allowedRelationsAreNotMandatoryForRecall() {
        var reference = new Reference(Set.of(READ), Set.of(SUPPORT), Set.of());
        var withoutOptional = evaluate(reference, List.of(READ), RunState.SUCCESS);
        var withOptional = evaluate(reference, List.of(READ, SUPPORT), RunState.SUCCESS);
        assertEquals(Verdict.PASS, withoutOptional.verdict());
        assertEquals(Verdict.PASS, withOptional.verdict());
        assertEquals(1.0, withOptional.precision());
        assertEquals(1.0, withOptional.recall());
        assertEquals(1, withOptional.matchedAllowed());
    }

    @Test void unknownReferenceNeverCreatesACompletePass() {
        var reference = new Reference(Set.of(READ), Set.of(), Set.of(SUPPORT));
        var result = evaluate(reference, List.of(READ, SUPPORT), RunState.SUCCESS);
        assertEquals(Verdict.INCONCLUSIVE, result.verdict());
        assertEquals(List.of(SUPPORT), result.unresolvedPredictions());
        assertTrue(result.falsePositives().isEmpty());
        assertEquals(1, result.unresolvedReferenceCount());
    }

    @Test void unobservedUnknownReferenceAlsoRemainsOpen() {
        var reference = new Reference(Set.of(READ), Set.of(), Set.of(SUPPORT));
        assertEquals(Verdict.INCONCLUSIVE, evaluate(reference, List.of(READ), RunState.SUCCESS).verdict());
    }

    @Test void knownErrorsAreNotHiddenByUnknownReference() {
        var reference = new Reference(Set.of(READ), Set.of(), Set.of(SUPPORT));
        assertEquals(Verdict.FAIL, evaluate(reference, List.of(WRITE), RunState.SUCCESS).verdict());
    }

    @Test void incompleteRunsNeverReceiveQualityRatesOrPass() {
        for (var state : List.of(RunState.PARTIAL, RunState.FAILED, RunState.CANCELLED)) {
            var result = evaluate(REQUIRED, List.of(READ, SUPPORT), state);
            assertEquals(Verdict.INCONCLUSIVE, result.verdict());
            assertNull(result.precision());
            assertNull(result.recall());
            assertEquals(2, result.matchedRequired());
        }
    }

    @Test void notRunIsDistinctFromAnEmptySuccessfulPrediction() {
        var result = evaluate(REQUIRED, List.of(), RunState.NOT_RUN);
        assertEquals(Verdict.NOT_RUN, result.verdict());
        assertNull(result.precision());
        assertNull(result.recall());
        assertThrows(IllegalArgumentException.class, () -> evaluate(REQUIRED, List.of(READ), RunState.NOT_RUN));
    }

    @Test void noPredictionsHaveUndefinedPrecisionAndZeroRecall() {
        var result = evaluate(REQUIRED, List.of(), RunState.SUCCESS);
        assertNull(result.precision());
        assertEquals(0.0, result.recall());
        assertEquals(Verdict.FAIL, result.verdict());
    }

    @Test void explicitEmptyReferenceDoesNotInventPerfectRates() {
        var result = evaluate(new Reference(Set.of(), Set.of(), Set.of()), List.of(), RunState.SUCCESS);
        assertEquals(Verdict.PASS, result.verdict());
        assertNull(result.precision());
        assertNull(result.recall());
    }

    @Test void duplicatePredictionsAreRejectedInsteadOfDeduplicatedIntoSuccess() {
        assertThrows(IllegalArgumentException.class,
                () -> evaluate(REQUIRED, List.of(READ, READ, SUPPORT), RunState.SUCCESS));
    }

    @Test void referenceClassificationsMustBeDisjoint() {
        assertThrows(IllegalArgumentException.class, () -> new Reference(Set.of(READ), Set.of(READ), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new Reference(Set.of(READ), Set.of(), Set.of(READ)));
        assertThrows(IllegalArgumentException.class, () -> new Reference(Set.of(), Set.of(READ), Set.of(READ)));
    }

    @Test void outputOrderingIsIndependentOfInputOrder() {
        var first = evaluate(REQUIRED, List.of(WRITE), RunState.SUCCESS);
        var shuffled = new Reference(new java.util.LinkedHashSet<>(List.of(SUPPORT, READ)), Set.of(), Set.of());
        assertEquals(first, evaluate(shuffled, List.of(WRITE), RunState.SUCCESS));
        assertThrows(UnsupportedOperationException.class, () -> first.falsePositives().clear());
        assertThrows(UnsupportedOperationException.class, () -> REQUIRED.required().clear());
    }

    @Test void invalidFieldsAndNullInputFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> new Relation(" ", "USES", "IP-1116"));
        assertThrows(IllegalArgumentException.class, () -> new Relation("UA-1580", "USES\n", "IP-1116"));
        assertThrows(NullPointerException.class, () -> new Relation(null, "USES", "IP-1116"));
        assertThrows(NullPointerException.class, () -> evaluate(REQUIRED, null, RunState.SUCCESS));
        assertThrows(NullPointerException.class, () -> evaluate(REQUIRED, List.of(), null));
    }
}
