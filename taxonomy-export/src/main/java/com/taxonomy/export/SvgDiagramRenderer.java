package com.taxonomy.export;

import com.taxonomy.diagram.DiagramScene;
import java.awt.Font;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Produces a standalone SVG with compact shapes and a visible, complete evidence inventory. */
public class SvgDiagramRenderer {
    private static final int EVIDENCE_FONT_SIZE = 14;
    private static final double EVIDENCE_COLUMN_WIDTH = 720;
    private static final double EVIDENCE_TEXT_WIDTH = EVIDENCE_COLUMN_WIDTH - 28;
    private static final double EVIDENCE_LINE_HEIGHT = 20;

    public String render(DiagramScene scene) {
        return render(scene, Map.of());
    }

    public String render(DiagramScene scene, Map<String, String> nodeReasons) {
        return render(scene, nodeReasons, Map.of());
    }

    public String render(DiagramScene scene, Map<String, String> nodeReasons,
                         Map<String, Map<String, String>> relationReasons) {
        if (scene == null || scene.isEmpty())
            throw new IllegalArgumentException("Architecture scene must contain at least one node");
        var nodeReferences = new LinkedHashMap<String, String>();
        scene.nodes().forEach(node -> nodeReferences.putIfAbsent(node.id(), "evidence-node-" + (nodeReferences.size() + 1)));
        var edgeReferences = new LinkedHashMap<String, String>();
        scene.edges().forEach(edge -> edgeReferences.putIfAbsent(edge.id(), "evidence-edge-" + (edgeReferences.size() + 1)));
        var evidence = evidence(scene, nodeReasons == null ? Map.of() : nodeReasons,
                relationReasons == null ? Map.of() : relationReasons, nodeReferences, edgeReferences);
        // Grow the sheet in both dimensions instead of making a narrow strip or shrinking the type.
        int columns = Math.max(1, (int) Math.ceil(Math.sqrt(
                evidence.size() * EVIDENCE_LINE_HEIGHT * Math.sqrt(2) / EVIDENCE_COLUMN_WIDTH)));
        int linesPerColumn = (evidence.size() + columns - 1) / columns;
        double width = Math.max(scene.width(), columns * EVIDENCE_COLUMN_WIDTH);
        var summary = new ArrayList<String>();
        for (String line : DiagramPresentation.inventorySummary(scene)) summary.addAll(
                DiagramTextWrapper.wrapMeasured(line, font(11, false), width - 28, Integer.MAX_VALUE, ""));
        double footer = Math.max(scene.height(), scene.nodes().stream().mapToDouble(n -> n.y() + n.height() + 24).max().orElse(0));
        double evidenceTitleY = footer + summary.size() * 16 + 40;
        var columnHeaders = new ArrayList<List<String>>();
        int headerLines = 1;
        for (int column = 0; column < columns; column++) {
            int firstLine = column * linesPerColumn;
            int end = Math.min(evidence.size(), firstLine + linesPerColumn);
            while (firstLine + 1 < end && evidence.get(firstLine).value().isEmpty()) firstLine++;
            var first = evidence.get(firstLine);
            var header = DiagramTextWrapper.wrapMeasured("Evidence " + (column + 1) + "/" + columns
                            + " | " + first.kind() + " " + first.id() + (first.first() ? "" : " (continued)"),
                    font(EVIDENCE_FONT_SIZE, true), EVIDENCE_TEXT_WIDTH, Integer.MAX_VALUE, "Evidence");
            columnHeaders.add(header);
            headerLines = Math.max(headerLines, header.size());
        }
        double evidenceTop = evidenceTitleY + 36 + headerLines * EVIDENCE_LINE_HEIGHT;
        double height = evidenceTop + linesPerColumn * EVIDENCE_LINE_HEIGHT + 20;
        StringBuilder svg = new StringBuilder(16_384);
        svg.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            .append("<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" role=\"img\" viewBox=\"0 0 ")
            .append(number(width)).append(' ').append(number(height)).append("\" width=\"")
            .append(number(width)).append("\" height=\"").append(number(height)).append("\">\n")
            .append("<title>").append(xml(scene.title())).append("</title>\n")
            .append("<desc>Requirement-derived architecture diagram. Shapes link to the visible inventory of complete labels, scores and recorded explanations below. AI verification is not human approval.</desc>\n")
            .append("<defs><marker id=\"arrow\" markerWidth=\"10\" markerHeight=\"8\" refX=\"9\" refY=\"4\" orient=\"auto\"><path d=\"M0,0 L10,4 L0,8 z\" fill=\"#586174\"/></marker></defs>\n")
            .append("<rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>\n");
        for (var edge : scene.edges()) {
            String status = DiagramPresentation.relationshipStatus(edge.metadata());
            boolean proposed = edge.metadata() != null && edge.metadata().proposed();
            svg.append("<a xlink:href=\"#").append(edgeReferences.get(edge.id())).append("\"><g class=\"architecture-edge\" data-edge-id=\"").append(xml(edge.id()))
                .append("\" data-origin=\"").append(edge.metadata() == null || edge.metadata().origin() == null ? "unrecorded" : edge.metadata().origin().name())
                .append("\"><title>").append(xml(edge.id() + " | " + edge.sourceId() + " -> " + edge.targetId() + " | " + edge.relationType()
                        + "\n" + String.join("\n", DiagramPresentation.relationshipDetails(edge.metadata())))).append("</title>\n")
                .append("<line x1=\"").append(number(edge.sourceX())).append("\" y1=\"").append(number(edge.sourceY()))
                .append("\" x2=\"").append(number(edge.targetX())).append("\" y2=\"").append(number(edge.targetY()))
                .append("\" stroke=\"").append(proposed ? "#9333ea" : "#586174").append("\" stroke-width=\"")
                .append(number(1.2 + Math.max(0, edge.relevance()) * 1.8)).append('"');
            if (proposed) svg.append(" stroke-dasharray=\"6 4\"");
            svg.append(" marker-end=\"url(#arrow)\"/>\n");
            svg.append("<metadata><relationship xmlns=\"urn:taxonomy:diagram\">");
            metadataValue(svg, "provenance", edge.metadata());
            svg.append("</relationship></metadata>\n");
            double captionWidth = Math.max(12, Math.min(210, Math.abs(edge.targetX() - edge.sourceX()) - 12));
            double captionX = (edge.sourceX() + edge.targetX()) / 2;
            double captionY = (edge.sourceY() + edge.targetY()) / 2;
            text(svg, captionX, captionY - 15, "middle", 11, false, "#394150",
                    DiagramTextWrapper.wrapMeasured(edge.relationType(), font(11, false), captionWidth, 1, "Relationship").getFirst());
            text(svg, captionX, captionY + 23, "middle", 11, false, "#394150",
                    DiagramTextWrapper.wrapMeasured(status, font(11, false), captionWidth, 1, "Origin unrecorded").getFirst());
            svg.append("</g></a>\n");
        }
        for (var node : scene.nodes()) {
            double nodeTextWidth = node.width() - 28;
            svg.append("<a xlink:href=\"#").append(nodeReferences.get(node.id())).append("\"><g class=\"architecture-node\" data-node-id=\"").append(xml(node.id()))
                .append("\" transform=\"translate(").append(number(node.x())).append(',').append(number(node.y())).append(")\">")
                .append("<title>").append(xml(node.id() + " | " + node.label() + " | " + node.type()
                        + " | Depth " + node.depth() + (node.container() ? " | Container" : ""))).append("</title>\n")
                .append("<rect width=\"").append(number(node.width())).append("\" height=\"").append(number(node.height()))
                .append("\" rx=\"10\" fill=\"").append(DiagramPresentation.fill(node.type())).append("\" stroke=\"")
                .append(node.anchor() ? "#172554" : "#64748b").append("\" stroke-width=\"").append(node.anchor() ? "3" : "1.3").append("\"/>\n");
            text(svg, 14, 17, "start", 10, true, "#334155", DiagramTextWrapper.wrapMeasured(node.id(), font(10, true), nodeTextWidth, 1, "ID unrecorded").getFirst());
            var labels = DiagramTextWrapper.wrapMeasured(node.label(), font(13, false), nodeTextWidth, 2, "Unnamed architecture element");
            for (int i = 0; i < labels.size(); i++) text(svg, 14, 36 + i * 17, "start", 13, false, "#0f172a", labels.get(i));
            text(svg, 14, 72, "start", 10, false, "#475569", DiagramTextWrapper.wrapMeasured(
                    node.type() + " · " + DiagramPresentation.percent(node.relevance()), font(10, false), nodeTextWidth, 1, "Unclassified").getFirst());
            svg.append("</g></a>\n");
        }
        for (int i = 0; i < summary.size(); i++) text(svg, 14, footer + 16 + i * 16, "start", 11, false, "#334155",
                summary.get(i));
        text(svg, 14, evidenceTitleY, "start", 16, true, "#0f172a", "Scores and complete explanations");
        for (int column = 0; column < columns; column++) {
            double x = 14 + column * EVIDENCE_COLUMN_WIDTH;
            var header = columnHeaders.get(column);
            for (int line = 0; line < header.size(); line++) text(svg, x,
                    evidenceTitleY + 28 + line * EVIDENCE_LINE_HEIGHT, "start", EVIDENCE_FONT_SIZE, true, "#334155", header.get(line));
            int index = column * linesPerColumn;
            int end = Math.min(evidence.size(), index + linesPerColumn);
            while (index < end) {
                var first = evidence.get(index);
                svg.append("<g class=\"architecture-evidence\" data-evidence-kind=\"").append(first.kind())
                        .append("\" data-evidence-id=\"").append(xml(first.id())).append('"');
                if (first.first()) svg.append(" id=\"").append(first.reference()).append('"');
                svg.append(">\n");
                do {
                    var line = evidence.get(index);
                    if (!line.value().isEmpty()) text(svg, x, evidenceTop + (index % linesPerColumn) * EVIDENCE_LINE_HEIGHT,
                            "start", EVIDENCE_FONT_SIZE, line.bold(), "#0f172a", line.value());
                    index++;
                } while (index < end && evidence.get(index).reference().equals(first.reference()));
                svg.append("</g>\n");
            }
        }
        return svg.append("</svg>\n").toString();
    }

