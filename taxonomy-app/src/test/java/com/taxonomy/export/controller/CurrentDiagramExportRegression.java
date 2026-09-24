package com.taxonomy.export.controller;

import com.taxonomy.dto.RequirementArchitectureView;
import com.taxonomy.dto.RequirementElementView;
import com.taxonomy.dto.RequirementRelationshipView;
import com.taxonomy.export.DiagramProjectionService;
import com.taxonomy.export.LayeredDiagramLayoutService;
import com.taxonomy.export.SvgDiagramRenderer;
import com.taxonomy.export.VisioDiagramService;
import com.taxonomy.export.VisioPackageBuilder;
import com.taxonomy.export.service.ExportFacade;
import com.taxonomy.export.service.ExportFormatExtensionRegistry;
import com.taxonomy.export.service.VisioExportExtension;
import com.taxonomy.export.service.SparxExportExtension;
import com.taxonomy.export.service.SvgExportExtension;
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
                new VisioExportExtension(new VisioDiagramService(), new VisioPackageBuilder()),
                new SparxExportExtension(),
                new SvgExportExtension(new LayeredDiagramLayoutService(), new SvgDiagramRenderer())));
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

    static void exportsCompleteSvgFromWorkingModelWithoutViewportState() {
        var view = view(12);
        var response = controller().exportCurrentDiagram("svg", view);
        check(response.getStatusCode().value() == 200, "SVG export status");
        check("image/svg+xml".equals(response.getHeaders().getFirst("Content-Type")), "SVG media type");
        check(response.getBody() instanceof byte[], "SVG body is not bytes");
        String svg = new String((byte[]) response.getBody(), StandardCharsets.UTF_8);
        check(svg.startsWith("<svg") || svg.contains("<svg"), "SVG root missing");
        for (int i = 0; i < 12; i++) {
            check(svg.contains("NODE-" + i + "-END"), "Full-model SVG lost node " + i);
        }
        check(!svg.contains("impact-map-viewport") && !svg.contains("decision-map-svg"),
                "Model export leaked browser viewport markup");
    }

    static void exportsSparxWorkingViewWithoutScoringOrSyncMutation() throws Exception {
        var view = view(3);
        var nodes = new ArrayList<>(view.getIncludedElements());
        var response = controller().exportCurrentDiagram("sparx", view);
        check(response.getStatusCode().value() == 200, "Sparx format must be registered");
        check("application/zip".equals(response.getHeaders().getFirst("Content-Type")), "Sparx ZIP media type");
        check(response.getHeaders().getFirst("Content-Disposition").contains("sparx.zip"), "Honest bundle extension");
        byte[] xmi = null;
        try (var zip = new ZipInputStream(new ByteArrayInputStream((byte[]) response.getBody()))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                byte[] bytes = zip.readAllBytes();
                if (entry.getName().equals("architecture.xmi")) xmi = bytes;
            }
        }
        check(xmi != null, "Sparx XMI missing");
        var decoded = new com.taxonomy.exchange.sparx.SparxXmiCodec().read(xmi, null, false);
        check(decoded.artifacts().size() == 3 && decoded.relations().size() == 2, "Sparx membership changed");
        check(view.getIncludedElements().equals(nodes) && view.getIncludedRelationships().size() == 2, "Sparx export changed working view");
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
        // Validate at the shared facade, before any format-specific exporter can
        // hide the error. Finite numbers outside [0,1] are invalid too.
        for (double score : new double[]{-.01, 1.01, 2, Double.NaN,
                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            var badNode = view(2);
            badNode.getIncludedElements().getFirst().setRelevance(score);
            expectInvalidScore(badNode, "Node score accepted: " + score);
            var badEdge = view(2);
            badEdge.getIncludedRelationships().getFirst().setPropagatedRelevance(score);
            expectInvalidScore(badEdge, "Relationship score accepted: " + score);
        }
        for (double score : new double[]{0, 1}) {
            var boundary = view(2);
            boundary.getIncludedElements().getFirst().setRelevance(score);
            boundary.getIncludedRelationships().getFirst().setPropagatedRelevance(score);
            var diagram = facade().buildCurrentDiagram(boundary);
            check(diagram.nodes().getFirst().relevance() == score, "Valid node boundary changed");
            check(diagram.edges().getFirst().relevance() == score, "Valid edge boundary changed");
        }

    }

    private static void expectInvalidScore(RequirementArchitectureView view, String message) {
        try {
            facade().buildCurrentDiagram(view);
            throw new AssertionError(message);
        } catch (IllegalArgumentException expected) {
            // The common boundary must reject this before a format adapter runs.
        }
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
        exportsCompleteSvgFromWorkingModelWithoutViewportState();
        rejectsInvalidSnapshotsWithoutScoring();
        exportsSparxWorkingViewWithoutScoringOrSyncMutation();
        System.out.println("PASS: complete VSDX/SVG exports without LLM/viewport state; invalid inputs and normalized score ranges checked");
    }
}
