package com.taxonomy.export;

import com.taxonomy.visio.VisioConnect;
import com.taxonomy.visio.VisioDocument;
import com.taxonomy.visio.VisioPage;
import com.taxonomy.visio.VisioShape;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisioPackageBuilderTest {

    private final VisioPackageBuilder builder = new VisioPackageBuilder();

    @Test
    void buildProducesNonEmptyByteArray() throws IOException {
        byte[] result = builder.build(createSimpleDocument());

        assertNotNull(result);
        assertTrue(result.length > 0);
    }

    @Test
    void buildProducesValidZipArchive() throws IOException {
        byte[] result = builder.build(createSimpleDocument());

        try (var zip = new ZipInputStream(new ByteArrayInputStream(result))) {
            assertNotNull(zip.getNextEntry(),
                    "ZIP archive should contain at least one entry");
        }
    }

    @Test
    void zipContainsRequiredPackageParts() throws IOException {
        byte[] result = builder.build(createSimpleDocument());

        assertTrue(containsEntry(result, "[Content_Types].xml"));
        assertTrue(containsEntry(result, "_rels/.rels"));
        assertTrue(containsEntry(result, "docProps/core.xml"));
        assertTrue(containsEntry(result, "visio/document.xml"));
        assertTrue(containsEntry(result, "visio/_rels/document.xml.rels"));
        assertTrue(containsEntry(result, "visio/pages/pages.xml"));
        assertTrue(containsEntry(result, "visio/pages/_rels/pages.xml.rels"));
        assertTrue(containsEntry(result, "visio/pages/page1.xml"));
    }

    @Test
    void multiplePagesProduceMultiplePageFiles() throws IOException {
        var document = new VisioDocument();
        document.getPages().add(new VisioPage("page1", "Page 1"));
        document.getPages().add(new VisioPage("page2", "Page 2"));

        byte[] result = builder.build(document);

        assertTrue(containsEntry(result, "visio/pages/page1.xml"));
        assertTrue(containsEntry(result, "visio/pages/page2.xml"));
    }

    @Test
    void contentTypesContainsVisioAndCorePropertyTypes() throws IOException {
        byte[] result = builder.build(createSimpleDocument());
        String contentTypes = readEntry(result, "[Content_Types].xml");

        assertTrue(contentTypes.contains("vnd.ms-visio.drawing.main+xml"));
        assertTrue(contentTypes.contains("vnd.ms-visio.pages+xml"));
        assertTrue(contentTypes.contains("vnd.ms-visio.page+xml"));
        assertTrue(contentTypes.contains(
                "vnd.openxmlformats-package.core-properties+xml"));
    }

    @Test
    void rootRelationshipsContainDocumentAndCoreProperties() throws IOException {
        byte[] result = builder.build(createSimpleDocument());
        String relationships = readEntry(result, "_rels/.rels");

        assertTrue(relationships.contains("visio/document.xml"));
        assertTrue(relationships.contains("relationships/document"));
        assertTrue(relationships.contains("docProps/core.xml"));
        assertTrue(relationships.contains("metadata/core-properties"));
    }

    @Test
    void emptyDocumentStillProducesAParseablePackageSkeleton() throws IOException {
        byte[] result = builder.build(new VisioDocument());

        assertNotNull(result);
        assertTrue(result.length > 0);
        assertTrue(containsEntry(result, "[Content_Types].xml"));
        assertTrue(containsEntry(result, "visio/document.xml"));
        assertTrue(containsEntry(result, "visio/pages/pages.xml"));
    }

    @Test
    void documentWithShapesAndConnectorsProducesPageContent() throws IOException {
        var document = representativeDocument();

        byte[] result = builder.build(document);

        String pageXml = readEntry(result, "visio/pages/page1.xml");
        assertNotNull(pageXml);
        assertTrue(pageXml.contains("Capability A"));
        assertTrue(pageXml.contains("REALIZES"));
        assertTrue(pageXml.contains("<Shapes>"));
        assertTrue(pageXml.contains("<Connects>"));
    }

    private static VisioDocument createSimpleDocument() {
        var document = new VisioDocument();
        var page = new VisioPage("page1", "Page 1");
        page.getShapes().add(new VisioShape(
                "1", "Shape 1", 1.0, 1.0, 2.0, 1.0, "Default", false));
        document.getPages().add(page);
        return document;
    }

    static VisioDocument representativeDocument() {
        var document = new VisioDocument();
        var page = new VisioPage("p1", "Architecture");
        page.getShapes().add(new VisioShape(
                "10", "Capability A", 1.5, 2.0, 2.0, 1.0, "Capability", true));
        page.getShapes().add(new VisioShape(
                "42", "Service B", 4.5, 2.0, 2.0, 1.0, "Service", false));
        page.getConnects().add(new VisioConnect("10", "42", "REALIZES"));
        document.getPages().add(page);
        return document;
    }

    private static boolean containsEntry(byte[] zipBytes, String entryName)
            throws IOException {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals(entryName)) {
                    return true;
                }
            }
        }
        return false;
    }

    static String readEntry(byte[] zipBytes, String entryName) throws IOException {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals(entryName)) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }
}
