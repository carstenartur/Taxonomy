package com.taxonomy.export;

import com.taxonomy.diagram.DiagramEdge;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
import com.taxonomy.diagram.VisioDrawingOptions;
import com.taxonomy.visio.VisioConnect;
import com.taxonomy.visio.VisioExportMetadata;
import com.taxonomy.visio.VisioProperty;
import com.taxonomy.visio.VisioLoss;
import com.taxonomy.visio.VisioDocument;
import com.taxonomy.visio.VisioPage;
import com.taxonomy.visio.VisioShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a neutral {@link DiagramModel} into a {@link VisioDocument} by laying
 * out shapes by layer, with an optional second anchor/impact selection page.
 */
public class VisioDiagramService {

    private static final Logger log = LoggerFactory.getLogger(VisioDiagramService.class);

    private static final double SHAPE_WIDTH = 2.0;
    private static final double SHAPE_HEIGHT = 0.9;
    private static final double H_GAP = 3.0;
    private static final double V_GAP = 1.2;
    private static final double MARGIN_X = 1.5;
    private static final double MARGIN_Y = 1.5;

    /**
     * Converts a {@link DiagramModel} without snapshot authority metadata.
     */
    public VisioDocument convert(DiagramModel model) {
        return convert(model, VisioExportMetadata.unbound());
    }

    public VisioDocument convert(DiagramModel model, VisioExportMetadata metadata) {
        return convert(model, metadata, null);
    }

    public VisioDocument convert(DiagramModel model, VisioExportMetadata metadata, VisioDecisionTree decisionTree) {
        return convert(model, metadata, decisionTree, null);
    }

