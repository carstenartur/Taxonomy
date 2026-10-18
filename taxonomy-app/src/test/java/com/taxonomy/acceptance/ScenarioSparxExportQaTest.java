package com.taxonomy.acceptance;

import com.taxonomy.diagram.*;
import com.taxonomy.dto.RelationOrigin;
import com.taxonomy.export.SparxDiagramHandoff;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.UUID;

class ScenarioSparxExportQaTest {
    @Test
    void actualWorkingViewBundlePassesTheScenarioReaderAndSemanticParityChecks() throws Exception {
        var evidence = new DiagramRelationMetadata(null, RelationOrigin.LLM_SUPPORTED, .7,
                "Scoped inclusion", "Recorded derivation", "Requested function uses the service", null, List.of());
        var graph = new DiagramModel("Scoped architecture", List.of(
                new DiagramNode("BP-1", "Requested process", "Business Processes", .8, false, 0),
                new DiagramNode("CR-1", "Supporting service", "Core Services", .7, false, 0)),
                List.of(new DiagramEdge("uses", "BP-1", "CR-1", "USES", .7, "STRUCTURAL", evidence)),
                new DiagramLayout("LR", true));
        byte[] bundle = SparxDiagramHandoff.build(graph, UUID.randomUUID());
        ScenarioExportQa.verifySparx(graph, Map.of("adapter-sparx.sparx.zip", bundle));
    }
}
