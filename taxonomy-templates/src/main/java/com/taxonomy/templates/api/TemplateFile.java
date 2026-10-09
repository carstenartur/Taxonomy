package com.taxonomy.templates.api;

import java.time.Instant;
import java.util.Objects;

/** Public, repository-independent template contract. */
public record TemplateFile(
        TemplateManifest manifest,
        String commitId,
        byte[] content,
        Instant lastModified) {
    public TemplateFile {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(commitId, "commitId");
        content = content.clone();
    }
    @Override public byte[] content() { return content.clone(); }
    public String etag() { return "\"" + commitId + "\""; }
}
