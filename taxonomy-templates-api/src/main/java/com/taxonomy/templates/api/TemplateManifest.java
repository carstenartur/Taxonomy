package com.taxonomy.templates.api;

/** Public, repository-independent template contract. */
public record TemplateManifest(
        int schemaVersion,
        String templateId,
        String displayName,
        String fileName,
        String mediaType,
        String updatedAt,
        String updatedBy,
        long uncompressedSize,
        int partCount,
        String packageSha256) {
}
