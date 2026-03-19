package com.taxonomy.dto;

/**
 * Represents a requirement candidate extracted from document text by AI.
 *
 * @param text       the extracted requirement text (1–3 sentences)
 * @param sectionRef the source section or paragraph reference (e.g. "§ 4 Abs. 2"), or {@code null}
 * @param confidence the extraction confidence (0.0–1.0)
 * @param type       the requirement type: FUNCTIONAL, ORGANIZATIONAL, TECHNICAL, LEGAL, or PROCESS
 */
public record AiExtractedCandidate(
        String text,
        String sectionRef,
        double confidence,
        String type
) {}
