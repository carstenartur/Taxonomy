package com.taxonomy.portfolio.service;

import com.taxonomy.dto.AnalysisCoverage;
import com.taxonomy.dto.ProductCoverageGap;
import com.taxonomy.portfolio.repository.ProductGapSnapshotRow;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectProductGapsTest {
    private static final ProductCoverageGap GAP = new ProductCoverageGap("F", "Family", 80, List.of("P"), "Unmet",
            50, Map.of("P", 28.5));
    private static final Map<String, Double> SCORES = Map.of("F", 80d, "P", 0d);

    @Test void sameCatalogueCombinesObservationsAcrossVersionsWhileUnpinnedSnapshotsStayIndependent() {
        var groups = new ProjectProductGaps();
        var evidence = new ProjectProductGaps.Evidence(List.of(GAP), SCORES, null, Map.of("P", "Missing function"), null);
        var first = row("s1", 1, "catalogue-a");
        groups.include(first, evidence);
        groups.include(first, evidence);
        groups.include(row("s2", 2, "catalogue-a"), evidence);
        groups.include(new ProductGapSnapshotRow("s6", 4L, "REQ-4", 6L, 1, "catalogue-a", null), evidence);
        groups.include(row("s3", 3, "catalogue-b"), evidence);
        groups.include(row("s4", 4, null), evidence);
        groups.include(row("s5", 5, null), evidence);
        assertThat(groups.families()).hasSize(4);
        var family = groups.families().getFirst();
        assertThat(family.observations()).extracting(ProjectProductGaps.Observation::snapshotId).containsExactly("s1", "s2", "s6");
        assertThat(family.observations()).extracting(ProjectProductGaps.Observation::requirementVersion).containsExactly(1, 2, 1);
        assertThat(family.observations()).extracting(ProjectProductGaps.Observation::requirementKey).containsExactly("REQ-3", "REQ-3", "REQ-4");
        assertThat(family.observations().getFirst().candidates()).containsExactly(
                new ProjectProductGaps.Candidate("P", 28.5, 0, "Missing function"));
    }

    @Test void missingScoresAndUnassessedCandidatesCannotConfirmGaps() {
        var groups = new ProjectProductGaps();
        var source = row("s1", 1, "catalogue-a");
        groups.include(source, new ProjectProductGaps.Evidence(List.of(GAP), Map.of("F", 80d), SCORES, Map.of(), null));
        var assessment = new AnalysisCoverage.NodeAssessment(AnalysisCoverage.State.UNKNOWN, null, null,
                AnalysisCoverage.Descendants.UNASSESSED, null);
        var coverage = new AnalysisCoverage(Map.of("P", assessment), 0, 1, 0);
        groups.include(source, new ProjectProductGaps.Evidence(List.of(GAP), SCORES, null, Map.of(), coverage));
        assertThat(groups.families()).isEmpty();
    }

    @Test void historicalGapKeepsItsUnknownThresholdAndOriginalStoredScores() {
        var groups = new ProjectProductGaps();
        var historical = JsonMapper.builder().build().readValue("""
                {"productCoverageGaps":[{"productFamilyCode":"F","productFamilyName":"Family","familyScore":80,
                "candidateCodes":["P"],"reason":"Historical finding"}],"scores":{"F":80,"P":0},"other":"legacy"}
                """, ProjectProductGaps.Evidence.class);
        groups.include(row("old", 1, "catalogue-a"), historical);
        var observation = groups.families().getFirst().observations().getFirst();
        assertThat(observation.minimumSuitabilityScore()).isNull();
        assertThat(observation.candidates()).containsExactly(new ProjectProductGaps.Candidate("P", null, 0d, null));
    }

    private static ProductGapSnapshotRow row(String id, int version, String catalogue) {
        return new ProductGapSnapshotRow(id, 3L, "REQ-3", (long) version, version, catalogue, null);
    }
}
