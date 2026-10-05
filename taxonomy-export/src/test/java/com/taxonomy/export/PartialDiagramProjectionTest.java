package com.taxonomy.export;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.taxonomy.dto.RequirementArchitectureView;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PartialDiagramProjectionTest {

    private final JsonMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private final DiagramProjectionService service = new DiagramProjectionService();

    @ParameterizedTest
    @ValueSource(strings = {"null", "{\"nodes\":{},\"assessedNodes\":0,\"unknownNodes\":0,\"failedOrBlockedNodes\":0}"})
    void partialStatusSurvivesViewExchangeWithoutInventingCoverage(String coverage) throws Exception {
        RequirementArchitectureView view = mapper.readValue("""
                {"analysisStatus":"PARTIAL", "analysisCoverage":%s,
                 "includedElements":[], "includedRelationships":[]}
                """.formatted(coverage), RequirementArchitectureView.class);
        var restored = mapper.readValue(mapper.writeValueAsString(view), RequirementArchitectureView.class);

        for (var diagram : java.util.List.of(service.projectRaw(restored, "Existing result"),
                service.project(restored, "Existing result"))) {
            assertThat(diagram.title()).contains("Existing result", "PARTIAL", "TEILERGEBNIS")
                    .doesNotContain("0 unassessed", "0 unbewertet", "null");
            assertThat(diagram.nodes()).isEmpty();
            assertThat(diagram.edges()).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"SUCCESS\""})
    void completedOrUnmarkedLegacyViewKeepsItsTitle(String status) throws Exception {
        var view = mapper.readValue("{\"analysisStatus\":" + status + "}", RequirementArchitectureView.class);

        assertThat(service.projectRaw(view, "Existing result").title()).isEqualTo("Existing result");
    }
}
