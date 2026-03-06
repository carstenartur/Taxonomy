package com.nato.taxonomy;

import com.nato.taxonomy.visio.VisioConnect;
import com.nato.taxonomy.visio.VisioDocument;
import com.nato.taxonomy.visio.VisioPage;
import com.nato.taxonomy.visio.VisioShape;
import com.nato.taxonomy.visio.converter.VisioDocumentConverter;
import com.nato.taxonomy.visio.converter.VisioPageContentsConverter;
import com.nato.taxonomy.visio.converter.VisioPagesConverter;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.XppDriver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for the XStream converter classes that produce the Visio XML parts.
 */
class VisioConverterTests {

    private XStream pageXstream;
    private XStream documentXstream;
    private XStream pagesXstream;

    @BeforeEach
    void setUp() {
        pageXstream = new XStream(new XppDriver());
        pageXstream.setMode(XStream.NO_REFERENCES);
        pageXstream.alias("PageContents", VisioPage.class);
        pageXstream.registerConverter(new VisioPageContentsConverter());

        documentXstream = new XStream(new XppDriver());
        documentXstream.setMode(XStream.NO_REFERENCES);
        documentXstream.alias("VisioDocument", VisioDocument.class);
        documentXstream.registerConverter(new VisioDocumentConverter());

        pagesXstream = new XStream(new XppDriver());
        pagesXstream.setMode(XStream.NO_REFERENCES);
        pagesXstream.alias("Pages", VisioDocument.class);
        pagesXstream.registerConverter(new VisioPagesConverter());
    }

    @Test
    void pageContentsConverterProducesRootElement() {
        VisioPage page = new VisioPage("1", "Test Page");
        String xml = pageXstream.toXML(page);
        assertThat(xml).contains("PageContents");
    }

    @Test
    void pageContentsConverterIncludesNamespaces() {
        VisioPage page = new VisioPage("1", "Test Page");
        String xml = pageXstream.toXML(page);
        assertThat(xml).contains("http://schemas.microsoft.com/office/visio/2012/main");
        assertThat(xml).contains("http://schemas.openxmlformats.org/officeDocument/2006/relationships");
    }

    @Test
    void pageContentsConverterWritesShapeElement() {
        VisioPage page = new VisioPage("1", "Page");
        page.getShapes().add(new VisioShape("42", "My Shape", 1.5, 2.0, 2.0, 0.75, "Capabilities", false));

        String xml = pageXstream.toXML(page);

        assertThat(xml).contains("Shape");
        assertThat(xml).contains("My Shape");
        assertThat(xml).contains("42");
    }

    @Test
    void pageContentsConverterWritesCellElements() {
        VisioPage page = new VisioPage("1", "Page");
        page.getShapes().add(new VisioShape("1", "Node", 1.5, 2.0, 2.0, 0.75, "Services", false));

        String xml = pageXstream.toXML(page);

        assertThat(xml).contains("PinX");
        assertThat(xml).contains("PinY");
        assertThat(xml).contains("Width");
        assertThat(xml).contains("Height");
        assertThat(xml).contains("FillForegnd");
    }

    @Test
    void pageContentsConverterUsesGreenForAnchorShapes() {
        VisioPage page = new VisioPage("1", "Page");
        page.getShapes().add(new VisioShape("1", "Anchor", 1.0, 1.0, 2.0, 0.75, "Capabilities", true));

        String xml = pageXstream.toXML(page);

        assertThat(xml).contains("#4CAF50");
        assertThat(xml).doesNotContain("#2196F3");
    }

    @Test
    void pageContentsConverterUsesBlueForNonAnchorShapes() {
        VisioPage page = new VisioPage("1", "Page");
        page.getShapes().add(new VisioShape("1", "Regular", 1.0, 1.0, 2.0, 0.75, "Services", false));

        String xml = pageXstream.toXML(page);

        assertThat(xml).contains("#2196F3");
        assertThat(xml).doesNotContain("#4CAF50");
    }

    @Test
    void pageContentsConverterWritesTextElement() {
        VisioPage page = new VisioPage("1", "Page");
        page.getShapes().add(new VisioShape("1", "Hello World", 1.0, 1.0, 2.0, 0.75, "Capabilities", false));

        String xml = pageXstream.toXML(page);

        assertThat(xml).contains("<Text>Hello World</Text>");
    }

    @Test
    void pageContentsConverterWritesConnectorShape() {
        VisioPage page = new VisioPage("1", "Page");
        page.getShapes().add(new VisioShape("1", "A", 1.0, 1.0, 2.0, 0.75, "Capabilities", true));
        page.getShapes().add(new VisioShape("2", "B", 4.0, 1.0, 2.0, 0.75, "Services", false));
        page.getConnects().add(new VisioConnect("1", "2", "SUPPORTS"));

        String xml = pageXstream.toXML(page);

        assertThat(xml).contains("Dynamic connector");
        assertThat(xml).contains("BeginX");
        assertThat(xml).contains("EndX");
    }

    @Test
    void pageContentsConverterWritesConnectElements() {
        VisioPage page = new VisioPage("1", "Page");
        page.getShapes().add(new VisioShape("1", "A", 1.0, 1.0, 2.0, 0.75, "Capabilities", true));
        page.getShapes().add(new VisioShape("2", "B", 4.0, 1.0, 2.0, 0.75, "Services", false));
        page.getConnects().add(new VisioConnect("1", "2", "SUPPORTS"));

        String xml = pageXstream.toXML(page);

        assertThat(xml).contains("Connect");
        assertThat(xml).contains("FromSheet");
        assertThat(xml).contains("ToSheet");
        assertThat(xml).contains("BeginX");
        assertThat(xml).contains("EndX");
    }

