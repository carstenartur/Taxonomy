package com.taxonomy.architecture.pipeline;

import com.taxonomy.dto.RequirementArchitectureView;
import com.taxonomy.dto.RequirementElementView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchitectureViewPipelineTest {

    private final ArchitecturePipelineInvariantValidator validator =
            new ArchitecturePipelineInvariantValidator();

    @Test
    void rejectsPipelineWhereAnchorSelectionIsNotFirstEnabledStep() {
        List<String> executionLog = new ArrayList<>();
        ArchitecturePipelineStep preAnchor = recordingStep("pre-anchor", 50, executionLog);
        ArchitecturePipelineStep anchorSelection = new AnchorSelectionStep();

        ArchitectureViewPipeline pipeline = new ArchitectureViewPipeline(
                new ArchitecturePipelineStepRegistry(List.of(anchorSelection, preAnchor)), validator);

        assertThatThrownBy(() -> pipeline.execute(contextWithScores(Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(AnchorSelectionStep.STEP_ID)
                .hasMessageContaining("pre-anchor");
        assertThat(executionLog).isEmpty();
    }

    @Test
    void shortCircuitsAfterAnchorSelectionWhenNoAnchorsAreFound() {
        List<String> executionLog = new ArrayList<>();
        ArchitecturePipelineStep afterAnchor = recordingStep("after-anchor", 200, executionLog);

        ArchitectureViewPipeline pipeline = new ArchitectureViewPipeline(
                new ArchitecturePipelineStepRegistry(List.of(new AnchorSelectionStep(), afterAnchor)),
                validator);

        RequirementArchitectureView view = pipeline.execute(contextWithScores(Map.of()));

        assertThat(executionLog).isEmpty();
        assertThat(view.getAnchors()).isEmpty();
        assertThat(view.getNotes()).containsExactly(
                "No nodes met the anchor threshold; architecture view is empty.");
    }

    @Test
    void failsImmediatelyWhenCustomStepCreatesDuplicateElements() {
        ArchitecturePipelineStep corruptingStep = new ArchitecturePipelineStep() {
            @Override public String id() { return "corrupt-elements"; }
            @Override public int order() { return 300; }
            @Override public void apply(ArchitectureViewContext context) {
                RequirementElementView first = element("CP");
                RequirementElementView duplicate = element("CP");
                context.setElements(new ArrayList<>(List.of(first, duplicate)));
            }
        };
        ArchitectureViewContext context = contextWithScores(Map.of("CP", 90));
        ArchitectureViewPipeline pipeline = new ArchitectureViewPipeline(
                new ArchitecturePipelineStepRegistry(
                        List.of(new AnchorSelectionStep(), corruptingStep)), validator);

        assertThatThrownBy(() -> pipeline.execute(context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate element CP")
                .hasMessageContaining("corrupt-elements");
    }

    @Test
    void invariantValidatorRejectsRelationsPointingOutsideFinalView() {
        ArchitectureViewContext context = contextWithScores(Map.of());
        context.setElements(new ArrayList<>(List.of(element("CP"))));
        var relationship = new com.taxonomy.dto.RequirementRelationshipView();
        relationship.setSourceCode("CP");
        relationship.setTargetCode("CR");
        relationship.setRelationType("REALIZES");
        context.setRelationships(new ArrayList<>(List.of(relationship)));

        assertThatThrownBy(() -> validator.beforeReturn(context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside the view");
    }

    private static RequirementElementView element(String code) {
        RequirementElementView element = new RequirementElementView();
        element.setNodeCode(code);
        element.setRelevance(1.0);
        return element;
    }

    private static ArchitectureViewContext contextWithScores(Map<String, Integer> scores) {
        return new ArchitectureViewContext(scores, "test", 0, null);
    }

    private static ArchitecturePipelineStep recordingStep(
            String id, int order, List<String> executionLog) {
        return new ArchitecturePipelineStep() {
            @Override public String id() { return id; }
            @Override public int order() { return order; }
            @Override public void apply(ArchitectureViewContext context) {
                executionLog.add(id);
            }
        };
    }
}
