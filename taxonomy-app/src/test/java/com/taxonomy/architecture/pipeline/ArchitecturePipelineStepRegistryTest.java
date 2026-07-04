package com.taxonomy.architecture.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ArchitecturePipelineStepRegistry}.
 *
 * <p>No Spring context required — the registry is a plain Java class.
 */
class ArchitecturePipelineStepRegistryTest {

    // ── Happy-path: ordering and metadata ────────────────────────────────────

    @Test
    void registryOrdersStepsByOrderAscending() {
        ArchitecturePipelineStep stepA = stubStep("step-a", 200);
        ArchitecturePipelineStep stepB = stubStep("step-b", 100);
        ArchitecturePipelineStep stepC = stubStep("step-c", 300);

        ArchitecturePipelineStepRegistry registry =
                new ArchitecturePipelineStepRegistry(List.of(stepA, stepB, stepC));

        List<ArchitecturePipelineStep> enabled = registry.getEnabledSteps();

        assertThat(enabled).extracting(ArchitecturePipelineStep::id)
                .containsExactly("step-b", "step-a", "step-c");
    }

    @Test
    void registryReturnsDescriptorsInOrder() {
        ArchitecturePipelineStep step1 = stubStep("alpha", 10);
        ArchitecturePipelineStep step2 = stubStep("beta",  20);

        ArchitecturePipelineStepRegistry registry =
                new ArchitecturePipelineStepRegistry(List.of(step2, step1));

        List<ArchitecturePipelineStepDescriptor> descriptors = registry.listDescriptors();

        assertThat(descriptors).extracting(ArchitecturePipelineStepDescriptor::id)
                .containsExactly("alpha", "beta");
        assertThat(descriptors).extracting(ArchitecturePipelineStepDescriptor::order)
                .containsExactly(10, 20);
    }

    @Test
    void getEnabledStepsExcludesDisabledSteps() {
        ArchitecturePipelineStep enabled  = stubStep("enabled",  100);
        ArchitecturePipelineStep disabled = stubDisabledStep("disabled", 200);

        ArchitecturePipelineStepRegistry registry =
                new ArchitecturePipelineStepRegistry(List.of(enabled, disabled));

        List<ArchitecturePipelineStep> result = registry.getEnabledSteps();

        assertThat(result).extracting(ArchitecturePipelineStep::id)
                .containsExactly("enabled");
    }

    @Test
    void listDescriptorsIncludesAllStepsIncludingDisabled() {
        ArchitecturePipelineStep enabled  = stubStep("enabled",  100);
        ArchitecturePipelineStep disabled = stubDisabledStep("disabled", 200);

        ArchitecturePipelineStepRegistry registry =
                new ArchitecturePipelineStepRegistry(List.of(enabled, disabled));

        List<ArchitecturePipelineStepDescriptor> descriptors = registry.listDescriptors();

        assertThat(descriptors).extracting(ArchitecturePipelineStepDescriptor::id)
                .containsExactlyInAnyOrder("enabled", "disabled");
        assertThat(descriptors).filteredOn(d -> !d.enabledByDefault())
                .extracting(ArchitecturePipelineStepDescriptor::id)
                .containsExactly("disabled");
    }

    @Test
    void emptyRegistryReturnsEmptyLists() {
        ArchitecturePipelineStepRegistry registry =
                new ArchitecturePipelineStepRegistry(List.of());

        assertThat(registry.getEnabledSteps()).isEmpty();
        assertThat(registry.listDescriptors()).isEmpty();
    }

    // ── Duplicate ID validation ───────────────────────────────────────────────

    @Test
    void rejectsDuplicateStepIds() {
        ArchitecturePipelineStep step1 = stubStep("same-id", 100);
        ArchitecturePipelineStep step2 = stubStep("same-id", 200);

        assertThatThrownBy(() ->
                new ArchitecturePipelineStepRegistry(List.of(step1, step2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("same-id");
    }

    // ── Duplicate order validation ────────────────────────────────────────────

    @Test
    void rejectsDuplicateStepOrders() {
        ArchitecturePipelineStep step1 = stubStep("id-one", 100);
        ArchitecturePipelineStep step2 = stubStep("id-two", 100);

        assertThatThrownBy(() ->
                new ArchitecturePipelineStepRegistry(List.of(step1, step2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("100");
    }

    // ── Blank-ID validation ───────────────────────────────────────────────────

    @Test
    void rejectsStepWithBlankId() {
        ArchitecturePipelineStep blank = stubStep("  ", 100);

        assertThatThrownBy(() ->
                new ArchitecturePipelineStepRegistry(List.of(blank)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-blank ID");
    }

    @Test
    void rejectsStepWithNullId() {
        ArchitecturePipelineStep nullId = stubStep(null, 100);

        assertThatThrownBy(() ->
                new ArchitecturePipelineStepRegistry(List.of(nullId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-blank ID");
    }

    // ── Default pipeline step IDs and ordering ────────────────────────────────

    @Test
    void defaultStepIdsAreStable() {
        assertThat(AnchorSelectionStep.STEP_ID).isEqualTo("anchor-selection");
        assertThat(RelevancePropagationStep.STEP_ID).isEqualTo("relevance-propagation");
        assertThat(ElementBuildStep.STEP_ID).isEqualTo("element-build");
        assertThat(LeafEnrichmentStep.STEP_ID).isEqualTo("leaf-enrichment");
        assertThat(RelationshipBuildStep.STEP_ID).isEqualTo("relationship-build");
        assertThat(ProvisionalRelationStep.STEP_ID).isEqualTo("provisional-relation");
        assertThat(NodeLimitStep.STEP_ID).isEqualTo("node-limit");
        assertThat(ImpactRelationStep.STEP_ID).isEqualTo("impact-relation");
        assertThat(ScoringTraceStep.STEP_ID).isEqualTo("scoring-trace");
        assertThat(ImpactSelectionStep.STEP_ID).isEqualTo("impact-selection");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static ArchitecturePipelineStep stubStep(String id, int order) {
        return new ArchitecturePipelineStep() {
            @Override public String id()    { return id; }
            @Override public int order()    { return order; }
            @Override public void apply(ArchitectureViewContext ctx) {}
        };
    }

    private static ArchitecturePipelineStep stubDisabledStep(String id, int order) {
        return new ArchitecturePipelineStep() {
            @Override public String id()              { return id; }
            @Override public int order()              { return order; }
            @Override public boolean enabledByDefault() { return false; }
            @Override public void apply(ArchitectureViewContext ctx) {}
        };
    }
}
