package com.taxonomy.analysis.demo.evaluation;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
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
        return new DemoReferenceEvaluator.Observation(R0, Map.of("UA-1605", 60, "CR-1036", 20), Set.of("UA-1605 USES CR-1036"));
    }

    private static DemoReferenceEvaluator.Observation a1() {
        return new DemoReferenceEvaluator.Observation(R1,
                Map.of("UA-1605", 70, "CR-1036", 25, "CR-1006", 12, "CR-1065", 15, "BR-1101", 25),
                Set.of("UA-1605 USES CR-1036", "UA-1605 USES CR-1006", "UA-1605 USES CR-1065"));
    }

    @Test void consistentDemoPassesAndReportsImprovements() {
        var report = DemoReferenceEvaluator.evaluate(reference, a0(), a1());
        assertThat(report.overall()).isEqualTo(DemoReferenceEvaluator.Overall.PASS);
        assertThat(report.improvements()).contains("aspect:channels", "aspect:privacy", "node:BR-1101",
                "relation:UA-1605 USES CR-1006");
    }

    @Test void surveillanceMutationIsDetected() {
        var scores = new HashMap<>(a1().scores());
        scores.put("CI-1121", 10);
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
        scores.put("CI-1121", 10);
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
}
