package com.taxonomy.architecture.pipeline;

import com.taxonomy.dto.RequirementArchitectureView;
import com.taxonomy.dto.RequirementElementView;
import com.taxonomy.dto.RequirementRelationshipView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link NodeLimitStep}.
 *
 * <p>No Spring context required — the step is a pure function.
 */
class NodeLimitStepTest {

    private final NodeLimitStep step = new NodeLimitStep();

    // ── No-op cases ──────────────────────────────────────────────────────────

    @Test
    void doesNothingWhenLimitIsZero() {
        ArchitectureViewContext ctx = buildContext(0);
        addElements(ctx, "BP", "CP", "CR");
        addRelationship(ctx, "BP", "CP");

        step.execute(ctx);

        assertThat(ctx.getElements()).hasSize(3);
        assertThat(ctx.getRelationships()).hasSize(1);
        assertThat(ctx.getView().getNotes()).isEmpty();
    }

    @Test
    void doesNothingWhenElementCountWithinLimit() {
        ArchitectureViewContext ctx = buildContext(5);
        addElements(ctx, "BP", "CP");

        step.execute(ctx);

        assertThat(ctx.getElements()).hasSize(2);
        assertThat(ctx.getView().getNotes()).isEmpty();
    }

    @Test
    void doesNothingWhenElementCountEqualsLimit() {
        ArchitectureViewContext ctx = buildContext(3);
        addElements(ctx, "BP", "CP", "CR");

        step.execute(ctx);

        assertThat(ctx.getElements()).hasSize(3);
        assertThat(ctx.getView().getNotes()).isEmpty();
    }

    // ── Truncation cases ─────────────────────────────────────────────────────

    @Test
    void truncatesElementsToMaxCount() {
        ArchitectureViewContext ctx = buildContext(2);
        addElements(ctx, "BP", "CP", "CR");

        step.execute(ctx);

        assertThat(ctx.getElements()).hasSize(2);
        assertThat(ctx.getElements()).extracting(RequirementElementView::getNodeCode)
                .containsExactly("BP", "CP");
    }

    @Test
    void removesRelationshipsWithTruncatedEndpoints() {
        ArchitectureViewContext ctx = buildContext(2);
        addElements(ctx, "BP", "CP", "CR");
        addRelationship(ctx, "BP", "CP"); // kept (both in top-2)
        addRelationship(ctx, "BP", "CR"); // removed (CR is truncated)
        addRelationship(ctx, "CP", "CR"); // removed (CR is truncated)

        step.execute(ctx);

        assertThat(ctx.getRelationships()).hasSize(1);
        assertThat(ctx.getRelationships().get(0).getSourceCode()).isEqualTo("BP");
        assertThat(ctx.getRelationships().get(0).getTargetCode()).isEqualTo("CP");
    }

    @Test
    void addsNoteWhenTruncating() {
        ArchitectureViewContext ctx = buildContext(2);
        addElements(ctx, "BP", "CP", "CR");

        step.execute(ctx);

        assertThat(ctx.getView().getNotes())
                .anyMatch(n -> n.contains("limited to 2 elements"));
    }

    @Test
    void keepsAllRelationshipsWithinKeptSet() {
        ArchitectureViewContext ctx = buildContext(3);
        addElements(ctx, "BP", "CP", "CR", "CI"); // 4 elements, limit 3
        addRelationship(ctx, "BP", "CP");
        addRelationship(ctx, "CP", "CR");
        addRelationship(ctx, "BP", "CI"); // removed (CI is 4th)

        step.execute(ctx);

        assertThat(ctx.getRelationships()).hasSize(2);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static ArchitectureViewContext buildContext(int maxNodes) {
        return new ArchitectureViewContext(java.util.Map.of(), "test", maxNodes, null);
    }

    private static void addElements(ArchitectureViewContext ctx, String... codes) {
        List<RequirementElementView> elements = new ArrayList<>(ctx.getElements());
        for (String code : codes) {
            RequirementElementView el = new RequirementElementView();
            el.setNodeCode(code);
            elements.add(el);
        }
        ctx.setElements(elements);
    }

    private static void addRelationship(ArchitectureViewContext ctx, String src, String tgt) {
        List<RequirementRelationshipView> rels = new ArrayList<>(ctx.getRelationships());
        RequirementRelationshipView rv = new RequirementRelationshipView();
        rv.setSourceCode(src);
        rv.setTargetCode(tgt);
        rels.add(rv);
        ctx.setRelationships(rels);
    }
}
