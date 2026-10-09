package com.taxonomy.export.controller;

import com.taxonomy.analysis.service.SavedAnalysisService;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.*;
import com.taxonomy.export.service.ExportFacade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Set;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exchange scope is optional provenance; selected evidence must use catalogue membership. */
class SavedScopeExchangeTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Integer> catalogueLookups = new HashMap<>();
    private final TaxonomyService catalogue = new TaxonomyService(null, null, null) {
        private final Map<String, String> roots = Map.of(
                "BP", "BP", "IP", "IP", "CP", "CP",
                "IP-looking-selected", "BP", "BP-looking-other", "IP");
        @Override public Set<String> getRootCodes() { return Set.of("BP", "IP", "CP"); }
        @Override public TaxonomyNode getNodeByCode(String code) {
            catalogueLookups.merge(code, 1, Integer::sum);
            if (!roots.containsKey(code)) return null;
            var node = new TaxonomyNode();
            node.setCode(code); node.setTaxonomyRoot(roots.get(code));
            return node;
        }
    };
    private final SavedAnalysisService service = new SavedAnalysisService(mapper, catalogue);

    private ExportApiController controller() throws Exception {
        var facade = new ExportFacade(null, null, null, null, null, null, null, null, service);
        var controller = new ExportApiController(facade, null);
        var field = ExportApiController.class.getDeclaredField("recoveryObjectMapper");
        field.setAccessible(true); field.set(controller, mapper);
        return controller;
    }

    private SavedAnalysis evidence(int version) {
        var saved = service.buildExport("Manual or automatic requirement", Map.of("BP", 70), Map.of(), "MOCK");
        saved.setVersion(version);
        if (version == 3) {
            saved.setRawScores(Map.of("BP", 70)); saved.setAnalysisStatus("SUCCESS");
            saved.setAnalysisCoverage(new AnalysisCoverage(Map.of("BP", assessed(70, 70)), 1, 0, 0));
        }
        return saved;
    }

    private AnalysisCoverage.NodeAssessment assessed(int raw, Integer effective) {
        return new AnalysisCoverage.NodeAssessment(AnalysisCoverage.State.RELEVANT, raw, effective,
                AnalysisCoverage.Descendants.COMPLETE, null);
    }

    private int exchange(String direction, SavedAnalysis saved) throws Exception {
        var controller = controller();
        return (direction.equals("import") ? controller.importScores(mapper.writeValueAsString(saved))
                : controller.exportScores(mapper.convertValue(saved, new TypeReference<Map<String, Object>>() {})))
                .getStatusCode().value();
    }

    @ParameterizedTest
    @CsvSource({"1, false", "1, true", "2, false", "2, true", "3, false", "3, true"})
    void absentOrExplicitNullScopeSurvivesImportAndReExport(int version, boolean explicitNull) throws Exception {
        var tree = mapper.convertValue(evidence(version), new TypeReference<Map<String, Object>>() {});
        if (explicitNull) tree.put("analysisScope", null); else tree.remove("analysisScope");
        var imported = controller().importScores(mapper.writeValueAsString(tree));
        assertThat(imported.getStatusCode().value()).isEqualTo(200);
        assertThat(imported.getBody().get("analysisScope")).isNull();
        var exported = controller().exportScores(imported.getBody());
        assertThat(exported.getStatusCode().value()).isEqualTo(200);
        assertThat(mapper.valueToTree(exported.getBody()).path("analysisScope").isNull()).isTrue();
    }

    @Test
    void manualExportAndImportDoesNotClaimAnAutomaticFullRun() throws Exception {
        var exported = controller().exportScores(Map.of("requirement", "Manual requirement", "scores", Map.of("BP", 70), "provider", "MANUAL"));
        assertThat(exported.getStatusCode().value()).isEqualTo(200);
        var imported = controller().importScores(mapper.writeValueAsString(exported.getBody()));
        assertThat(imported.getStatusCode().value()).isEqualTo(200);
        assertThat(imported.getBody().get("analysisScope")).isNull();
    }

    @ParameterizedTest
    @CsvSource({"1, PARTIAL", "2, PARTIAL", "1, SUCCESS", "2, SUCCESS"})
    void legacyCoverageFreeStatusSurvivesHttpExportAndImport(int version, String analysisStatus) throws Exception {
        var saved = evidence(version);
        saved.setScores(Map.of("BP", 70, "IP", 40));
        saved.setAnalysisStatus(analysisStatus);
        var mvc = MockMvcBuilders.standaloneSetup(controller()).build();
        var exported = mvc.perform(post("/api/scores/export").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(saved)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var output = mapper.readTree(exported);
        assertThat(output.path("version").asInt()).isEqualTo(2);
        assertThat(output.path("analysisCoverage").isNull()).isTrue();
        assertThat(output.path("analysisStatus").asString()).isEqualTo(analysisStatus);
        var imported = mvc.perform(post("/api/scores/import").contentType(MediaType.APPLICATION_JSON).content(exported))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(imported).path("analysisStatus").asString()).isEqualTo(analysisStatus);
        assertThat(mapper.readTree(imported).path("scores").size()).isEqualTo(2);
    }

    @ParameterizedTest
    @CsvSource({"export, 1", "import, 1", "export, 2", "import, 2", "export, 3", "import, 3"})
    void rejectsDeclaredUnknownRootOnEveryExchangeVersion(String direction, int version) throws Exception {
        var saved = evidence(version);
        saved.setAnalysisScope(new AnalysisScope(Set.of("NOT_A_ROOT"), AnalysisMode.TAXONOMIES_ONLY));
        assertThat(exchange(direction, saved)).isEqualTo(400);
    }

    @ParameterizedTest
    @CsvSource({"export, 1", "import, 1", "export, 2", "import, 2", "export, 3", "import, 3"})
    void rejectsEffectiveScoresFromAnotherCatalogueRoot(String direction, int version) throws Exception {
        var saved = evidence(version);
        saved.setAnalysisScope(new AnalysisScope(Set.of("IP"), AnalysisMode.FULL));
        assertThat(exchange(direction, saved)).isEqualTo(400);
    }

    @ParameterizedTest
    @CsvSource({"export, raw", "import, raw", "export, unknown", "import, unknown"})
    void rejectsOtherRootRawOrUnassessedCoverageEvenWhenEffectiveScoresAreSelected(String direction, String kind) throws Exception {
        var saved = evidence(3);
        saved.setAnalysisScope(new AnalysisScope(Set.of("BP"), AnalysisMode.TAXONOMIES_ONLY));
        boolean raw = kind.equals("raw");
        if (raw) saved.setRawScores(Map.of("BP", 70, "BP-looking-other", 35));
        saved.setAnalysisCoverage(new AnalysisCoverage(Map.of("BP", assessed(70, 70),
                "BP-looking-other", raw ? assessed(35, null) : new AnalysisCoverage.NodeAssessment(
                        AnalysisCoverage.State.UNKNOWN, null, null, AnalysisCoverage.Descendants.UNASSESSED, "LEFT_OPEN:q")),
                raw ? 2 : 1, raw ? 0 : 1, raw ? 0 : 1));
        assertThat(exchange(direction, saved)).isEqualTo(400);
    }

    @ParameterizedTest
    @CsvSource({"FULL", "TAXONOMIES_ONLY"})
    void selectedPartialEvidenceRoundTripUsesMembershipInsteadOfCodePrefixes(AnalysisMode mode) throws Exception {
        var saved = evidence(3);
        saved.setScores(Map.of("IP-looking-selected", 70)); saved.setRawScores(saved.getScores());
        saved.setAnalysisScope(new AnalysisScope(Set.of("BP", "CP"), mode));
        saved.setAnalysisCoverage(new AnalysisCoverage(Map.of("IP-looking-selected", assessed(70, 70),
                "BP", new AnalysisCoverage.NodeAssessment(AnalysisCoverage.State.UNKNOWN, null, null,
                        AnalysisCoverage.Descendants.PARTIAL, "LEFT_OPEN:q")), 1, 1, 1));
        var exported = controller().exportScores(mapper.convertValue(saved, new TypeReference<Map<String, Object>>() {}));
        assertThat(exported.getStatusCode().value()).isEqualTo(200);
        var imported = controller().importScores(mapper.writeValueAsString(exported.getBody()));
        assertThat(imported.getStatusCode().value()).isEqualTo(200);
        var body = mapper.valueToTree(imported.getBody());
        assertThat(mapper.treeToValue(body.path("analysisScope"), AnalysisScope.class)).isEqualTo(saved.getAnalysisScope());
        assertThat(body.path("analysisStatus").asString()).isEqualTo("PARTIAL");
        assertThat(body.path("scores").size()).isEqualTo(1);
        assertThat(body.path("analysisCoverage").path("nodes").size()).isEqualTo(2);
        assertThat(body.path("analysisCoverage").path("nodes").has("CP")).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"1", "2"})
    void unscopedLegacyUnknownScoresStillReturnWarnings(int version) throws Exception {
        var saved = evidence(version); saved.setScores(Map.of("UNKNOWN_LEGACY", 0));
        var imported = controller().importScores(mapper.writeValueAsString(saved));
        assertThat(imported.getStatusCode().value()).isEqualTo(200);
        assertThat(mapper.valueToTree(imported.getBody()).path("warnings").get(0).asString())
                .isEqualTo("Unknown node code: UNKNOWN_LEGACY");
    }

    @ParameterizedTest
    @CsvSource({"1", "2"})
    void selectedLegacyEvidenceRetainsItsScopeThroughExchange(int version) throws Exception {
        var saved = evidence(version);
        saved.setAnalysisScope(new AnalysisScope(Set.of("BP"), AnalysisMode.TAXONOMIES_ONLY));
        var exported = controller().exportScores(mapper.convertValue(saved, new TypeReference<Map<String, Object>>() {}));
        assertThat(exported.getStatusCode().value()).isEqualTo(200);
        var imported = controller().importScores(mapper.writeValueAsString(exported.getBody()));
        assertThat(imported.getStatusCode().value()).isEqualTo(200);
        assertThat(imported.getBody().get("analysisScope")).isEqualTo(saved.getAnalysisScope());
    }

    @ParameterizedTest
    @CsvSource({"2", "3"})
    void allRootTaxonomiesOnlyEvidenceDoesNotRequireCompleteCoverage(int version) throws Exception {
        var saved = evidence(version);
        saved.setAnalysisScope(new AnalysisScope(Set.of(), AnalysisMode.TAXONOMIES_ONLY));
        var exported = controller().exportScores(mapper.convertValue(saved, new TypeReference<Map<String, Object>>() {}));
        assertThat(exported.getStatusCode().value()).isEqualTo(200);
        var imported = controller().importScores(mapper.writeValueAsString(exported.getBody()));
        assertThat(imported.getStatusCode().value()).isEqualTo(200);
        assertThat(imported.getBody().get("analysisScope")).isEqualTo(saved.getAnalysisScope());
    }

    @ParameterizedTest
    @CsvSource({"raw", "coverage"})
    void importChecksSelectedOptionalEvidenceEvenWithoutVersionThree(String field) throws Exception {
        var saved = evidence(2);
        saved.setAnalysisScope(new AnalysisScope(Set.of("BP"), AnalysisMode.FULL));
        if (field.equals("raw")) saved.setRawScores(Map.of("BP-looking-other", 35));
        else saved.setAnalysisCoverage(new AnalysisCoverage(Map.of("BP-looking-other",
                new AnalysisCoverage.NodeAssessment(AnalysisCoverage.State.UNKNOWN, null, null,
                        AnalysisCoverage.Descendants.UNASSESSED, "NOT_EVALUATED")), 0, 1, 0));
        assertThat(exchange("import", saved)).isEqualTo(400);
    }

    @Test
    void selectedVersionThreeExchangeResolvesEachCatalogueIdentityOncePerOperation() throws Exception {
        var saved = evidence(3);
        saved.setAnalysisScope(new AnalysisScope(Set.of("BP"), AnalysisMode.FULL));
        saved.setAnalysisCoverage(new AnalysisCoverage(Map.of("BP", assessed(70, 70),
                "IP-looking-selected", new AnalysisCoverage.NodeAssessment(AnalysisCoverage.State.UNKNOWN, null, null,
                        AnalysisCoverage.Descendants.UNASSESSED, "NOT_EVALUATED")), 1, 1, 0));
        var exported = controller().exportScores(mapper.convertValue(saved, new TypeReference<Map<String, Object>>() {}));
        assertThat(exported.getStatusCode().value()).isEqualTo(200);
        assertThat(catalogueLookups).containsExactlyInAnyOrderEntriesOf(Map.of("BP", 1, "IP-looking-selected", 1));
        catalogueLookups.clear();
        var imported = controller().importScores(mapper.writeValueAsString(exported.getBody()));
        assertThat(imported.getStatusCode().value()).isEqualTo(200);
        assertThat(catalogueLookups).containsExactlyInAnyOrderEntriesOf(Map.of("BP", 1, "IP-looking-selected", 1));
        assertThat(mapper.valueToTree(imported.getBody()).path("warnings").isEmpty()).isTrue();
    }
}
