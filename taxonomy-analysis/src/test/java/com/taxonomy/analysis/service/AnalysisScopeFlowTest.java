package com.taxonomy.analysis.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.*;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class AnalysisScopeFlowTest {
    private static final AnalysisScope SELECTED = new AnalysisScope(Set.of("BP"), AnalysisMode.TAXONOMIES_ONLY);
    private static class Catalogue extends TaxonomyService {
        Catalogue() { super(null, null, null); }
        private TaxonomyNode node(String code, String parent) {
            var node = new TaxonomyNode(); node.setCode(code); node.setNameEn(code);
            node.setParentCode(parent); node.setTaxonomyRoot(code.substring(0, 2)); return node;
        }
        @Override public List<TaxonomyNode> getRootNodes() { return List.of(node("BP", null), node("CP", null)); }
        @Override public List<TaxonomyNode> getChildrenOf(String code) {
            return switch (code) {
                case "BP" -> List.of(node("BP-1", "BP"));
                case "BP-1" -> List.of(node("BP-2", "BP-1"));
                case "CP" -> throw new AssertionError("Unselected taxonomy expanded");
                default -> List.of();
            };
        }
        @Override public Map<String, String> getAssessmentContexts(List<TaxonomyNode> nodes) { return Map.of(); }
        private TaxonomyNodeDto dto(String code, TaxonomyNodeDto... children) {
            var node = new TaxonomyNodeDto(); node.setCode(code); node.setNameEn(code);
            node.setTaxonomyRoot(code.substring(0, 2)); node.setChildren(List.of(children)); return node;
        }
        @Override public List<TaxonomyNodeDto> getFullTree() {
            return List.of(dto("BP", dto("BP-1", dto("BP-2"))), dto("CP", dto("CP-1")));
        }
    }
    private static class Provider extends LlmProviderConfig {
        Provider() { super(null); }
        @Override public com.taxonomy.extension.api.llm.ProviderId getActiveProviderId() { return LlmProvider.OPENAI.id(); }
        @Override public String getActiveProviderName() { return "OPENAI"; }
        @Override public String getApiKey(LlmProvider provider) { return "test-key"; }
    }
    private static class Gateway implements LlmGateway {
        final List<String> prompts = new ArrayList<>();
        @Override public String sendHttpRequest(String prompt, String key) {
            prompts.add(prompt);
            String code = switch (prompts.size()) {
                case 1 -> "BP"; case 2 -> "BP-1"; case 3 -> "BP-2";
                default -> throw new AssertionError("Unexpected provider call");
            };
            return "{\"" + code + "\":{\"score\":20,\"reason\":\"independent relevance\"}}";
        }
        @Override public String extractResponseText(String response) { return response; }
        @Override public String providerName() { return "OPENAI"; }
    }
    private record Fixture(LlmService llm, Gateway gateway) { }
    private Fixture fixture() {
        var mapper = new ObjectMapper(); var provider = new Provider(); var gateway = new Gateway();
        var registry = new LlmGatewayRegistry(provider, new RestTemplate(), mapper, null, null, null, null) {
            @Override public LlmGateway getGatewayById(com.taxonomy.extension.api.llm.ProviderId selected) { return gateway; }
        };
        var prompts = new PromptTemplateService(); prompts.loadDefaults();
        return new Fixture(new LlmService(provider, registry, mapper, new Catalogue(), prompts, null, null), gateway);
    }
    @Test void selectedScoringKeepsFullDisplayTreeAndSelectedCoverage() {
        var fixture = fixture(); var result = fixture.llm().analyzeWithBudget("requirement", SELECTED);
        assertThat(result.getRawScores()).isEqualTo(Map.of("BP", 20, "BP-1", 20, "BP-2", 20));
        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat(result.getAnalysisScope()).isEqualTo(SELECTED);
        assertThat(result.getTree()).extracting(TaxonomyNodeDto::getCode).containsExactly("BP", "CP");
        assertThat(result.getTree().get(1).getMatchPercentage()).isNull();
        assertThat(result.getAnalysisCoverage().nodes().keySet()).containsExactlyInAnyOrder("BP", "BP-1", "BP-2");
        assertThat(result.getAnalysisCoverage().assessedNodes()).isEqualTo(3);
        assertThat(fixture.gateway().prompts).hasSize(3);
        assertThat(fixture.gateway().prompts.getFirst()).contains("independent relevance");
    }
    @Test void fullAndStreamingShareSelectedRecursiveScoringAndProgress() {
        var full = fixture(); var stream = fixture();
        var registry = new AnalysisProgressRegistry(new StandardEnvironment());
        var context = new WorkspaceContext("alice", "alice-ws", "draft");
        AnalysisResult result;
        try (var run = registry.open(null, "alice", context, null)) {
            result = full.llm().analyzeWithBudget("requirement", SELECTED);
            var progress = registry.snapshot(run.id(), "alice", context).nodeProgress();
            assertThat(progress.total()).isEqualTo(3);
            assertThat(progress.taxonomies()).extracting(AnalysisNodeProgress.Taxonomy::root).containsExactly("BP");
        }
        var events = new ArrayList<String>(); var scores = new HashMap<String, Integer>();
        stream.llm().analyzeStreaming("requirement", SELECTED, new AnalysisEventCallback() {
            public void onPhase(String message, int progress) { events.add("phase"); }
            public void onScores(Map<String,Integer> values, Map<String,String> reasons, String message, LlmCallDetail detail) {
                events.add("scores"); scores.putAll(values);
            }
            public void onExpanding(String parent, List<String> children) { events.add("expanding"); }
            public void onComplete(String status, Map<String,Integer> values, List<String> warnings,
                    List<TaxonomyDiscrepancy> discrepancies, List<ProductCoverageGap> gaps) {
                events.add("complete"); assertThat(status).isEqualTo("SUCCESS"); assertThat(values).isEqualTo(scores);
            }
            public void onError(String status, String error, Map<String,Integer> values, List<String> warnings,
                    List<TaxonomyDiscrepancy> discrepancies, List<ProductCoverageGap> gaps) { fail(error); }
        });
        assertThat(scores).isEqualTo(result.getRawScores());
        assertThat(stream.gateway().prompts).isEqualTo(full.gateway().prompts);
        assertThat(events).containsExactly("phase", "scores", "expanding", "scores", "expanding", "scores", "complete");
    }
    @Test void invalidScopeFailsBeforeProviderWork() {
        var fixture = fixture();
        assertThatThrownBy(() -> fixture.llm().analyzeWithBudget("requirement", new AnalysisScope(Set.of("UNKNOWN"), AnalysisMode.FULL)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(fixture.gateway().prompts).isEmpty();
    }
    @Test void scopeIsCanonicalImmutableAndBackwardsCompatible() {
        var input = new LinkedHashSet<>(List.of("CP", "BP")); var scope = new AnalysisScope(input, null); input.clear();
        assertThat(scope.taxonomyRoots()).containsExactly("BP", "CP");
        assertThatThrownBy(() -> scope.taxonomyRoots().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new AnalysisScope(Set.of(" "), null)).isInstanceOf(IllegalArgumentException.class);
        var nullEntry = new HashSet<String>(); nullEntry.add(null);
        assertThatThrownBy(() -> new AnalysisScope(nullEntry, null)).isInstanceOf(IllegalArgumentException.class);
        var mapper = new ObjectMapper();
        assertThat(mapper.readValue(mapper.writeValueAsString(scope), AnalysisScope.class)).isEqualTo(scope);
        assertThat(mapper.readValue("{}", AnalysisRequest.class).getAnalysisScope()).isEqualTo(AnalysisScope.full());
        assertThat(mapper.readValue("{}", AnalysisResult.class).getAnalysisScope()).isEqualTo(AnalysisScope.full());
    }
}
