package com.taxonomy.export;

import com.microsoft.schemas.office.visio.x2012.main.PageContentsDocument;
import com.microsoft.schemas.office.visio.x2012.main.PagesDocument;
import com.microsoft.schemas.office.visio.x2012.main.VisioDocumentDocument1;
import com.taxonomy.visio.VisioConnect;
import com.taxonomy.visio.VisioDocument;
import com.taxonomy.visio.VisioPage;
import com.taxonomy.visio.VisioShape;
import org.apache.poi.xdgf.usermodel.XDGFPage;
import org.apache.poi.xdgf.usermodel.XmlVisioDocument;
import org.apache.xmlbeans.XmlError;
import org.apache.xmlbeans.XmlObject;
import org.apache.xmlbeans.XmlOptions;
import org.junit.jupiter.api.Test;

import java.awt.geom.AffineTransform;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class VisioPackagePoiCompatibilityTest {

    private final VisioPackageBuilder builder = new VisioPackageBuilder();

    @Test
    void steepArchitectureConnectorRunsInTheGutterWithoutCrossingItsTargetColumn() throws Exception {
        var page = new VisioPage("0", "Time tracking");
        page.getShapes().add(new VisioShape("1", "N25", 7.5, 3.9, 2, .9, "UA", false));
        for (int row = 0; row < 3; row++) {
            page.getShapes().add(new VisioShape(Integer.toString(row + 2),
                    "N" + (20 + row), 4.5, 8.7 + row * 1.2, 2, .9, "CR", false));
        }
        page.getConnects().add(new VisioConnect("1", "4", "SUPPORTS"));
        var document = new VisioDocument();
        document.getPages().add(page);
        try (var reader = new XmlVisioDocument(new ByteArrayInputStream(builder.build(document)))) {
            var content = reader.getPages().iterator().next().getContent();
            var connector = content.getTopLevelShapes().stream().filter(shape -> shape.getID() == 5)
                    .findFirst().orElseThrow();
            var transform = new AffineTransform();
            transform.translate(connector.getPinX(), connector.getPinY());
            transform.rotate(connector.getAngle());
            transform.translate(-connector.getLocPinX(), -connector.getLocPinY());
            var path = connector.getPath().getPathIterator(transform);
            double[] point = new double[6];
            for (double[] expected : List.of(new double[]{6.5, 3.9}, new double[]{6, 3.9},
                    new double[]{6, 11.1}, new double[]{5.5, 11.1})) {
                assertThat(path.isDone()).isFalse();
                path.currentSegment(point);
                assertThat(point[0]).isCloseTo(expected[0], offset(.00001));
                assertThat(point[1]).isCloseTo(expected[1], offset(.00001));
                path.next();
            }
            assertThat(path.isDone()).isTrue();
            assertThat(content.getConnections()).hasSize(2);
        }
    }

    @Test
    void generatedVisioPartsValidateAgainstPoiSchemaBindings() throws Exception {
        byte[] packageBytes = builder.build(
                VisioPackageBuilderTest.representativeDocument());

        assertSchemaValid(VisioDocumentDocument1.Factory.parse(
                VisioPackageBuilderTest.readEntry(
                        packageBytes, "visio/document.xml")));
        assertSchemaValid(PagesDocument.Factory.parse(
                VisioPackageBuilderTest.readEntry(
                        packageBytes, "visio/pages/pages.xml")));
        assertSchemaValid(PageContentsDocument.Factory.parse(
                VisioPackageBuilderTest.readEntry(
                        packageBytes, "visio/pages/page1.xml")));
    }

    @Test
    void generatedPackageContainsDesktopWindowPartAndRelationship() throws Exception {
        byte[] packageBytes = builder.build(
                VisioPackageBuilderTest.representativeDocument());

        String windows = VisioPackageBuilderTest.readEntry(
                packageBytes, "visio/windows.xml");
        String relationships = VisioPackageBuilderTest.readEntry(
                packageBytes, "visio/_rels/document.xml.rels");

        assertThat(windows).contains("<Windows")
                .contains("http://schemas.microsoft.com/office/visio/2012/main");
        assertThat(relationships)
                .contains("http://schemas.microsoft.com/visio/2010/relationships/windows")
                .contains("Target=\"windows.xml\"");
    }

    @Test
    void generatedPackageIsReadableByApachePoiXdGF() throws Exception {
        byte[] packageBytes = builder.build(
                VisioPackageBuilderTest.representativeDocument());

        try (XmlVisioDocument document = new XmlVisioDocument(
                new ByteArrayInputStream(packageBytes))) {
            assertThat(document.getPages()).hasSize(1);

            XDGFPage page = document.getPages().iterator().next();
            assertThat(page.getName()).isEqualTo("Architecture");
            assertThat(page.getPageSize().getWidth()).isGreaterThanOrEqualTo(11.0);
            assertThat(page.getPageSize().getHeight()).isGreaterThanOrEqualTo(8.5);
            assertThat(page.getContent().getTopLevelShapes()).hasSize(3);
            assertThat(page.getContent().getConnections()).hasSize(2);
            assertThat(page.getContent().getShapeById(10)).isNotNull();
            assertThat(page.getContent().getShapeById(42)).isNotNull();
            assertThat(page.getContent().getShapeById(43)).isNotNull();
        }
    }

    private static void assertSchemaValid(XmlObject xmlObject) {
        List<XmlError> errors = new ArrayList<>();
        XmlOptions options = new XmlOptions().setErrorListener(errors);

        assertThat(xmlObject.validate(options))
                .as("schema errors: %s", errors)
                .isTrue();
    }
}
