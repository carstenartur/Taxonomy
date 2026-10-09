package com.taxonomy.templates.api;

import java.util.Map;

/** Public, repository-independent template contract. */
public record TemplateDiff(
        String templateId,
        String fromRevision,
        String toRevision,
        Map<String, PartChange> changes) {
}
