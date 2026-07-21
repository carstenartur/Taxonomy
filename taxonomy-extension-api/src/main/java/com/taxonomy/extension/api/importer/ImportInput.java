package com.taxonomy.extension.api.importer;

import java.io.InputStream;
import java.util.Objects;

/** Input stream and optional target branch for a framework import. */
public record ImportInput(InputStream inputStream, String branch) {

    public ImportInput {
        inputStream = Objects.requireNonNull(inputStream, "inputStream must not be null");
    }

    public static ImportInput forPreview(InputStream inputStream) {
        return new ImportInput(inputStream, null);
    }
}
