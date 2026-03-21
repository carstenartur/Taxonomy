package com.taxonomy.export;

import com.taxonomy.visio.VisioConnect;
import com.taxonomy.visio.VisioDocument;
import com.taxonomy.visio.VisioPage;
import com.taxonomy.visio.VisioShape;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

class VisioPackageBuilderTest {

    private final VisioPackageBuilder builder = new VisioPackageBuilder();

    @Test
    void buildProducesNonEmptyByteArray() throws IOException {
        var doc = createSimpleDocument();

        byte[] result = builder.build(doc);

        assertNotNull(result);
        assertTrue(result.length > 0);
    }

    @Test
    void buildProducesValidZipArchive() throws IOException {
        var doc = createSimpleDocument();

        byte[] result = builder.build(doc);

        // Should be parseable as a ZIP file
        try (var zis = new ZipInputStream(new ByteArrayInputStream(result))) {
            ZipEntry entry = zis.getNextEntry();
            assertNotNull(entry, "ZIP archive should contain at least one entry");
        }
    }

    @Test
    void zipContainsContentTypesFile() throws IOException {
        var doc = createSimpleDocument();

        byte[] result = builder.build(doc);

        assertTrue(containsEntry(result, "[Content_Types].xml"));
    }

    @Test
    void zipContainsRootRels() throws IOException {
        var doc = createSimpleDocument();

        byte[] result = builder.build(doc);

        assertTrue(containsEntry(result, "_rels/.rels"));
    }

    @Test
    void zipContainsDocumentXml() throws IOException {
        var doc = createSimpleDocument();

        byte[] result = builder.build(doc);

        assertTrue(containsEntry(result, "visio/document.xml"));
    }

    @Test
    void zipContainsDocumentRels() throws IOException {
        var doc = createSimpleDocument();

        byte[] result = builder.build(doc);

        assertTrue(containsEntry(result, "visio/_rels/document.xml.rels"));
    }

    @Test
    void zipContainsPagesXml() throws IOException {
        var doc = createSimpleDocument();

        byte[] result = builder.build(doc);

        assertTrue(containsEntry(result, "visio/pages/pages.xml"));
    }

    @Test
    void zipContainsPagesRels() throws IOException {
        var doc = createSimpleDocument();

        byte[] result = builder.build(doc);

        assertTrue(containsEntry(result, "visio/pages/_rels/pages.xml.rels"));
    }

    @Test
    void zipContainsIndividualPageFile() throws IOException {
        var doc = createSimpleDocument();

        byte[] result = builder.build(doc);

        assertTrue(containsEntry(result, "visio/pages/page1.xml"));
    }

    @Test
    void multiplePagesProduceMultiplePageFiles() throws IOException {
        var doc = new VisioDocument();
        doc.getPages().add(new VisioPage("page1", "Page 1"));
        doc.getPages().add(new VisioPage("page2", "Page 2"));

        byte[] result = builder.build(doc);

        assertTrue(containsEntry(result, "visio/pages/page1.xml"));
        assertTrue(containsEntry(result, "visio/pages/page2.xml"));
    }

    @Test
    void contentTypesContainsVisioContentTypes() throws IOException {
        var doc = createSimpleDocument();

        byte[] result = builder.build(doc);
        String contentTypes = readEntry(result, "[Content_Types].xml");

        assertTrue(contentTypes.contains("vnd.ms-visio.drawing.main+xml"));
        assertTrue(contentTypes.contains("vnd.ms-visio.pages+xml"));
        assertTrue(contentTypes.contains("vnd.ms-visio.page+xml"));
    }

    @Test
    void rootRelsContainsDocumentRelationship() throws IOException {
        var doc = createSimpleDocument();

        byte[] result = builder.build(doc);
        String rels = readEntry(result, "_rels/.rels");

        assertTrue(rels.contains("visio/document.xml"));
        assertTrue(rels.contains("relationships/document"));
    }

    @Test
    void emptyDocumentStillProducesValidPackage() throws IOException {
        var doc = new VisioDocument();

        byte[] result = builder.build(doc);

        assertNotNull(result);
        assertTrue(result.length > 0);
        assertTrue(containsEntry(result, "[Content_Types].xml"));
        assertTrue(containsEntry(result, "visio/document.xml"));
    }

    @Test
    void documentWithShapesAndConnectsProducesValidOutput() throws IOException {
        var doc = new VisioDocument();
        var page = new VisioPage("p1", "Architecture");
        page.getShapes().add(new VisioShape("s1", "Capability A", 1.0, 1.0, 2.0, 1.0, "Capability", true));
        page.getShapes().add(new VisioShape("s2", "Service B", 3.0, 1.0, 2.0, 1.0, "Service", false));
        page.getConnects().add(new VisioConnect("s1", "s2", "REALIZES"));
        doc.getPages().add(page);

        byte[] result = builder.build(doc);

        assertNotNull(result);
        assertTrue(result.length > 0);
        String pageXml = readEntry(result, "visio/pages/page1.xml");
        assertNotNull(pageXml);
    }

    // --- helper methods ---

    private VisioDocument createSimpleDocument() {
        var doc = new VisioDocument();
        var page = new VisioPage("page1", "Page 1");
        page.getShapes().add(new VisioShape("s1", "Shape 1", 1.0, 1.0, 2.0, 1.0, "Default", false));
        doc.getPages().add(page);
        return doc;
    }

    private boolean containsEntry(byte[] zipBytes, String entryName) throws IOException {
        try (var zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals(entryName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String readEntry(byte[] zipBytes, String entryName) throws IOException {
        try (var zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals(entryName)) {
                    return new String(zis.readAllBytes());
                }
            }
        }
        return null;
    }
}