    public VisioDocument convert(DiagramModel model, VisioExportMetadata metadata, VisioDecisionTree decisionTree,
                                 VisioDrawingOptions options) {
        validateGraph(model);
        VisioDocument doc = new VisioDocument();
        doc.setDrawingOptions(options);
        doc.setGerman(metadata.german());
        boolean german = doc.isGerman();
        doc.getProperties().putAll(metadata.document());
        doc.getProperties().put("taxonomy.exportProfile", VisioProperty.text(VisioHandoffProfile.ID));
        doc.getProperties().put("taxonomy.scoreSemantics", VisioProperty.text("relevance is a normalized selection score in [0,1], not a probability"));
        doc.getLosses().addAll(metadata.losses());
        if (model.nodes().isEmpty() && decisionTree != null && !decisionTree.nodes().isEmpty()) {
            decisionTree.appendTo(doc, options);
            if (options == null || options.appendix()) VisioContextPages.append(doc, false);
            if (options != null) VisioContextPages.addRequirementShape(doc, options.german());
            return doc;
        }
        doc.getLosses().add(new VisioLoss("document", "", "layout", "MAPPED",
                "Generated layer layout in inches; browser geometry, routing and typography are not copied."));
        VisioPage page = new VisioPage("0", model.title() != null ? model.title()
                : german ? "Architektursicht" : "Architecture View");
        doc.getPages().add(page);

        // Group nodes by layer for layout
        List<DiagramNode> sortedNodes = model.nodes().stream().sorted(Comparator.comparing(DiagramNode::id)).toList();
        Map<Integer, List<DiagramNode>> layerGroups = new LinkedHashMap<>();
        for (DiagramNode node : sortedNodes) {
            layerGroups.computeIfAbsent(node.layer(), k -> new java.util.ArrayList<>()).add(node);
        }

        // Assign positions: layers left-to-right, nodes top-to-bottom within a layer
        Map<String, String> nodeIdToShapeId = new LinkedHashMap<>();
        int shapeIdx = 0;
        for (DiagramNode node : sortedNodes) {
            nodeIdToShapeId.put(node.id(), Integer.toString(++shapeIdx));
        }
        var decisionsByCode = new java.util.HashMap<String, VisioDecisionTree.Node>();
        if (options != null && decisionTree != null) {
            decisionTree.nodes().forEach(node -> decisionsByCode.put(node.code(), node));
        }
        boolean wall = options != null && options.mode() == VisioDrawingOptions.Mode.WALL;
        boolean screenWithResponsibilities = options != null && !wall && sortedNodes.stream().anyMatch(node ->
                node.responsibility() != null || (decisionsByCode.containsKey(node.id())
                        && decisionsByCode.get(node.id()).responsibility() != null));
        double width = options == null ? SHAPE_WIDTH : 3.3;
        double height = options == null ? SHAPE_HEIGHT : VisioPresentation.screenCardHeight(screenWithResponsibilities);
        if (wall) height = options.showsShortReasons() ? 1.7 : 1.32;
        int rowsPerColumn = options == null ? Math.max(10, (int) Math.ceil(Math.sqrt(sortedNodes.size()))) : Integer.MAX_VALUE;
        // Full relationship captions need two inches between cards, plus collision padding.
        double columnSpacing = !model.edges().isEmpty() && !VisioPresentation.usesCompactKeys(sortedNodes.size(), model.edges().size())
                ? SHAPE_WIDTH + 2.3 : H_GAP;
        int layerIdx = 0;
        for (var entry : layerGroups.entrySet().stream()
                .sorted(Comparator.comparingInt(Map.Entry::getKey)).toList()) {
            List<DiagramNode> layerNodes = entry.getValue();
            for (int i = 0; i < layerNodes.size(); i++) {
                DiagramNode node = layerNodes.get(i);
                String shapeId = nodeIdToShapeId.get(node.id());

                var decision = decisionsByCode.get(node.id());
                var assignment = node.responsibility();
                var displayAssignment = assignment != null || decision == null ? assignment : decision.responsibility();
                String owner = displayAssignment == null ? null : displayAssignment.name();
                var elementProperties = metadata.elements().getOrDefault(node.id(), Map.of());
                var presenceReason = elementProperties.get("taxonomy.presenceReason");
                String reason = presenceReason == null ? null : presenceReason.value();
                if ((reason == null || reason.isBlank()) && decision != null) reason = decision.reason();
                String displayedLabel = VisioPresentation.display(node.label(), options == null ? 24 : 46, 3);
                String caption = options == null
                        ? VisioPresentation.display("N" + shapeId + " | " + node.id(), 24, 1) + "\n"
                            + displayedLabel + "\n" + VisioPresentation.nodeFooter(Integer.toString(node.depth()), owner, 24, german)
                        : VisioPresentation.drawingNodeCaption(node.id(),
                            java.math.BigDecimal.valueOf(node.relevance()).movePointRight(2).stripTrailingZeros().toPlainString() + "%",
                            displayedLabel, owner, reason, options);
                double x = options == null ? MARGIN_X + (layerIdx + i / rowsPerColumn) * columnSpacing : 2.2 + layerIdx * 5.1;
                double y = options == null ? MARGIN_Y + (i % rowsPerColumn) * V_GAP : 1.25 + i * (height + .35);
                var shape = new VisioShape(shapeId, caption, x, y, width, height, node.type(), node.anchor());
                shape.getProperties().putAll(elementProperties);
                var values = shape.getProperties();
                values.put("taxonomy.label", VisioProperty.text(node.label()));
                values.put("taxonomy.displayKey", VisioProperty.text("N" + shapeId));
                VisioPresentation.labelLoss(doc, node.id(), node.label(), displayedLabel);
                values.put("taxonomy.id", VisioProperty.text(node.id()));
                values.put("taxonomy.type", VisioProperty.text(node.type()));
                values.put("taxonomy.relevance", VisioProperty.number(node.relevance()));
                values.put("taxonomy.anchor", VisioProperty.bool(node.anchor()));
                values.put("taxonomy.selectedForImpact", VisioProperty.bool(node.selectedForImpact()));
                values.put("taxonomy.container", VisioProperty.bool(node.container()));
                values.put("taxonomy.depth", VisioProperty.number(node.depth()));
                values.put("taxonomy.layer", VisioProperty.number(node.layer()));
                if (node.parentId() != null) values.put("taxonomy.parentId", VisioProperty.text(node.parentId()));
                if (assignment != null) {
                    putResponsibility(values, assignment);
                }
                page.getShapes().add(shape);
                if (node.container()) doc.getLosses().add(new VisioLoss("element", node.id(), "container", "MAPPED",
                        "Explicit visual container rendered with native membership for present descendants; it remains non-semantic and retains original parent identity in shape data."));
            }
            layerIdx += 1 + (layerNodes.size() - 1) / rowsPerColumn;
        }

        // Create connectors
        for (DiagramEdge edge : model.edges().stream().sorted(Comparator.comparing(DiagramEdge::id)).toList()) {
            String fromShape = nodeIdToShapeId.get(edge.sourceId());
            String toShape = nodeIdToShapeId.get(edge.targetId());
            if (fromShape != null && toShape != null) {
                VisioConnect connector = new VisioConnect(fromShape, toShape, edge.relationType());
                connector.getProperties().putAll(metadata.relationships().getOrDefault(edge.id(), Map.of()));
                var values = connector.getProperties();
                values.put("taxonomy.id", VisioProperty.text(edge.id()));
                values.put("taxonomy.type", VisioProperty.text(edge.relationType()));
                values.put("taxonomy.sourceId", VisioProperty.text(edge.sourceId()));
                values.put("taxonomy.targetId", VisioProperty.text(edge.targetId()));
                values.put("taxonomy.relevance", VisioProperty.number(edge.relevance()));
                if (edge.relationCategory() != null) values.put("taxonomy.relationCategory", VisioProperty.text(edge.relationCategory()));
                addProvenance(values, edge.metadata());
                page.getConnects().add(connector);
            }
        }

        log.info("VisioDiagram: {} shapes, {} connectors on page '{}'",
                page.getShapes().size(), page.getConnects().size(), page.getName());

        java.util.Set<String> selected = model.nodes().stream().filter(n -> n.anchor() || n.selectedForImpact())
                .map(n -> nodeIdToShapeId.get(n.id())).collect(java.util.stream.Collectors.toSet());
        if (options == null && !selected.isEmpty() && selected.size() < page.getShapes().size()) {
            VisioPage impact = new VisioPage("1", german ? "Anker- und Auswirkungsauswahl" : "Anchor and impact selection");
            page.getShapes().stream().filter(s -> selected.contains(s.getId())).map(VisioDiagramService::copyShape)
                    .forEach(impact.getShapes()::add);
            page.getConnects().stream().filter(c -> selected.contains(c.getFromShape()) && selected.contains(c.getToShape()))
                    .map(VisioDiagramService::copyConnect).forEach(impact.getConnects()::add);
            doc.getPages().add(impact);
        }
        var statusCounts = new java.util.TreeMap<String, Integer>();
        model.edges().forEach(edge -> statusCounts.merge(DiagramPresentation.relationshipStatus(edge.metadata()), 1, Integer::sum));
        if (model.edges().stream().anyMatch(edge -> edge.metadata() != null)) {
            statusCounts.forEach((status, count) -> doc.getProperties().put("taxonomy.relationships" + status.replaceAll("[^A-Za-z0-9]", ""), VisioProperty.number(count)));
            String legend = "Relationships: " + model.edges().size() + " | " + statusCounts.entrySet().stream()
                    .map(e -> e.getKey() + ": " + e.getValue()).collect(java.util.stream.Collectors.joining(" | "))
                    + "\nDashed = AI-proposed. Solid = other/unrecorded origin.\nAI support is not human approval; supplied review metadata remains authoritative.";
            if (german) {
                legend = "Beziehungen: " + model.edges().size() + " | " + statusCounts.entrySet().stream()
                        .map(e -> VisioPresentation.statusLabel(e.getKey(), true) + ": " + e.getValue())
                        .collect(java.util.stream.Collectors.joining(" | "))
                        + "\nGestrichelt: KI-Vorschlag. Durchgezogen: sonstige oder unbekannte Herkunft."
                        + "\nMaßgeblich ist der gespeicherte Prüfstatus.";
            }
            if (options != null) {
                legend = options.german() ? "Gestrichelt: KI-Vorschlag · Durchgezogen: sonstige / nicht erfasste Herkunft"
                        : "Dashed: AI-proposed · Solid: other or unrecorded origin";
            }
            double top = page.getShapes().stream().mapToDouble(shape -> shape.getY() + shape.getHeight() / 2).max().orElse(0);
            page.setLegend(new com.taxonomy.visio.VisioTextBox(legend, 5.5, top + 1.3, 10, 1.5));
        }
        VisioPresentation.apply(doc, decisionTree, options);
        if (options != null) {
            attachDrawingData(doc, model, decisionsByCode);
            VisioContextPages.addRequirementShape(doc, options.german());
        }
        return doc;
    }

