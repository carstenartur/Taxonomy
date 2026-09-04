package com.taxonomy.analysis.controller;

import com.taxonomy.analysis.usecase.AnalysisStreamEvent;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.AnalysisScoreDetail;
import com.taxonomy.dto.AnalysisScoreKind;
import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.dto.TaxonomyNodeDto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnalysisSseEventMapperTest {

    private final AnalysisSseEventMapper mapper = new AnalysisSseEventMapper();

    @Test
    void incrementalScoresStayRawUntilCompleteFamilyContextExists() {
        LlmCallDetail detail = new LlmCallDetail();
        detail.setPrompt("prompt");
        detail.setRawResponse("raw");
        detail.setProvider("GEMINI");
        detail.setDurationMs(42L);
        detail.setError("minor");

        AnalysisSseEventMapper.MappedEvent mapped = mapper.map(new AnalysisStreamEvent.Scores(
                Map.of("CP", 80),
                Map.of("CP", "reason"),
                "Capabilities scored 80/100",
                detail));

        assertThat(mapped.name()).isEqualTo("scores");
        Map<String, Object> payload = payload(mapped);
        assertThat(payload)
                .containsEntry("scores", Map.of("CP", 80))
                .containsEntry("rawScores", Map.of("CP", 80))
                .containsEntry("reasons", Map.of("CP", "reason"))
                .containsEntry("description", "Capabilities scored 80/100")
                .containsEntry("message", "Capabilities scored 80/100")
                .containsEntry("prompt", "prompt")
                .containsEntry("rawResponse", "raw")
                .containsEntry("provider", "GEMINI")
                .containsEntry("durationMs", 42L)
                .containsEntry("error", "minor")
                .doesNotContainKey("effectiveScores");
        assertThat(((Map<?, ?>) payload.get("scoreDetails")).get("CP")).isNotNull();
    }

    @Test
    void mapTerminalEventsPreserveExistingNamesAndAddEffectiveScores() {
        AnalysisSseEventMapper.MappedEvent complete = mapper.map(new AnalysisStreamEvent.Complete(
                "SUCCESS",
                Map.of("CP", 80, "CR", 0),
                List.of("warn"),
                List.of(),
                List.of()));
        AnalysisSseEventMapper.MappedEvent error = mapper.map(new AnalysisStreamEvent.Error(
                "PARTIAL",
                "boom",
                Map.of("CP", 80),
                List.of("warn"),
                List.of(),
                List.of()));

        assertThat(complete.name()).isEqualTo("complete");
        Map<String, Object> completePayload = payload(complete);
        assertThat(completePayload)
                .containsEntry("status", "SUCCESS")
                .containsEntry("totalScores", Map.of("CP", 80, "CR", 0))
                .containsEntry("rawScores", Map.of("CP", 80, "CR", 0))
                .containsEntry("effectiveScores", Map.of("CP", 80, "CR", 0))
                .containsEntry("totalMatched", 1)
                .containsEntry("warnings", List.of("warn"))
                .containsEntry("discrepancies", List.of())
                .containsEntry("productCoverageGaps", List.of());

        assertThat(error.name()).isEqualTo("error");
        Map<String, Object> errorPayload = payload(error);
        assertThat(errorPayload)
                .containsEntry("status", "PARTIAL")
                .containsEntry("errorMessage", "boom")
                .containsEntry("partialScores", Map.of("CP", 80))
                .containsEntry("rawScores", Map.of("CP", 80))
                .containsEntry("effectiveScores", Map.of("CP", 80))
                .containsEntry("warnings", List.of("warn"))
                .containsEntry("discrepancies", List.of())
                .containsEntry("productCoverageGaps", List.of());
    }

    @Test
    void terminalProductScoresExposeSuitabilityAndFamilyWeightedRelevance() {
        TaxonomyService taxonomyService = mock(TaxonomyService.class);
        TaxonomyNodeDto root = node("IP", null, "CATEGORY");
        TaxonomyNodeDto family = node("IP-F", "IP", "PRODUCT_FAMILY");
        TaxonomyNodeDto product = node("IP-P", "IP-F", "PRODUCT");
        family.setChildren(List.of(product));
        root.setChildren(List.of(family));
        when(taxonomyService.getFullTree()).thenReturn(List.of(root));

        AnalysisSseEventMapper semanticMapper = new AnalysisSseEventMapper(taxonomyService);
        AnalysisSseEventMapper.MappedEvent mapped = semanticMapper.map(
                new AnalysisStreamEvent.Complete(
                        "SUCCESS",
                        Map.of("IP", 100, "IP-F", 40, "IP-P", 80),
                        List.of(), List.of(), List.of()));

        Map<String, Object> payload = payload(mapped);
        assertThat(((Map<?, ?>) payload.get("totalScores")).get("IP-P")).isEqualTo(32);
        assertThat(((Map<?, ?>) payload.get("rawScores")).get("IP-P")).isEqualTo(80);
        assertThat(((Map<?, ?>) payload.get("productSuitabilityScores")).get("IP-P"))
                .isEqualTo(80);
        AnalysisScoreDetail productDetail = (AnalysisScoreDetail)
                ((Map<?, ?>) payload.get("scoreDetails")).get("IP-P");
        assertThat(productDetail.kind()).isEqualTo(AnalysisScoreKind.PRODUCT_SUITABILITY);
        assertThat(productDetail.effectiveRelevance()).isEqualTo(32);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(AnalysisSseEventMapper.MappedEvent event) {
        assertThat(event.payload()).isInstanceOf(Map.class);
        return (Map<String, Object>) event.payload();
    }

    private TaxonomyNodeDto node(String code, String parentCode, String role) {
        TaxonomyNodeDto node = new TaxonomyNodeDto();
        node.setCode(code);
        node.setParentCode(parentCode);
        node.setAnalysisRole(role);
        return node;
    }
}
