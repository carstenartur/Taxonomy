package com.taxonomy.templates.api;

/** Public, repository-independent template contract. */
public record TemplateRevision(
        String commitId,
        String author,
        String committedAt,
        String message) {
}
