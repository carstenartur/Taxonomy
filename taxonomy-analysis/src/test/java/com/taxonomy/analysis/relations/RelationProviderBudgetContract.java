package com.taxonomy.analysis.relations;

import com.taxonomy.analysis.service.AiPromptBudgetPolicy;
import com.taxonomy.analysis.service.AiTargetCatalogService;
import com.taxonomy.analysis.service.LlmProvider;
import com.taxonomy.analysis.service.LlmProviderConfig;
import com.taxonomy.analysis.service.LlmService;
import tools.jackson.databind.ObjectMapper;

/** Isolated real budget lookup with a controlled raw-completion boundary, without HTTP. */
public final class RelationProviderBudgetContract {
    public static void main(String[] args) throws Exception { testCanonicalProviderIdentityIsUsedForEveryBudgetLookup(); }

    public static void testCanonicalProviderIdentityIsUsedForEveryBudgetLookup() throws Exception {
        var config = new LlmProviderConfig(null) {
            @Override public boolean isProviderConfigured(LlmProvider provider) { return true; }
            @Override public String getOpenAiCompatibleModel(LlmProvider provider) { return "fixture"; }
            @Override public String getOpenAiCompatibleUrl(LlmProvider provider) { return "http://127.0.0.1/fixture"; }
            @Override public String getLocalModelId() { return "fixture-local-profile"; }
        };
        var budget = new AiPromptBudgetPolicy(new AiTargetCatalogService(config));
        for (LlmProvider provider : LlmProvider.values()) {
            int[] calls = {0};
            var llm = new LlmService(null, null, new ObjectMapper(), null, null, null, null) {
                @Override public LlmProvider getActiveProvider() { return provider; }
                @Override public String getActiveProviderName() { return "Presentation label / " + provider; }
                @Override public String callLlmRaw(String prompt) { calls[0]++; return "fixture response"; }
            };
            var subject = new RequirementRelationSearchService(null, null, llm, budget);
            var complete = RequirementRelationSearchService.class.getDeclaredMethod("complete", String.class);
            complete.setAccessible(true);
            try {
                Object result = complete.invoke(subject, "small fixture prompt");
                if (!"fixture response".equals(result) || calls[0] != 1) {
                    throw new AssertionError("Budget lookup did not reach the raw boundary exactly once: " + provider);
                }
            } catch (java.lang.reflect.InvocationTargetException failure) {
                throw new AssertionError("Provider identity was replaced by a display label: " + provider, failure.getCause());
            }
            // Correct provider identity must not disable the actual prompt-size guard.
            try {
                complete.invoke(subject, "x".repeat(120_001));
                throw new AssertionError("Oversized prompt passed the real budget: " + provider);
            } catch (java.lang.reflect.InvocationTargetException failure) {
                if (!(failure.getCause() instanceof com.taxonomy.analysis.service.PromptBudgetExceededException)) {
                    throw new AssertionError("Unexpected oversized-prompt failure", failure.getCause());
                }
            }
            if (calls[0] != 1) { throw new AssertionError("Oversized prompt reached the model"); }
        }
        System.out.println("Canonical provider identities and prompt limits: 8 providers passed");
    }
}
