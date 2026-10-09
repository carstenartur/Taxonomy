package com.taxonomy.composition.reformulation;

import com.taxonomy.analysis.service.LlmProvider;
import com.taxonomy.analysis.service.LlmProviderConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.*;

/** Freezing the model identity must not interpret endpoint text as an expensive regex input. */
class ReformulationModelIdentityTest {
    @ParameterizedTest @CsvSource({
            "/v1beta/models/gemini-3-flash-preview:generateContent,gemini-3-flash-preview",
            "/proxy/models/v1/models/custom-model:generateContent,custom-model",
            "/gateway/custom-model:generateContent,/gateway/custom-model"})
    void preservesTheConfiguredModelIdentity(String path, String expected) {
        assertThat(model(path)).isEqualTo(expected);
    }

    @Test void longProxyPathWithoutModelsSegmentHasBoundedProcessingTime() {
        String path = "/gateway/" + "-".repeat(100_000) + ":generateContent";
        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertThat(model(path)).isEqualTo(path.substring(0, path.indexOf(':'))));
    }

    private static String model(String path) {
        var providers = mock(LlmProviderConfig.class);
        when(providers.getGeminiUrl()).thenReturn("https://provider.example" + path);
        var service = new ReformulationExecutionService(null, null, null, providers, null,
                new JsonMapper(), null, null);
        return ReflectionTestUtils.invokeMethod(service, "model", LlmProvider.GEMINI.id());
    }
}
