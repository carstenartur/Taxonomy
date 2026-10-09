package com.taxonomy.templates.api;

/** Public, repository-independent template contract. */
public record TemplatePartView(
        String path,
        long size,
        String mediaType,
        String textContent) {
}
