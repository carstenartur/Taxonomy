package com.taxonomy.export;

import com.taxonomy.visio.VisioConnect;
import com.taxonomy.visio.VisioPage;
import com.taxonomy.visio.VisioShape;
import com.taxonomy.visio.converter.VisioPageContentsConverter;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.XppDriver;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VisioDynamicGlueContractTest {

    private static final String VISIO_NS =
            "http://schemas.microsoft.com/office/visio/2012/main";

    @Test
    void masterlessConnectorUsesCanonicalRotatingTransformAndDynamicGlue()
            throws Exception {
        VisioPage page = new VisioPage("1", "Architecture");
        page.getShapes().add(new VisioShape(
                "1", "Source", 1, 1, 2, 1, "Capability", false));
        page.getShapes().add(new VisioShape(
                "2", "Target", 4, 3, 2, 1, "Service", false));
        page.getConnects().add(new VisioConnect("1", "2", "SUPPORTS"));

        XStream xstream = new XStream(new XppDriver());
        xstream.setMode(XStream.NO_REFERENCES);
        xstream.alias("PageContents", VisioPage.class);
        xstream.registerConverter(new VisioPageContentsConverter());
        Document document = parse(xstream.toXML(page));

        Element connector = shapeById(document, "3");
        assertThat(cellValue(connector, "OneD")).isEqualTo("1");
        assertThat(cellValue(connector, "ObjType")).isEqualTo("2");
        assertThat(cellValue(connector, "GlueType")).isEqualTo("2");

        // Persisted values already place the connector between the linked shape
        // centres, even before a consumer recalculates ShapeSheet formulas.
        assertThat(cellValue(connector, "BeginX")).isEqualTo("1");
        assertThat(cellValue(connector, "BeginY")).isEqualTo("1");
        assertThat(cellValue(connector, "EndX")).isEqualTo("4");
        assertThat(cellValue(connector, "EndY")).isEqualTo("3");
        assertThat(cellValue(connector, "PinX")).isEqualTo("2.5");
        assertThat(cellValue(connector, "PinY")).isEqualTo("2");
        assertThat(cellValue(connector, "Width"))
                .isEqualTo("3.605551275463989");
        assertThat(cellValue(connector, "LocPinX"))
                .isEqualTo("1.8027756377319946");
        assertThat(cellValue(connector, "Angle"))
                .isEqualTo("0.5880026035475675");

        assertThat(cellFormula(connector, "PinX"))
                .isEqualTo("(BeginX+EndX)*0.5");
        assertThat(cellFormula(connector, "PinY"))
                .isEqualTo("(BeginY+EndY)*0.5");
        assertThat(cellFormula(connector, "Width"))
                .isEqualTo("SQRT((EndX-BeginX)^2+(EndY-BeginY)^2)");
        assertThat(cellValue(connector, "Height")).isEqualTo("0");
        assertThat(cellFormula(connector, "LocPinX")).isEqualTo("Width*0.5");
        assertThat(cellValue(connector, "LocPinY")).isEqualTo("0");
        assertThat(cellFormula(connector, "Angle"))
                .isEqualTo("ATAN2(EndY-BeginY,EndX-BeginX)");

        Element lineTo = geometryRow(connector, "LineTo");
        assertThat(cellFormula(lineTo, "X")).isEqualTo("Width");
        assertThat(cellValue(lineTo, "X"))
                .isEqualTo("3.605551275463989");
        assertThat(cellValue(lineTo, "Y")).isEqualTo("0");

        NodeList connects = document.getElementsByTagNameNS(VISIO_NS, "Connect");
        assertThat(connects.getLength()).isEqualTo(2);
        assertThat(connectAttributes(connects))
                .containsExactly(
                        "BeginX:1:PinX",
                        "EndX:2:PinX");
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(
                "http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature(
                "http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature(
                "http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(
                xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static Element shapeById(Document document, String id) {
        NodeList shapes = document.getElementsByTagNameNS(VISIO_NS, "Shape");
        for (int index = 0; index < shapes.getLength(); index++) {
            Element shape = (Element) shapes.item(index);
            if (id.equals(shape.getAttribute("ID"))) {
                return shape;
            }
        }
        throw new AssertionError("Missing shape " + id);
    }

    private static Element geometryRow(Element shape, String type) {
        NodeList rows = shape.getElementsByTagNameNS(VISIO_NS, "Row");
        for (int index = 0; index < rows.getLength(); index++) {
            Element row = (Element) rows.item(index);
            if (type.equals(row.getAttribute("T"))) {
                return row;
            }
        }
        throw new AssertionError("Missing geometry row " + type);
    }

    private static String cellValue(Element parent, String name) {
        return cell(parent, name).getAttribute("V");
    }

    private static String cellFormula(Element parent, String name) {
        return cell(parent, name).getAttribute("F");
    }

    private static Element cell(Element parent, String name) {
        NodeList cells = parent.getElementsByTagNameNS(VISIO_NS, "Cell");
        for (int index = 0; index < cells.getLength(); index++) {
            Element cell = (Element) cells.item(index);
            if (name.equals(cell.getAttribute("N"))) {
                return cell;
            }
        }
        throw new AssertionError("Missing cell " + name);
    }

    private static List<String> connectAttributes(NodeList connects) {
        List<String> values = new ArrayList<>();
        for (int index = 0; index < connects.getLength(); index++) {
            Element connect = (Element) connects.item(index);
            values.add(connect.getAttribute("FromCell")
                    + ":" + connect.getAttribute("ToSheet")
                    + ":" + connect.getAttribute("ToCell"));
        }
        return values;
    }
}