    /** Add redundant details after page links, IDs and recorded properties have taken their reserved slots. */
    private static void attachDrawingData(VisioDocument document, DiagramModel model,
                                          Map<String, VisioDecisionTree.Node> decisions) {
        var nodes = new java.util.HashMap<String, DiagramNode>();
        model.nodes().forEach(node -> nodes.put(node.id(), node));
        for (var page : document.getPages()) {
            if (page.isEvidencePage()) continue;
            for (var shape : page.getShapes()) {
                var values = shape.getProperties();
                if (!values.containsKey("taxonomy.displayKey")) continue;
                String id = values.get("taxonomy.id").value();
                var decision = decisions.get(id);
                if (decision == null) continue;
                var reason = decision.reason();
                if (reason != null && !reason.isBlank() && values.size() < VisioPackageValidator.MAX_PROPERTIES) {
                    var existing = values.get("taxonomy.presenceReason");
                    if (existing == null || existing.value().isBlank()) values.put("taxonomy.presenceReason", VisioProperty.text(reason));
                    else if (!existing.value().equals(reason)) values.put("taxonomy.decisionReason", VisioProperty.text(reason));
                }
                if (nodes.get(id).responsibility() == null && decision.responsibility() != null
                        && values.size() + 4 <= VisioPackageValidator.MAX_PROPERTIES) {
                    putResponsibility(values, decision.responsibility());
                }
            }
            for (var link : page.getConnects()) {
                var values = link.getProperties();
                if (!values.containsKey("taxonomy.id") || values.size() + 2 > VisioPackageValidator.MAX_PROPERTIES) continue;
                values.put("taxonomy.sourceLabel", VisioProperty.text(nodes.get(values.get("taxonomy.sourceId").value()).label()));
                values.put("taxonomy.targetLabel", VisioProperty.text(nodes.get(values.get("taxonomy.targetId").value()).label()));
            }
        }
    }

