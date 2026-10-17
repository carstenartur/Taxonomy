package com.taxonomy.export;

import com.taxonomy.diagram.DiagramScene;
import java.awt.Font;
import java.util.Locale;

/** Produces a standalone, script-free SVG with bounded text and recoverable saved labels. */
public class SvgDiagramRenderer {
    public String render(DiagramScene scene) {
        if (scene == null || scene.isEmpty())
            throw new IllegalArgumentException("Architecture scene must contain at least one node");
        var summary = DiagramPresentation.inventorySummary(scene);
        double footer = Math.max(scene.height(), scene.nodes().stream().mapToDouble(n -> n.y() + n.height() + 24).max().orElse(0));
        double height = footer + summary.size() * 16 + 24;
        StringBuilder svg = new StringBuilder(16_384);
        svg.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            .append("<svg xmlns=\"http://www.w3.org/2000/svg\" role=\"img\" viewBox=\"0 0 ")
            .append(number(scene.width())).append(' ').append(number(height)).append("\" width=\"")
            .append(number(scene.width())).append("\" height=\"").append(number(height)).append("\">\n")
            .append("<title>").append(xml(scene.title())).append("</title>\n")
            .append("<desc>Requirement-derived architecture diagram. Full labels and relationship provenance are retained in per-element titles. AI verification is not human approval.</desc>\n")
            .append("<defs><marker id=\"arrow\" markerWidth=\"10\" markerHeight=\"8\" refX=\"9\" refY=\"4\" orient=\"auto\"><path d=\"M0,0 L10,4 L0,8 z\" fill=\"#586174\"/></marker></defs>\n")
            .append("<rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>\n");
        for (var edge : scene.edges()) {
            String status = DiagramPresentation.relationshipStatus(edge.metadata());
            boolean proposed = edge.metadata() != null && edge.metadata().proposed();
            svg.append("<g class=\"architecture-edge\" data-edge-id=\"").append(xml(edge.id()))
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
            svg.append("</g>\n");
        }
        for (var node : scene.nodes()) {
            double width = node.width() - 28;
            svg.append("<g class=\"architecture-node\" data-node-id=\"").append(xml(node.id()))
                .append("\" transform=\"translate(").append(number(node.x())).append(',').append(number(node.y())).append(")\">")
                .append("<title>").append(xml(node.id() + " | " + node.label() + " | " + node.type()
                        + " | Depth " + node.depth() + (node.container() ? " | Container" : ""))).append("</title>\n")
                .append("<rect width=\"").append(number(node.width())).append("\" height=\"").append(number(node.height()))
                .append("\" rx=\"10\" fill=\"").append(DiagramPresentation.fill(node.type())).append("\" stroke=\"")
                .append(node.anchor() ? "#172554" : "#64748b").append("\" stroke-width=\"").append(node.anchor() ? "3" : "1.3").append("\"/>\n");
            text(svg, 14, 17, "start", 10, true, "#334155", DiagramTextWrapper.wrapMeasured(node.id(), font(10, true), width, 1, "ID unrecorded").getFirst());
            var labels = DiagramTextWrapper.wrapMeasured(node.label(), font(13, false), width, 2, "Unnamed architecture element");
            for (int i = 0; i < labels.size(); i++) text(svg, 14, 36 + i * 17, "start", 13, false, "#0f172a", labels.get(i));
            text(svg, 14, 72, "start", 10, false, "#475569", DiagramTextWrapper.wrapMeasured(
                    node.type() + " · " + DiagramPresentation.percent(node.relevance()), font(10, false), width, 1, "Unclassified").getFirst());
            svg.append("</g>\n");
        }
        for (int i = 0; i < summary.size(); i++) text(svg, 14, footer + 16 + i * 16, "start", 11, false, "#334155",
                DiagramTextWrapper.wrapMeasured(summary.get(i), font(11, false), scene.width() - 28, 1, "").getFirst());
        return svg.append("</svg>\n").toString();
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
