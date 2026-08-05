package com.taxonomy.export;

import com.taxonomy.diagram.DiagramLayout;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.dto.RequirementArchitectureView;
import com.taxonomy.dto.RequirementElementView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PersistedDiagramProjectionTest {

    @Test
    void usesExactlyTheElementsStoredInTheSnapshotView() {
        RequirementElementView element = new RequirementElementView();
        element.setNodeCode("CP-1");
        element.setTitle("Persisted capability");
        element.setTaxonomySheet("CP");
        element.setRelevance(0.91);
        element.setAnchor(true);
        element.setSelectedForImpact(true);

        RequirementArchitectureView view = new RequirementArchitectureView();
        view.setIncludedElements(List.of(element));

        DiagramProjectionService projectionService = new DiagramProjectionService();
        projectionService.setPolicy(raw -> new DiagramModel(
                raw.title(), List.of(), List.of(), new DiagramLayout("LR", true)));

        DiagramModel replay = PersistedDiagramProjection.project(
                projectionService, view, "Persisted snapshot");

        assertThat(replay.nodes()).extracting(node -> node.id())
                .containsExactly("CP-1");
        assertThat(replay.title()).isEqualTo("Persisted snapshot");
    }
}
