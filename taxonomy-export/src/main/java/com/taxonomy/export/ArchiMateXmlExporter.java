package com.taxonomy.export;

import com.taxonomy.archimate.ArchiMateElement;
import com.taxonomy.archimate.ArchiMateModel;
import com.taxonomy.archimate.ArchiMateRelationship;
import com.taxonomy.archimate.ArchiMateView;
import com.taxonomy.archimate.ArchiMateViewConnection;
import com.taxonomy.archimate.ArchiMateViewNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Serializes an {@link ArchiMateModel} to the ArchiMate Model Exchange File Format (XML).
 * <p>
 * Produces a standards-compliant ArchiMate 3.x exchange file that can be imported into
 * Archi, ARIS, Sparx EA, BiZZdesign, and other ArchiMate-compatible tools.
 * <p>
 * No external XML libraries are used – XML is assembled with a {@link StringBuilder}
 * and all text content is properly escaped.
 */
public class ArchiMateXmlExporter {

    private static final Logger log = LoggerFactory.getLogger(ArchiMateXmlExporter.class);

    /**
     * Exports the model as a UTF-8 encoded ArchiMate XML byte array.
     *
     * @param model the ArchiMate model to export
     * @return UTF-8 encoded XML bytes
     */
    public byte[] export(ArchiMateModel model) {
        String xml = buildXml(model);
        log.info("ArchiMate XML export: {} chars", xml.length());
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    private String buildXml(ArchiMateModel model) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<model xmlns=\"http://www.opengroup.org/xsd/archimate/3.0/\"\n");
        sb.append("       xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        // The 3.0 namespace is retained for backward compatibility; the schema document
        // was updated to revision 3.1 by The Open Group without changing the namespace URI.
        sb.append("       xsi:schemaLocation=\"http://www.opengroup.org/xsd/archimate/3.0/")
          .append(" http://www.opengroup.org/xsd/archimate/3.1/archimate3_Diagram.xsd\"\n");
        sb.append("       identifier=\"id-model-1\">\n");
        sb.append("  <name xml:lang=\"en\">").append(escapeXml(model.title())).append("</name>\n");

        appendElements(sb, model.elements());
        appendRelationships(sb, model.relationships());
        appendOrganizations(sb, model.organizations());
        appendViews(sb, model.view());

        sb.append("</model>");
        return sb.toString();
    }

    private void appendElements(StringBuilder sb, List<ArchiMateElement> elements) {
        sb.append("  <elements>\n");
        for (ArchiMateElement el : elements) {
            sb.append("    <element identifier=\"id-").append(escapeXml(el.id()))
              .append("\" xsi:type=\"").append(escapeXml(el.archiMateType())).append("\">\n");
            sb.append("      <name xml:lang=\"en\">").append(escapeXml(el.label()))
              .append("</name>\n");
            if (el.documentation() != null && !el.documentation().isBlank()) {
                sb.append("      <documentation xml:lang=\"en\">")
                  .append(escapeXml(el.documentation())).append("</documentation>\n");
            }
            sb.append("    </element>\n");
        }
        sb.append("  </elements>\n");
    }

    private void appendRelationships(StringBuilder sb, List<ArchiMateRelationship> relationships) {
        sb.append("  <relationships>\n");
        for (ArchiMateRelationship rel : relationships) {
            sb.append("    <relationship identifier=\"id-rel-").append(escapeXml(rel.id()))
              .append("\" xsi:type=\"").append(escapeXml(rel.archiMateType()))
              .append("\" source=\"id-").append(escapeXml(rel.sourceId()))
              .append("\" target=\"id-").append(escapeXml(rel.targetId())).append("\"");
            if (rel.accessType() != null) {
                sb.append(" accessType=\"").append(escapeXml(rel.accessType())).append("\"");
            }
            sb.append(">\n");
            sb.append("      <name xml:lang=\"en\">").append(escapeXml(rel.name()))
              .append("</name>\n");
            sb.append("    </relationship>\n");
        }
        sb.append("  </relationships>\n");
    }

    private void appendOrganizations(StringBuilder sb, Map<String, List<String>> organizations) {
        if (organizations == null || organizations.isEmpty()) {
            return;
        }
        sb.append("  <organizations>\n");
        for (Map.Entry<String, List<String>> entry : organizations.entrySet()) {
            sb.append("    <item>\n");
            sb.append("      <label xml:lang=\"en\">").append(escapeXml(entry.getKey()))
              .append("</label>\n");
            for (String nodeId : entry.getValue()) {
                sb.append("      <item identifierRef=\"id-").append(escapeXml(nodeId))
                  .append("\"/>\n");
            }
            sb.append("    </item>\n");
        }
        sb.append("  </organizations>\n");
    }

    private void appendViews(StringBuilder sb, ArchiMateView view) {
        if (view == null) {
            return;
        }
        sb.append("  <views>\n");
        sb.append("    <diagrams>\n");
        sb.append("      <view identifier=\"id-").append(escapeXml(view.id()))
          .append("\" viewpoint=\"Layered\">\n");
        sb.append("        <name xml:lang=\"en\">").append(escapeXml(view.name()))
          .append("</name>\n");

        for (ArchiMateViewNode node : view.nodes()) {
            sb.append("        <node identifier=\"id-vn-").append(escapeXml(node.id()))
              .append("\" elementRef=\"id-").append(escapeXml(node.elementId()))
              .append("\" x=\"").append(node.x())
              .append("\" y=\"").append(node.y())
              .append("\" w=\"").append(node.w())
              .append("\" h=\"").append(node.h()).append("\">\n");
            sb.append("          <label xml:lang=\"en\">").append(escapeXml(node.label()))
              .append("</label>\n");
            sb.append("          <style>\n");
            sb.append("            <fillColor r=\"").append(node.r())
              .append("\" g=\"").append(node.g())
              .append("\" b=\"").append(node.b()).append("\"/>\n");
            if (node.lineWidth() > 1) {
                sb.append("            <lineWidth>").append(node.lineWidth())
                  .append("</lineWidth>\n");
            }
            sb.append("          </style>\n");
            sb.append("        </node>\n");
        }

        for (ArchiMateViewConnection conn : view.connections()) {
            sb.append("        <connection identifier=\"id-vc-").append(escapeXml(conn.id()))
              .append("\" relationshipRef=\"id-rel-").append(escapeXml(conn.relationshipId()))
              .append("\" source=\"id-vn-").append(escapeXml(conn.sourceNodeId()))
              .append("\" target=\"id-vn-").append(escapeXml(conn.targetNodeId()))
              .append("\"/>\n");
        }

        sb.append("      </view>\n");
        sb.append("    </diagrams>\n");
        sb.append("  </views>\n");
    }

    static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
