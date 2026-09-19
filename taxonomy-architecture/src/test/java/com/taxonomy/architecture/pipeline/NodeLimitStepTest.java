package com.taxonomy.architecture.pipeline;

import com.taxonomy.dto.RequirementAnchor;
import com.taxonomy.dto.RequirementElementView;
import com.taxonomy.dto.RequirementRelationshipView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

        step.apply(ctx);

        assertThat(ctx.getElements()).hasSize(3);
        assertThat(ctx.getRelationships()).hasSize(1);
        assertThat(ctx.getView().getNotes()).isEmpty();
    }

    @Test
    void doesNothingWhenElementCountWithinLimit() {
        ArchitectureViewContext ctx = buildContext(5);
        addElements(ctx, "BP", "CP");

        step.apply(ctx);

        assertThat(ctx.getElements()).hasSize(2);
        assertThat(ctx.getView().getNotes()).isEmpty();
    }

    @Test
    void doesNothingWhenElementCountEqualsLimit() {
        ArchitectureViewContext ctx = buildContext(3);
        addElements(ctx, "BP", "CP", "CR");

        step.apply(ctx);

        assertThat(ctx.getElements()).hasSize(3);
        assertThat(ctx.getView().getNotes()).isEmpty();
    }

    // ── Truncation cases ─────────────────────────────────────────────────────

    @Test
    void truncatesElementsToMaxCount() {
        ArchitectureViewContext ctx = buildContext(2);
        addElements(ctx, "BP", "CP", "CR");

        step.apply(ctx);

        assertThat(ctx.getElements()).hasSize(2);
        assertThat(ctx.getElements()).extracting(RequirementElementView::getNodeCode)
                .containsExactly("BP", "CP");
    }

    @Test
    void prioritizesStrongRelationshipEndpointsOverDisconnectedHigherRankedNodes() {
        ArchitectureViewContext ctx = buildContext(2);
        addElements(ctx, "UNCONNECTED-A", "UNCONNECTED-B", "CP", "CR");
        addRelationship(ctx, "UNCONNECTED-A", "UNCONNECTED-B", 0.20);
        addRelationship(ctx, "CP", "CR", 0.90);

        step.apply(ctx);

        assertThat(ctx.getElements()).extracting(RequirementElementView::getNodeCode)
                .containsExactly("CP", "CR");
        assertThat(ctx.getRelationships()).hasSize(1);
        assertThat(ctx.getRelationships().get(0).getSourceCode()).isEqualTo("CP");
        assertThat(ctx.getRelationships().get(0).getTargetCode()).isEqualTo("CR");
    }

    @Test
    void removesRelationshipsWithTruncatedEndpoints() {
        ArchitectureViewContext ctx = buildContext(2);
        addElements(ctx, "BP", "CP", "CR");
        addRelationship(ctx, "BP", "CP"); // kept (both endpoints fit)
        addRelationship(ctx, "BP", "CR"); // removed (CR no longer fits)
        addRelationship(ctx, "CP", "CR"); // removed (CR no longer fits)

        step.apply(ctx);

        assertThat(ctx.getRelationships()).hasSize(1);
        assertThat(ctx.getRelationships().get(0).getSourceCode()).isEqualTo("BP");
        assertThat(ctx.getRelationships().get(0).getTargetCode()).isEqualTo("CP");
    }

    @Test
    void addsNoteWhenTruncating() {
        ArchitectureViewContext ctx = buildContext(2);
        addElements(ctx, "BP", "CP", "CR");

        step.apply(ctx);

        assertThat(ctx.getView().getNotes())
                .anyMatch(n -> n.contains("limited to 2 elements"));
    }

    @Test
    void keepsAllRelationshipsWithinKeptSet() {
        ArchitectureViewContext ctx = buildContext(3);
        addElements(ctx, "BP", "CP", "CR", "CI"); // 4 elements, limit 3
        addRelationship(ctx, "BP", "CP");
        addRelationship(ctx, "CP", "CR");
        addRelationship(ctx, "BP", "CI");

        step.apply(ctx);

        assertThat(ctx.getRelationships()).hasSize(2);
        assertThat(ctx.getRelationships()).allMatch(relationship ->
                ctx.getElements().stream().map(RequirementElementView::getNodeCode)
                        .toList().contains(relationship.getSourceCode())
                        && ctx.getElements().stream().map(RequirementElementView::getNodeCode)
                        .toList().contains(relationship.getTargetCode()));
    }

    @Test
    void projectsAnchorsOntoTheBoundedViewWithoutDiscardingAnalysisScores() {
        ArchitectureViewContext ctx = new ArchitectureViewContext(
                Map.of("BP", 95, "CP", 94, "CR", 93, "CI", 80),
                "test", 2, null);
        ctx.setAnchors(new ArrayList<>(List.of(
                new RequirementAnchor("BP", 95, "direct"),
                new RequirementAnchor("CP", 94, "direct"),
                new RequirementAnchor("CR", 93, "direct"))));
        // Mirror ArchitectureViewPipeline, which publishes anchors immediately
        // after anchor selection and before the node-limit step runs.
        ctx.getView().setAnchors(ctx.getAnchors());
        addElements(ctx, "BP", "CP", "CR", "CI");
        addRelationship(ctx, "BP", "CP");
        addRelationship(ctx, "BP", "CR");

        step.apply(ctx);

        assertThat(ctx.getElements()).extracting(RequirementElementView::getNodeCode)
                .containsExactly("BP", "CP");
        assertThat(ctx.getAnchors()).extracting(RequirementAnchor::getNodeCode)
                .containsExactly("BP", "CP");
        assertThat(ctx.getView().getAnchors()).extracting(RequirementAnchor::getNodeCode)
                .containsExactly("BP", "CP");
        assertThat(ctx.getScores()).containsEntry("CR", 93);
        assertThat(ctx.getRelationships()).hasSize(1);
        assertThat(ctx.getView().getNotes())
                .anyMatch(note -> note.contains("Additional anchor nodes outside this bounded view: 1")
                        && note.contains("scores remain in the complete analysis"));

        ArchitecturePipelineInvariantValidator validator =
                new ArchitecturePipelineInvariantValidator();
        validator.afterStep(step, ctx);
        validator.beforeReturn(ctx);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static ArchitectureViewContext buildContext(int maxNodes) {
        return new ArchitectureViewContext(Map.of(), "test", maxNodes, null);
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
        addRelationship(ctx, src, tgt, 0.0);
    }

    private static void addRelationship(ArchitectureViewContext ctx,
                                        String src,
                                        String tgt,
                                        double evidence) {
        List<RequirementRelationshipView> rels = new ArrayList<>(ctx.getRelationships());
        RequirementRelationshipView rv = new RequirementRelationshipView();
        rv.setSourceCode(src);
        rv.setTargetCode(tgt);
        rv.setRelationType("REALIZES");
        rv.setConfidence(evidence);
        rv.setPropagatedRelevance(evidence);
        rels.add(rv);
        ctx.setRelationships(rels);
    }
}
