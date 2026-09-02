package com.taxonomy.export;

import com.microsoft.schemas.office.visio.x2012.main.PageContentsDocument;
import com.microsoft.schemas.office.visio.x2012.main.PagesDocument;
import com.microsoft.schemas.office.visio.x2012.main.VisioDocumentDocument1;
import org.apache.poi.xdgf.usermodel.XDGFPage;
import org.apache.poi.xdgf.usermodel.XmlVisioDocument;
import org.apache.xmlbeans.XmlError;
import org.apache.xmlbeans.XmlObject;
import org.apache.xmlbeans.XmlOptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VisioPackagePoiCompatibilityTest {

    private final VisioPackageBuilder builder = new VisioPackageBuilder();

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
