package com.taxonomy.export;

import com.taxonomy.diagram.DiagramEdge;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;

import java.util.HashMap;
import java.util.Map;

/**
 * Exports a {@link DiagramModel} as Structurizr DSL text.
 *
 * <p>This is the reverse of {@code StructurizrDslParser}: given an internal architecture
 * model, it produces a Structurizr custom view with explicit taxonomy identities.
 * The flat model does not imply a C4 software-system/container hierarchy.</p>
 */
public class StructurizrExportService {

    /**
     * Exports the given {@link DiagramModel} as a Structurizr DSL workspace string.
     *
     * @param model the neutral diagram model
     * @return Structurizr DSL text
     */
    public String export(DiagramModel model) {
        StringBuilder sb = new StringBuilder();
        sb.append("workspace {\n\n");
        sb.append("    model {\n");

        Map<String, String> idMap = new HashMap<>();
        int counter = 1;

        for (DiagramNode node : model.nodes()) {
            // Container-only nodes are visual grouping constructs — skip them
            // so that they do not become false architecture semantics in the export.
            if (node.container()) continue;
            // Use node.id() to guarantee uniqueness; fall back to label for readability
            String id = sanitizeId(node.id(), counter++);
            // Ensure uniqueness even after sanitization
            String baseId = id;
            while (idMap.containsValue(id)) id = baseId + "_" + counter++;
            idMap.put(node.id(), id);
            // The neutral graph has no C4 system/container hierarchy. Custom elements
            // preserve its taxonomy semantics without inventing software boundaries.
            String c4Type = "element";
            sb.append("        ").append(id).append(" = ").append(c4Type)
                    .append(" \"").append(escapeQuotes(node.label() + " [" + node.id() + "]")).append("\"");
            if (node.type() != null && !node.type().isEmpty()) {
                sb.append(" \"").append(escapeQuotes(node.type())).append("\"");
            }
            sb.append(" {\n            properties {\n");
            sb.append("                \"taxonomy.id\" \"").append(escapeQuotes(node.id())).append("\"\n");
            sb.append("                \"taxonomy.label\" \"").append(escapeQuotes(node.label())).append("\"\n");
            sb.append("            }\n        }\n");
        }

        if (!model.nodes().isEmpty() && !model.edges().isEmpty()) {
            sb.append('\n');
        }

        for (DiagramEdge edge : model.edges()) {
            // Skip edges referencing nodes not declared in the model
            if (!idMap.containsKey(edge.sourceId()) || !idMap.containsKey(edge.targetId())) {
                continue;
            }
            String src = idMap.get(edge.sourceId());
            String tgt = idMap.get(edge.targetId());
            String desc = edge.relationType() != null ? edge.relationType() : "";
            sb.append("        ").append(src).append(" -> ").append(tgt)
                    .append(" \"").append(escapeQuotes(desc)).append("\"")
                    .append('\n');
        }

        sb.append("    }\n\n");
        sb.append("    views {\n");
        sb.append("        custom \"overview\" {\n");
        sb.append("            include *\n");
        sb.append("            autoLayout\n");
        sb.append("        }\n");
        sb.append("    }\n");
        sb.append("}\n");

        return sb.toString();
    }

    String sanitizeId(String label, int counter) {
        if (label == null || label.isBlank()) {
            return "element" + counter;
        }
        String id = label.replaceAll("[^a-zA-Z0-9]", "");
        if (id.isEmpty()) {
            return "element" + counter;
        }
        return Character.toLowerCase(id.charAt(0)) + id.substring(1);
    }

    private String escapeQuotes(String s) {
        return s == null ? "" : s.replace("\"", "\\\"");
    }
}
