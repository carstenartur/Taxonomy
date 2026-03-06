package com.nato.taxonomy.service;

import com.nato.taxonomy.visio.VisioConnect;
import com.nato.taxonomy.visio.VisioDocument;
import com.nato.taxonomy.visio.VisioPage;
import com.nato.taxonomy.visio.VisioShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds a Visio .vsdx file (OPC/ZIP package) from a {@link VisioDocument}.
 * <p>
 * The .vsdx format is an Open Packaging Convention (OPC) ZIP archive containing
 * XML parts that describe the Visio document structure.
 */
@Service
public class VisioPackageBuilder {

    private static final Logger log = LoggerFactory.getLogger(VisioPackageBuilder.class);

    /**
     * Builds a .vsdx byte array from a VisioDocument.
     *
     * @param doc the Visio document model
     * @return the .vsdx file content as a byte array
     * @throws IOException if an I/O error occurs during ZIP creation
     */
    public byte[] build(VisioDocument doc) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        try (ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
            // [Content_Types].xml
            writeEntry(zos, "[Content_Types].xml", buildContentTypes(doc));

            // _rels/.rels
            writeEntry(zos, "_rels/.rels", buildRootRels());

            // visio/document.xml
            writeEntry(zos, "visio/document.xml", buildDocumentXml(doc));

            // visio/_rels/document.xml.rels
            writeEntry(zos, "visio/_rels/document.xml.rels", buildDocumentRels(doc));

            // visio/pages/pages.xml
            writeEntry(zos, "visio/pages/pages.xml", buildPagesXml(doc));

            // Individual page files
            for (int i = 0; i < doc.getPages().size(); i++) {
                VisioPage page = doc.getPages().get(i);
                writeEntry(zos, "visio/pages/page" + (i + 1) + ".xml", buildPageXml(page));
            }

            // visio/pages/_rels/pages.xml.rels
            writeEntry(zos, "visio/pages/_rels/pages.xml.rels", buildPagesRels(doc));
        }

