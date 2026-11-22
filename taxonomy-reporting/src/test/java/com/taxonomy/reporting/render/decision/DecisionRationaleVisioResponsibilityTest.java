package com.taxonomy.reporting.render.decision;

import com.taxonomy.diagram.DiagramLayout;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
import com.taxonomy.dto.TaxonomyResponsibilityLookup.Assignment;
import com.taxonomy.export.LayeredDiagramLayoutService;
import com.taxonomy.export.VisioDiagramService;
import com.taxonomy.export.VisioPackageBuilder;
import com.taxonomy.extension.api.report.ReportRenderContext;
import com.taxonomy.reporting.api.decision.DecisionReportOptions;
import com.taxonomy.reporting.api.decision.DecisionReportScope;
import com.taxonomy.reporting.api.document.ArchitectureReportDocument;
import com.taxonomy.reporting.render.document.ArchitectureReportDocuments;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionRationaleVisioResponsibilityTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void usesDrawingVisibilityForPreviouslyAssignedNodesWithoutChangingTheFrozenSource(boolean show) throws Exception {
        var base = DecisionCompactReportTest.report(DecisionReportOptions.TreeLayout.AUTO, 2);
        var treeOwner = new Assignment("tree-unit", "Recorded tree owner", "tree-source");
        var graphOwner = new Assignment("graph-unit", "Recorded graph owner", "graph-source");
        var tree = base.scope().decisionTree().withResponsibilities(Map.of("CP", treeOwner));
        var original = new DiagramNode("other", "Saved element", "Capability", .8, true, 0, 1,
                false, null, false, graphOwner);
        var graph = new DiagramModel("Frozen graph", List.of(original), List.of(), new DiagramLayout("LR", false));
        var digest = ArchitectureReportDocument.graphSha256(graph);
        var evidence = new ArchitectureReportDocument.SnapshotEvidence(1L, 2L, 3L, 4,
                "snapshot", "repo", "workspace", "branch", "commit", "provider", "model", "taxonomy", digest);
        var architecture = ArchitectureReportDocuments.from("Architecture", "en", base.requirement(), "Scope",
                "Recommendation", List.of(), graph, new LayeredDiagramLayoutService().layout(graph), tree, evidence);
        var options = new DecisionReportOptions(null, null, null, null, null, show);
        var scope = new DecisionReportScope(null, null, base.scope().availableRoots(), base.scope().reportRoots(),
                base.scope().selectedNodeCodes(), tree, options, true, true);
        var decision = base.withScope(scope).withArchitecture(architecture);
        var beans = new DefaultListableBeanFactory();
        var renderer = new DecisionRationaleVisioRenderer(beans.getBeanProvider(VisioDiagramService.class),
                beans.getBeanProvider(VisioPackageBuilder.class));
        assertThat(renderer.descriptor().binary()).isTrue();
        var result = renderer.render(ReportRenderContext.ofPayload(decision));
        var pages = new StringBuilder();
        var drawingText = new StringBuilder();
        var parser = DocumentBuilderFactory.newNSInstance().newDocumentBuilder();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(result.bytes()))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().matches("visio/pages/page[0-9]+\\.xml")) {
                    byte[] page = zip.readAllBytes();
                    pages.append(new String(page, StandardCharsets.UTF_8));
                    var captions = parser.parse(new ByteArrayInputStream(page)).getElementsByTagNameNS("*", "Text");
                    for (int i = 0; i < captions.getLength(); i++) {
                        drawingText.append(captions.item(i).getTextContent()).append('\n');
                    }
                }
            }
        }
        assertThat(pages).contains("Saved element", "Capabilities");
        if (show) {
            assertThat(pages).contains(graphOwner.name(), treeOwner.name(), graphOwner.unitId(), treeOwner.unitId());
            assertThat(drawingText).contains(graphOwner.name(), treeOwner.name());
        } else {
            assertThat(pages).doesNotContain(graphOwner.name(), treeOwner.name(), graphOwner.unitId(), treeOwner.unitId());
            assertThat(drawingText).doesNotContain(graphOwner.name(), treeOwner.name());
        }
        assertThat(decision.architecture().diagram().nodes().getFirst().responsibility()).isEqualTo(graphOwner);
        assertThat(ArchitectureReportDocument.graphSha256(decision.architecture().diagram())).isEqualTo(digest);
        assertThat(decision.scope().decisionTree().responsibilitiesByCode()).containsEntry("CP", treeOwner);
    }
}
