package com.taxonomy.analysis.demo.evaluation;

import com.taxonomy.dto.AnalysisCoverage;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Mutations of a passing A0/A1 pair must be detected; missing work stays NOT_EXECUTED. */
class DemoReferenceEvaluatorTest {
    private static final String R0 = "Wir benötigen unternehmensweit ein System zur Arbeitszeiterfassung der Mitarbeiter.";
    private static final String R1 = """
            Mitarbeiter erfassen ihre Arbeitszeit im Browser und an Online-Terminals.
            Eine Offline-Erfassung ist nicht erforderlich; Buchungen erfolgen nur online.
            Mitarbeiter beantragen Korrekturen ihrer Zeitbuchungen, die von ihren Vorgesetzten genehmigt werden.
            Die Anmeldung erfolgt über die zentrale Unternehmensanmeldung; Mitarbeiterstammdaten werden aus dem führenden HR-System übernommen.
            Das System erfasst weder Standortdaten noch biometrische Merkmale der Mitarbeiter.
            """ + R0;
    private final DemoReference reference = DemoReference.load("time-tracking-v1");

    private static DemoReferenceEvaluator.Observation a0() {
        return new DemoReferenceEvaluator.Observation(R0, Map.of("UA-1605", 60d, "CR-1036", 20d),
                Set.of("UA-1605 USES CR-1036"), Map.of("CO", new AnalysisCoverage.NodeAssessment(
                AnalysisCoverage.State.UNKNOWN, null, null, AnalysisCoverage.Descendants.UNASSESSED,
                "CLARIFICATION: Capture channels are not specified. Is any network communication required?")));
    }

    private static DemoReferenceEvaluator.Observation a1() {
        return new DemoReferenceEvaluator.Observation(R1,
                Map.of("CO", 40d, "CO-1038", 40d, "UA-1605", 70d, "CR-1036", 25d,
                        "CR-1006", 12d, "CR-1065", 15d, "BR-1101", 25d),
                Set.of("UA-1605 USES CR-1036", "UA-1605 USES CR-1006", "UA-1605 USES CR-1065"));
    }

    @Test void consistentDemoPassesAndReportsImprovements() {
        var report = DemoReferenceEvaluator.evaluate(reference, R0, a0(), a1());
        assertThat(report.overall()).isEqualTo(DemoReferenceEvaluator.Overall.PASS);
        assertThat(report.improvements()).contains("aspect:channels", "aspect:privacy", "node:BR-1101",
                "relation:UA-1605 USES CR-1006");
    }

    @Test void removingTheOriginalCannotPassEvenWhenEveryR1DecisionAndDirectedRelationStillMatches() {
        var edited = new DemoReferenceEvaluator.Observation(R1.replace(R0, ""), a1().scores(), a1().relations());
        var report = DemoReferenceEvaluator.evaluate(reference, R0, a0(), edited);
        assertThat(report.overall()).isEqualTo(DemoReferenceEvaluator.Overall.FAIL);
        assertThat(report.checks()).filteredOn(c -> c.status() == DemoReferenceEvaluator.Status.FAIL)
                .extracting(DemoReferenceEvaluator.Check::id).containsExactly("source:retained-original");
    }

    @Test void anR1DecisionCannotBeClaimedRetroactivelyAsTheAnalysedR0() {
        var retroactive = new DemoReferenceEvaluator.Observation(R0 + "\nBuchungen erfolgen nur online.",
                a0().scores(), a0().relations(), a0().coverage());
        var report = DemoReferenceEvaluator.evaluate(reference, R0, retroactive, a1());
        assertThat(report.overall()).isEqualTo(DemoReferenceEvaluator.Overall.FAIL);
        assertThat(report.checks()).filteredOn(c -> c.status() == DemoReferenceEvaluator.Status.FAIL)
                .extracting(DemoReferenceEvaluator.Check::id).containsExactly("source:frozen-original");
    }