    private record EvidenceLine(String kind, String id, String reference, String value, boolean bold, boolean first) { }

    private static List<EvidenceLine> evidence(DiagramScene scene, Map<String, String> nodeReasons,
                                               Map<String, Map<String, String>> relationReasons,
                                               Map<String, String> nodeReferences, Map<String, String> edgeReferences) {
        var lines = new ArrayList<EvidenceLine>();
        var seenNodes = new HashSet<String>();
        for (var node : scene.nodes()) if (seenNodes.add(node.id())) {
            var parts = new ArrayList<String>();
            parts.add("Node " + node.id() + " | " + DiagramPresentation.percent(node.relevance()) + " | Depth " + node.depth()
                    + (node.container() ? " | Container" : ""));
            parts.add(node.label());
            parts.add((node.type() == null ? "Unclassified" : node.type()) + " | Parent: "
                    + (node.parentId() == null ? "not included" : node.parentId()));
            String reason = nodeReasons.get(node.id());
            if (reason != null && !reason.isBlank()) parts.add("Explanation: " + reason);
            appendEvidence(lines, "node", node.id(), nodeReferences.get(node.id()), parts);
        }
        var seenEdges = new HashSet<String>();
        for (var edge : scene.edges()) if (seenEdges.add(edge.id())) {
            var parts = new ArrayList<String>();
            parts.add("Relationship " + edge.id() + " | " + edge.sourceId() + " -> " + edge.targetId());
            parts.add(edge.relationType() + " | " + DiagramPresentation.percent(edge.relevance()) + " | " + edge.relationCategory());
            parts.addAll(DiagramPresentation.relationshipDetails(edge.metadata()));
            parts.addAll(DiagramPresentation.relationshipEvidenceDetails(relationReasons.get(edge.id()), false));
            appendEvidence(lines, "relationship", edge.id(), edgeReferences.get(edge.id()), parts);
        }
        return lines;
    }

