package com.taxonomy.architecture.pipeline;

import java.io.Serializable;

/**
 * Serializable metadata descriptor for a single {@link ArchitecturePipelineStep}.
 *
 * <p>Used by {@link ArchitecturePipelineStepRegistry} to expose step metadata
 * without requiring access to the step implementation itself.
 *
 * @param id               stable, kebab-case step identifier (e.g. {@code "leaf-enrichment"})
 * @param order            numeric order value; lower values run first
 * @param enabledByDefault {@code true} when the step is active in the default pipeline
 * @param coreInvariant    {@code true} when the step enforces a structural invariant that
 *                         must not be bypassed (e.g. anchor selection, node-limit truncation).
 *                         {@code false} marks safe extension points where alternate
 *                         implementations may be substituted.
 */
public record ArchitecturePipelineStepDescriptor(
        String id,
        int order,
        boolean enabledByDefault,
        boolean coreInvariant
) implements Serializable {
}
