package com.taxonomy.architecture.pipeline;

import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry for architecture-view pipeline steps.
 *
 * <p>All Spring beans implementing {@link ArchitecturePipelineStep} are collected
 * here automatically.  The registry validates that no two steps share the same
 * {@link ArchitecturePipelineStep#id() ID} or
 * {@link ArchitecturePipelineStep#order() order} value, then stores them sorted
 * by order for deterministic execution.
 *
 * <p>Use {@link #getEnabledSteps()} to obtain the ordered list of steps that
 * should run for each pipeline execution, and {@link #listDescriptors()} to
 * enumerate the metadata of all registered steps.
 */
@Service
public class ArchitecturePipelineStepRegistry {

    private final List<ArchitecturePipelineStep> orderedSteps;

    public ArchitecturePipelineStepRegistry(List<ArchitecturePipelineStep> steps) {
        Map<String, ArchitecturePipelineStep> byId    = new LinkedHashMap<>();
        Map<Integer, ArchitecturePipelineStep> byOrder = new LinkedHashMap<>();

        steps.stream()
                .map(ArchitecturePipelineStepRegistry::validatedStep)
                .sorted(Comparator.comparingInt(ArchitecturePipelineStep::order))
                .forEach(step -> {
                    ArchitecturePipelineStep prevId = byId.putIfAbsent(step.id(), step);
                    if (prevId != null) {
                        throw new IllegalStateException(
                                "Duplicate pipeline step ID '%s' registered by %s and %s"
                                        .formatted(step.id(),
                                                prevId.getClass().getName(),
                                                step.getClass().getName()));
                    }

                    ArchitecturePipelineStep prevOrder = byOrder.putIfAbsent(step.order(), step);
                    if (prevOrder != null) {
                        throw new IllegalStateException(
                                "Duplicate pipeline step order %d registered by %s and %s"
                                        .formatted(step.order(),
                                                prevOrder.getClass().getName(),
                                                step.getClass().getName()));
                    }
                });

        this.orderedSteps = List.copyOf(byId.values());
    }

    /**
     * Returns all registered steps in ascending order, filtered to those
     * where {@link ArchitecturePipelineStep#enabledByDefault()} is {@code true}.
     */
    public List<ArchitecturePipelineStep> getEnabledSteps() {
        return orderedSteps.stream()
                .filter(ArchitecturePipelineStep::enabledByDefault)
                .toList();
    }

    /**
     * Returns the descriptors of all registered steps in ascending order,
     * including steps that are not enabled by default.
     */
    public List<ArchitecturePipelineStepDescriptor> listDescriptors() {
        return orderedSteps.stream()
                .map(ArchitecturePipelineStep::descriptor)
                .toList();
    }

    // --- Validation helpers -------------------------------------------

    private static ArchitecturePipelineStep validatedStep(ArchitecturePipelineStep step) {
        if (step == null) {
            throw new IllegalStateException(
                    "A null ArchitecturePipelineStep was passed to the registry");
        }
        String id = step.id();
        if (id == null || id.isBlank()) {
            throw new IllegalStateException(
                    "Pipeline step %s must declare a non-blank ID"
                            .formatted(step.getClass().getName()));
        }
        return step;
    }
}
