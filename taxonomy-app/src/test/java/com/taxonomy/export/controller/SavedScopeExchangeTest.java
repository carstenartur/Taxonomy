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

import java.util.List;
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

    @org.junit.jupiter.api.Test
    void followUpIdentitySurvivesScoreExportAndImportWithoutGrantingAuthority() throws Exception {
        String operation = "10000000-0000-4000-8000-000000000001";
        var body = Map.<String, Object>of("requirement", "requirement", "scores", Map.of("BP", 0d),
                "provider", "DEMO.TIME-TRACKING-V1.R0",
                "analysisOperationId", operation, "analysisProviderId", "DEMO.TIME-TRACKING-V1.R0");
        var exported = controller().exportScores(body);
        assertThat(exported.getStatusCode().value()).isEqualTo(200);
        var imported = controller().importScores(mapper.writeValueAsString(exported.getBody()));
        assertThat(imported.getStatusCode().value()).isEqualTo(200);
        assertThat(imported.getBody()).containsEntry("analysisOperationId", operation)
                .containsEntry("analysisProviderId", "DEMO.TIME-TRACKING-V1.R0");
    }

    private SavedAnalysis evidence(int version) {
        var saved = service.buildExport("Manual or automatic requirement", Map.of("BP", 70d), Map.of(), "MOCK");
        saved.setVersion(version);
        if (version >= 3) {
            saved.setRawScores(Map.of("BP", 70d)); saved.setAnalysisStatus("SUCCESS");
            saved.setAnalysisCoverage(new AnalysisCoverage(Map.of("BP", assessed(70, 70)), 1, 0, 0));
        }
        return saved;
    }

    @ParameterizedTest
    @CsvSource({"short", "standard", "detailed"})
    void recordedReasonLengthSurvivesExportImportAndReExport(String length) throws Exception {
        var request = new HashMap<String, Object>(Map.of("requirement", "requirement",
                "scores", Map.of("BP", 70d), "reasons", Map.of("BP", "Recorded reason"),
                "provider", "MOCK", "analysisReasonLength", length));
        var exported = controller().exportScores(request);
        assertThat(exported.getStatusCode().value()).isEqualTo(200);
        var imported = controller().importScores(mapper.writeValueAsString(exported.getBody()));
        assertThat(imported.getStatusCode().value()).isEqualTo(200);
        var reExported = controller().exportScores(imported.getBody());
        assertThat(reExported.getStatusCode().value()).isEqualTo(200);
        for (var result : List.of(exported.getBody(), imported.getBody(), reExported.getBody())) {
            var json = mapper.valueToTree(result);
            assertThat(json.path("analysisReasonLength").asString()).isEqualTo(length);
            assertThat(json.path("reasons").path("BP").asString()).isEqualTo("Recorded reason");
        }
    }

    @ParameterizedTest
    @CsvSource({"de", "en"})
    void analysisLanguageSurvivesExportImportWithoutChangingReasons(String language) throws Exception {
        var request = new HashMap<String, Object>(Map.of("requirement", "requirement",
                "scores", Map.of("BP", 70d), "reasons", Map.of("BP", "Original rationale ORIGINAL-END-7319"),
                "provider", "MOCK", "analysisLanguage", language));
        var exported = controller().exportScores(request);
        assertThat(exported.getStatusCode().value()).isEqualTo(200);
        var imported = controller().importScores(mapper.writeValueAsString(exported.getBody()));
        assertThat(imported.getStatusCode().value()).isEqualTo(200);
        var reExported = controller().exportScores(imported.getBody());
        assertThat(reExported.getStatusCode().value()).isEqualTo(200);
        for (var result : List.of(exported.getBody(), imported.getBody(), reExported.getBody())) {
            var json = mapper.valueToTree(result);
            assertThat(json.path("analysisLanguage").asString()).isEqualTo(language);
            assertThat(json.path("reasons").path("BP").asString()).isEqualTo("Original rationale ORIGINAL-END-7319");
        }
    }

    @Test
    void missingLanguageRemainsUnknownAndUnsupportedLanguageIsRejected() throws Exception {
        var request = new HashMap<String, Object>(Map.of("requirement", "requirement", "scores", Map.of("BP", 70d), "provider", "MOCK"));
        var imported = controller().importScores(mapper.writeValueAsString(request));
        assertThat(imported.getBody()).containsEntry("analysisLanguage", null);
        var exported = controller().exportScores(imported.getBody());
        assertThat(mapper.valueToTree(exported.getBody()).path("analysisLanguage").isNull()).isTrue();
        request.put("analysisLanguage", "fr");
        assertThat(controller().exportScores(request).getStatusCode().value()).isEqualTo(400);
        assertThat(controller().importScores(mapper.writeValueAsString(request)).getStatusCode().value()).isEqualTo(400);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("invalidRecordedLanguages")
    void invalidLanguageIsRejectedInsteadOfLosingRecordedMetadata(String direction, Object value) throws Exception {
        var request = new HashMap<String, Object>(Map.of("requirement", "requirement",
                "scores", Map.of("BP", 70d), "provider", "MOCK", "analysisLanguage", value));
        var response = direction.equals("export") ? controller().exportScores(request)
                : controller().importScores(mapper.writeValueAsString(request));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> invalidRecordedLanguages() {
        return java.util.stream.Stream.of("export", "import").flatMap(direction ->
                java.util.stream.Stream.<Object>of("fr", "EN", "de-DE", "", " ", "\t",
                                42, true, Map.of("value", "en"), List.of("en"))
                        .map(value -> org.junit.jupiter.params.provider.Arguments.of(direction, value)));
    }

    @ParameterizedTest
    @CsvSource({"1, false", "2, true", "3, false", "4, true"})
    void unrecordedReasonLengthStaysUnknownInLegacyAndManualEvidence(int version, boolean explicitNull) throws Exception {
        var request = mapper.convertValue(evidence(version), new TypeReference<Map<String, Object>>() {});
        request.put("provider", "MANUAL");
        if (explicitNull) request.put("analysisReasonLength", null); else request.remove("analysisReasonLength");
        var imported = controller().importScores(mapper.writeValueAsString(request));
        assertThat(imported.getStatusCode().value()).isEqualTo(200);
        assertThat(imported.getBody()).containsKey("analysisReasonLength");
        assertThat(imported.getBody().get("analysisReasonLength")).isNull();
        var exported = controller().exportScores(imported.getBody());
        assertThat(exported.getStatusCode().value()).isEqualTo(200);
        assertThat(mapper.valueToTree(exported.getBody()).path("analysisReasonLength").isNull()).isTrue();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("invalidRecordedReasonLengths")
    void invalidReasonLengthIsRejectedInsteadOfLosingRecordedMetadata(String direction, Object value) throws Exception {
        var request = new HashMap<String, Object>(Map.of("requirement", "requirement", "scores", Map.of("BP", 70d),
                "provider", "MOCK", "analysisReasonLength", value));
        var response = direction.equals("export") ? controller().exportScores(request)
                : controller().importScores(mapper.writeValueAsString(request));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> invalidRecordedReasonLengths() {
        return java.util.stream.Stream.of("export", "import").flatMap(direction ->
                java.util.stream.Stream.<Object>of("unlimited", "", " ", "\t", "\r\n",
                                42, true, Map.of("value", "detailed"), List.of("detailed"))
                        .map(value -> org.junit.jupiter.params.provider.Arguments.of(direction, value)));
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
    @CsvSource({"1, false", "1, true", "2, false", "2, true", "3, false", "3, true", "4, false", "4, true"})
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
        var exported = controller().exportScores(Map.of("requirement", "Manual requirement", "scores", Map.of("BP", 70d), "provider", "MANUAL"));
        assertThat(exported.getStatusCode().value()).isEqualTo(200);
        var imported = controller().importScores(mapper.writeValueAsString(exported.getBody()));
        assertThat(imported.getStatusCode().value()).isEqualTo(200);
        assertThat(imported.getBody().get("analysisScope")).isNull();
    }

    @ParameterizedTest
    @CsvSource({"1, PARTIAL", "2, PARTIAL", "1, SUCCESS", "2, SUCCESS"})
    void legacyCoverageFreeStatusSurvivesHttpExportAndImport(int version, String analysisStatus) throws Exception {
        var saved = evidence(version);
        saved.setScores(Map.of("BP", 70d, "IP", 40d));
        saved.setAnalysisStatus(analysisStatus);
        var mvc = MockMvcBuilders.standaloneSetup(controller()).build();
        var exported = mvc.perform(post("/api/scores/export").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(saved)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var output = mapper.readTree(exported);
        assertThat(output.path("version").asInt()).isEqualTo(4);
        assertThat(output.path("analysisCoverage").isNull()).isTrue();
        assertThat(output.path("analysisStatus").asString()).isEqualTo(analysisStatus);
        var imported = mvc.perform(post("/api/scores/import").contentType(MediaType.APPLICATION_JSON).content(exported))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(imported).path("analysisStatus").asString()).isEqualTo(analysisStatus);
        assertThat(mapper.readTree(imported).path("scores").size()).isEqualTo(2);
    }

    @Test
    void fractionalVersionFourRetainsZeroAndUnknownEvidenceThroughHttpExchange() throws Exception {
        var saved = evidence(4);
        saved.setScores(Map.of("BP", 0.125d, "IP", 0d));
        saved.setRawScores(saved.getScores());
        saved.setAnalysisCoverage(new AnalysisCoverage(Map.of(
                "BP", new AnalysisCoverage.NodeAssessment(AnalysisCoverage.State.RELEVANT, 0.125d, 0.125d,
                        AnalysisCoverage.Descendants.COMPLETE, null),
                "IP", new AnalysisCoverage.NodeAssessment(AnalysisCoverage.State.NOT_RELEVANT, 0d, 0d,
                        AnalysisCoverage.Descendants.COMPLETE, null),
                "CP", new AnalysisCoverage.NodeAssessment(AnalysisCoverage.State.UNKNOWN, null, null,
                        AnalysisCoverage.Descendants.UNASSESSED, "LEFT_OPEN:fixture")), 2, 1, 1));
        var mvc = MockMvcBuilders.standaloneSetup(controller()).build();
        var exported = mvc.perform(post("/api/scores/export").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(saved)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var imported = mvc.perform(post("/api/scores/import").contentType(MediaType.APPLICATION_JSON).content(exported))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(exported).path("version").asInt()).isEqualTo(4);
        for (var result : List.of(mapper.readTree(exported), mapper.readTree(imported))) {
            assertThat(result.path("analysisStatus").asString()).isEqualTo("PARTIAL");
            assertThat(result.path("scores").size()).isEqualTo(2);
            assertThat(result.path("scores").path("BP").asDouble()).isEqualTo(0.125);
            assertThat(result.path("rawScores").path("BP").asDouble()).isEqualTo(0.125);
            assertThat(result.path("scores").path("IP").asDouble()).isEqualTo(0);
            assertThat(result.path("scores").has("CP")).isFalse();
            assertThat(result.path("rawScores").has("CP")).isFalse();
            var unknown = result.path("analysisCoverage").path("nodes").path("CP");
            assertThat(unknown.path("state").asString()).isEqualTo("UNKNOWN");
            assertThat(unknown.path("score").isNull()).isTrue();
            assertThat(unknown.path("effectiveRelevance").isNull()).isTrue();
        }
    }

    @ParameterizedTest
    @CsvSource({"export, 1", "import, 1", "export, 2", "import, 2", "export, 3", "import, 3", "export, 4", "import, 4"})
    void rejectsDeclaredUnknownRootOnEveryExchangeVersion(String direction, int version) throws Exception {
        var saved = evidence(version);
        saved.setAnalysisScope(new AnalysisScope(Set.of("NOT_A_ROOT"), AnalysisMode.TAXONOMIES_ONLY));
        assertThat(exchange(direction, saved)).isEqualTo(400);
    }

    @ParameterizedTest
    @CsvSource({"export, 1", "import, 1", "export, 2", "import, 2", "export, 3", "import, 3", "export, 4", "import, 4"})
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
        if (raw) saved.setRawScores(Map.of("BP", 70d, "BP-looking-other", 35d));
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
        saved.setScores(Map.of("IP-looking-selected", 70d)); saved.setRawScores(saved.getScores());
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
        var saved = evidence(version); saved.setScores(Map.of("UNKNOWN_LEGACY", 0d));
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
    @CsvSource({"2", "3", "4"})
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
        if (field.equals("raw")) saved.setRawScores(Map.of("BP-looking-other", 35d));
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
