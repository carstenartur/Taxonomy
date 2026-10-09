package com.taxonomy.architecture.decision;

import com.taxonomy.reporting.api.decision.DecisionRationaleReport;
import com.taxonomy.reporting.api.decision.DecisionReportScope;
import com.taxonomy.reporting.api.decision.DecisionReportOptions;

import com.taxonomy.reporting.api.document.DecisionTreeOverview;
import com.taxonomy.reporting.api.document.DecisionTreeOverview.DecisionTreeRow;
import com.taxonomy.dto.AnalysisMode;
import com.taxonomy.dto.AnalysisScope;
import com.taxonomy.dto.AnalysisScoreDetail;
import com.taxonomy.dto.AnalysisScoreKind;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DecisionReportScopeJsonRoundTripTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final Set<String> SELECTED = Set.of("IP", "IP-1", "IP-0", "IP-unknown");

    @Test
    void completeReportRoundTripsForEveryProfileWithoutLosingEvidence() throws Exception {
        for (var profile : DecisionReportOptions.Profile.values()) {
            var original = report(scope(profile));
            var wire = mapper.valueToTree(original);
            var restored = mapper.treeToValue(wire, DecisionRationaleReport.class);
            assertEquals(wire, mapper.valueToTree(restored));
            assertEquals(SELECTED, restored.scope().selectedNodeCodes());
            assertEquals(original.scope().recordedReasons(), restored.scope().recordedReasons());
            assertEquals(original.scope().analysisScope(), restored.scope().analysisScope());
            assertEquals(original.status(), restored.status());
            assertFalse(restored.scope().analysisComplete());
            assertFalse(restored.scope().selectionComplete());
        }
    }

    @Test
    void restoresSelectionFromEvidenceIncludingRejectedAndUnevaluatedNodes() throws Exception {
        var original = scope(DecisionReportOptions.Profile.COMPACT);
        var wire = mapper.valueToTree(original);
        assertFalse(wire.has("selectedNodeCodes"));
        assertFalse(wire.has("scoreSemanticsFingerprint"));
        var restored = mapper.treeToValue(wire, DecisionReportScope.class);
        assertEquals(SELECTED, restored.selectedNodeCodes());
        assertEquals(Set.of("IP"), restored.reportRoots());
        assertEquals(original.decisionTree(), restored.decisionTree());
        assertNull(restored.scoreSemanticsFingerprint());
    }

    @Test
    void adaptationAfterReloadDoesNotAdmitScoresOutsideTheExportSelection() throws Exception {
        var restored = mapper.readValue(mapper.writeValueAsString(report(scope(
                DecisionReportOptions.Profile.COMPACT))), DecisionRationaleReport.class);
        var inside = new AnalysisScoreDetail("IP-1", AnalysisScoreKind.PRODUCT_SUITABILITY,
                60, 60, "IP", 100);
        var outside = new AnalysisScoreDetail("BP-1", AnalysisScoreKind.HIERARCHICAL_RELEVANCE,
                90, 90, "BP", 100);
        var adapted = new DecisionRationaleScoreSemanticsAdapter().adapt(restored,
                Map.of("IP-1", inside, "BP-1", outside), Locale.ENGLISH);
        assertEquals(Map.of("IP-1", inside), adapted.scoreDetails());
        assertEquals(SELECTED, adapted.scope().selectedNodeCodes());
    }

    @Test
    void legacyEmptyScopeStillRoundTripsWithoutInventingRecordedScope() throws Exception {
        var original = DecisionReportScope.legacy(List.of());
        var restored = mapper.readValue(mapper.writeValueAsString(original), DecisionReportScope.class);
        assertEquals(original, restored);
        assertNull(restored.analysisScope());
        assertNull(restored.analysisCoverage());
        assertEquals(Set.of(), restored.selectedNodeCodes());
    }

    @Test
    void keepsAnExplicitSourceIndexRatherThanReplacingItWithTheVisibleTree() {
        var selected = new HashSet<>(SELECTED);
        selected.add("IP-unassessed-descendant");
        var source = scope(DecisionReportOptions.Profile.FULL);
        var copy = new DecisionReportScope(source.analysisScope(), null, source.availableRoots(),
                source.reportRoots(), selected, source.decisionTree(), source.options(), false, false);
        selected.clear();
        assertTrue(copy.selectedNodeCodes().contains("IP-unassessed-descendant"));
        assertEquals(5, copy.selectedNodeCodes().size());
        assertThrows(UnsupportedOperationException.class, () -> copy.selectedNodeCodes().add("BP"));
    }

    @Test
    void reconstructedSelectionIsImmutable() throws Exception {
        var restored = mapper.readValue(mapper.writeValueAsString(scope(
                DecisionReportOptions.Profile.STANDARD)), DecisionReportScope.class);
        assertThrows(UnsupportedOperationException.class, () -> restored.selectedNodeCodes().add("BP"));
    }

    @Test
    void ignoresInjectedInternalSelectionAndFingerprintFields() throws Exception {
        ObjectNode wire = (ObjectNode) mapper.valueToTree(scope(DecisionReportOptions.Profile.COMPACT));
        wire.putArray("selectedNodeCodes").add("BP").add("BP-1");
        wire.put("scoreSemanticsFingerprint", "untrusted-internal-fingerprint");
        var restored = mapper.treeToValue(wire, DecisionReportScope.class);
        assertEquals(SELECTED, restored.selectedNodeCodes());
        assertNull(restored.scoreSemanticsFingerprint());
    }

    @Test
    void missingDecisionTreeRemainsInvalid() {
        var source = scope(DecisionReportOptions.Profile.FULL);
        assertThrows(NullPointerException.class, () -> new DecisionReportScope(
                source.analysisScope(), null, source.availableRoots(), source.reportRoots(),
                null, null, source.options(), false, false));
    }

    @Test
    void repeatedRoundTripsKeepTheSamePublicEvidence() throws Exception {
        var wire = mapper.valueToTree(report(scope(DecisionReportOptions.Profile.STANDARD)));
        for (int i = 0; i < 3; i++) {
            var restored = mapper.treeToValue(wire, DecisionRationaleReport.class);
            assertEquals(SELECTED, restored.scope().selectedNodeCodes());
            var next = mapper.valueToTree(restored);
            assertEquals(wire, next);
            wire = next;
        }
    }

    private static DecisionRationaleReport report(DecisionReportScope scope) {
        return new DecisionRationaleReport("Selected report", "en", "Recorded requirement",
                DecisionRationaleReport.ReportStatus.DRAFT_INCOMPLETE, null, null,
                List.of(), List.of(), List.of("Analysis is incomplete"), List.of(), List.of(),
                null, Map.of(), null, scope);
    }

    private static DecisionReportScope scope(DecisionReportOptions.Profile profile) {
        var tree = new DecisionTreeOverview(List.of(
                new DecisionTreeRow(0, "IP", "Products", 100,
                        DecisionRationaleReport.Disposition.CONTINUED, null, null),
                new DecisionTreeRow(1, "IP-1", "Selected product", 60,
                        DecisionRationaleReport.Disposition.LEAF_CANDIDATE, null, null),
                new DecisionTreeRow(1, "IP-0", "Rejected product", 0,
                        DecisionRationaleReport.Disposition.REJECTED, null, null),
                new DecisionTreeRow(1, "IP-unknown", "Open evaluation", null,
                        DecisionRationaleReport.Disposition.NOT_EVALUATED, null, null)),
                List.of("Recorded warning"));
        return new DecisionReportScope(new AnalysisScope(Set.of("IP", "BP"),
                AnalysisMode.TAXONOMIES_ONLY), null,
                List.of(new DecisionReportScope.Root("IP", "Products", true),
                        new DecisionReportScope.Root("BP", "Processes", true)),
                Set.of("IP"), SELECTED, tree,
                new DecisionReportOptions(profile, null, null, null, null), false, false,
                "internal-source-fingerprint", Map.of("IP-0", "Recorded rejection reason"));
    }
}
