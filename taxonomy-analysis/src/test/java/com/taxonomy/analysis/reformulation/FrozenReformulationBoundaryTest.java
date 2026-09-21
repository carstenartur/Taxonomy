package com.taxonomy.analysis.reformulation;

import com.taxonomy.reformulation.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class FrozenReformulationBoundaryTest {
    @Test
    void collapsedWalkUpStepsKeepTerminalBoundariesWithoutUnrelatedArchiveEdges() {
        var seen = synthesize("{\"scores\":{\"BP-1000\":75,\"CP-1000\":75}}");
        assertThat(seen.keySet()).containsExactlyInAnyOrder("BP", "CP");
        for (var input : seen.values()) {
            assertThat(input.boundaryEdges()).containsOnlyKeys("edge-shared");
            assertThat(input.boundaryEdges().get("edge-shared"))
                    .contains("\"sourceCode\":\"BP-1000\"", "\"targetCode\":\"CP-1000\"");
            assertThat(input.baseline().originalText()).isEqualTo("Capture and publish observations.");
        }
    }

    @Test
    void unmatchedSourceDoesNotAuthorizeUnrelatedArchitectureEdges() {
        var seen = synthesize("{\"scores\":{}}");
        assertThat(seen.keySet()).containsExactly(WalkUpPlanner.DOCUMENT_ROOT);
        assertThat(seen.get(WalkUpPlanner.DOCUMENT_ROOT).boundaryEdges()).isEmpty();
    }

    private Map<String, NodeSynthesisInput> synthesize(String payload) {
        var json = JsonMapper.builder().build();
        Map<String, NodeSynthesisInput> seen = new LinkedHashMap<>();
        var provider = new NodeReformulationService(null, null, json) {
            @Override public NodeSynthesisResult synthesize(NodeSynthesisInput input, ReformulationStepExecutor steps) {
                seen.put(input.nodeId(), input);
                return new NodeSynthesisResult(input.nodeId(), "Summary", List.of(),
                        input.directContributions().stream().map(Statement::id).toList(),
                        List.of(), List.of(), List.of(), List.of());
            }
        };
        var scope = new ReformulationBaseline.Scope("repository", "workspace", "draft", 1L, 1L);
        var baseline = ReformulationBaseline.freeze(
                new ReformulationBaseline.Source(scope, 1L, "Capture and publish observations."),
                new ReformulationBaseline.Snapshot(scope, "snapshot", 1L, payload),
                Map.of("catalogue", """
                    [{"code":"BP","nameEn":"Business Processes","children":[{"code":"BP-1000","nameEn":"Business Processes"}]},
                     {"code":"CP","nameEn":"Capabilities","children":[{"code":"CP-1000","nameEn":"Capabilities"}]},
                     {"code":"CR","nameEn":"Core Services"},{"code":"CO","nameEn":"Communications Services"}]
                    """, "relationMappings", """
                    [{"id":"shared","sourceCode":"BP-1000","targetCode":"CP-1000","relationType":"SUPPORTS"},
                     {"id":"unrelated","sourceCode":"CR","targetCode":"CO","relationType":"SUPPORTS"}]
                    """), "en", "test");
        new FrozenReformulationEngine(provider, json).synthesize(baseline, List.of(), List.of());
        return seen;
    }
}
