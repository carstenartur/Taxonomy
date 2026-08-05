package com.taxonomy.export;

import com.taxonomy.diagram.DiagramScene;
import com.taxonomy.diagram.DiagramSceneEdge;
import com.taxonomy.diagram.DiagramSceneNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Produces a standalone, script-free SVG from a render-ready diagram scene. */
public class SvgDiagramRenderer {

    public String render(DiagramScene scene) {
        if (scene == null || scene.isEmpty()) {
            throw new IllegalArgumentException("Architecture scene must contain at least one node");
        }

        StringBuilder svg = new StringBuilder(16_384);
        svg.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<svg xmlns=\"http://www.w3.org/2000/svg\" role=\"img\" ")
                .append("viewBox=\"0 0 ").append(number(scene.width())).append(' ')
                .append(number(scene.height())).append("\" width=\"")
                .append(number(scene.width())).append("\" height=\"")
                .append(number(scene.height())).append("\">\n")
                .append("  <title>").append(xml(scene.title())).append("</title>\n")
                .append("  <desc>Requirement-derived architecture diagram</desc>\n")
                .append("  <defs><marker id=\"arrow\" markerWidth=\"10\" markerHeight=\"8\" ")
                .append("refX=\"9\" refY=\"4\" orient=\"auto\"><path d=\"M0,0 L10,4 L0,8 z\" ")
                .append("fill=\"#586174\"/></marker></defs>\n")
                .append("  <rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>\n");

        for (DiagramSceneEdge edge : scene.edges()) {
            svg.append("  <g class=\"architecture-edge\" data-edge-id=\"")
                    .append(xml(edge.id())).append("\">\n")
                    .append("    <line x1=\"").append(number(edge.sourceX()))
                    .append("\" y1=\"").append(number(edge.sourceY()))
                    .append("\" x2=\"").append(number(edge.targetX()))
                    .append("\" y2=\"").append(number(edge.targetY()))
                    .append("\" stroke=\"#586174\" stroke-width=\"")
                    .append(number(1.2 + Math.max(0.0, edge.relevance()) * 1.8))
                    .append("\" marker-end=\"url(#arrow)\"/>\n");
            String relation = edge.relationType() == null ? "" : edge.relationType();
            if (!relation.isBlank()) {
                double x = (edge.sourceX() + edge.targetX()) / 2.0;
                double y = (edge.sourceY() + edge.targetY()) / 2.0 - 6.0;
                svg.append("    <text x=\"").append(number(x)).append("\" y=\"")
                        .append(number(y))
                        .append("\" text-anchor=\"middle\" font-family=\"Arial, sans-serif\" ")
                        .append("font-size=\"11\" fill=\"#394150\">")
                        .append(xml(relation)).append("</text>\n");
            }
            svg.append("  </g>\n");
        }

        for (DiagramSceneNode node : scene.nodes()) {
            svg.append("  <g class=\"architecture-node\" data-node-id=\"")
                    .append(xml(node.id())).append("\" transform=\"translate(")
                    .append(number(node.x())).append(',').append(number(node.y())).append(")\">\n")
                    .append("    <rect width=\"").append(number(node.width()))
                    .append("\" height=\"").append(number(node.height()))
                    .append("\" rx=\"10\" fill=\"").append(fill(node.type()))
                    .append("\" stroke=\"").append(node.anchor() ? "#172554" : "#64748b")
                    .append("\" stroke-width=\"").append(node.anchor() ? "3" : "1.3")
                    .append("\"/>\n")
                    .append("    <text x=\"14\" y=\"22\" font-family=\"Arial, sans-serif\" ")
                    .append("font-size=\"11\" font-weight=\"bold\" fill=\"#334155\">")
                    .append(xml(node.id())).append("</text>\n");

            List<String> lines = wrap(node.label(), 32, 2);
            for (int index = 0; index < lines.size(); index++) {
                svg.append("    <text x=\"14\" y=\"")
                        .append(44 + index * 17)
                        .append("\" font-family=\"Arial, sans-serif\" font-size=\"13\" ")
                        .append("fill=\"#0f172a\">")
                        .append(xml(lines.get(index))).append("</text>\n");
            }
            svg.append("    <text x=\"").append(number(node.width() - 12))
                    .append("\" y=\"18\" text-anchor=\"end\" font-family=\"Arial, sans-serif\" ")
                    .append("font-size=\"10\" fill=\"#475569\">")
                    .append(xml(node.type())).append(" · ")
                    .append(Math.round(node.relevance() * 100.0)).append("%</text>\n")
                    .append("  </g>\n");
        }

        svg.append("</svg>\n");
        return svg.toString();
    }

    private static List<String> wrap(String value, int maximum, int maximumLines) {
        String normalized = value == null || value.isBlank() ? "Unnamed architecture element" : value.strip();
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : normalized.split("\\s+")) {
            if (!line.isEmpty() && line.length() + word.length() + 1 > maximum) {
                lines.add(line.toString());
                line.setLength(0);
                if (lines.size() == maximumLines - 1) {
                    break;
                }
            }
            if (!line.isEmpty()) line.append(' ');
            line.append(word);
        }
        if (!line.isEmpty() && lines.size() < maximumLines) {
            String tail = line.toString();
            if (lines.size() == maximumLines - 1 && tail.length() > maximum) {
                tail = tail.substring(0, Math.max(1, maximum - 1)) + "…";
            }
            lines.add(tail);
        }
        return lines;
    }

    private static String fill(String type) {
        if (type == null) return "#f1f5f9";
        return switch (type) {
            case "Capabilities" -> "#dbeafe";
            case "Business Processes", "Business Roles" -> "#dcfce7";
            case "Core Services", "COI Services", "Services" -> "#fef3c7";
            case "User Applications", "Applications" -> "#f3e8ff";
            case "Information Products" -> "#ffe4e6";
            case "Communications Services" -> "#cffafe";
            default -> "#f1f5f9";
        };
    }

    private static String xml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
