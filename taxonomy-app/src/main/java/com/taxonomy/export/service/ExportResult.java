package com.taxonomy.export.service;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Export payload returned by {@link ExportFormatExtension#export(ExportContext)}.
 *
 * @param content the raw export bytes
 */
public record ExportResult(byte[] content) implements Serializable {

    public ExportResult {
        Objects.requireNonNull(content, "content must not be null");
        content = Arrays.copyOf(content, content.length);
    }

    /**
     * Returns a defensive copy of the content bytes.
     */
    public byte[] bytes() {
        return Arrays.copyOf(content, content.length);
    }

    /**
     * Decodes the content as a UTF-8 string (for text-based formats).
     */
    public String utf8() {
        return new String(content, StandardCharsets.UTF_8);
    }
}