    private static void putResponsibility(Map<String, VisioProperty> values, com.taxonomy.dto.TaxonomyResponsibilityLookup.Assignment assignment) {
        values.put("taxonomy.responsibilityUnitId", VisioProperty.text(assignment.unitId()));
        values.put("taxonomy.responsibilityName", VisioProperty.text(assignment.name()));
        values.put("taxonomy.responsibilitySourceId", VisioProperty.text(assignment.sourceId()));
        values.put("taxonomy.responsibilityColor", VisioProperty.text(assignment.color()));
    }

    private static void addProvenance(Map<String, VisioProperty> values, com.taxonomy.diagram.DiagramRelationMetadata m) {
        // Snapshot-origin/review attribution remains authoritative if supplied by the historical path.
        if (m == null) return;
        values.put("taxonomy.provenanceStatus", VisioProperty.text(DiagramPresentation.relationshipStatus(m)));
        values.put("taxonomy.proposed", VisioProperty.bool(m.proposed()));
        if (m.relationId() != null) values.put("taxonomy.relationId", VisioProperty.text(m.relationId().toString()));
        if (m.origin() != null) values.putIfAbsent("taxonomy.origin", VisioProperty.text(m.origin().name()));
        if (m.confidence() != null) {
            values.putIfAbsent("taxonomy.confidence", VisioProperty.number(m.confidence()));
            values.put("taxonomy.provenanceConfidence", VisioProperty.number(m.confidence()));
        }
        if (m.includedBecause() != null) values.put("taxonomy.includedBecause", VisioProperty.text(m.includedBecause()));
        if (m.derivationReason() != null) values.put("taxonomy.derivationReason", VisioProperty.text(m.derivationReason()));
        if (m.presenceReason() != null) values.put("taxonomy.presenceReason", VisioProperty.text(m.presenceReason()));
        if (m.seedType() != null) values.put("taxonomy.seedType", VisioProperty.text(m.seedType().name()));
        int index = 0;
        for (var evidence : m.requirementEvidence()) {
            String prefix = "taxonomy.evidence" + (++index);
            values.put(prefix + "sourceId", VisioProperty.text(evidence.sourceId()));
            values.put(prefix + "targetId", VisioProperty.text(evidence.targetId()));
            values.put(prefix + "type", VisioProperty.text(evidence.type()));
            values.put(prefix + "direction", VisioProperty.text(evidence.direction().name()));
            values.put(prefix + "contribution", VisioProperty.text(evidence.contribution().text()));
            values.put(prefix + "quote", VisioProperty.text(evidence.contribution().quote()));
            values.put(prefix + "condition", VisioProperty.text(evidence.contribution().condition()));
            var d = evidence.evidence();
            if (d != null) {
                values.put(prefix + "outcome", VisioProperty.text(String.valueOf(d.outcome())));
                values.put(prefix + "necessity", VisioProperty.text(String.valueOf(d.necessity())));
                if (d.contribution() != null) values.put(prefix + "decisionContribution", VisioProperty.text(d.contribution()));
                if (d.quote() != null) values.put(prefix + "decisionQuote", VisioProperty.text(d.quote()));
                if (d.condition() != null) values.put(prefix + "decisionCondition", VisioProperty.text(d.condition()));
                if (d.alternativeGroup() != null) values.put(prefix + "alternativeGroup", VisioProperty.text(d.alternativeGroup()));
                if (d.rationale() != null) values.put(prefix + "rationale", VisioProperty.text(d.rationale()));
                if (d.question() != null) values.put(prefix + "question", VisioProperty.text(d.question()));
            }
        }
    }

