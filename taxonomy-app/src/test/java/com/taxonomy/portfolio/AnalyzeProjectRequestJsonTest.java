package com.taxonomy.portfolio;

import com.taxonomy.portfolio.dto.PortfolioDtos.AnalyzeProjectRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The browser sends {@code all} as {@code null} or omits it entirely for single-requirement
 * analyses. Deserialization must not fail with HTTP 400 in that case.
 */
class AnalyzeProjectRequestJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void explicitNullAllIsDeserializedAsNoFullProjectAnalysis() throws Exception {
        AnalyzeProjectRequest request = mapper.readValue(
                "{\"requirementIds\":[7],\"all\":null,\"provider\":\"MOCK\"}",
                AnalyzeProjectRequest.class);

        assertThat(request.all()).isNull();
        assertThat(request.analyzeAll()).isFalse();
        assertThat(request.requirementIds()).containsExactly(7L);
    }

    @Test
    void missingAllIsDeserializedAsNoFullProjectAnalysis() throws Exception {
        AnalyzeProjectRequest request = mapper.readValue(
                "{\"requirementIds\":[7]}", AnalyzeProjectRequest.class);

        assertThat(request.analyzeAll()).isFalse();
    }

    @Test
    void explicitTrueAllRequestsFullProjectAnalysis() throws Exception {
        AnalyzeProjectRequest request = mapper.readValue(
                "{\"requirementIds\":[],\"all\":true}", AnalyzeProjectRequest.class);

        assertThat(request.analyzeAll()).isTrue();
        assertThat(request.requirementIds()).isEqualTo(List.of());
    }
}
