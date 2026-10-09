package com.taxonomy.analysis.service;

import com.taxonomy.extension.api.llm.ProviderId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.*;

class AiTargetCatalogServiceTest {
    @ParameterizedTest @CsvSource({
            "' -- Model A -- ',model-a", "'MODEL_v1.2',model_v1.2", "'---',unspecified",
            "'!!model!!!name??',model-name", "'a--b',a--b"})
    void externalModelsKeepTheirStableTargetIdentifiers(String model, String expected) {
        assertThat(catalog(model).describeProvider("EXAMPLE").targetId()).isEqualTo("example:" + expected);
    }

    @Test void longInternalHyphenRunHasBoundedProcessingTime() {
        String model = "a" + "-".repeat(200_000) + "b";
        var catalog = catalog(model);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertThat(catalog.describeProvider("EXAMPLE").targetId()).isEqualTo("example:" + model));
    }

    private static AiTargetCatalogService catalog(String model) {
        var providers = mock(LlmProviderConfig.class);
        var id = new ProviderId("EXAMPLE");
        when(providers.registeredProviderIds()).thenReturn(List.of(id));
        when(providers.isProviderConfigured(id)).thenReturn(true);
        when(providers.getOpenAiCompatibleModel(id)).thenReturn(model);
        when(providers.getOpenAiCompatibleUrl(id)).thenReturn("https://provider.example/api");
        return new AiTargetCatalogService(providers);
    }
}
