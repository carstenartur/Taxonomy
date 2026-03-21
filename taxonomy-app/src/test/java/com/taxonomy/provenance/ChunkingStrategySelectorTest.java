package com.taxonomy.provenance;

import com.taxonomy.dto.ChunkingStrategy;
import com.taxonomy.provenance.service.ChunkingStrategySelector;
import com.taxonomy.provenance.service.DocumentParserService;
import com.taxonomy.shared.service.LocalEmbeddingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ChunkingStrategySelector}.
 */
class ChunkingStrategySelectorTest {

    private ChunkingStrategySelector selector;
    private LocalEmbeddingService embeddingService;

    @BeforeEach
    void setUp() {
        embeddingService = mock(LocalEmbeddingService.class);
        selector = new ChunkingStrategySelector(new DocumentParserService(), embeddingService);
    }

    @Test
    void structuralWhenManyHeadings() {
        String text = "Chapter 1 Overview\n\n" +
                "Content of chapter one is long enough to pass the minimum filter for extraction.\n\n" +
                "Section 2 Details\n\n" +
                "Content of section two is long enough to pass the minimum filter for extraction.\n\n" +
                "§ 3 Datenschutz\n\n" +
                "Content of paragraph three is long enough to pass minimum filter for extraction.\n\n" +
                "Artikel 4 Implementation\n\n" +
                "Content of article four is long enough to pass the minimum filter for extraction.";

        assertThat(selector.selectStrategy(text)).isEqualTo(ChunkingStrategy.STRUCTURAL);
    }

    @Test
    void semanticWhenFewHeadingsAndEmbeddingAvailable() {
        when(embeddingService.isAvailable()).thenReturn(true);

        String text = "§ 1 Allgemeines\n\n" +
                "Content paragraph one is long enough to pass the minimum filter for extraction.\n\n" +
                "Content paragraph two is long enough to pass the minimum filter for extraction.";

        assertThat(selector.selectStrategy(text)).isEqualTo(ChunkingStrategy.SEMANTIC);
    }

    @Test
    void paragraphBasedWhenFewHeadingsAndNoEmbedding() {
        when(embeddingService.isAvailable()).thenReturn(false);

        String text = "§ 1 Allgemeines\n\n" +
                "Content paragraph is long enough to pass the minimum filter for extraction.";

        assertThat(selector.selectStrategy(text)).isEqualTo(ChunkingStrategy.PARAGRAPH_BASED);
    }

    @Test
    void nullTextReturnsParagraphBased() {
        assertThat(selector.selectStrategy(null)).isEqualTo(ChunkingStrategy.PARAGRAPH_BASED);
    }

    @Test
    void emptyTextReturnsParagraphBased() {
        assertThat(selector.selectStrategy("")).isEqualTo(ChunkingStrategy.PARAGRAPH_BASED);
    }

    @Test
    void countHeadingsDetectsVariousFormats() {
        // Test indirectly through selectStrategy — with 3+ headings should return STRUCTURAL
        String text = "Chapter 1 Intro\n\n" +
                "Body text is long enough to be considered substantial for testing purposes.\n\n" +
                "REQUIREMENTS\n\n" +
                "Body text is long enough to be considered substantial for testing purposes.\n\n" +
                "[H1] Summary\n\n" +
                "Body text is long enough to be considered substantial for testing purposes.";

        assertThat(selector.selectStrategy(text)).isEqualTo(ChunkingStrategy.STRUCTURAL);
    }
}