    @Test void separateOfflineScenarioAcceptsAParaphraseAndRejectsTheOppositeDecision() {
        String original = "Prüfberichte müssen auch bei einem Netzausfall erfasst werden.";
        var offlineReference = new DemoReference("field-inspection", 1,
                List.of(new DemoReference.Aspect("buffer", Map.of("de", "Lokale Aufbewahrung"),
                        List.of("lokal zwischengespeichert", "auf dem Gerät gepuffert"))),
                List.of(new DemoReference.Forbidden("discard", Map.of("de", "Kein Datenverlust"),
                        "ungesendete Berichte werden verworfen")),
                List.of(), List.of(), List.of(), List.of());
        var initial = new DemoReferenceEvaluator.Observation(original, Map.of(), Set.of());
        var paraphrase = new DemoReferenceEvaluator.Observation("Berichte werden auf dem Gerät gepuffert.\n" + original,
                Map.of(), Set.of());
        var passing = DemoReferenceEvaluator.evaluate(offlineReference, original, initial, paraphrase);
        assertThat(passing.overall()).isEqualTo(DemoReferenceEvaluator.Overall.PASS);
        assertThat(passing.improvements()).containsExactly("aspect:buffer");

        var contradicting = new DemoReferenceEvaluator.Observation(paraphrase.requirementText()
                + "\nBei Netzausfall gilt: ungesendete Berichte werden verworfen.", Map.of(), Set.of());
        var failing = DemoReferenceEvaluator.evaluate(offlineReference, original, initial, contradicting);
        assertThat(failing.overall()).isEqualTo(DemoReferenceEvaluator.Overall.FAIL);
        assertThat(failing.checks()).filteredOn(c -> c.status() == DemoReferenceEvaluator.Status.FAIL)
                .extracting(DemoReferenceEvaluator.Check::id).containsExactly("forbidden-phrase:discard");
    }

    @Test void onlineConnectivityCannotPassTheReferenceAsZero() {
        var scores = new HashMap<>(a1().scores());
        scores.put("CO", 0d);
        var report = DemoReferenceEvaluator.evaluate(reference, a0(),
                new DemoReferenceEvaluator.Observation(R1, scores, a1().relations()));

        assertThat(report.checks()).filteredOn(c -> "node:CO".equals(c.id()) && "R1".equals(c.iteration()))
                .singleElement().satisfies(c -> assertThat(c.status()).isEqualTo(DemoReferenceEvaluator.Status.FAIL));
    }

    @Test void anExplicitZeroCannotPassAsTheExpectedOpenRoot() {
        var scores = new HashMap<>(a0().scores());
        scores.put("CO", 0d);
        var report = DemoReferenceEvaluator.evaluate(reference,
                new DemoReferenceEvaluator.Observation(R0, scores, a0().relations()), a1());

        assertThat(report.checks()).filteredOn(c -> "root:CO".equals(c.id()) && "R0".equals(c.iteration()))
                .singleElement().satisfies(c -> assertThat(c.status()).isEqualTo(DemoReferenceEvaluator.Status.FAIL));
    }

    @Test void missingRootEvidenceDoesNotMasqueradeAsAnExplicitClarification() {
        var withoutCoverage = new DemoReferenceEvaluator.Observation(R0, a0().scores(), a0().relations());
        var report = DemoReferenceEvaluator.evaluate(reference, withoutCoverage, a1());

        assertThat(report.checks()).filteredOn(c -> "root:CO".equals(c.id()) && "R0".equals(c.iteration()))
                .singleElement().satisfies(c -> assertThat(c.status()).isEqualTo(DemoReferenceEvaluator.Status.NOT_EXECUTED));
    }

    @Test void surveillanceMutationIsDetected() {
        var scores = new HashMap<>(a1().scores());
        scores.put("CI-1121", 10d);
        var mutated = new DemoReferenceEvaluator.Observation(R1 + "\nBeim Stempeln wird zusätzlich der GPS-Standort des Mitarbeiters gespeichert.",
                scores, a1().relations());
        var report = DemoReferenceEvaluator.evaluate(reference, a0(), mutated);
        assertThat(report.overall()).isEqualTo(DemoReferenceEvaluator.Overall.FAIL);
        assertThat(report.checks()).filteredOn(c -> c.status() == DemoReferenceEvaluator.Status.FAIL)
                .extracting(DemoReferenceEvaluator.Check::id)
                .contains("forbidden-node:CI-1121", "forbidden-phrase:gps");
    }

