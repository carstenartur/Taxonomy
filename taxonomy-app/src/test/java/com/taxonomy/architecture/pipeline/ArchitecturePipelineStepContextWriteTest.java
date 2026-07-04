package com.taxonomy.architecture.pipeline;

import com.taxonomy.dto.RequirementElementView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that a custom {@link ArchitecturePipelineStep} can read from and write
 * to the {@link ArchitectureViewContext} in a controlled way.
 *
 * <p>No Spring context required — the context and the fake step are plain Java objects.
 */
class ArchitecturePipelineStepContextWriteTest {

    // ── A step can read scores from the context ───────────────────────────────

    @Test
    void customStepCanReadScoresFromContext() {
        Map<String, Integer> scores = Map.of("BP", 91, "CP", 75);
        ArchitectureViewContext ctx = contextWithScores(scores);

        List<String> observed = new ArrayList<>();
        ArchitecturePipelineStep reader = new ArchitecturePipelineStep() {
            @Override public String id()    { return "score-reader"; }
            @Override public int order()    { return 50; }
            @Override public void apply(ArchitectureViewContext c) {
                c.getScores().forEach((k, v) -> observed.add(k + "=" + v));
            }
        };

        reader.apply(ctx);

        assertThat(observed).containsExactlyInAnyOrder("BP=91", "CP=75");
    }

    // ── A step can add elements to the context ────────────────────────────────

    @Test
    void customStepCanAddElementsToContext() {
        ArchitectureViewContext ctx = contextWithScores(Map.of("BP", 91));

        ArchitecturePipelineStep writer = new ArchitecturePipelineStep() {
            @Override public String id()    { return "element-injector"; }
            @Override public int order()    { return 350; }
            @Override public void apply(ArchitectureViewContext c) {
                RequirementElementView el = new RequirementElementView();
                el.setNodeCode("CR");
                el.setRelevance(0.99);
                el.setAnchor(false);
                c.getElements().add(el);
            }
        };

        writer.apply(ctx);

        assertThat(ctx.getElements()).hasSize(1);
        assertThat(ctx.getElements().get(0).getNodeCode()).isEqualTo("CR");
        assertThat(ctx.getElements().get(0).getRelevance()).isEqualTo(0.99);
    }

    // ── A step can replace the whole element list ─────────────────────────────

    @Test
    void customStepCanReplaceElementList() {
        ArchitectureViewContext ctx = contextWithScores(Map.of("CP", 80));

        // Pre-populate elements
        RequirementElementView existing = new RequirementElementView();
        existing.setNodeCode("CP");
        ctx.getElements().add(existing);

        ArchitecturePipelineStep replacer = new ArchitecturePipelineStep() {
            @Override public String id()    { return "element-replacer"; }
            @Override public int order()    { return 999; }
            @Override public void apply(ArchitectureViewContext c) {
                List<RequirementElementView> newList = new ArrayList<>();
                RequirementElementView fresh = new RequirementElementView();
                fresh.setNodeCode("CR");
                newList.add(fresh);
                c.setElements(newList);
            }
        };

        replacer.apply(ctx);

        assertThat(ctx.getElements()).hasSize(1);
        assertThat(ctx.getElements().get(0).getNodeCode()).isEqualTo("CR");
    }

    // ── enabledByDefault defaults to true ────────────────────────────────────

    @Test
    void enabledByDefaultReturnsTrueByDefault() {
        ArchitecturePipelineStep step = new ArchitecturePipelineStep() {
            @Override public String id()    { return "default-step"; }
            @Override public int order()    { return 1; }
            @Override public void apply(ArchitectureViewContext ctx) {}
        };

        assertThat(step.enabledByDefault()).isTrue();
    }

    // ── descriptor is derived from interface methods ──────────────────────────

    @Test
    void descriptorIsAutoDerivedFromInterfaceMethods() {
        ArchitecturePipelineStep step = new ArchitecturePipelineStep() {
            @Override public String id()    { return "my-step"; }
            @Override public int order()    { return 42; }
            @Override public void apply(ArchitectureViewContext ctx) {}
        };

        ArchitecturePipelineStepDescriptor desc = step.descriptor();

        assertThat(desc.id()).isEqualTo("my-step");
        assertThat(desc.order()).isEqualTo(42);
        assertThat(desc.enabledByDefault()).isTrue();
    }

    // ── A step can set the usedProvisional flag ───────────────────────────────

    @Test
    void customStepCanSetUsedProvisionalFlag() {
        ArchitectureViewContext ctx = contextWithScores(Map.of());

        ArchitecturePipelineStep flagSetter = new ArchitecturePipelineStep() {
            @Override public String id()    { return "provisional-flag-setter"; }
            @Override public int order()    { return 1; }
            @Override public void apply(ArchitectureViewContext c) {
                c.setUsedProvisional(true);
            }
        };

        flagSetter.apply(ctx);

        assertThat(ctx.isUsedProvisional()).isTrue();
    }

    // ── Pipeline with a fake step runs all enabled steps in order ─────────────

    @Test
    void registryRunsFakeStepAtCorrectPosition() {
        List<String> executionOrder = new ArrayList<>();

        ArchitecturePipelineStep step100 = recordingStep("first",  100, executionOrder);
        ArchitecturePipelineStep step300 = recordingStep("third",  300, executionOrder);
        ArchitecturePipelineStep step200 = recordingStep("second", 200, executionOrder);

        ArchitecturePipelineStepRegistry registry =
                new ArchitecturePipelineStepRegistry(List.of(step100, step300, step200));

        ArchitectureViewContext ctx = contextWithScores(Map.of());
        for (ArchitecturePipelineStep step : registry.getEnabledSteps()) {
            step.apply(ctx);
        }

        assertThat(executionOrder).containsExactly("first", "second", "third");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static ArchitectureViewContext contextWithScores(Map<String, Integer> scores) {
        return new ArchitectureViewContext(scores, "test", 0, null);
    }

    private static ArchitecturePipelineStep recordingStep(
            String id, int order, List<String> log) {
        return new ArchitecturePipelineStep() {
            @Override public String id()    { return id; }
            @Override public int order()    { return order; }
            @Override public void apply(ArchitectureViewContext ctx) { log.add(id); }
        };
    }
}
