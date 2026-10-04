package com.taxonomy.analysis.service;

import com.taxonomy.catalog.service.LocalEmbeddingService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class LlmProviderFrozenScopeTest {
    private final LlmProviderConfig config = new LlmProviderConfig(mock(LocalEmbeddingService.class));

    @Test
    void admittedMockCannotFallBackToTheWorkersRealProviderAndScopesRestore() {
        ReflectionTestUtils.setField(config, "llmProviderConfig", "GEMINI");
        config.setRequestProvider(LlmProvider.OPENAI);
        try {
            assertThatThrownBy(() -> {
                try (var ignored = config.withRequestProvider("MOCK")) {
                    assertThat(config.isMockMode()).isTrue();
                    assertThat(config.getActiveProviderName()).isEqualTo("Mock");
                    try (var real = config.withRequestProvider("MISTRAL")) {
                        assertThat(config.isMockMode()).isFalse();
                        assertThat(config.getActiveProvider()).isEqualTo(LlmProvider.MISTRAL);
                    }
                    assertThat(config.isMockMode()).isTrue();
                    throw new IllegalStateException("provider failure");
                }
            }).hasMessage("provider failure");
            assertThat(config.isMockMode()).isFalse();
            assertThat(config.getActiveProvider()).isEqualTo(LlmProvider.OPENAI);
        } finally {
            config.clearRequestProvider();
        }
    }

    @Test
    void frozenRealProviderOverridesWorkerMockConfiguration() {
        ReflectionTestUtils.setField(config, "llmMock", true);
        try (var ignored = config.withRequestProvider("GEMINI")) {
            assertThat(config.isMockMode()).isFalse();
            assertThat(config.getActiveProviderName()).isEqualTo("Gemini");
        }
        assertThat(config.isMockMode()).isTrue();
        assertThatThrownBy(() -> config.withRequestProvider("unknown"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(config.isMockMode()).isTrue();
    }

    @Test
    void concurrentFrozenProvidersRemainThreadConfined() throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var mock = workers.submit(() -> {
                try (var ignored = config.withRequestProvider("MOCK")) {
                    barrier.await(5, TimeUnit.SECONDS);
                    return config.getActiveProviderName();
                }
            });
            var real = workers.submit(() -> {
                try (var ignored = config.withRequestProvider("OPENAI")) {
                    barrier.await(5, TimeUnit.SECONDS);
                    return config.getActiveProviderName();
                }
            });
            assertThat(mock.get(10, TimeUnit.SECONDS)).isEqualTo("Mock");
            assertThat(real.get(10, TimeUnit.SECONDS)).isEqualTo("OpenAI");
        }
        assertThat(config.isMockMode()).isFalse();
    }
}
