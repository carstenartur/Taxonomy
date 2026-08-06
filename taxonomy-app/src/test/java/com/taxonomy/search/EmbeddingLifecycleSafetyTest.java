package com.taxonomy.search;

import com.taxonomy.shared.config.SpringContextHolder;
import com.taxonomy.shared.service.LocalEmbeddingService;
import org.hibernate.search.engine.backend.document.DocumentElement;
import org.hibernate.search.engine.backend.document.IndexFieldReference;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EmbeddingLifecycleSafetyTest {

    @Test
    void applicationDefaultsRequireExplicitEmbeddingAndDownloadOptIn() throws Exception {
        Properties properties = new Properties();
        try (InputStream stream = getClass().getResourceAsStream("/application.properties")) {
            assertThat(stream).isNotNull();
            properties.load(stream);
        }

        assertThat(properties.getProperty("embedding.enabled"))
                .isEqualTo("${TAXONOMY_EMBEDDING_ENABLED:false}");
        assertThat(properties.getProperty("embedding.allow-download"))
                .isEqualTo("${TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD:false}");
    }

    @SuppressWarnings("unchecked")
    @Test
    void hibernateSearchBridgeDoesNotBuildTextOrEmbedWhenDisabled() throws Exception {
        LocalEmbeddingService embeddingService = mock(LocalEmbeddingService.class);
        when(embeddingService.isEnabled()).thenReturn(false);

        GenericApplicationContext context = new GenericApplicationContext();
        context.getBeanFactory().registerSingleton("localEmbeddingService", embeddingService);
        context.refresh();
        new SpringContextHolder().setApplicationContext(context);

        DocumentElement target = mock(DocumentElement.class);
        IndexFieldReference<float[]> field = mock(IndexFieldReference.class);
        AtomicBoolean textBuilt = new AtomicBoolean(false);

        try {
            assertThatCode(() -> EmbeddingBridgeSupport.writeEmbedding(
                    target, field, "entity", entity -> {
                        textBuilt.set(true);
                        return entity;
                    })).doesNotThrowAnyException();

            assertThat(textBuilt).isFalse();
            verify(embeddingService).isEnabled();
            verify(embeddingService, never()).isAvailable();
            verify(embeddingService, never()).embed(anyString());
            verifyNoInteractions(target);
        } finally {
            context.close();
        }
    }
}
