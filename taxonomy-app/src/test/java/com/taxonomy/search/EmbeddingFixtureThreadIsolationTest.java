package com.taxonomy.search;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Background mass indexing may use the globally registered fixture while its own index is built. */
class EmbeddingFixtureThreadIsolationTest {
    @Test void anotherIndexingThreadCannotRetagTheVectorAwaitingItsModelIdentity() throws Exception {
        var embeddings = new EmbeddingModelKnnIsolationTest.FixtureEmbeddingService();
        embeddings.embedDocument("Current profile.\n");
        String currentIdentity = embeddings.embeddingIndexKey();
        assertThat(currentIdentity).isEqualTo("fixture-current-model-sha256-and-contract");

        // Force the precise interleaving between the bridge's embedDocument and embeddingIndexKey calls.
        try (var background = Executors.newSingleThreadExecutor()) {
            var otherIdentity = background.submit(() -> {
                embeddings.embedDocument("Wrong profile.\n");
                return embeddings.embeddingIndexKey();
            }).get(5, TimeUnit.SECONDS);
            assertThat(otherIdentity).isEqualTo("fixture-other-model-sha256-and-contract");
            assertThat(embeddings.embeddingIndexKey()).isEqualTo(currentIdentity);
        }
    }
}
