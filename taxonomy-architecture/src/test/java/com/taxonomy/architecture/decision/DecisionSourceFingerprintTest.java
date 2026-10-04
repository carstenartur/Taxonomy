package com.taxonomy.architecture.decision;

import com.taxonomy.architecture.decision.DecisionRationaleReportService.AnalysisSnapshotProvenance;
import com.taxonomy.architecture.decision.DecisionRationaleReportService.DecisionAnalysisInput;
import com.taxonomy.dto.AnalysisMode;
import com.taxonomy.dto.AnalysisScope;
import com.taxonomy.dto.AnalysisScoreDetail;
import com.taxonomy.dto.AnalysisScoreKind;
import com.taxonomy.dto.TaxonomyDataFingerprint;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecisionSourceFingerprintTest {
    private final List<TaxonomyNodeDto> tree = List.of(node("CP", "Capabilities", 0,
            node("CP-product", "Product", 1), node("CP-zero", "Rejected", 1)));
    private final Map<String, Integer> scores = Map.of("CP", 80, "CP-product", 64, "CP-zero", 0);
    private final Map<String, AnalysisScoreDetail> details = Map.of("CP-product",
            new AnalysisScoreDetail("CP-product", AnalysisScoreKind.PRODUCT_SUITABILITY, 80, 64, "CP", 80));
    private final DecisionRationaleScoreSemanticsAdapter adapter = new DecisionRationaleScoreSemanticsAdapter();
    private final DecisionRationaleReportService service = new DecisionRationaleReportService(null,
            new TaxonomyCatalogueMetadataService(null, null, "catalogue.xlsx") {
                @Override
                public CatalogueMetadata getMetadata() {
                    return new CatalogueMetadata("catalogue.xlsx", "v1", "hash", "source", null, null, null, null);
                }
            }, new DecisionReportBuildMetadataService("1", "commit"), "UTC");
    private final WorkspaceContext context = new WorkspaceContext("author", "workspace", "main", "repo");

    @Test
    void distinguishesRecordedReasonsFromIdenticalGeneratedDescriptions() {
        var displayReasons = adapter.enrichReasons(Map.of(), details, Locale.ENGLISH);
        var generated = report(displayReasons, Map.of());
        var recorded = report(displayReasons, displayReasons);

        assertTrue(generated.scope().recordedReasons().isEmpty());
        assertEquals(0, generated.metadata().suppliedReasonCount());
        assertEquals(displayReasons, recorded.scope().recordedReasons());
        assertEquals(1, recorded.metadata().suppliedReasonCount());
        assertTrue(fingerprint(generated).matches("[a-f0-9]{64}"));
        assertNotEquals(fingerprint(generated), fingerprint(recorded),
                "Identical display text must not conceal different recorded evidence");
    }

    @Test
    void ignoresTheLanguageOfGeneratedDescriptions() {
        var english = adapter.enrichReasons(Map.of(), details, Locale.ENGLISH);
        var german = adapter.enrichReasons(Map.of(), details, Locale.GERMAN);
        assertNotEquals(english, german, "The fixture must exercise different generated descriptions");

        var englishReport = report(english, Map.of());
        var germanReport = report(german, Map.of());
        assertTrue(fingerprint(englishReport).matches("[a-f0-9]{64}"));
        assertEquals(fingerprint(englishReport), fingerprint(germanReport),
                "Localizing a generated description must not change source identity");
    }

    private DecisionRationaleReport report(Map<String, String> displayReasons, Map<String, String> recordedReasons) {
        var provenance = new AnalysisSnapshotProvenance("snapshot", 1L, 2L, 3L, 1, Instant.EPOCH,
                "author", "mock-model", TaxonomyDataFingerprint.sha256(tree), "prompt");
        var input = new DecisionAnalysisInput("Frozen requirement", scores, displayReasons, "MOCK", "SUCCESS",
                List.of(), List.of(), tree, provenance, details).withRecordedReasons(recordedReasons)
                .withScope(new AnalysisScope(Set.of("CP"), AnalysisMode.TAXONOMIES_ONLY), null);
        return adapter.adapt(service.generate(input, context, null, Locale.ENGLISH), details, Locale.ENGLISH);
    }

    private static String fingerprint(DecisionRationaleReport report) {
        return report.metadata().analysisSnapshotFingerprintSha256();
    }

    private static TaxonomyNodeDto node(String code, String title, int level, TaxonomyNodeDto... children) {
        var node = new TaxonomyNodeDto();
        node.setCode(code);
        node.setNameEn(title);
        node.setLevel(level);
        node.setChildren(List.of(children));
        return node;
    }
}
