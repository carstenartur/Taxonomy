package com.taxonomy.templates;

/**
 * Produces a deterministic, non-sensitive report that exercises the active
 * document template without coupling template administration to the architecture package.
 */
@FunctionalInterface
public interface DocumentTemplateReportPreview {

    byte[] renderPreview();
}
