package com.taxonomy.export;

import com.taxonomy.visio.VisioDocument;
import com.taxonomy.visio.VisioPage;
import com.taxonomy.visio.VisioNativeShape;
import com.taxonomy.visio.converter.VisioDocumentConverter;
import com.taxonomy.visio.converter.VisioPageContentsConverter;
import com.taxonomy.visio.converter.VisioPagesConverter;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.XppDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds the currently supported, deliberately bounded Visio {@code .vsdx}
 * package profile from a {@link VisioDocument}.
 * <p>
 * The package uses the Open Packaging Convention and Visio 2012 XML namespace.
 * Structural and independent-reader verification belong to this builder's test
 * contract; compatibility with a particular Microsoft Visio release requires
 * separate product evidence.
 */
public class VisioPackageBuilder {

    private static final Logger log = LoggerFactory.getLogger(VisioPackageBuilder.class);
    private static final String XML_DECLARATION =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";
    // The DOS minimum (Jan 1) is also ZipEntry's sentinel for an extended
    // timestamp, whose conversion uses the default timezone. Jan 2 stays in
    // the DOS range and emits no timezone-dependent extended timestamp.
    private static final LocalDateTime ZIP_EPOCH = LocalDateTime.of(1980, 1, 2, 0, 0);

    /**
     * Builds a deterministic {@code .vsdx} byte array from a validated document.
     *
     * @param doc the Visio document model
     * @return the package bytes
     * @throws IOException if ZIP creation fails
     * @throws IllegalArgumentException if the document violates the supported package profile
     */
    public byte[] build(VisioDocument doc) throws IOException {
        VisioPackageValidator.validate(doc);
        // Stable sorting retains page IDs and all native page-name hyperlinks.
        // Reserve evidence capacity during conversion, but do not put dozens of
        // text pages between the overview and its editable relationship drawings.
        doc.getPages().sort(java.util.Comparator.comparing(VisioPage::isEvidencePage));
        Map<String, String> parts = buildParts(doc, Map.of());
        long bytes = partBytes(parts);
        boolean trimmed = false;
        // Appendices now follow drawings. Optional relationship pages may be
        // in the middle, so prune them explicitly, never the mandatory text.
        // Cache by stable page ID: positional part names change after removal.
        Map<String, String> pageContents = new LinkedHashMap<>();
        for (int index = 0; index < doc.getPages().size(); index++) {
            pageContents.put(doc.getPages().get(index).getId(), parts.get("visio/pages/page" + (index + 1) + ".xml"));
        }
        for (int index = doc.getPages().size() - 1; bytes > VisioHandoffProfile.MAX_XML_BYTES && index > 0; index--) {
            VisioPage page = doc.getPages().get(index);
            if (!page.isRelationshipDetail()) continue;
            bytes -= pageContents.get(page.getId()).getBytes(StandardCharsets.UTF_8).length;
            doc.getPages().remove(index);
            trimmed = true;
        }
        if (trimmed) {
            VisioPresentation.updateDetailCoverage(doc, "byte capacity");
            parts = buildParts(doc, pageContents);
        }
        VisioOpcValidator.validate(parts);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            zip.setLevel(Deflater.BEST_COMPRESSION);
            for (Map.Entry<String, String> part : parts.entrySet()) {
                writeEntry(zip, part.getKey(), part.getValue());
            }
        }

