package com.taxonomy.reporting.api;

import com.taxonomy.reporting.api.decision.DecisionRationaleReport;
import com.taxonomy.reporting.api.decision.DecisionRationaleReport.*;
import com.taxonomy.reporting.api.decision.DecisionReportScope;
import com.taxonomy.reporting.api.document.ArchitectureReportDocument;
import com.taxonomy.dto.AnalysisCoverage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Runs with only the report SDK and domain contracts on its production classpath. */
class ReportModelContractTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void completeSnapshotMetadataSurvivesIndependentSerialization() throws Exception {
        var original = report();
        var restored = json.readValue(json.writeValueAsBytes(original), DecisionRationaleReport.class);
        assertEquals(original, restored);
        assertEquals("analysis-sha", restored.metadata().analysisSnapshotFingerprintSha256());
        assertEquals("snapshot", restored.metadata().analysisSnapshotId());
        assertEquals("workspace", restored.metadata().workspaceId());
        assertEquals("based-on-commit", restored.metadata().basedOnCommit());
        assertEquals(4, restored.metadata().requirementVersionNumber());
        assertTrue(restored.metadata().hierarchyFromImmutableSnapshot());
        assertEquals("prompt-sha", restored.metadata().promptFingerprintSha256());
    }

    @Test
    void missingScoresRemainDistinctFromExplicitZeroInTheWireContract() throws Exception {
        var missing = new PathStep(1, "evidence-node", "Evidence", null, null, null, ReasonSource.MISSING);
        var zero = new PathStep(1, "evidence-node", "Evidence", 0, 0.0, "Rejected", ReasonSource.AI_SCORING);
        assertNotEquals(missing, zero);
        assertNull(json.readValue(json.writeValueAsBytes(missing), PathStep.class).absoluteScore());
        assertEquals(0, json.readValue(json.writeValueAsBytes(zero), PathStep.class).absoluteScore());
    }

    @Test
    void partialCoveragePreservesSourceIdentityAndUnassessedEvidence() {
        var nodes = new LinkedHashMap<String, AnalysisCoverage.NodeAssessment>();
        nodes.put("evidence-node", new AnalysisCoverage.NodeAssessment(AnalysisCoverage.State.UNKNOWN,
                null, null, AnalysisCoverage.Descendants.UNASSESSED, "LEFT_OPEN:provider-unavailable"));
        var coverage = new AnalysisCoverage(nodes, 0, 1, 1);
        var partial = report().withAnalysisCoverage(coverage);
        nodes.clear();
        assertEquals(ReportStatus.DRAFT_INCOMPLETE, partial.status());
        assertEquals("analysis-sha", partial.metadata().analysisSnapshotFingerprintSha256());
        assertEquals("Provide a secure architecture decision.", partial.requirement());
        assertNull(partial.scope().analysisCoverage().nodes().get("evidence-node").score());
        assertEquals(1, partial.scope().analysisCoverage().unknownNodes());
    }

    @Test
    void callerCollectionsCannotChangeAnAlreadyCapturedReport() {
        var notes = new ArrayList<>(List.of("captured"));
        var base = report();
        var immutable = new DecisionRationaleReport(base.title(), base.languageTag(), base.requirement(),
                base.status(), base.metadata(), base.executiveSummary(), base.chapters(), base.leadingLeaves(),
                notes, base.productCoverageGaps(), base.discrepancies(), base.viewContext());
        notes.clear();
        assertEquals(List.of("captured"), immutable.warnings());
        assertThrows(UnsupportedOperationException.class, () -> immutable.warnings().clear());
    }

    @Test
    void publicRecordComponentsContainNoLiveImplementationTypes() {
        var pending = new ArrayDeque<Class<?>>(List.of(DecisionRationaleReport.class,
                DecisionReportScope.class, ArchitectureReportDocument.class));
        var visited = new HashSet<Class<?>>();
        while (!pending.isEmpty()) {
            var type = pending.remove();
            if (!visited.add(type) || !type.isRecord()) continue;
            for (var component : type.getRecordComponents()) {
                String signature = component.getGenericType().getTypeName();
                for (String forbidden : List.of("com.taxonomy.architecture", "com.taxonomy.catalog",
                        "com.taxonomy.templates", "org.springframework", "jakarta.persistence")) {
                    assertFalse(signature.contains(forbidden), signature);
                }
                pending.add(component.getType());
            }
        }
    }

    static DecisionRationaleReport report() {
        Instant generatedAt = Instant.parse("2026-08-22T14:30:00Z");
        DecisionRationaleReport.ReportMetadata metadata =
                new DecisionRationaleReport.ReportMetadata(
                        generatedAt,
                        "template-user",
                        "1.4.0",
                        "build-commit",
                        "catalogue.xlsx",
                        "2026-08",
                        "source-sha",
                        "data-sha",
                        "analysis-sha",
                        "Bundled test catalogue",
                        10,
                        2,
                        "repository",
                        "workspace",
                        "main",
                        "based-on-commit",
                        generatedAt,
                        false,
                        false,
                        "MOCK",
                        "SUCCESS",
                        "mock-model",
                        "snapshot",
                        1L,
                        2L,
                        3L,
                        4,
                        generatedAt,
                        "analysis-author",
                        "recorded-taxonomy-sha",
                        "prompt-sha",
                        true,
                        "Europe/Berlin",
                        4,
                        10,
                        3,
                        100.0);
        return new DecisionRationaleReport(
                "Template-backed decision report",
                "en",
                "Provide a secure architecture decision.",
                DecisionRationaleReport.ReportStatus.FINAL,
                metadata,
                new DecisionRationaleReport.ExecutiveSummary(
                        null,
                        List.of(),
                        "No leading leaf is needed for this template contract test.",
                        "The dynamic report body is rendered after the editable cover."),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null);
    }

}
