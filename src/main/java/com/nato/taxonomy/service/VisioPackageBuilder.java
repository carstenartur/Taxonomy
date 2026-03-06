package com.nato.taxonomy.service;

import com.nato.taxonomy.visio.VisioDocument;
import com.nato.taxonomy.visio.VisioPage;
import com.nato.taxonomy.visio.converter.VisioDocumentConverter;
import com.nato.taxonomy.visio.converter.VisioPageContentsConverter;
import com.nato.taxonomy.visio.converter.VisioPagesConverter;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.XppDriver;
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
    private static final String XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";

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
        XStream xstream = new XStream(new XppDriver());
        xstream.setMode(XStream.NO_REFERENCES);
        xstream.alias("VisioDocument", VisioDocument.class);
        xstream.registerConverter(new VisioDocumentConverter());
        return XML_DECLARATION + xstream.toXML(doc);
    }

    private String buildDocumentRels(VisioDocument doc) {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.microsoft.com/visio/2010/relationships/pages" Target="pages/pages.xml"/>
                </Relationships>""";
    }

    private String buildPagesXml(VisioDocument doc) {
        XStream xstream = new XStream(new XppDriver());
        xstream.setMode(XStream.NO_REFERENCES);
        xstream.alias("Pages", VisioDocument.class);
        xstream.registerConverter(new VisioPagesConverter());
        return XML_DECLARATION + xstream.toXML(doc);
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
        XStream xstream = new XStream(new XppDriver());
        xstream.setMode(XStream.NO_REFERENCES);
        xstream.alias("PageContents", VisioPage.class);
        xstream.registerConverter(new VisioPageContentsConverter());
        return XML_DECLARATION + xstream.toXML(page);
    }
}
