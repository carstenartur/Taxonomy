package com.taxonomy.dto;

/**
 * Represents a match between a regulation paragraph and an architecture taxonomy node.
 *
 * @param nodeCode     the taxonomy node code (e.g. "CP-1023")
 * @param linkType     the relationship type: MANDATES, REQUIRES, ENABLES, CONSTRAINS, or REFERENCES
 * @param confidence   the match confidence (0.0–1.0)
 * @param paragraphRef the source paragraph reference in the regulation (e.g. "§ 4 Abs. 2")
 * @param reason       a brief justification (1–2 sentences)
 */
public record RegulationArchitectureMatch(
        String nodeCode,
        String linkType,
        double confidence,
        String paragraphRef,
        String reason
) {}
