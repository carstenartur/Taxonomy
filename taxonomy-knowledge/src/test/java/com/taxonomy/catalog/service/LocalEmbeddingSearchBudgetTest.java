package com.taxonomy.catalog.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The ANN exploration budget is not the response limit or a relevance threshold. */
class LocalEmbeddingSearchBudgetTest {
    @Test
    void smallResultPagesUseASeparateCandidatePool() {
        assertEquals(100, LocalEmbeddingService.semanticCandidateCount(1));
        assertEquals(100, LocalEmbeddingService.semanticCandidateCount(10));
        assertEquals(200, LocalEmbeddingService.semanticCandidateCount(20));
    }

    @Test
    void additionalExplorationIsBoundedAndNeverDropsRequestedResults() {
        assertEquals(1000, LocalEmbeddingService.semanticCandidateCount(100));
        assertEquals(1000, LocalEmbeddingService.semanticCandidateCount(1000));
        assertEquals(1001, LocalEmbeddingService.semanticCandidateCount(1001));
        assertEquals(Integer.MAX_VALUE, LocalEmbeddingService.semanticCandidateCount(Integer.MAX_VALUE));
    }

    @Test
    void invalidCandidateRequestsFailRatherThanOverflowOrRunInference() {
        assertThrows(IllegalArgumentException.class, () -> LocalEmbeddingService.semanticCandidateCount(0));
        assertThrows(IllegalArgumentException.class, () -> LocalEmbeddingService.semanticCandidateCount(-1));
        assertThrows(IllegalArgumentException.class, () -> LocalEmbeddingService.semanticCandidateCount(Integer.MIN_VALUE));
    }
}