        log.info("Built .vsdx package: {} bytes", baos.size());
        return baos.toByteArray();
    }

    private void writeEntry(ZipOutputStream zos, String name, String content) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private String buildContentTypes(VisioDocument doc) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        sb.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">\n");
        sb.append("  <Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>\n");
        sb.append("  <Default Extension=\"xml\" ContentType=\"application/xml\"/>\n");
        sb.append("  <Override PartName=\"/visio/document.xml\" ContentType=\"application/vnd.ms-visio.drawing.main+xml\"/>\n");
        sb.append("  <Override PartName=\"/visio/pages/pages.xml\" ContentType=\"application/vnd.ms-visio.pages+xml\"/>\n");
        for (int i = 0; i < doc.getPages().size(); i++) {
            sb.append("  <Override PartName=\"/visio/pages/page").append(i + 1)
              .append(".xml\" ContentType=\"application/vnd.ms-visio.page+xml\"/>\n");
        }
        sb.append("</Types>");
        return sb.toString();
    }

    private String buildRootRels() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.microsoft.com/visio/2010/relationships/document" Target="visio/document.xml"/>
                </Relationships>""";
    }

    private String buildDocumentXml(VisioDocument doc) {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <VisioDocument xmlns="http://schemas.microsoft.com/office/visio/2012/main"
                               xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                               xml:space="preserve">
                  <DocumentProperties>
                    <Creator>NATO NC3T Taxonomy Browser</Creator>
                    <Description>Architecture diagram generated from requirement analysis</Description>
                  </DocumentProperties>
                  <DocumentSettings/>
                  <FaceNames/>
                  <StyleSheets/>
                </VisioDocument>""";
    }

    private String buildDocumentRels(VisioDocument doc) {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.microsoft.com/visio/2010/relationships/pages" Target="pages/pages.xml"/>
                </Relationships>""";
    }

    private String buildPagesXml(VisioDocument doc) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        sb.append("<Pages xmlns=\"http://schemas.microsoft.com/office/visio/2012/main\"\n");
        sb.append("       xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">\n");
        for (int i = 0; i < doc.getPages().size(); i++) {
            VisioPage page = doc.getPages().get(i);
            sb.append("  <Page ID=\"").append(i).append("\" Name=\"").append(escapeXml(page.getName())).append("\">\n");
            sb.append("    <Rel r:id=\"rId").append(i + 1).append("\"/>\n");
            sb.append("  </Page>\n");
        }
        sb.append("</Pages>");
        return sb.toString();
    }

    private String buildPagesRels(VisioDocument doc) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        sb.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n");
        for (int i = 0; i < doc.getPages().size(); i++) {
            sb.append("  <Relationship Id=\"rId").append(i + 1)
              .append("\" Type=\"http://schemas.microsoft.com/visio/2010/relationships/page\" Target=\"page")
              .append(i + 1).append(".xml\"/>\n");
        }
        sb.append("</Relationships>");
        return sb.toString();
    }

    private String buildPageXml(VisioPage page) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        sb.append("<PageContents xmlns=\"http://schemas.microsoft.com/office/visio/2012/main\"\n");
        sb.append("              xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">\n");

        // Shapes
        for (VisioShape shape : page.getShapes()) {
            sb.append("  <Shape ID=\"").append(escapeXml(shape.getId())).append("\" NameU=\"")
              .append(escapeXml(shape.getText())).append("\" Type=\"Shape\">\n");
            sb.append("    <Cell N=\"PinX\" V=\"").append(shape.getX()).append("\"/>\n");
            sb.append("    <Cell N=\"PinY\" V=\"").append(shape.getY()).append("\"/>\n");
            sb.append("    <Cell N=\"Width\" V=\"").append(shape.getWidth()).append("\"/>\n");
            sb.append("    <Cell N=\"Height\" V=\"").append(shape.getHeight()).append("\"/>\n");

            // Fill color: green-tinted for anchors, blue for regular
            if (shape.isAnchor()) {
                sb.append("    <Cell N=\"FillForegnd\" V=\"#4CAF50\"/>\n");
            } else {
                sb.append("    <Cell N=\"FillForegnd\" V=\"#2196F3\"/>\n");
            }

            sb.append("    <Text>").append(escapeXml(shape.getText())).append("</Text>\n");
            sb.append("  </Shape>\n");
        }

        // Connectors
        int connectorId = page.getShapes().size();
        for (VisioConnect connect : page.getConnects()) {
            connectorId++;
            sb.append("  <Shape ID=\"").append(connectorId).append("\" Type=\"Shape\" Master=\"Dynamic connector\">\n");
            sb.append("    <Cell N=\"BeginX\" V=\"0\"/>\n");
            sb.append("    <Cell N=\"BeginY\" V=\"0\"/>\n");
            sb.append("    <Cell N=\"EndX\" V=\"1\"/>\n");
            sb.append("    <Cell N=\"EndY\" V=\"1\"/>\n");
            sb.append("    <Text>").append(escapeXml(connect.getRelationType())).append("</Text>\n");
            sb.append("  </Shape>\n");
        }

        // Connect elements
        connectorId = page.getShapes().size();
        for (VisioConnect connect : page.getConnects()) {
            connectorId++;
            sb.append("  <Connect FromSheet=\"").append(connectorId)
              .append("\" FromCell=\"BeginX\" ToSheet=\"").append(escapeXml(connect.getFromShape()))
              .append("\" ToCell=\"PinX\"/>\n");
            sb.append("  <Connect FromSheet=\"").append(connectorId)
              .append("\" FromCell=\"EndX\" ToSheet=\"").append(escapeXml(connect.getToShape()))
              .append("\" ToCell=\"PinX\"/>\n");
        }

        sb.append("</PageContents>");
        return sb.toString();
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
