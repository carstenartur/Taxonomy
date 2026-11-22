package com.taxonomy.export.service;

import com.taxonomy.export.AnalysisDiagramSource;
import com.taxonomy.visio.VisioProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Server export identity for unsaved source; independent of optional application features. */
@Component
public final class VisioWorkingViewMetadata {
    private final BuildProperties buildProperties;
    private final String fallbackVersion;
    private final Clock clock;

    @Autowired
    public VisioWorkingViewMetadata(ObjectProvider<BuildProperties> buildProperties,
            @Value("${app.display-version:${info.app.version:unknown}}") String fallbackVersion) {
        this(buildProperties.getIfAvailable(), fallbackVersion, Clock.systemUTC());
    }

    /** Explicit clock makes a given working-view export context reproducible. */
    public VisioWorkingViewMetadata(BuildProperties buildProperties, String fallbackVersion, Clock clock) {
        this.buildProperties = buildProperties;
        this.fallbackVersion = normalized(fallbackVersion, "unknown");
        this.clock = Objects.requireNonNull(clock, "export clock");
    }

    static Map<String, VisioProperty> document(String originalText, VisioWorkingViewMetadata context) {
        return document(originalText, context, null);
    }

    static Map<String, VisioProperty> document(String originalText, VisioWorkingViewMetadata context,
            AnalysisDiagramSource analysis) {
        var properties = new LinkedHashMap<String, VisioProperty>();
        if (context != null) {
            properties.put("taxonomy.generatorName", VisioProperty.text("Taxonomy Architecture Analyzer"));
            properties.put("taxonomy.generatorVersion", VisioProperty.text(context.buildProperties == null
                    ? context.fallbackVersion : normalized(context.buildProperties.getVersion(), context.fallbackVersion)));
            properties.put("taxonomy.exportedAt", VisioProperty.text(context.clock.instant()));
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.isAuthenticated()
                    && !(authentication instanceof AnonymousAuthenticationToken)
                    && authentication.getName() != null && !authentication.getName().isBlank()) {
                properties.put("taxonomy.exportedBy", VisioProperty.text(authentication.getName().strip()));
            }
        }
        // An operator public base cannot establish an editing target for unsaved client data.
        properties.put("taxonomy.requirementUrlStatus", VisioProperty.text(
                "Unavailable: this working view has no saved requirement editing target."));
        properties.put("taxonomy.requirementTextProvenance", VisioProperty.text(
                analysis == null ? "Client-provided working source; no saved requirement version authority."
                        : "Server-side requirement analysis."));
        if (analysis != null) {
            if (analysis.provider() != null) properties.put("taxonomy.provider", VisioProperty.text(analysis.provider()));
            if (analysis.status() != null) properties.put("taxonomy.analysisStatus", VisioProperty.text(analysis.status()));
            if (analysis.branch() != null) properties.put("taxonomy.branch", VisioProperty.text(analysis.branch()));
            if (analysis.commit() != null) properties.put("taxonomy.commit", VisioProperty.text(analysis.commit()));
        }
        if (originalText != null) properties.put("taxonomy.requirementText", VisioProperty.text(originalText));
        return properties;
    }

    private static String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