    private static void appendEvidence(List<EvidenceLine> lines, String kind, String id, String reference, List<String> parts) {
        boolean first = true;
        for (int part = 0; part < parts.size(); part++) {
            boolean bold = part == 0;
            for (String line : DiagramTextWrapper.wrapMeasured(parts.get(part), font(EVIDENCE_FONT_SIZE, bold),
                    EVIDENCE_TEXT_WIDTH, Integer.MAX_VALUE, "Unrecorded")) {
                lines.add(new EvidenceLine(kind, id, reference, line, bold, first));
                first = false;
            }
        }
        lines.add(new EvidenceLine(kind, id, reference, "", false, false));
    }

    private static Font font(int size, boolean bold) { return new Font("Arial", bold ? Font.BOLD : Font.PLAIN, size); }
    private static void text(StringBuilder svg, double x, double y, String anchor, int size, boolean bold, String color, String value) {
        svg.append("<text x=\"").append(number(x)).append("\" y=\"").append(number(y)).append("\" text-anchor=\"").append(anchor)
            .append("\" font-family=\"Arial, sans-serif\" font-size=\"").append(size).append('"');
        if (bold) svg.append(" font-weight=\"bold\"");
        if (!value.isEmpty()) svg.append(" textLength=\"").append(number(DiagramTextWrapper.measure(value, font(size, bold))))
            .append("\" lengthAdjust=\"spacingAndGlyphs\"");
        svg.append(" fill=\"").append(color).append("\">").append(xml(value)).append("</text>\n");
    }
    /** Record fields are separate XML elements, including explicit absence and nested scoped evidence. */
    private static void metadataValue(StringBuilder xml, String name, Object value) {
        xml.append('<').append(name);
        if (value == null) { xml.append(" recorded=\"false\"/>"); return; }
        xml.append('>');
        if (value instanceof java.util.List<?> items) {
            for (Object item : items) metadataValue(xml, "item", item);
        } else if (value.getClass().isRecord()) {
            for (var component : value.getClass().getRecordComponents()) {
                try { metadataValue(xml, component.getName(), component.getAccessor().invoke(value)); }
                catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot serialize recorded provenance", error); }
            }
        } else xml.append(xml(value instanceof Enum<?> e ? e.name() : value.toString()));
        xml.append("</").append(name).append('>');
    }
    private static String xml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
    private static String number(double value) { return String.format(Locale.ROOT, "%.2f", value); }
}
