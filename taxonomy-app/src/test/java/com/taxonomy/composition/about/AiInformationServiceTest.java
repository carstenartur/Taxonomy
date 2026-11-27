package com.taxonomy.composition.about;

import com.taxonomy.analysis.service.AiTargetCatalogService;
import com.taxonomy.analysis.service.LlmProviderConfig;
import com.taxonomy.catalog.service.LocalEmbeddingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class AiInformationServiceTest {

    private final LocalEmbeddingService embeddings = new LocalEmbeddingService();
    private final LlmProviderConfig config = new LlmProviderConfig(embeddings);
    private final AiInformationService information = new AiInformationService(embeddings, new AiTargetCatalogService(config));

    @Test
    void unrecognizedProviderLeavesEmbeddingInformationAvailable() {
        ReflectionTestUtils.setField(config, "llmProviderConfig", "https://fake-user:dummy@private.invalid/api?token=dummy");

        var snapshot = information.snapshot();

        assertThat(snapshot.embedding().state().name()).isEqualTo("DISABLED");
        assertThat(snapshot.analysis().state().name()).isEqualTo("INVALID_CONFIGURATION");
        assertThat(snapshot.analysis().provider()).isNull();
        assertThat(snapshot.toString()).doesNotContain("private.invalid", "fake-user", "dummy");
    }

    @ParameterizedTest
    @ValueSource(strings = {"operator-model:latest", "Qwen/Qwen2.5-7B-Instruct"})
    void customEndpointShowsTheModelIdentifierWithoutTheEndpointAddress(String model) {
        ReflectionTestUtils.setField(config, "llmProviderConfig", "CUSTOM_OPENAI");
        ReflectionTestUtils.setField(config, "customLlmUrl", "http://localhost:9999/private/chat/completions?token=dummy");
        ReflectionTestUtils.setField(config, "customLlmModel", model);
        ReflectionTestUtils.setField(config, "customLlmApiKey", "test-only-credential");

        var analysis = information.snapshot().analysis();

        assertThat(analysis.state().name()).isEqualTo("CONFIGURED");
        assertThat(analysis.mode().name()).isEqualTo("OPERATOR_ENDPOINT");
        assertThat(analysis.provider()).isEqualTo("CUSTOM_OPENAI");
        assertThat(analysis.model()).isEqualTo(model);
        assertThat(analysis.toString()).doesNotContain("localhost", "private", "dummy", "test-only-credential");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/operator/private/model", "private/../model", "org/model?token=dummy",
            "https://private.invalid/model", "C:\\operator\\private\\model"})
    void invalidCustomEndpointAndUnsafeModelValueDoNotLeakDetails(String model) {
        ReflectionTestUtils.setField(config, "llmProviderConfig", "CUSTOM_OPENAI");
        ReflectionTestUtils.setField(config, "customLlmUrl", "https://user:dummy@private.invalid/chat/completions");
        ReflectionTestUtils.setField(config, "customLlmModel", model);

        var analysis = information.snapshot().analysis();

        assertThat(analysis.state().name()).isEqualTo("UNAVAILABLE");
        assertThat(analysis.mode().name()).isEqualTo("OPERATOR_ENDPOINT");
        assertThat(analysis.model()).isNull();
        assertThat(analysis.toString()).doesNotContain("/operator", "private", "dummy");
    }

    @Test
    void builtinHttpConfigurationIdentifiesTheAnalysisModelWithNoCredentialProbe() {
        ReflectionTestUtils.setField(config, "llmProviderConfig", "OPENAI");

        var analysis = information.snapshot().analysis();

        assertThat(analysis.state().name()).isEqualTo("UNAVAILABLE");
        assertThat(analysis.mode().name()).isEqualTo("PROVIDER_HTTP");
        assertThat(analysis.provider()).isEqualTo("OPENAI");
        assertThat(analysis.model()).isEqualTo("gpt-4o-mini");
    }

    @Test
    void embeddedAnalysisRefersToTheEncoderWithoutPromotingAnUnloadedProfileToAModel() {
        ReflectionTestUtils.setField(config, "llmProviderConfig", "LOCAL_ONNX");
        ReflectionTestUtils.setField(embeddings, "embeddingEnabled", true);

        var snapshot = information.snapshot();

        assertThat(snapshot.embedding().state().name()).isEqualTo("NOT_LOADED");
        assertThat(snapshot.analysis().mode().name()).isEqualTo("EMBEDDED_ONNX");
        assertThat(snapshot.analysis().state().name()).isEqualTo("CONFIGURED");
        assertThat(snapshot.analysis().model()).isNull();
    }

    @Test
    void deterministicMockModeCanBeReadEvenWithInvalidProviderConfiguration() {
        ReflectionTestUtils.setField(config, "llmMock", true);
        ReflectionTestUtils.setField(config, "llmProviderConfig", "unregistered-fixture");

        var analysis = information.snapshot().analysis();

        assertThat(analysis.state().name()).isEqualTo("CONFIGURED");
        assertThat(analysis.provider()).isEqualTo("MOCK");
        assertThat(analysis.mode().name()).isEqualTo("MOCK_REPLAY");
        assertThat(analysis.model()).isEqualTo("taxonomy-deterministic-v1");
    }
}
