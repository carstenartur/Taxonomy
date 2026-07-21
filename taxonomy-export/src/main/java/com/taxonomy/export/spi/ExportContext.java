package com.taxonomy.export.spi;

import com.taxonomy.diagram.DiagramModel;

import java.io.Serializable;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable input passed to a diagram export extension.
 *
 * @param diagram projected, format-neutral diagram model
 * @param options optional renderer-specific options
 */
public record ExportContext(
        DiagramModel diagram,
        Map<String, Object> options) implements Serializable {

    public ExportContext {
        diagram = Objects.requireNonNull(diagram, "diagram must not be null");
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    public static ExportContext of(DiagramModel diagram) {
        return new ExportContext(diagram, Map.of());
    }
}
