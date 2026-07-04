package com.taxonomy.architecture.report;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Rendered report payload.
 *
 * @param content rendered bytes
 */
public record ReportRenderResult(byte[] content) implements Serializable {

    public ReportRenderResult {
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
