package com.taxonomy.templates;

import com.taxonomy.templates.api.TemplateContribution;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Validate the whole set before any template is read or written. */
final class TemplateContributions {
    private TemplateContributions() {}

    static Map<String, TemplateContribution> index(List<TemplateContribution> contributions) {
        var indexed = new LinkedHashMap<String, TemplateContribution>();
        for (var contribution : List.copyOf(Objects.requireNonNull(contributions, "contributions"))) {
            if (indexed.putIfAbsent(contribution.templateId(), contribution) != null) {
                throw new IllegalStateException("Duplicate template contribution for " + contribution.templateId());
            }
        }
        return Collections.unmodifiableMap(indexed);
    }
}
