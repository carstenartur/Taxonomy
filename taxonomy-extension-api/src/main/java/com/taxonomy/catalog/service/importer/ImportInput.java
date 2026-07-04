package com.taxonomy.catalog.service.importer;

import java.io.InputStream;

/**
 * Encapsulates the input data required for a framework import operation.
 *
 * @param inputStream the uploaded file content
 * @param branch      target branch for materialization; may be {@code null} for preview-only calls
 */
public record ImportInput(InputStream inputStream, String branch) {

    /**
     * Convenience factory for preview calls where no branch is needed.
     */
    public static ImportInput forPreview(InputStream inputStream) {
        return new ImportInput(inputStream, null);
    }
}
