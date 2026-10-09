package com.taxonomy.templates.api;

/**
 * Current per-template representation metadata. Despite the historic field name,
 * {@code headCommit} is the last commit that changed this template, not the shared
 * repository branch head.
 */
public record TemplateDescriptor(
        String templateId,
        String displayName,
        String fileName,
        String headCommit,
        String updatedAt,
        String updatedBy,
        long uncompressedSize,
        int partCount,
        String packageSha256) {
}
