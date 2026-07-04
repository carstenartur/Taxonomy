package com.taxonomy.architecture.pipeline;

import com.taxonomy.dto.RequirementArchitectureView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchitectureViewPipelineTest {

    @Test
    void rejectsPipelineWhereAnchorSelectionIsNotFirstEnabledStep() {
        List<String> executionLog = new ArrayList<>();
        ArchitecturePipelineStep preAnchor = recordingStep("pre-anchor", 50, executionLog);
        ArchitecturePipelineStep anchorSelection = new AnchorSelectionStep();

        ArchitectureViewPipeline pipeline = new ArchitectureViewPipeline(
                new ArchitecturePipelineStepRegistry(List.of(anchorSelection, preAnchor)));

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
                new ArchitecturePipelineStepRegistry(List.of(new AnchorSelectionStep(), afterAnchor)));

        RequirementArchitectureView view = pipeline.execute(contextWithScores(Map.of()));

        assertThat(executionLog).isEmpty();
        assertThat(view.getAnchors()).isEmpty();
        assertThat(view.getNotes()).containsExactly(
                "No nodes met the anchor threshold; architecture view is empty.");
    }

    private static ArchitectureViewContext contextWithScores(Map<String, Integer> scores) {
        return new ArchitectureViewContext(scores, "test", 0, null);
    }

    private static ArchitecturePipelineStep recordingStep(
            String id, int order, List<String> executionLog) {
        return new ArchitecturePipelineStep() {
            @Override public String id() { return id; }
            @Override public int order() { return order; }
            @Override public void apply(ArchitectureViewContext ctx) {
                executionLog.add(id);
            }
        };
    }
}
