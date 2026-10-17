package com.taxonomy.export;

import com.taxonomy.diagram.DiagramEdge;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
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
        validateGraph(model);
        VisioDocument doc = new VisioDocument();
        doc.getProperties().putAll(metadata.document());
        doc.getProperties().put("taxonomy.exportProfile", VisioProperty.text(VisioHandoffProfile.ID));
        doc.getProperties().put("taxonomy.scoreSemantics", VisioProperty.text("relevance is a normalized selection score in [0,1], not a probability"));
        doc.getLosses().addAll(metadata.losses());
        doc.getLosses().add(new VisioLoss("document", "", "layout", "MAPPED",
                "Generated layer layout in inches; browser geometry, routing and typography are not copied."));
        VisioPage page = new VisioPage("0", model.title() != null ? model.title() : "Architecture View");
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
        int layerIdx = 0;
        for (var entry : layerGroups.entrySet().stream()
                .sorted(Comparator.comparingInt(Map.Entry::getKey)).toList()) {
            List<DiagramNode> layerNodes = entry.getValue();
            for (int i = 0; i < layerNodes.size(); i++) {
                DiagramNode node = layerNodes.get(i);
                String shapeId = nodeIdToShapeId.get(node.id());

                double x = MARGIN_X + layerIdx * H_GAP;
                double y = MARGIN_Y + i * V_GAP;

                VisioShape shape = new VisioShape(
                        shapeId,
                        VisioPresentation.display(node.id(), 24, 1) + "\n" + VisioPresentation.display(node.label(), 24, 3) + "\nDepth " + node.depth(),
                        x, y,
                        SHAPE_WIDTH, SHAPE_HEIGHT,
                        node.type(),
                        node.anchor());
                shape.getProperties().putAll(metadata.elements().getOrDefault(node.id(), Map.of()));
                var values = shape.getProperties();
                values.put("taxonomy.label", VisioProperty.text(node.label()));
                values.put("taxonomy.displayKey", VisioProperty.text("N" + shapeId));
                VisioPresentation.labelLoss(doc, node.id(), node.label(), VisioPresentation.display(node.label(), 24, 3));
                values.put("taxonomy.id", VisioProperty.text(node.id()));
                values.put("taxonomy.type", VisioProperty.text(node.type()));
                values.put("taxonomy.relevance", VisioProperty.number(node.relevance()));
                values.put("taxonomy.anchor", VisioProperty.bool(node.anchor()));
                values.put("taxonomy.selectedForImpact", VisioProperty.bool(node.selectedForImpact()));
                values.put("taxonomy.container", VisioProperty.bool(node.container()));
                values.put("taxonomy.depth", VisioProperty.number(node.depth()));
                values.put("taxonomy.layer", VisioProperty.number(node.layer()));
                if (node.parentId() != null) values.put("taxonomy.parentId", VisioProperty.text(node.parentId()));
                page.getShapes().add(shape);
                if (node.container()) doc.getLosses().add(new VisioLoss("element", node.id(), "container", "MAPPED",
                        "Visual container rendered as a flat rectangle, explicitly marked non-semantic; parent identity retained in shape data."));
            }
            layerIdx++;
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
        if (!selected.isEmpty() && selected.size() < page.getShapes().size()) {
            VisioPage impact = new VisioPage("1", "Anchor and impact selection");
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
                    + "\nDashed purple = AI-proposed. Solid = other/unrecorded origin.\nHuman approval is not recorded; AI support is not human approval.";
            double top = page.getShapes().stream().mapToDouble(shape -> shape.getY() + shape.getHeight() / 2).max().orElse(0);
            page.setLegend(new com.taxonomy.visio.VisioTextBox(legend, 5.5, top + 1.3, 10, 1.5));
        }
        VisioPresentation.apply(doc);
        return doc;
    }

    private static void addProvenance(Map<String, VisioProperty> values, com.taxonomy.diagram.DiagramRelationMetadata m) {
        // Snapshot-origin/review attribution remains authoritative if supplied by the historical path.
        if (m == null) return;
        values.put("taxonomy.provenanceStatus", VisioProperty.text(DiagramPresentation.relationshipStatus(m)));
        values.put("taxonomy.proposed", VisioProperty.bool(m.proposed()));
        if (m.relationId() != null) values.put("taxonomy.relationId", VisioProperty.text(m.relationId().toString()));
        if (m.origin() != null) values.putIfAbsent("taxonomy.origin", VisioProperty.text(m.origin().name()));
        if (m.confidence() != null) values.put("taxonomy.confidence", VisioProperty.number(m.confidence()));
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
