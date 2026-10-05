package com.taxonomy.composition.analysis.artemis;

import com.taxonomy.analysis.service.LlmGatewayRegistry;
import com.taxonomy.analysis.service.LlmProvider;
import com.taxonomy.analysis.service.LlmProviderConfig;
import com.taxonomy.analysis.service.ProviderConcurrencyPermits;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArtemisProviderPermitsConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ArtemisProviderPermitsConfiguration.class);

    @Test
    void localDefaultAndDisabledClusterModeKeepExistingAdmission() {
        runner.run(context -> assertThat(context).doesNotHaveBean(ProviderConcurrencyPermits.class));
        runner.withPropertyValues("taxonomy.analysis.transport.mode=artemis",
                        "taxonomy.analysis.provider-permits.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ProviderConcurrencyPermits.class));
    }

    @Test
    void enabledPermitsRequireArtemisAndExplicitValidatedMappings() {
        runner.withPropertyValues("taxonomy.analysis.provider-permits.enabled=true")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasStackTraceContaining("require artemis"));
        enabled().run(context -> assertThat(context.getStartupFailure())
                .hasStackTraceContaining("require explicit provider-groups"));
        enabled().withPropertyValues("taxonomy.analysis.provider-permits.provider-groups.openai=../invalid")
                .run(context -> assertThat(context.getStartupFailure()).hasStackTraceContaining("Quota group"));
        enabled().withPropertyValues("taxonomy.analysis.provider-permits.provider-groups.local-onnx=local")
                .run(context -> assertThat(context.getStartupFailure()).hasStackTraceContaining("LOCAL_ONNX"));
    }

    @Test
    void springBindsAliasesAndInjectsPermitsIntoProductionRegistry() {
        enabled().withPropertyValues("taxonomy.analysis.provider-permits.provider-groups.openai=shared-account",
                        "taxonomy.analysis.provider-permits.provider-groups.custom-openai=shared-account",
                        "taxonomy.analysis.provider-permits.provider-groups.gemini=google-account",
                        "taxonomy.analysis.provider-permits.maximum-wait-ms=350")
                .withBean(LlmGatewayRegistry.class, () -> {
                    var http = new RestTemplate((uri, method) -> {
                        throw new AssertionError("Unpermitted HTTP attempt");
                    });
                    return new LlmGatewayRegistry(new LlmProviderConfig(null), http,
                            JsonMapper.builder().build(), null, null, null, null);
                }).run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ProviderConcurrencyPermits.class);
                    var settings = context.getBean(ArtemisProviderPermitSettings.class);
                    assertThat(settings.providerGroups()).containsEntry(LlmProvider.OPENAI, "shared-account")
                            .containsEntry(LlmProvider.CUSTOM_OPENAI, "shared-account");
                    assertThat(settings.maximumWaitMillis()).isEqualTo(350);
                    assertThatThrownBy(() -> context.getBean(LlmGatewayRegistry.class).getGateway(LlmProvider.GEMINI)
                            .sendHttpRequest("fixture", "fixture-key"))
                            .isInstanceOf(ProviderConcurrencyPermits.UnavailableException.class);
                });
    }

    private ApplicationContextRunner enabled() {
        return runner.withPropertyValues("taxonomy.analysis.transport.mode=artemis",
                        "taxonomy.analysis.provider-permits.enabled=true")
                .withBean(ArtemisAnalysisConnection.class, () -> new ArtemisAnalysisConnection(
                        new ActiveMQConnectionFactory("tcp://127.0.0.1:1")));
    }
}
