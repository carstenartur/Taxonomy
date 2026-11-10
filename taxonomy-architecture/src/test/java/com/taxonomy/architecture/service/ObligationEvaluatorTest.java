package com.taxonomy.architecture.service;

import com.taxonomy.dto.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ObligationEvaluatorTest {
    private final ObligationEvaluator evaluator = new ObligationEvaluator();
    private final Map<String,String> roots = Map.of("service", "CR", "process", "BP", "role", "BR");
    @Test void alternativeTargetTypesSatisfyOneCardinality() {
        var result = evaluator.evaluate(ObligationProfiles.SERVICE_SUPPORT, roots,
                List.of(new ObligationEvaluator.Relation("service","role","SUPPORTS","accepted")), EvidenceCompleteness.COMPLETE);
        assertThat(result).singleElement().satisfies(j -> {
            assertThat(j.status()).isEqualTo(ObligationJudgment.Status.SATISFIED);
            assertThat(j.observedCount()).isEqualTo(1);
            assertThat(j.targetRoots()).containsExactlyInAnyOrder("BP", "BR");
        });
    }
    @Test void incompleteEvidenceCannotProveAbsence() {
        assertThat(evaluator.evaluate(ObligationProfiles.SERVICE_SUPPORT, roots, List.of(), EvidenceCompleteness.INCOMPLETE))
                .singleElement().extracting(ObligationJudgment::status).isEqualTo(ObligationJudgment.Status.INCONCLUSIVE);
    }
    @Test void completeEvidenceCanProveScopedViolation() {
        assertThat(evaluator.evaluate(ObligationProfiles.SERVICE_SUPPORT, roots, List.of(), EvidenceCompleteness.COMPLETE))
                .singleElement().extracting(ObligationJudgment::status).isEqualTo(ObligationJudgment.Status.VIOLATED);
    }
    @Test void proposedHighConfidenceRelationIsNotAcceptedEvidence() {
        assertThat(evaluator.evaluate(ObligationProfiles.SERVICE_SUPPORT, roots,
                List.of(new ObligationEvaluator.Relation("service","process","SUPPORTS","proposed")), EvidenceCompleteness.COMPLETE))
                .singleElement().extracting(ObligationJudgment::status).isEqualTo(ObligationJudgment.Status.VIOLATED);
    }
    @Test void legalityAloneCreatesNoObligation() {
        assertThat(evaluator.evaluate(ObligationProfiles.COMPATIBILITY, roots, List.of(), EvidenceCompleteness.COMPLETE)).isEmpty();
    }
    @Test void duplicateEvidenceDoesNotInflateCardinalityAndMaximumIsEvaluated() {
        var profile = new ObligationProfile("bounded", "1", "Authored fixture", List.of(
                new ObligationProfile.Rule("exactly-one", Set.of("CR"), "SUPPORTS", Set.of("BP", "BR"), 1, 1,
                        ObligationProfile.Severity.ERROR, "Complete accepted outgoing relations")));
        var role = new ObligationEvaluator.Relation("service", "role", "SUPPORTS", "accepted");
        assertThat(evaluator.evaluate(profile, roots, List.of(role, role), EvidenceCompleteness.COMPLETE))
                .singleElement().extracting(ObligationJudgment::status).isEqualTo(ObligationJudgment.Status.SATISFIED);
        assertThat(evaluator.evaluate(profile, roots, List.of(role,
                new ObligationEvaluator.Relation("service", "process", "SUPPORTS", "accepted")), EvidenceCompleteness.COMPLETE))
                .singleElement().extracting(ObligationJudgment::status).isEqualTo(ObligationJudgment.Status.VIOLATED);
    }
    @Test void evenPositiveIncompleteEvidenceIsReportedConservatively() {
        assertThat(evaluator.evaluate(ObligationProfiles.SERVICE_SUPPORT, roots,
                List.of(new ObligationEvaluator.Relation("service", "role", "SUPPORTS", "accepted")), EvidenceCompleteness.INCOMPLETE))
                .singleElement().extracting(ObligationJudgment::status).isEqualTo(ObligationJudgment.Status.INCONCLUSIVE);
    }
}
