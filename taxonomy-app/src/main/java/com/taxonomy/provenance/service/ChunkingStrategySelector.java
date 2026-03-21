package com.taxonomy.provenance.service;

import com.taxonomy.dto.ChunkingStrategy;
import com.taxonomy.shared.service.LocalEmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Selects the best {@link ChunkingStrategy} for a given raw text based on its
 * structural characteristics and the availability of the embedding service.
 *
 * <ul>
 *   <li>{@link ChunkingStrategy#STRUCTURAL} — chosen when the text has ≥3
 *       recognisable headings (sections, paragraphs, articles).</li>
 *   <li>{@link ChunkingStrategy#SEMANTIC} — chosen as a fallback when headings
 *       are scarce but the local embedding service is available.</li>
 *   <li>{@link ChunkingStrategy#PARAGRAPH_BASED} — last-resort fallback when
 *       neither structural nor semantic chunking is feasible.</li>
 * </ul>
 */
@Service
public class ChunkingStrategySelector {

    private static final Logger log = LoggerFactory.getLogger(ChunkingStrategySelector.class);

    /** Minimum number of detected headings for structural chunking. */
    private static final int STRUCTURAL_HEADING_THRESHOLD = 3;

    private final DocumentParserService parserService;
    private final LocalEmbeddingService embeddingService;

    public ChunkingStrategySelector(DocumentParserService parserService,
                                     LocalEmbeddingService embeddingService) {
        this.parserService = parserService;
        this.embeddingService = embeddingService;
    }

    /**
     * Selects the optimal chunking strategy for the given raw text.
     *
     * @param rawText the raw text extracted from a document
     * @return the recommended chunking strategy
     */
    public ChunkingStrategy selectStrategy(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return ChunkingStrategy.PARAGRAPH_BASED;
        }

        int headingCount = countHeadings(rawText);

        if (headingCount >= STRUCTURAL_HEADING_THRESHOLD) {
            log.debug("Selected STRUCTURAL strategy ({} headings detected)", headingCount);
            return ChunkingStrategy.STRUCTURAL;
        }

        if (embeddingService.isAvailable()) {
            log.debug("Selected SEMANTIC strategy ({} headings, embedding available)", headingCount);
            return ChunkingStrategy.SEMANTIC;
        }

        log.debug("Selected PARAGRAPH_BASED strategy ({} headings, embedding unavailable)", headingCount);
        return ChunkingStrategy.PARAGRAPH_BASED;
    }

    /**
     * Counts the number of headings in the raw text by scanning for heading
     * patterns using the parser's detection logic.
     */
    int countHeadings(String rawText) {
        int count = 0;
        String[] paragraphs = rawText.split("\\n\\s*\\n");
        for (String para : paragraphs) {
            String trimmed = para.strip();
            if (!trimmed.isEmpty() && parserService.detectHeading(trimmed) != null) {
                count++;
            }
        }
        return count;
    }
}