    private static VisioShape copyShape(VisioShape source) {
        VisioShape copy = new VisioShape(source.getId(), source.getText(), source.getX(), source.getY(),
                source.getWidth(), source.getHeight(), source.getType(), source.isAnchor());
        copy.getProperties().putAll(source.getProperties());
        return copy;
    }

    private static VisioConnect copyConnect(VisioConnect source) {
        VisioConnect copy = new VisioConnect(source.getFromShape(), source.getToShape(), source.getRelationType());
        copy.getProperties().putAll(source.getProperties());
        return copy;
    }

    private static void validateGraph(DiagramModel model) {
        if (model == null || model.nodes() == null || model.edges() == null || model.layout() == null) {
            throw new IllegalArgumentException("Complete canonical graph required");
        }
        if (model.nodes().size() > 10_000 || model.edges().size() > 30_000) {
            throw new IllegalArgumentException("Visio profile limit: 10,000 elements and 30,000 relationships");
        }
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (var node : model.nodes()) {
            if (node == null || node.id() == null || node.id().isBlank() || !ids.add(node.id())
                    || node.type() == null || node.type().isBlank()) throw new IllegalArgumentException("Invalid or duplicate canonical element identity/type");
            score(node.relevance());
        }
        for (var node : model.nodes()) {
            if (node.parentId() != null && (!ids.contains(node.parentId()) || node.id().equals(node.parentId()))) {
                throw new IllegalArgumentException("Invalid canonical parent reference");
            }
        }
        java.util.Set<String> edgeIds = new java.util.HashSet<>();
        for (var edge : model.edges()) {
            if (edge == null || edge.id() == null || edge.id().isBlank() || !edgeIds.add(edge.id())
                    || !ids.contains(edge.sourceId()) || !ids.contains(edge.targetId())
                    || edge.relationType() == null || edge.relationType().isBlank()) {
                throw new IllegalArgumentException("Invalid or duplicate canonical relationship identity/reference/type");
            }
            if (edge.sourceId().equals(edge.targetId())) throw new IllegalArgumentException("Visio profile does not support self-loop connector geometry: " + edge.id());
            score(edge.relevance());
        }
    }

    private static void score(double value) {
        if (!Double.isFinite(value) || value < 0 || value > 1) throw new IllegalArgumentException("Relevance must be finite and in [0,1]");
    }
}
