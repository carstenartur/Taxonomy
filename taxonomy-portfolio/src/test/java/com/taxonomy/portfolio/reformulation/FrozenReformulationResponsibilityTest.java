package com.taxonomy.portfolio.reformulation;

import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.RequirementArchitectureView;
import com.taxonomy.dto.RequirementElementView;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.dto.TaxonomyResponsibilityLookup.Assignment;
import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.portfolio.dto.PortfolioDtos.*;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.service.PortfolioJsonCodec;
import com.taxonomy.reformulation.ReformulationBaseline;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FrozenReformulationResponsibilityTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void frozenArchitectureRetainsResponsibilityAndConsistentCatalogueIdentity(boolean conflictingUuid) {
        var json = new PortfolioJsonCodec(JsonMapper.builder().build());
        var scope = new ReformulationBaseline.Scope("repo", "workspace", "draft", 1L, 2L);
        var source = new ReformulationBaseline.Source(scope, 3L, "Original requirement");
        var responsibility = new Assignment("unit-42", "Operations team", "CP-1000");
        var node = new TaxonomyNodeDto();
        node.setCode("CP-1023");
        node.setNameEn("Capability A");
        node.setLevel(1);
        node.setUuid("76ac92c2-fe90-4bc2-9f7c-4ec1ca901f34");
        var element = new RequirementElementView();
        element.setNodeCode(node.getCode());
        element.setResponsibility(responsibility);
        if (conflictingUuid) element.setCatalogueUuid("0a5bc58c-c75c-4dc3-8768-ec49423e5aa2");
        var view = new RequirementArchitectureView();
        view.setIncludedElements(List.of(element));
        var analysis = new AnalysisResult(Map.of(), List.of(node));
        analysis.setArchitectureView(view);
        var mapping = new ElementMappingView(4L, "snapshot", node.getCode(), "Capability A", "CP",
                70, .7, .9, null, null, null, true, null, null, null, null, null, null);
        var summary = new SnapshotSummary("snapshot", 1L, 2L, "REQ-2", 3L, 1, "job", AnalysisStatus.SUCCESS,
                "provider", "model", "taxonomy-digest", null, "workspace", "draft", null, null, 0, 0, null);
        var detail = new SnapshotDetail(summary, analysis, null, null, null, List.of(mapping), List.of());
        var version = new RequirementVersionView(3L, 1, source.text(), StableIdentityHash.sha256(source.text()), null, null, null, null);
        var project = new ProjectView(1L, "PRJ-1", "Project", null, null, null, "workspace",
                null, null, null, null, null, null, 1, 0, 0);
        var snapshot = new ReformulationBaseline.Snapshot(scope, "snapshot", 3L, json.write(analysis));
        var baseline = ReformulationBaseline.freeze(source, snapshot, Map.of(
                "snapshotDetail", json.write(detail),
                "catalogue", json.write(List.of(node)),
                "elementMappings", json.write(List.of(mapping)),
                "relationMappings", "[]",
                "sourceVersion", json.write(version),
                "project", json.write(project)), "en", "1");

        var assigned = new FrozenReformulationArchitectureAssembler(json).assemble(baseline).graph().nodes().getFirst();
        assertEquals(responsibility, assigned.responsibility());
        assertEquals("Capability A", assigned.label());
        assertEquals(conflictingUuid ? null : node.getUuid(), assigned.catalogueUuid());
    }
}
