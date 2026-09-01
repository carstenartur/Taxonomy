package com.taxonomy.extension.api.report;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/**
 * Rendered report payload plus immutable, format-neutral artifact metadata.
 *
 * <p>Metadata describes the exact artifact produced by the renderer. It is not an
 * unrestricted HTTP-header map; protocol adapters must explicitly allow-list any
 * values they expose.</p>
 */
public record ReportRenderResult(
        byte[] content,
        Map<String, String> artifactMetadata) implements Serializable {

    /** Source- and binary-compatible constructor for renderers without metadata. */
    public ReportRenderResult(byte[] content) {
        this(content, Map.of());
    }

    public ReportRenderResult {
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(
                artifactMetadata, "artifactMetadata must not be null");
        content = Arrays.copyOf(content, content.length);
        artifactMetadata = Map.copyOf(artifactMetadata);
    }

    /** Record accessor with the same defensive-copy contract as {@link #bytes()}. */
    public byte[] content() {
        return bytes();
    }

    public byte[] bytes() {
        return Arrays.copyOf(content, content.length);
    }

    public String utf8() {
        return new String(content, StandardCharsets.UTF_8);
    }
}
