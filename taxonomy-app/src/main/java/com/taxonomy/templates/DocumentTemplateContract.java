package com.taxonomy.templates;

import java.util.Map;

/**
 * Semantic contract for a named OOXML template.
 *
 * <p>The generic DOTX codec validates package safety. Contracts add the report-specific
 * placeholders and structural invariants required by one renderer.</p>
 */
public interface DocumentTemplateContract {

    /** Stable template ID used in the versioned template repository. */
    String templateId();

    /** Validate one already unpacked, safe OOXML package. */
    void validate(Map<String, byte[]> packageParts);
}
