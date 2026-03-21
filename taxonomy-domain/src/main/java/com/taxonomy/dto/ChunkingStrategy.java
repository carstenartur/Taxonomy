package com.taxonomy.dto;

/**
 * Strategy for splitting a document into chunks.
 *
 * <ul>
 *   <li>{@link #STRUCTURAL} — heading-based hierarchical splitting (preferred
 *       for well-structured documents with ≥3 recognisable headings)</li>
 *   <li>{@link #SEMANTIC} — embedding-cosine-distance-based splitting (fallback
 *       when headings are scarce but the embedding service is available)</li>
 *   <li>{@link #PARAGRAPH_BASED} — simple paragraph-boundary splitting (last-resort
 *       fallback)</li>
 * </ul>
 */
public enum ChunkingStrategy {
    STRUCTURAL,
    SEMANTIC,
    PARAGRAPH_BASED
}