    @Test
    void pageContentsConverterConnectorIdStartsAfterLastShape() {
        VisioPage page = new VisioPage("1", "Page");
        page.getShapes().add(new VisioShape("1", "A", 1.0, 1.0, 2.0, 0.75, "Capabilities", true));
        page.getShapes().add(new VisioShape("2", "B", 4.0, 1.0, 2.0, 0.75, "Services", false));
        page.getConnects().add(new VisioConnect("1", "2", "SUPPORTS"));

        String xml = pageXstream.toXML(page);

        // With 2 shapes, connector ID should be 3
        assertThat(xml).contains("ID=\"3\"");
    }

    @Test
    void pageContentsConverterEscapesSpecialCharactersInAttributes() {
        VisioPage page = new VisioPage("1", "Page");
        page.getShapes().add(new VisioShape("1", "A & B <tag>", 1.0, 1.0, 2.0, 0.75, "Capabilities", false));

        String xml = pageXstream.toXML(page);

        assertThat(xml).contains("&amp;");
        assertThat(xml).contains("&lt;");
        assertThat(xml).doesNotContain("<tag>");
    }

    @Test
    void pageContentsConverterUnmarshalThrowsUnsupported() {
        VisioPageContentsConverter converter = new VisioPageContentsConverter();
        assertThrows(UnsupportedOperationException.class,
                () -> converter.unmarshal(null, null));
    }

    // ── VisioDocumentConverter ──────────────────────────────────────────────

    @Test
    void documentConverterProducesRootElement() {
        VisioDocument doc = new VisioDocument();
        String xml = documentXstream.toXML(doc);
        assertThat(xml).contains("VisioDocument");
    }

    @Test
    void documentConverterIncludesNamespaces() {
        VisioDocument doc = new VisioDocument();
        String xml = documentXstream.toXML(doc);
        assertThat(xml).contains("http://schemas.microsoft.com/office/visio/2012/main");
        assertThat(xml).contains("http://schemas.openxmlformats.org/officeDocument/2006/relationships");
    }

    @Test
    void documentConverterIncludesDocumentProperties() {
        VisioDocument doc = new VisioDocument();
        String xml = documentXstream.toXML(doc);
        assertThat(xml).contains("DocumentProperties");
        assertThat(xml).contains("NATO NC3T Taxonomy Browser");
        assertThat(xml).contains("Architecture diagram generated from requirement analysis");
    }

    @Test
    void documentConverterIncludesRequiredSections() {
        VisioDocument doc = new VisioDocument();
        String xml = documentXstream.toXML(doc);
        assertThat(xml).contains("DocumentSettings");
        assertThat(xml).contains("FaceNames");
        assertThat(xml).contains("StyleSheets");
    }

    @Test
    void documentConverterUnmarshalThrowsUnsupported() {
        VisioDocumentConverter converter = new VisioDocumentConverter();
        assertThrows(UnsupportedOperationException.class,
                () -> converter.unmarshal(null, null));
    }

    // ── VisioPagesConverter ─────────────────────────────────────────────────

    @Test
    void pagesConverterProducesRootElement() {
        VisioDocument doc = new VisioDocument();
        String xml = pagesXstream.toXML(doc);
        assertThat(xml).contains("Pages");
    }

    @Test
    void pagesConverterIncludesNamespaces() {
        VisioDocument doc = new VisioDocument();
        String xml = pagesXstream.toXML(doc);
        assertThat(xml).contains("http://schemas.microsoft.com/office/visio/2012/main");
        assertThat(xml).contains("http://schemas.openxmlformats.org/officeDocument/2006/relationships");
    }

    @Test
    void pagesConverterWritesPageEntry() {
        VisioDocument doc = new VisioDocument();
        VisioPage page = new VisioPage("0", "Architecture");
        doc.getPages().add(page);

        String xml = pagesXstream.toXML(doc);

        assertThat(xml).contains("Page");
        assertThat(xml).contains("Architecture");
        assertThat(xml).contains("Rel");
        assertThat(xml).contains("rId1");
    }

    @Test
    void pagesConverterWritesMultiplePages() {
        VisioDocument doc = new VisioDocument();
        doc.getPages().add(new VisioPage("0", "Page One"));
        doc.getPages().add(new VisioPage("1", "Page Two"));

        String xml = pagesXstream.toXML(doc);

        assertThat(xml).contains("Page One");
        assertThat(xml).contains("Page Two");
        assertThat(xml).contains("rId1");
        assertThat(xml).contains("rId2");
    }

    @Test
    void pagesConverterProducesEmptyPagesForEmptyDocument() {
        VisioDocument doc = new VisioDocument();
        String xml = pagesXstream.toXML(doc);
        // Root element should exist but no Page children
        assertThat(xml).contains("Pages");
        assertThat(xml).doesNotContain("<Page ");
    }

    @Test
    void pagesConverterUnmarshalThrowsUnsupported() {
        VisioPagesConverter converter = new VisioPagesConverter();
        assertThrows(UnsupportedOperationException.class,
                () -> converter.unmarshal(null, null));
    }
}
