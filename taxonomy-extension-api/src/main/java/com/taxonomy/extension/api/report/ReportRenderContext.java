package com.taxonomy.extension.api.report;

import com.taxonomy.dto.ArchitectureReport;

import java.io.Serializable;
import java.util.Map;
import java.util.Objects;

/** Immutable input for a report renderer extension. */
public record ReportRenderContext(
        ArchitectureReport report,
        Map<String, Object> options) implements Serializable {

    public ReportRenderContext {
        report = Objects.requireNonNull(report, "report must not be null");
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    public static ReportRenderContext of(ArchitectureReport report) {
        return new ReportRenderContext(report, Map.of());
    }
}