        log.info("Built bounded .vsdx package: {} bytes, {} parts", output.size(), parts.size());
        return output.toByteArray();
    }

    /** VSDX plus a directly readable, checksum-bound handoff manifest and mapping profile. */
    public byte[] buildBundle(VisioDocument doc) throws IOException {
        byte[] vsdx = build(doc);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            zip.setLevel(Deflater.BEST_COMPRESSION);
            ZipEntry entry = new ZipEntry("diagram.vsdx");
            entry.setTimeLocal(ZIP_EPOCH);
            zip.putNextEntry(entry); zip.write(vsdx); zip.closeEntry();
            writeEntry(zip, "mapping-profile.json", VisioHandoffProfile.profileJson());
            writeEntry(zip, "manifest.json", VisioHandoffProfile.bundleManifest(vsdx, VisioHandoffProfile.manifest(doc)));
        }
        return output.toByteArray();
    }

    private static long partBytes(Map<String, String> parts) {
        return parts.values().stream().mapToLong(value -> value.getBytes(StandardCharsets.UTF_8).length).sum();
    }

    private Map<String, String> buildParts(VisioDocument doc, Map<String, String> previousParts) {
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("[Content_Types].xml", buildContentTypes(doc));
        parts.put("_rels/.rels", buildRootRelationships());
        parts.put("docProps/core.xml", buildCoreProperties(doc));
        parts.put("docProps/app.xml", buildExtendedProperties(doc));
        parts.put("docProps/custom.xml", buildCustomProperties(doc));
        parts.put("taxonomy/manifest.json", VisioHandoffProfile.manifest(doc));
        parts.put("taxonomy/mapping-profile.json", VisioHandoffProfile.profileJson());
        parts.put("visio/document.xml", buildDocumentXml(doc));
        parts.put("visio/windows.xml", VisioDrawingView.windowsXml(doc));
        parts.put("visio/_rels/document.xml.rels", buildDocumentRelationships());
        parts.put("visio/pages/pages.xml", buildPagesXml(doc));
        parts.put("visio/pages/_rels/pages.xml.rels", buildPagesRelationships(doc));
        parts.put("visio/masters/masters.xml", buildMastersXml());
        parts.put("visio/masters/_rels/masters.xml.rels", buildMastersRelationships());
        for (VisioNativeShape master : VisioNativeShape.values())
            parts.put("visio/masters/master" + master.id() + ".xml", buildMasterXml(master));

        for (int index = 0; index < doc.getPages().size(); index++) {
            VisioPage page = doc.getPages().get(index);
            String name = "visio/pages/page" + (index + 1) + ".xml";
            parts.put(name, previousParts.containsKey(page.getId()) ? previousParts.get(page.getId()) : buildPageXml(page));
            if (!VisioNativeShape.usedOn(page).isEmpty())
                parts.put("visio/pages/_rels/page" + (index + 1) + ".xml.rels", buildPageMasterRelationships(page));
        }
        return parts;
    }

    private void writeEntry(ZipOutputStream zip, String name, String content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTimeLocal(ZIP_EPOCH);
        zip.putNextEntry(entry);
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private String buildContentTypes(VisioDocument doc) {
        StringBuilder xml = new StringBuilder(XML_DECLARATION);
        xml.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">\n");
        xml.append("  <Default Extension=\"rels\" ")
                .append("ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>\n");
        xml.append("  <Default Extension=\"xml\" ContentType=\"application/xml\"/>\n");
        xml.append("  <Override PartName=\"/docProps/core.xml\" ")
                .append("ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>\n");
        xml.append("  <Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>\n");
        xml.append("  <Override PartName=\"/docProps/custom.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.custom-properties+xml\"/>\n");
        xml.append("  <Override PartName=\"/taxonomy/manifest.json\" ContentType=\"application/json\"/>\n");
        xml.append("  <Override PartName=\"/taxonomy/mapping-profile.json\" ContentType=\"application/json\"/>\n");
        xml.append("  <Override PartName=\"/visio/document.xml\" ")
                .append("ContentType=\"application/vnd.ms-visio.drawing.main+xml\"/>\n");
        xml.append("  <Override PartName=\"/visio/windows.xml\" ")
                .append("ContentType=\"application/vnd.ms-visio.windows+xml\"/>\n");
        xml.append("  <Override PartName=\"/visio/pages/pages.xml\" ")
                .append("ContentType=\"application/vnd.ms-visio.pages+xml\"/>\n");
        xml.append("  <Override PartName=\"/visio/masters/masters.xml\" ContentType=\"application/vnd.ms-visio.masters+xml\"/>\n");
        for (VisioNativeShape master : VisioNativeShape.values())
            xml.append("  <Override PartName=\"/visio/masters/master").append(master.id())
                    .append(".xml\" ContentType=\"application/vnd.ms-visio.master+xml\"/>\n");
        for (int index = 0; index < doc.getPages().size(); index++) {
            xml.append("  <Override PartName=\"/visio/pages/page")
                    .append(index + 1)
                    .append(".xml\" ContentType=\"application/vnd.ms-visio.page+xml\"/>\n");
        }
        xml.append("</Types>");
        return xml.toString();
    }

    private String buildRootRelationships() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.microsoft.com/visio/2010/relationships/document" Target="visio/document.xml"/>
                  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
                  <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
                  <Relationship Id="rId4" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/custom-properties" Target="docProps/custom.xml"/>
                  <Relationship Id="rId5" Type="https://github.com/carstenartur/Taxonomy/relationships/export-manifest" Target="taxonomy/manifest.json"/>
                  <Relationship Id="rId6" Type="https://github.com/carstenartur/Taxonomy/relationships/mapping-profile" Target="taxonomy/mapping-profile.json"/>
                </Relationships>""";
    }

    private String buildCoreProperties(VisioDocument doc) {
        String title = doc.getPages().stream()
                .map(VisioPage::getName)
                .filter(name -> name != null && !name.isBlank())
                .findFirst()
                .orElse("Taxonomy architecture");

        return XML_DECLARATION
                + "<cp:coreProperties"
                + " xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\""
                + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n"
                + "  <dc:title>" + escapeXml(title) + "</dc:title>\n"
                + "  <dc:creator>Taxonomy Architecture Analyzer</dc:creator>\n"
                + "  <dc:description>Experimental visual architecture projection; Microsoft Visio compatibility is not certified.</dc:description>\n"
                + "</cp:coreProperties>";
    }

    private String buildExtendedProperties(VisioDocument doc) {
        return XML_DECLARATION + "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\">"
                + "<Application>Taxonomy Architecture Analyzer</Application><Pages>" + doc.getPages().size() + "</Pages></Properties>";
    }

    private String buildCustomProperties(VisioDocument doc) {
        var properties = new java.util.TreeMap<>(doc.getProperties());
        properties.put("taxonomy.exportProfile", com.taxonomy.visio.VisioProperty.text(VisioHandoffProfile.ID));
        properties.put("taxonomy.profileSha256", com.taxonomy.visio.VisioProperty.text(VisioHandoffProfile.sha256(
                VisioHandoffProfile.profileJson().getBytes(StandardCharsets.UTF_8))));
        VisioPackageValidator.validateProperties(properties);
        StringBuilder xml = new StringBuilder(XML_DECLARATION).append("<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/custom-properties\" xmlns:vt=\"http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes\">");
        int pid = 2;
        for (var entry : properties.entrySet()) {
            String type = switch (entry.getValue().kind()) { case STRING -> "lpwstr"; case NUMBER -> "r8"; case BOOLEAN -> "bool"; };
            xml.append("<property fmtid=\"{D5CDD505-2E9C-101B-9397-08002B2CF9AE}\" pid=\"").append(pid++)
                    .append("\" name=\"").append(escapeXml(entry.getKey())).append("\"><vt:").append(type).append(">")
                    .append(escapeXml(entry.getValue().value())).append("</vt:").append(type).append("></property>");
        }
        return xml.append("</Properties>").toString();
    }

    private String buildDocumentXml(VisioDocument doc) {
        XStream xstream = new XStream(new XppDriver());
        xstream.setMode(XStream.NO_REFERENCES);
        xstream.alias("VisioDocument", VisioDocument.class);
        xstream.registerConverter(new VisioDocumentConverter());
        return XML_DECLARATION + xstream.toXML(doc);
    }

    private String buildDocumentRelationships() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.microsoft.com/visio/2010/relationships/pages" Target="pages/pages.xml"/>
                  <Relationship Id="rId2" Type="http://schemas.microsoft.com/visio/2010/relationships/windows" Target="windows.xml"/>
                  <Relationship Id="rId3" Type="http://schemas.microsoft.com/visio/2010/relationships/masters" Target="masters/masters.xml"/>
                </Relationships>""";
    }

    private String buildMastersXml() {
        StringBuilder xml = new StringBuilder(XML_DECLARATION)
                .append("<Masters xmlns=\"http://schemas.microsoft.com/office/visio/2012/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">");
        for (VisioNativeShape master : VisioNativeShape.values()) {
            xml.append("<Master ID=\"").append(master.id()).append("\" Name=\"").append(master.label())
                    .append("\" NameU=\"").append(master.label()).append("\" IsCustomName=\"1\" IsCustomNameU=\"1\" MatchByName=\"1\">")
                    .append("<PageSheet><Cell N=\"PageWidth\" V=\"4\"/><Cell N=\"PageHeight\" V=\"3\"/></PageSheet>")
                    .append("<Rel r:id=\"rId").append(master.id()).append("\"/></Master>");
        }
        return xml.append("</Masters>").toString();
    }

    private String buildMastersRelationships() {
        StringBuilder xml = new StringBuilder(XML_DECLARATION)
                .append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (VisioNativeShape master : VisioNativeShape.values())
            xml.append("<Relationship Id=\"rId").append(master.id())
                    .append("\" Type=\"http://schemas.microsoft.com/visio/2010/relationships/master\" Target=\"master")
                    .append(master.id()).append(".xml\"/>");
        return xml.append("</Relationships>").toString();
    }

    private String buildMasterXml(VisioNativeShape master) {
        XStream xstream = new XStream(new XppDriver());
        xstream.setMode(XStream.NO_REFERENCES);
        xstream.alias("MasterContents", VisioPage.class);
        xstream.registerConverter(new VisioPageContentsConverter(master));
        return XML_DECLARATION + xstream.toXML(new VisioPage("0", master.label()));
    }

    private String buildPageMasterRelationships(VisioPage page) {
        StringBuilder xml = new StringBuilder(XML_DECLARATION)
                .append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int id : VisioNativeShape.usedOn(page))
            xml.append("<Relationship Id=\"rId").append(id)
                    .append("\" Type=\"http://schemas.microsoft.com/visio/2010/relationships/master\" Target=\"../masters/master")
                    .append(id).append(".xml\"/>");
        return xml.append("</Relationships>").toString();
    }

    private String buildPagesXml(VisioDocument doc) {
        XStream xstream = new XStream(new XppDriver());
        xstream.setMode(XStream.NO_REFERENCES);
        xstream.alias("Pages", VisioDocument.class);
        xstream.registerConverter(new VisioPagesConverter());
        return XML_DECLARATION + xstream.toXML(doc);
    }

    private String buildPagesRelationships(VisioDocument doc) {
        StringBuilder xml = new StringBuilder(XML_DECLARATION);
        xml.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n");
        for (int index = 0; index < doc.getPages().size(); index++) {
            xml.append("  <Relationship Id=\"rId")
                    .append(index + 1)
                    .append("\" Type=\"http://schemas.microsoft.com/visio/2010/relationships/page\"")
                    .append(" Target=\"page")
                    .append(index + 1)
                    .append(".xml\"/>\n");
        }
        xml.append("</Relationships>");
        return xml.toString();
    }

    private String buildPageXml(VisioPage page) {
        XStream xstream = new XStream(new XppDriver());
        xstream.setMode(XStream.NO_REFERENCES);
        xstream.alias("PageContents", VisioPage.class);
        xstream.registerConverter(new VisioPageContentsConverter());
        return XML_DECLARATION + xstream.toXML(page);
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
