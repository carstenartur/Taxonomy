package com.taxonomy.export.spi;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/** Export payload returned by an {@link ExportFormatExtension}. */
public record ExportResult(byte[] content) implements Serializable {

    public ExportResult {
        Objects.requireNonNull(content, "content must not be null");
        content = Arrays.copyOf(content, content.length);
    }

    public byte[] bytes() {
        return Arrays.copyOf(content, content.length);
    }

    public String utf8() {
        return new String(content, StandardCharsets.UTF_8);
    }
}
