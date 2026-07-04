package com.taxonomy.architecture.pipeline;

import com.taxonomy.shared.extension.TaxonomyExtension;

/**
 * Extension SPI for architecture-view pipeline steps.
 *
 * <p>Each implementation encapsulates one deterministic transformation of the
 * {@link ArchitectureViewContext}, such as anchor selection, relevance propagation,
 * or leaf enrichment.  Implementations are registered as Spring beans and
 * discovered automatically by {@link ArchitecturePipelineStepRegistry}.
 *
 * <h2>Step contracts</h2>
 * <ul>
 *   <li><b>Core invariants</b> — steps with {@code coreInvariant = true} in their
 *       descriptor enforce structural requirements of the pipeline (valid anchors,
 *       valid elements, node-count limits).  Their ordering and semantics must not
 *       be changed; replacing or disabling them will break the pipeline output.</li>
 *   <li><b>Safe extension points</b> — steps with {@code coreInvariant = false}
 *       may be augmented by adding a new implementation that declares
 *       an {@link #order()} value adjacent to the step it extends. Replacing a
 *       default step requires changing bean registration so only one implementation
 *       occupies each unique order/ID slot, and the replacement must still leave
 *       the context in a consistent state for subsequent steps.</li>
 * </ul>
 *
 * <h2>Default step sequence</h2>
 * <ol>
 *   <li>100 {@code anchor-selection} — <em>core invariant</em></li>
 *   <li>200 {@code relevance-propagation} — <em>core invariant</em></li>
 *   <li>300 {@code element-build} — <em>core invariant</em></li>
 *   <li>400 {@code leaf-enrichment} — safe extension point</li>
 *   <li>500 {@code relationship-build} — <em>core invariant</em></li>
 *   <li>600 {@code provisional-relation} — safe extension point</li>
 *   <li>700 {@code node-limit} — <em>core invariant</em></li>
 *   <li>800 {@code impact-relation} — safe extension point</li>
 *   <li>900 {@code scoring-trace} — safe extension point</li>
 *   <li>1000 {@code impact-selection} — safe extension point</li>
 * </ol>
 *
 * <p>Example:
 * <pre>{@code
 * @Service
 * public class MyCustomEnrichmentStep implements ArchitecturePipelineStep {
 *
 *     @Override
 *     public String id() { return "my-custom-enrichment"; }
 *
 *     @Override
 *     public int order() { return 450; }  // runs between leaf-enrichment (400) and relationship-build (500)
 *
 *     @Override
 *     public void apply(ArchitectureViewContext ctx) {
 *         // read and write ctx as needed
 *     }
 * }
 * }</pre>
 *
 * @see ArchitecturePipelineStepRegistry
 * @see ArchitecturePipelineStepDescriptor
 * @see ArchitectureViewPipeline
 */
public interface ArchitecturePipelineStep extends TaxonomyExtension {

    /**
     * Returns the stable, kebab-case identifier for this step (e.g. {@code "leaf-enrichment"}).
     * Must be unique across all registered steps.
     */
    String id();

    /**
     * Returns the numeric order value. Steps are executed in ascending order.
     * Must be unique across all registered steps.
     */
    int order();

    /**
     * Returns {@code true} when this step is part of the default pipeline.
     * Defaults to {@code true}; override to {@code false} for opt-in steps.
     */
    default boolean enabledByDefault() {
        return true;
    }

    /**
     * Applies this pipeline step to the given context.
     * Implementations may read from and write to any field in {@code ctx}.
     */
    void apply(ArchitectureViewContext ctx);

    /**
     * Returns the serializable descriptor for this step, derived from the
     * other interface methods.  Override for custom descriptor values.
     */
    default ArchitecturePipelineStepDescriptor descriptor() {
        return new ArchitecturePipelineStepDescriptor(id(), order(), enabledByDefault(), false);
    }
}
