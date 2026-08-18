package com.taxonomy.portfolio;

import com.taxonomy.analysis.service.LlmProvider;
import com.taxonomy.analysis.service.LlmProviderConfig;
import com.taxonomy.portfolio.dto.CopilotDtos.CopilotRunRequest;
import com.taxonomy.portfolio.model.AnalysisAutomationProfile;
import com.taxonomy.portfolio.service.AiAutomationPolicy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiAutomationPolicyTest {

    @Test
    void meteredProviderAllowsExplicitCopilotButNeverUnattendedAutopilot() {
        LlmProviderConfig providers = readyCustomProvider();
        AiAutomationPolicy policy = policy(
                providers, "METERED", true, true, "CUSTOM_OPENAI");

        var manual = policy.manual(new CopilotRunRequest(
                "CUSTOM_OPENAI", 40, AnalysisAutomationProfile.FULL,
                1, false, true, true));

        assertThat(manual.autopilot()).isFalse();
        assertThat(manual.provider()).isEqualTo("CUSTOM_OPENAI");
        assertThat(policy.autopilotReady()).isFalse();
        assertThat(policy.automaticAfterRequirementSaveReady()).isFalse();
        assertThat(policy.status().reason()).contains("UNMETERED");
        assertThatThrownBy(policy::autopilot)
                .hasMessageContaining("UNMETERED");
    }

    @Test
    void autopilotRequiresAnExplicitProviderEvenWhenTheActiveProviderIsReady() {
        LlmProviderConfig providers = readyCustomProvider();
        AiAutomationPolicy policy = policy(
                providers, "UNMETERED", true, true, " ");

        assertThat(policy.autopilotReady()).isFalse();
        assertThat(policy.status().autopilotProvider()).isNull();
        assertThat(policy.status().reason()).contains("explicit");
        assertThatThrownBy(policy::autopilot)
                .hasMessageContaining("explicit");
    }

    @Test
    void autopilotReadinessIsIndependentFromTheOptionalSaveHook() {
        LlmProviderConfig providers = readyCustomProvider();
        AiAutomationPolicy policy = policy(
                providers, "UNMETERED", true, false, "CUSTOM_OPENAI");

        var automatic = policy.autopilot();

        assertThat(policy.autopilotReady()).isTrue();
        assertThat(policy.automaticAfterRequirementSaveReady()).isFalse();
        assertThat(policy.status().runAfterRequirementSave()).isFalse();
        assertThat(policy.status().reason()).contains("after requirement saves is disabled");
        assertThat(automatic.autopilot()).isTrue();
        assertThat(automatic.provider()).isEqualTo("CUSTOM_OPENAI");
    }

    @Test
    void autopilotRequiresBothExplicitFlagsAndAConfiguredProvider() {
        LlmProviderConfig providers = readyCustomProvider();
        AiAutomationPolicy policy = policy(
                providers, "UNMETERED", true, true, "CUSTOM_OPENAI");

        var automatic = policy.autopilot();

        assertThat(policy.autopilotReady()).isTrue();
        assertThat(policy.automaticAfterRequirementSaveReady()).isTrue();
        assertThat(automatic.autopilot()).isTrue();
        assertThat(automatic.profile()).isEqualTo(AnalysisAutomationProfile.EXHAUSTIVE);
        assertThat(automatic.verificationPasses()).isEqualTo(2);
        assertThat(automatic.proposeSolutions()).isTrue();
        assertThat(automatic.proposeProducts()).isTrue();
    }

    @Test
    void manualCopilotCannotExceedTheOperatorArchitectureLimit() {
        LlmProviderConfig providers = readyCustomProvider();
        AiAutomationPolicy policy = policy(
                providers, "METERED", false, false, null);

        assertThatThrownBy(() -> policy.manual(new CopilotRunRequest(
                "CUSTOM_OPENAI", 51, AnalysisAutomationProfile.FULL,
                1, false, true, true)))
                .hasMessageContaining("configured limit of 50");
    }

    @Test
    void exhaustiveManualProfileUsesAtLeastTwoIndependentPasses() {
        LlmProviderConfig providers = readyCustomProvider();
        AiAutomationPolicy policy = policy(
                providers, "METERED", false, false, null);

        var manual = policy.manual(new CopilotRunRequest(
                null, null, AnalysisAutomationProfile.EXHAUSTIVE,
                null, false, null, null));

        assertThat(manual.verificationPasses()).isEqualTo(2);
        assertThat(manual.proposeSolutions()).isTrue();
        assertThat(manual.proposeProducts()).isTrue();
    }

    private static LlmProviderConfig readyCustomProvider() {
        LlmProviderConfig providers = mock(LlmProviderConfig.class);
        when(providers.getActiveProvider()).thenReturn(LlmProvider.CUSTOM_OPENAI);
        when(providers.isProviderConfigured(LlmProvider.CUSTOM_OPENAI)).thenReturn(true);
        return providers;
    }

    private static AiAutomationPolicy policy(
            LlmProviderConfig providers,
            String costPolicy,
            boolean enabled,
            boolean onSave,
            String autopilotProvider) {
        return new AiAutomationPolicy(
                providers,
                costPolicy,
                "FULL",
                "EXHAUSTIVE",
                1,
                2,
                50,
                enabled,
                onSave,
                autopilotProvider,
                true,
                true,
                1800);
    }
}
