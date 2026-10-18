package com.taxonomy.analysis.relations;

import com.taxonomy.analysis.service.*;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.relations.service.RelationCompatibilityMatrix;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SourceCatalogueCaptureTest {
    @Test void localCohortsReuseCapturedSourcesAndRootsOnlyWithinTheirOwnRequest() {
        var json = JsonMapper.builder().build();
        var sources = new LinkedHashMap<String, TaxonomyNode>();
        for (String id : List.of("first", "second", "third")) {
            var node = new TaxonomyNode(); node.setCode(id); node.setTaxonomyRoot("BP");
            node.setParentCode("BP"); node.setNameEn(id); node.setDescriptionEn("Initial description"); sources.put(id, node);
        }
        var root = new TaxonomyNode(); root.setCode("IP"); root.setTaxonomyRoot("IP"); root.setNameEn("Information"); root.setDescriptionEn("Root description");
        var lookups = new AtomicInteger(); var rootReads = new AtomicInteger();
        var catalogue = new TaxonomyService(null, null, null) {
            @Override public TaxonomyNode getNodeByCode(String id) { lookups.incrementAndGet(); return sources.get(id); }
            @Override public List<TaxonomyNode> getRootNodes() { rootReads.incrementAndGet(); return List.of(root); }
            @Override public Map<String, RelationAssessmentDescriptions> getRelationAssessmentDescriptions(List<TaxonomyNode> nodes) {
                var result = new LinkedHashMap<String, RelationAssessmentDescriptions>();
                nodes.forEach(n -> result.put(n.getCode(), new RelationAssessmentDescriptions(n.getDescriptionEn(), n.getDescriptionEn())));
                return result;
            }
        };
        var prompts = new ArrayList<String>();
        var llm = new LlmService(null, null, json, null, null, null, null) {
            @Override public String getActiveProviderName() { return "MOCK"; }
            @Override public com.taxonomy.extension.api.llm.ProviderId getActiveProviderId() { return LlmProvider.CUSTOM_OPENAI.id(); }
            @Override public boolean supportsGenerativeCompletion() { return true; }
            @Override public String callLlmRaw(String prompt, LlmCompletionOptions options) {
                prompts.add(prompt);
                var input = json.readTree(prompt.substring(prompt.lastIndexOf("INPUT\n") + 6));
                sources.values().forEach(n -> n.setDescriptionEn("Changed description"));
                return json.writeValueAsString(Map.of("selections", List.of(Map.of("nodeId", input.get("nodes").get(0).get("id").asString(),
                        "outcome", "REJECT", "contributions", List.of(), "rationale", "No requested source contribution", "question", ""))));
            }
        };
        var policy = new AiPromptBudgetPolicy(null) {
            @Override public com.taxonomy.analysis.dto.AiTargetDtos.AiTargetDescriptor requireWithinBudget(String text, String provider) { return null; }
        };
        var service = new RequirementRelationSearchService(catalogue, new RelationCompatibilityMatrix(), llm, policy);
        ReflectionTestUtils.setField(service, "maxSources", 1); ReflectionTestUtils.setField(service, "batchSize", 1);
        var scores = Map.of("first", 30d, "second", 20d, "third", 10d);
        var first = service.search("Read evidence", scores);
        assertEquals(3, first.progress().assessedSources());
        assertEquals(3, lookups.get(), "Each positive source is captured once across all local cohorts");
        assertEquals(1, rootReads.get(), "The request reuses its immutable root scalars");
        assertEquals(3, prompts.size());
        assertTrue(first.sources().stream().allMatch(s -> s.node().description().equals("Initial description")));
        assertTrue(prompts.stream().allMatch(p -> p.contains("Initial description") && !p.contains("Changed description")));
        prompts.clear();
        var second = service.search("Read evidence", scores);
        assertEquals(3, second.progress().assessedSources());
        assertEquals(6, lookups.get()); assertEquals(2, rootReads.get());
        assertTrue(second.sources().stream().allMatch(s -> s.node().description().equals("Changed description")),
                "A new request must capture the current catalogue rather than reuse a completed cache");
    }
}
