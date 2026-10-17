package com.taxonomy.analysis.relations;

import com.taxonomy.analysis.service.AiPromptBudgetPolicy;
import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.analysis.service.LlmProviderConfig;
import com.taxonomy.analysis.service.AiTargetCatalogService;
import com.taxonomy.analysis.service.PromptTemplateService;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.extension.api.llm.ProviderId;
import com.taxonomy.relations.service.RelationCompatibilityMatrix;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RelationRequestProfileTest {
    @Test
    void mockExecutionDoesNotInheritAWorkersCustomProviderBudget() {
        var config = new LlmProviderConfig(null);
        ReflectionTestUtils.setField(config, "llmProviderConfig", "CUSTOM_OPENAI");
        var catalogue = mock(TaxonomyService.class);
        var llm = new LlmService(config, null, new tools.jackson.databind.ObjectMapper(), catalogue,
                mock(PromptTemplateService.class), null, null);
        var budget = spy(new AiPromptBudgetPolicy(new AiTargetCatalogService(config)));
        var service = new RequirementRelationSearchService(catalogue, new RelationCompatibilityMatrix(), llm, budget);
        try (var scope = config.withRequestProvider("MOCK")) {
            RequirementRelationSearch.Options options = ReflectionTestUtils.invokeMethod(service, "runtimeOptions");
            assertEquals(10, options.limits().batchSize());
            assertEquals(120_000, options.maxPromptCharacters());
            verify(budget).requireWithinBudget("", "MOCK");
            String fingerprint = service.recoveryPolicyFingerprint();
            ReflectionTestUtils.setField(service, "maxOutputTokens", 0);
            assertEquals(fingerprint, service.recoveryPolicyFingerprint(), "mock policy cannot inherit irrelevant custom output settings");
            String prompt = RelationSearchProtocol.sourcePrompt("working time", java.util.List.of(
                    new com.taxonomy.dto.RelationSearchModel.Node("UA-time", "UA", "Time", "Working time", false)), Map.of());
            assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(service, "complete", prompt));
            verify(budget).requireWithinBudget(prompt, "MOCK");
        }
    }

    @Test
    void springBindsAutomaticDefaultsAndEnvironmentOverrides() {
        for (boolean override : new boolean[] {false, true}) {
            var fixture = new Fixture("CUSTOM_OPENAI");
            try (var context = new AnnotationConfigApplicationContext()) {
                if (override) context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("operator", Map.of(
                        "TAXONOMY_RELATION_BATCH_SIZE", "5", "TAXONOMY_RELATION_MAX_PROMPT_CHARACTERS", "24000")));
                context.registerBean(RequirementRelationSearchService.class, () -> fixture.service);
                context.refresh();
                assertEquals(override ? 5 : 3, fixture.options().limits().batchSize());
                assertEquals(override ? 24_000 : 16_000, fixture.options().maxPromptCharacters());
            }
        }
    }

    @Test
    void localCustomRequestsUseSmallerGroupsAndInputAllowance() {
        var fixture = new Fixture("CUSTOM_OPENAI");
        assertEquals(3, fixture.options().limits().batchSize());
        assertEquals(16_000, fixture.options().maxPromptCharacters());
    }

    @Test
    void standardProvidersRetainTheirExistingTraversalCapacity() {
        var fixture = new Fixture("OPENAI");
        assertEquals(10, fixture.options().limits().batchSize());
        assertEquals(120_000, fixture.options().maxPromptCharacters());
        assertEquals(24, fixture.options().limits().maxCalls());
    }

    @Test
    void providerScopeSelectsTheProfileWithoutChangingTheRecoveryPolicyIdentity() {
        var fixture = new Fixture("OPENAI");
        String fingerprint = fixture.service.recoveryPolicyFingerprint();
        fixture.provider.set("CUSTOM_OPENAI");
        assertEquals(3, fixture.options().limits().batchSize());
        assertEquals(fingerprint, fixture.service.recoveryPolicyFingerprint());
        fixture.provider.set("GEMINI");
        assertEquals(10, fixture.options().limits().batchSize());
        assertEquals(fingerprint, fixture.service.recoveryPolicyFingerprint());
    }

    @Test
    void explicitOverridesApplyAcrossProvidersAndInvalidateSavedPolicy() {
        var fixture = new Fixture("CUSTOM_OPENAI");
        String before = fixture.service.recoveryPolicyFingerprint();
        ReflectionTestUtils.setField(fixture.service, "batchSize", 7);
        ReflectionTestUtils.setField(fixture.service, "maxPromptCharacters", 35_000);
        assertEquals(7, fixture.options().limits().batchSize());
        assertEquals(35_000, fixture.options().maxPromptCharacters());
        assertNotEquals(before, fixture.service.recoveryPolicyFingerprint());
        fixture.provider.set("OPENAI");
        assertEquals(7, fixture.options().limits().batchSize());
        assertEquals(35_000, fixture.options().maxPromptCharacters());
        ReflectionTestUtils.setField(fixture.service, "batchSize", 0);
        assertThrows(IllegalArgumentException.class, fixture.service::validate);
    }

    private static final class Fixture {
        final AtomicReference<String> provider;
        final RequirementRelationSearchService service;
        Fixture(String initial) {
            provider = new AtomicReference<>(initial);
            var llm = mock(LlmService.class);
            when(llm.getActiveProviderId()).thenAnswer(ignored -> new ProviderId(provider.get()));
            service = new RequirementRelationSearchService(mock(TaxonomyService.class),
                    new RelationCompatibilityMatrix(), llm, mock(AiPromptBudgetPolicy.class));
        }
        RequirementRelationSearch.Options options() {
            return ReflectionTestUtils.invokeMethod(service, "runtimeOptions");
        }
    }
}
