package com.taxonomy.openapi;

import com.taxonomy.composition.analysis.AnalysisProfilePreviewController;
import com.taxonomy.portfolio.controller.ProjectAnalysisController;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class OpenApiGeneratedContractTest {
    @Test
    void generatedSpecificationDescribesTheRealControllerContracts() throws Exception {
        OpenApiContractCases.verify();
    }

    @Test
    void generatedSpecificationDescribesSelectedAndRecordedModels() throws Exception {
        var document = OpenApiContractCases.documentForFixture(AnalysisModelFixture.class);
        var paths = document.path("paths");
        var model = paths.path("/api/projects/{projectId}/analysis-jobs/{jobId}/model").path("get");
        var responses = model.path("responses");
        var schema = responses.path("200").path("content").path("application/json").path("schema");
        assertEquals("#/components/schemas/AnalysisModelBinding", schema.path("$ref").asText());
        assertTrue(responses.has("204"));
        assertFalse(responses.path("204").has("content"));
        assertTrue(responses.has("404"));
        for (String field : List.of("projectId", "jobId")) {
            assertTrue(model.path("parameters").valueStream().anyMatch(parameter ->
                    parameter.path("name").asText().equals(field) && parameter.path("in").asText().equals("path")
                            && parameter.path("required").asBoolean() && !parameter.path("description").asText("").isBlank()), field);
        }
        var preview = paths.path("/api/architecture/editor/analysis-brief").path("get");
        for (String status : List.of("200", "400", "404", "409")) {
            assertTrue(preview.path("responses").has(status), status);
        }
        for (String field : List.of("repositoryId", "workspaceScopeKey", "branch", "expectedCommit", "expectedSemanticRevision")) {
            assertTrue(preview.path("parameters").valueStream().anyMatch(parameter ->
                    parameter.path("name").asText().equals(field) && parameter.path("in").asText().equals("query")
                            && !parameter.path("description").asText("").isBlank()), field);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("openapi-contract-fixture")
    @Import(OpenApiContractCases.ApiFixture.class)
    static class AnalysisModelFixture {
        @Bean ProjectAnalysisController projectAnalysisController() {
            return new ProjectAnalysisController(null, null, null);
        }
        @Bean AnalysisProfilePreviewController analysisProfilePreviewController() {
            return new AnalysisProfilePreviewController(null, Optional.empty());
        }
    }
}
