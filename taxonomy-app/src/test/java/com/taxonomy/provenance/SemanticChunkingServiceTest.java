package com.taxonomy.provenance;

import com.taxonomy.provenance.service.SemanticChunkingService;
import com.taxonomy.shared.service.LocalEmbeddingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SemanticChunkingService}.
 */
class SemanticChunkingServiceTest {

    private SemanticChunkingService chunkingService;
    private LocalEmbeddingService embeddingService;

    @BeforeEach
    void setUp() {
        embeddingService = mock(LocalEmbeddingService.class);
        chunkingService = new SemanticChunkingService(embeddingService);
    }

    @Test
    void emptyTextReturnsEmptyList() {
        List<String> chunks = chunkingService.chunk("");
        assertThat(chunks).isEmpty();
    }

    @Test
    void nullTextReturnsEmptyList() {
        List<String> chunks = chunkingService.chunk(null);
        assertThat(chunks).isEmpty();
    }

    @Test
    void shortTextReturnsSingleChunkWhenEmbeddingUnavailable() {
        when(embeddingService.isAvailable()).thenReturn(false);

        String text = "This is a short document. It has only a few sentences. " +
                "Not enough for semantic chunking.";
        List<String> chunks = chunkingService.chunk(text);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).contains("short document");
    }

    @Test
    void fewSentencesReturnsSingleChunk() {
        when(embeddingService.isAvailable()).thenReturn(true);

        // Only 3 sentences — below the default window size of 5
        String text = "First sentence. Second sentence. Third sentence.";
        List<String> chunks = chunkingService.chunk(text);

        assertThat(chunks).hasSize(1);
    }

    @Test
    void fallsBackOnEmbeddingException() throws Exception {
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(anyString())).thenThrow(new RuntimeException("model error"));

        // Build a text with enough sentences
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append("Sentence number ").append(i).append(" is important. ");
        }

        List<String> chunks = chunkingService.chunk(sb.toString());

        // Should fall back to single chunk
        assertThat(chunks).hasSize(1);
    }

    @Test
    void producesMultipleChunksWithDistinctEmbeddings() throws Exception {
        when(embeddingService.isAvailable()).thenReturn(true);

        // Create embeddings that simulate a topic shift
        // First 5 windows: similar embeddings (topic A)
        // Then a clear distance peak
        // Next 5 windows: different embeddings (topic B)
        float[] topicA = {1.0f, 0.0f, 0.0f};
        float[] topicB = {0.0f, 1.0f, 0.0f};
        when(embeddingService.embed(anyString()))
                .thenReturn(topicA, topicA, topicA, topicA, topicA,
                        topicB, topicB, topicB, topicB, topicB);

        // Build text with 14 sentences (14-5+1=10 windows)
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 14; i++) {
            sb.append("Sentence number ").append(i).append(" has content. ");
        }

        List<String> chunks = chunkingService.chunk(sb.toString());

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
    }
}