    @Test void droppedAspectNodeAndRelationAreDetected() {
        var scores = new HashMap<>(a1().scores());
        scores.remove("BR-1101");
        var relations = new HashSet<>(a1().relations());
        relations.remove("UA-1605 USES CR-1065");
        var mutated = new DemoReferenceEvaluator.Observation(R1.replace("zentrale Unternehmensanmeldung", "eigene Anmeldung"),
                scores, relations);
        var failed = DemoReferenceEvaluator.evaluate(reference, a0(), mutated).checks().stream()
                .filter(c -> c.status() == DemoReferenceEvaluator.Status.FAIL).map(DemoReferenceEvaluator.Check::id).toList();
        assertThat(failed).contains("aspect:identity", "node:BR-1101", "relation:UA-1605 USES CR-1065");
    }

    @Test void reversedDependencyFailsOnlyTheAffectedIteration() {
        var relations = new HashSet<>(a1().relations());
        relations.remove("UA-1605 USES CR-1036");
        relations.add("CR-1036 USES UA-1605");
        var report = DemoReferenceEvaluator.evaluate(reference, a0(),
                new DemoReferenceEvaluator.Observation(R1, a1().scores(), relations));
        assertThat(report.checks()).filteredOn(check -> check.id().equals("relation:UA-1605 USES CR-1036"))
                .extracting(DemoReferenceEvaluator.Check::iteration, DemoReferenceEvaluator.Check::status)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("R0", DemoReferenceEvaluator.Status.PASS),
                        org.assertj.core.groups.Tuple.tuple("R1", DemoReferenceEvaluator.Status.FAIL));
    }

    @Test void contradictoryOfflineDecisionCannotPassWithUnchangedOnlineScores() {
        String offline = R1.replace("Eine Offline-Erfassung ist nicht erforderlich; Buchungen erfolgen nur online.",
                "Buchungen müssen auch ohne Verbindung am Terminal gespeichert werden.");
        var report = DemoReferenceEvaluator.evaluate(reference, a0(),
                new DemoReferenceEvaluator.Observation(offline, a1().scores(), a1().relations()));
        assertThat(report.checks()).filteredOn(check -> check.id().equals("aspect:offline"))
                .singleElement().satisfies(check -> {
                    assertThat(check.iteration()).isEqualTo("R1");
                    assertThat(check.status()).isEqualTo(DemoReferenceEvaluator.Status.FAIL);
                });
        assertThat(report.overall()).isEqualTo(DemoReferenceEvaluator.Overall.FAIL);
    }

    @Test void missingSecondAnalysisIsIncompleteNotNegative() {
        var report = DemoReferenceEvaluator.evaluate(reference, a0(), null);
        assertThat(report.overall()).isEqualTo(DemoReferenceEvaluator.Overall.INCOMPLETE);
        assertThat(report.checks()).filteredOn(c -> "R1".equals(c.iteration()))
                .allSatisfy(c -> assertThat(c.status()).isEqualTo(DemoReferenceEvaluator.Status.NOT_EXECUTED));
        assertThat(report.improvements()).isEmpty();
    }

    @Test void aKnownQualityFailureRemainsVisibleWhenTheSecondAnalysisIsMissing() {
        var unsafeR0 = new DemoReferenceEvaluator.Observation(
                R0 + "\nBeim Stempeln wird zusätzlich der GPS-Standort des Mitarbeiters gespeichert.",
                a0().scores(), a0().relations());
        var report = DemoReferenceEvaluator.evaluate(reference, unsafeR0, null);
        assertThat(report.checks()).filteredOn(c -> "forbidden-phrase:gps".equals(c.id())
                && "R0".equals(c.iteration()))
                .singleElement().extracting(DemoReferenceEvaluator.Check::status)
                .isEqualTo(DemoReferenceEvaluator.Status.FAIL);
        assertThat(report.checks()).filteredOn(c -> "R1".equals(c.iteration()))
                .isNotEmpty().allSatisfy(c -> assertThat(c.status()).isEqualTo(DemoReferenceEvaluator.Status.NOT_EXECUTED));
        assertThat(report.overall()).isEqualTo(DemoReferenceEvaluator.Overall.FAIL);
    }

    @Test void missingRelationEvidenceDoesNotOverrideAKnownForbiddenNode() {
        var scores = new HashMap<>(a1().scores());
        scores.put("CI-1121", 10d);
        var unsafeR1 = new DemoReferenceEvaluator.Observation(R1, scores, null);
        var report = DemoReferenceEvaluator.evaluate(reference, a0(), unsafeR1);
        assertThat(report.checks()).filteredOn(c -> "forbidden-node:CI-1121".equals(c.id())
                && "R1".equals(c.iteration()))
                .singleElement().extracting(DemoReferenceEvaluator.Check::status)
                .isEqualTo(DemoReferenceEvaluator.Status.FAIL);
        assertThat(report.checks()).filteredOn(c -> "EXPECTED_RELATION".equals(c.kind())
                && "R1".equals(c.iteration()))
                .isNotEmpty().allSatisfy(c -> assertThat(c.status()).isEqualTo(DemoReferenceEvaluator.Status.NOT_EXECUTED));
        assertThat(report.overall()).isEqualTo(DemoReferenceEvaluator.Overall.FAIL);
    }

    @Test void unfinishedRelationSearchRetainsKnownEdgesWithoutRejectingUnexaminedEdges() {
        var partial = new DemoReferenceEvaluator.Observation(R1, a1().scores(),
                Set.of("UA-1605 USES CR-1036"), Map.of(), true, false);
        var report = DemoReferenceEvaluator.evaluate(reference, a0(), partial);
        assertThat(report.checks()).filteredOn(c -> "EXPECTED_RELATION".equals(c.kind()) && "R1".equals(c.iteration()))
                .allSatisfy(c -> assertThat(c.status()).isEqualTo(c.id().equals("relation:UA-1605 USES CR-1036")
                        ? DemoReferenceEvaluator.Status.PASS : DemoReferenceEvaluator.Status.NOT_EXECUTED));
        assertThat(report.overall()).isEqualTo(DemoReferenceEvaluator.Overall.INCOMPLETE);
    }

    @Test void anUnfinishedFirstSearchCannotClaimAQualityImprovementFromMissingEdges() {
        var partial = new DemoReferenceEvaluator.Observation(R0, a0().scores(), Set.of(), a0().coverage(), true, false);
        var report = DemoReferenceEvaluator.evaluate(reference, partial, a1());
        assertThat(report.improvements()).noneMatch(value -> value.startsWith("relation:"));
    }

    @Test void partialScoringWithoutCoverageDoesNotTurnMissingNodesIntoZero() {
        var partial = new DemoReferenceEvaluator.Observation(R1, Map.of("UA-1605", 70d),
                a1().relations(), Map.of(), false, true);
        var report = DemoReferenceEvaluator.evaluate(reference, a0(), partial);
        assertThat(report.checks()).filteredOn(c -> "R1".equals(c.iteration()) && "node:UA-1605".equals(c.id()))
                .singleElement().satisfies(c -> assertThat(c.status()).isEqualTo(DemoReferenceEvaluator.Status.PASS));
        assertThat(report.checks()).filteredOn(c -> "R1".equals(c.iteration()) && "node:CO".equals(c.id()))
                .singleElement().satisfies(c -> assertThat(c.status()).isEqualTo(DemoReferenceEvaluator.Status.NOT_EXECUTED));
        assertThat(report.checks()).filteredOn(c -> "R1".equals(c.iteration()) && "forbidden-node:CI-1121".equals(c.id()))
                .singleElement().satisfies(c -> assertThat(c.status()).isEqualTo(DemoReferenceEvaluator.Status.NOT_EXECUTED));
        var knownFailure = new DemoReferenceEvaluator.Observation(R1, Map.of("UA-1605", 70d, "CI-1121", 10d),
                a1().relations(), Map.of(), false, true);
        assertThat(DemoReferenceEvaluator.evaluate(reference, a0(), knownFailure).checks())
                .filteredOn(c -> "R1".equals(c.iteration()) && "forbidden-node:CI-1121".equals(c.id()))
                .singleElement().satisfies(c -> assertThat(c.status()).isEqualTo(DemoReferenceEvaluator.Status.FAIL));
    }
}
