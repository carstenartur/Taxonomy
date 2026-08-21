package com.taxonomy.extension.api.report;

import com.taxonomy.dto.ArchitectureReport;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable input for a report renderer extension.
 *
 * <p>The original architecture-report accessor is retained for source compatibility.
 * New report families use {@link #ofPayload(Object)} and obtain their typed model with
 * {@link #payloadAs(Class)}. This keeps the extension API framework-free while allowing
 * more than one report family to share the same renderer registry.</p>
 */
@SuppressWarnings("serial")
public final class ReportRenderContext implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Object payload;
    private final Map<String, Object> options;

    /**
     * Source-compatible constructor retained from the original record API.
     */
    public ReportRenderContext(
            ArchitectureReport report,
            Map<String, Object> options) {
        this((Object) report, options);
    }

    private ReportRenderContext(
            Object payload,
            Map<String, Object> options) {
        this.payload = Objects.requireNonNull(payload, "report payload must not be null");
        this.options = options == null ? Map.of() : Map.copyOf(options);
    }

    /**
     * Backward-compatible accessor for the built-in architecture report family.
     */
    public ArchitectureReport report() {
        return payloadAs(ArchitectureReport.class);
    }

    /** Returns the untyped report payload for generic infrastructure. */
    public Object payload() {
        return payload;
    }

    /** Returns the immutable renderer options. */
    public Map<String, Object> options() {
        return options;
    }

    /**
     * Requires the payload to have the expected report-model type.
     *
     * @throws IllegalArgumentException when a renderer is invoked with the wrong model
     */
    public <T> T payloadAs(Class<T> expectedType) {
        Objects.requireNonNull(expectedType, "expectedType must not be null");
        if (!expectedType.isInstance(payload)) {
            throw new IllegalArgumentException(
                    "Report renderer expected " + expectedType.getName()
                            + " but received " + payload.getClass().getName());
        }
        return expectedType.cast(payload);
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof ReportRenderContext context
                && payload.equals(context.payload)
                && options.equals(context.options);
    }

    @Override
    public int hashCode() {
        return Objects.hash(payload, options);
    }

    @Override
    public String toString() {
        return "ReportRenderContext[payload=" + payload + ", options=" + options + "]";
    }

    /** Creates a context for the original architecture-report family. */
    public static ReportRenderContext of(ArchitectureReport report) {
        return new ReportRenderContext(report, Map.of());
    }

    /** Creates a context for any registered report family. */
    public static ReportRenderContext ofPayload(Object payload) {
        return new ReportRenderContext(payload, Map.of());
    }

    /** Creates a context for any report family with renderer-specific options. */
    public static ReportRenderContext ofPayload(
            Object payload,
            Map<String, Object> options) {
        return new ReportRenderContext(payload, options);
    }
}
