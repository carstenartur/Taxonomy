package com.taxonomy.templates.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Public, repository-independent template contract. */
public record TemplateDiff(
        String templateId,
        String fromRevision,
        String toRevision,
        Map<String, PartChange> changes) {
    public TemplateDiff {
        changes = Collections.unmodifiableMap(new LinkedHashMap<>(changes));
    }
}
