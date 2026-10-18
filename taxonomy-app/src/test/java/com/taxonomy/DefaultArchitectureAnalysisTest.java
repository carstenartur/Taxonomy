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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
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
        // Inject a malformed response explicitly. The normal mock now speaks the
        // relation protocol and must not be relied upon as an error fixture.
        doReturn("[]").when(llm).callLlmRaw(anyString(), org.mockito.ArgumentMatchers.any(com.taxonomy.analysis.service.LlmCompletionOptions.class));
        JsonNode result = analyze(true);
        assertThat(search.isEnabled()).isTrue();
        assertThat(result.path("status").asString()).isEqualTo("PARTIAL");
        assertThat(result.path("relationSearchReport").isObject()).isTrue();
        var report = result.path("relationSearchReport");
        List<String> warnings = new ArrayList<>();
        report.path("warnings").forEach(warning -> warnings.add(warning.asString()));
        assertThat(warnings).anyMatch(warning -> warning.startsWith("INVALID_SOURCE_RESPONSE "))
                .allMatch(warning -> warning.startsWith("INVALID_SOURCE_RESPONSE ")
                        || warning.startsWith("SOURCE_LIMIT:"));
        assertThat(report.path("totalCalls").asInt()).isPositive().isLessThanOrEqualTo(24);
        assertThat(report.path("result").path("edges")).isEmpty();
        assertThat(result.path("provisionalRelations").isEmpty()).isTrue();
        var view = result.path("architectureView");
        assertThat(view.path("includedRelationships")).isEmpty();
        assertDirectEvidence(result, view, Set.of());
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
        }).when(llm).callLlmRaw(anyString(), org.mockito.ArgumentMatchers.any(com.taxonomy.analysis.service.LlmCompletionOptions.class));
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
        Set<String> quotedSources = new LinkedHashSet<>();
        for (JsonNode source : report.path("sources")) {
            for (JsonNode contribution : source.path("contributions")) {
                if (ORIGINAL.equals(contribution.path("quote").asString())) {
                    quotedSources.add(source.path("node").path("id").asString());
                }
            }
        }
        assertThat(quotedSources).hasSize(1);
        String quotedSource = quotedSources.iterator().next();
        var requirementElements = new LinkedHashSet<String>();
        for (JsonNode element : view.path("includedElements")) {
            if ("REQUIREMENT_EVIDENCE".equals(element.path("origin").asString())) {
                requirementElements.add(element.path("nodeCode").asString());
                assertThat(element.path("presenceReason").asString()).contains("SOURCE_ONLY", ORIGINAL);
            }
        }
        assertThat(requirementElements).containsExactly(quotedSource);
        assertDirectEvidence(result, view, Set.of(quotedSource));
        assertThat(view.path("relationSearchReport")).isEqualTo(report);
    }

    @Test
    void hidingTheArchitectureViewDoesNotDisableDefaultEvidenceValidation() throws Exception {
        doReturn("[]").when(llm).callLlmRaw(anyString(), org.mockito.ArgumentMatchers.any(com.taxonomy.analysis.service.LlmCompletionOptions.class));
        JsonNode result = analyze(false);
        assertThat(search.isEnabled()).isTrue();
        assertThat(result.path("status").asString()).isEqualTo("PARTIAL");
        assertThat(result.path("relationSearchReport").isObject()).isTrue();
        assertThat(result.path("provisionalRelations").isEmpty()).isTrue();
        assertThat(result.path("architectureView").isMissingNode()
                || result.path("architectureView").isNull()).isTrue();
    }

    @Test
    void explicitMockDemonstrationUsesTheDefaultProtocolWithinItsOrdinaryBudget() throws Exception {
        JsonNode result = analyze(true);
        assertThat(search.isEnabled()).isTrue();
        assertThat(result.path("status").asString()).as(result.toString()).isEqualTo("SUCCESS");
        assertThat(result.path("provider").asString()).isEqualTo("Mock");
        assertThat(result.path("provisionalRelations").isEmpty()).isTrue();
        JsonNode report = result.path("relationSearchReport");
        assertThat(report.path("totalCalls").asInt()).isPositive().isLessThanOrEqualTo(24);
        assertThat(report.path("warnings").isEmpty()).isTrue();
        assertThat(report.path("result").path("unfinished").isEmpty()).isTrue();
        assertThat(report.path("result").path("edges").size()).isEqualTo(2);
        var view = result.path("architectureView");
        var relationships = view.path("includedRelationships");
        assertThat(relationships.size()).isEqualTo(2);
        Set<String> signatures = new LinkedHashSet<>();
        for (JsonNode relation : relationships) {
            signatures.add(relation.path("sourceCode").asString() + "|"
                    + relation.path("relationType").asString() + "|"
                    + relation.path("targetCode").asString());
            assertThat(relation.path("sourceCode").asString()).isEqualTo("UA-1574");
            assertThat(relation.path("presenceReason").asString()).contains("MOCK", ORIGINAL);
        }
        Set<String> expectedSignatures = Set.of(
                "UA-1574|CONSUMES|IP-1136", "UA-1574|USES|CR-1005");
        assertThat(signatures).containsExactlyInAnyOrderElementsOf(expectedSignatures);
        Set<String> relationElements = Set.of("UA-1574", "IP-1136", "CR-1005");
        Set<String> actualRelationElements = new LinkedHashSet<>();
        for (JsonNode element : view.path("includedElements")) {
            if ("RELATION_EVIDENCE".equals(element.path("origin").asString())) {
                actualRelationElements.add(element.path("nodeCode").asString());
            }
        }
        assertThat(actualRelationElements).containsExactlyInAnyOrderElementsOf(relationElements);
        Set<String> reportSignatures = new LinkedHashSet<>();
        for (JsonNode edge : report.path("result").path("edges")) {
            reportSignatures.add(edge.path("contribution").path("source").path("id").asString() + "|"
                    + edge.path("type").asString() + "|" + edge.path("target").path("id").asString());
        }
        assertThat(reportSignatures).containsExactlyInAnyOrderElementsOf(expectedSignatures);
        assertDirectEvidence(result, view, relationElements);
        JsonNode ipScore = result.path("scoreDetails").path("IP-1136");
        assertThat(ipScore.path("rawScore").asInt()).isEqualTo(60);
        assertThat(ipScore.path("effectiveRelevance").asInt()).isEqualTo(36);
        assertThat(result.path("rawScores").path("IP-1136").asInt()).isEqualTo(60);
        assertThat(result.path("scores").path("IP-1136").asInt()).isEqualTo(36);
    }

    private static void assertDirectEvidence(JsonNode result, JsonNode view, Set<String> evidenceNodes) {
        Map<String, JsonNode> treeNodes = new LinkedHashMap<>();
        for (JsonNode node : result.path("tree")) collectTreeNodes(node, treeNodes);
        JsonNode rawScores = result.path("rawScores");
        JsonNode scores = result.path("scores");
        JsonNode scoreDetails = result.path("scoreDetails");
        Set<String> scoredNodes = new LinkedHashSet<>();
        for (var entry : scores.properties()) {
            String code = entry.getKey();
            JsonNode node = treeNodes.get(code);
            int effective = entry.getValue().asInt();
            int raw = rawScores.path(code).asInt();
            JsonNode detail = scoreDetails.path(code);
            if (effective <= 0 || node == null || node.path("taxonomyContainer").asBoolean()) continue;
            assertThat(raw).as("Positive effective score for %s needs positive raw evidence", code).isPositive();
            assertThat(detail.path("nodeCode").asString()).isEqualTo(code);
            assertThat(detail.path("rawScore").asInt()).isEqualTo(raw);
            assertThat(detail.path("effectiveRelevance").asInt()).isEqualTo(effective);
            scoredNodes.add(code);
        }

        Set<String> includedNodes = new LinkedHashSet<>();
        Set<String> directNodes = new LinkedHashSet<>();
        Map<String, JsonNode> elementsByCode = new LinkedHashMap<>();
        for (JsonNode element : view.path("includedElements")) {
            String code = element.path("nodeCode").asString();
            assertThat(includedNodes.add(code)).as("Included identity %s must be unique", code).isTrue();
            JsonNode node = treeNodes.get(code);
            assertThat(node).as("Included identity %s must come from the retained result tree", code).isNotNull();
            assertThat(node.path("taxonomyContainer").asBoolean()).as(code).isFalse();
            String expectedTitle = node.path("nameEn").asString();
            assertThat(element.path("title").asString()).isEqualTo(expectedTitle.isBlank() ? code : expectedTitle);
            assertThat(element.path("taxonomySheet").asString()).isEqualTo(node.path("taxonomyRoot").asString());
            elementsByCode.put(code, element);
            String origin = element.path("origin").asString();
            if ("DIRECT_SCORED".equals(origin)) {
                directNodes.add(code);
                assertThat(scoredNodes).as("Direct evidence for %s must be positive and retained", code).contains(code);
                JsonNode detail = scoreDetails.path(code);
                assertThat(element.path("scoreDetail")).isEqualTo(detail);
                assertThat(element.path("directLlmScore").asInt()).isEqualTo(detail.path("rawScore").asInt());
                assertThat(element.path("relevance").asDouble())
                        .isEqualTo(detail.path("effectiveRelevance").asInt() / 100.0);
                assertThat(element.path("anchor").asBoolean()).isTrue();
                assertThat(element.path("presenceReason").asString()).contains("Directly assessed relevance");
            } else {
                assertThat(evidenceNodes).as("Only explicitly classified evidence may be non-direct: %s", code)
                        .contains(code);
            }
        }
        assertThat(includedNodes).containsExactlyInAnyOrderElementsOf(union(scoredNodes, evidenceNodes));
        Set<String> expectedDirectNodes = new LinkedHashSet<>(scoredNodes);
        expectedDirectNodes.removeAll(evidenceNodes);
        assertThat(directNodes).containsExactlyInAnyOrderElementsOf(expectedDirectNodes);

        Set<String> anchorNodes = new LinkedHashSet<>();
        Map<String, JsonNode> anchorsByCode = new LinkedHashMap<>();
        for (JsonNode anchor : view.path("anchors")) {
            String code = anchor.path("nodeCode").asString();
            assertThat(anchorNodes.add(code)).as("Anchor metadata for %s must be unique", code).isTrue();
            assertThat(elementsByCode).containsKey(code);
            anchorsByCode.put(code, anchor);
        }
        assertThat(view.path("totalAnchors").asInt()).isEqualTo(view.path("anchors").size());
        for (String code : directNodes) {
            assertThat(anchorNodes).as("Direct assessment %s needs anchor metadata", code).contains(code);
            JsonNode detail = scoreDetails.path(code);
            JsonNode anchor = anchorsByCode.get(code);
            assertThat(anchor.path("directScore").asInt()).isEqualTo(detail.path("effectiveRelevance").asInt());
            assertThat(anchor.path("reason").asString()).contains("raw score " + detail.path("rawScore").asInt(),
                    "effective relevance " + detail.path("effectiveRelevance").asInt() + "%");
        }
        assertThat(view.path("totalElements").asInt()).isEqualTo(view.path("includedElements").size());
        assertThat(view.path("totalRelationships").asInt()).isEqualTo(view.path("includedRelationships").size());
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        Set<String> result = new LinkedHashSet<>(first);
        result.addAll(second);
        return result;
    }

    private static void collectTreeNodes(JsonNode node, Map<String, JsonNode> nodes) {
        String code = node.path("code").asString();
        if (!code.isBlank()) assertThat(nodes.putIfAbsent(code, node))
                .as("Retained tree identity %s must be unique", code).isNull();
        for (JsonNode child : node.path("children")) collectTreeNodes(child, nodes);
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
