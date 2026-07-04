package com.taxonomy.export.service;

import com.taxonomy.diagram.DiagramModel;

import java.io.Serializable;
import java.util.Map;
import java.util.Objects;

/**
 * Context passed to {@link ExportFormatExtension#export(ExportContext)}.
 *
 * <p>Carries the projected {@link DiagramModel} and optional renderer-specific options
 * (e.g. {@code "locale"} for Mermaid label localization).
 *
 * @param diagram the projected diagram model
 * @param options optional format-specific options (immutable copy)
 */
public record ExportContext(
        DiagramModel diagram,
        Map<String, Object> options) implements Serializable {

    public ExportContext {
        diagram = Objects.requireNonNull(diagram, "diagram must not be null");
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    /**
     * Creates a context with no extra options.
     */
    public static ExportContext of(DiagramModel diagram) {
        return new ExportContext(diagram, Map.of());
    }
}
