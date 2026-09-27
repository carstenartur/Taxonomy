package com.taxonomy;

import com.taxonomy.analysis.relations.RequirementRelationSearchService;
import com.taxonomy.analysis.service.AnalysisMemoryGuard;
import com.taxonomy.analysis.service.LlmService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockStatic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Default-path HTTP composition, not a real-provider quality benchmark. */
@SpringBootTest(properties = "llm.mock=true")
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class DefaultArchitectureAnalysisTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ORIGINAL = "Provide secure voice communications";
    @Autowired private MockMvc mvc;
    @Autowired private RequirementRelationSearchService search;
    @MockitoSpyBean private LlmService llm;

    @Test
    void defaultRejectsInvalidRawEvidenceWithoutInventingLegacyEdges() throws Exception {
        // The existing mock raw boundary returns [], which is intentionally not
        // salvaged into a successful relationship response.
        JsonNode result = analyze(true);
        assertThat(search.isEnabled()).isTrue();
        assertThat(result.path("status").asString()).isEqualTo("PARTIAL");
        assertThat(result.path("relationSearchReport").isObject()).isTrue();
        assertThat(result.path("relationSearchReport").path("warnings").toString())
                .contains("INVALID_SOURCE_RESPONSE");
        assertThat(result.path("provisionalRelations").isEmpty()).isTrue();
        assertThat(result.path("architectureView").path("includedRelationships").isEmpty()).isTrue();
        assertThat(result.path("architectureView").path("includedElements").isEmpty()).isTrue();
    }

    @Test
    void defaultRetainsAQuotedSourceWhenItsDependenciesRemainOpen() throws Exception {
        AtomicBoolean selected = new AtomicBoolean();
        AtomicInteger rawCalls = new AtomicInteger();
        // Replace only raw completion. Catalogue reads, source ordering, protocol
        // parsing, real use case, projection and HTTP serialization remain real.
        // Scoring uses the existing deterministic catalogue-backed mock fixture.
        doAnswer(invocation -> {
            rawCalls.incrementAndGet();
            return sourceAndOpenRelationships(invocation.getArgument(0), selected);
        }).when(llm).callLlmRaw(anyString());
        JsonNode result = analyze(true);
        assertThat(search.isEnabled()).isTrue();
        assertThat(selected.get()).isTrue();
        assertThat(rawCalls.get()).isPositive().isLessThanOrEqualTo(24);
        assertThat(result.path("status").asString()).isEqualTo("PARTIAL");
        assertThat(result.path("provisionalRelations").isEmpty()).isTrue();
        JsonNode report = result.path("relationSearchReport");
        assertThat(report.path("sources").isEmpty()).isFalse();
        assertThat(report.path("result").path("edges").isEmpty()).isTrue();
        assertThat(report.path("result").path("unfinished").isEmpty()).isFalse();
        JsonNode view = result.path("architectureView");
        assertThat(view.path("includedRelationships").isEmpty()).isTrue();
        assertThat(view.path("includedElements").size()).isEqualTo(1);
        assertThat(view.path("includedElements").get(0).path("origin").asString())
                .isEqualTo("REQUIREMENT_EVIDENCE");
        assertThat(view.path("includedElements").get(0).path("presenceReason").asString())
                .contains("SOURCE_ONLY", ORIGINAL);
        assertThat(view.path("relationSearchReport")).isEqualTo(report);
    }

    @Test
    void hidingTheArchitectureViewDoesNotDisableDefaultEvidenceValidation() throws Exception {
        JsonNode result = analyze(false);
        assertThat(search.isEnabled()).isTrue();
        assertThat(result.path("status").asString()).isEqualTo("PARTIAL");
        assertThat(result.path("relationSearchReport").isObject()).isTrue();
        assertThat(result.path("provisionalRelations").isEmpty()).isTrue();
        assertThat(result.path("architectureView").isMissingNode()
                || result.path("architectureView").isNull()).isTrue();
    }

    private JsonNode analyze(boolean includeView) throws Exception {
        long mib = 1024L * 1024;
        try (var memory = mockStatic(AnalysisMemoryGuard.class)) {
            memory.when(AnalysisMemoryGuard::heapSample)
                    .thenReturn(new AnalysisMemoryGuard.Sample(64 * mib, 512 * mib));
            String requestBody = JSON.writeValueAsString(Map.of("businessText", ORIGINAL,
                    "includeArchitectureView", includeView));
            String body = mvc.perform(post("/api/analyze").contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(request().asyncNotStarted()).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            return JSON.readTree(body);
        }
    }

    private static String sourceAndOpenRelationships(String prompt, AtomicBoolean selected) {
        int offset = prompt.lastIndexOf("INPUT\n");
        if (offset < 0) throw new AssertionError("Expected the real relation protocol");
        JsonNode input = JSON.readTree(prompt.substring(offset + 6));
        if (input.has("nodes")) {
            List<Map<String, Object>> answers = new ArrayList<>();
            for (JsonNode node : input.get("nodes")) {
                boolean positive = selected.compareAndSet(false, true);
                answers.add(Map.of("nodeId", node.get("id").asString(),
                        "outcome", positive ? "EXPLICIT" : "REJECT",
                        "contributions", positive ? List.of(Map.of("text", "Requested communication contribution",
                                "quote", ORIGINAL, "condition", "")) : List.of(),
                        "rationale", "Controlled completion boundary for an HTTP composition test", "question", ""));
            }
            return JSON.writeValueAsString(Map.of("selections", answers));
        }
        List<Map<String, Object>> decisions = new ArrayList<>();
        for (JsonNode node : input.path("candidates")) {
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("targetId", node.path("id").asString());
            answer.put("outcome", "UNRESOLVED");
            answer.put("contribution", ""); answer.put("quote", ""); answer.put("necessity", null);
            answer.put("condition", ""); answer.put("alternativeGroup", "");
            answer.put("rationale", "Dependency is deliberately unassessed in this boundary fixture");
            answer.put("question", "Which dependency is required?");
            decisions.add(answer);
        }
        return JSON.writeValueAsString(Map.of("decisions", decisions));
    }
}
