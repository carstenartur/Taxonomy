package com.taxonomy.export.controller;

import com.taxonomy.dto.RequirementArchitectureView;
import com.taxonomy.dto.RequirementElementView;
import com.taxonomy.dto.RequirementRelationshipView;
import com.taxonomy.export.DiagramProjectionService;
import com.taxonomy.export.VisioDiagramService;
import com.taxonomy.export.VisioPackageBuilder;
import com.taxonomy.export.service.ExportFacade;
import com.taxonomy.export.service.ExportFormatExtensionRegistry;
import com.taxonomy.export.service.VisioExportExtension;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipInputStream;

/** The same behavioral assertions run under JUnit and the isolated Java driver. */
final class CurrentDiagramExportRegression {
    private static ExportFacade facade() {
        // Neither the LLM nor the architecture derivation service exists here.
        // Reintroducing an implicit analysis makes these real export calls fail.
        return new ExportFacade(null, null, new DiagramProjectionService(),
                new VisioDiagramService(), new VisioPackageBuilder(), null, null, null, null, null);
    }

    private static ExportApiController controller() {
        var registry = new ExportFormatExtensionRegistry(List.of(
                new VisioExportExtension(new VisioDiagramService(), new VisioPackageBuilder())));
        return new ExportApiController(facade(), registry);
    }

    static void exportsAllCurrentNodesWithoutScoring() throws Exception {
        for (int size : new int[]{50, 150}) {
            var view = view(size);
            var before = new ArrayList<>(view.getIncludedElements());
            var projected = facade().buildCurrentDiagram(view);
            check(projected.nodes().size() == size, "Second selection policy lost nodes");
            check(projected.edges().size() == size - 1, "Lost current relationships");
            var response = controller().exportCurrentDiagram("visio", view);
            check(response.getStatusCode().value() == 200, "Export status");
            check(response.getBody() instanceof byte[], "No binary file");
            String overview = null;
            int parts = 0;
            try (var zip = new ZipInputStream(new ByteArrayInputStream((byte[]) response.getBody()))) {
                for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                    byte[] content = zip.readAllBytes();
                    if (entry.getName().equals("visio/pages/page1.xml")) overview = new String(content, StandardCharsets.UTF_8);
                    parts++;
                }
            }
            check(parts > 5 && overview != null, "Incomplete VSDX package");
            for (int i = 0; i < size; i++) check(overview.contains("NODE-" + i + "-END"), "Missing native node " + i);
            check(view.getIncludedElements().equals(before), "Export mutated working state");
            check(view.getIncludedRelationships().size() == size - 1, "Export mutated relationships");
        }
    }

    static void rejectsInvalidSnapshotsWithoutScoring() {
        var c = controller();
        check(c.exportCurrentDiagram("visio", null).getStatusCode().value() == 400, "Null view accepted");
        check(c.exportCurrentDiagram("visio", view(0)).getStatusCode().value() == 400, "Empty view accepted");
        var duplicate = view(2);duplicate.getIncludedElements().get(1).setNodeCode("NODE-0-END");
        check(c.exportCurrentDiagram("visio", duplicate).getStatusCode().value() == 400, "Duplicate ID accepted");
        var dangling = view(2);dangling.getIncludedRelationships().getFirst().setTargetCode("absent");
        check(c.exportCurrentDiagram("visio", dangling).getStatusCode().value() == 400, "Dangling endpoint accepted");
        var invalid = view(1);invalid.getIncludedElements().getFirst().setRelevance(Double.NaN);
        check(c.exportCurrentDiagram("visio", invalid).getStatusCode().value() == 400, "NaN accepted");
        var oversized = view(1);oversized.setIncludedElements(Collections.nCopies(10_001, oversized.getIncludedElements().getFirst()));
        check(c.exportCurrentDiagram("visio", oversized).getStatusCode().value() == 400, "Oversized view accepted");
        check(c.exportCurrentDiagram("unknown", view(1)).getStatusCode().value() == 404, "Unknown format accepted");
    }

    private static RequirementArchitectureView view(int size) {
        var view = new RequirementArchitectureView();
        view.setViewTitle("Existing architecture, not a new analysis");
        var elements = new ArrayList<RequirementElementView>();
        var relationships = new ArrayList<RequirementRelationshipView>();
        for (int i = 0; i < size; i++) {
            var node = new RequirementElementView();
            node.setNodeCode("NODE-" + i + "-END");node.setTitle("Existing node " + i);
            node.setRelevance(.1);node.setTaxonomySheet("UA");elements.add(node);
            if (i > 0) {
                var edge = new RequirementRelationshipView();edge.setSourceCode("NODE-" + (i-1) + "-END");
                edge.setTargetCode(node.getNodeCode());edge.setRelationType("DEPENDS_ON");edge.setPropagatedRelevance(.1);
                relationships.add(edge);
            }
        }
        view.setIncludedElements(elements);view.setIncludedRelationships(relationships);
        return view;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        exportsAllCurrentNodesWithoutScoring();
        rejectsInvalidSnapshotsWithoutScoring();
        System.out.println("PASS: complete 50/150-node VSDX exports without LLM/derivation; seven invalid requests rejected");
    }
}
