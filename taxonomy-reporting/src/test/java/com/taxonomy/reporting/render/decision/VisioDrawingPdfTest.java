package com.taxonomy.reporting.render.decision;

import com.taxonomy.diagram.DiagramLayout;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
import com.taxonomy.diagram.VisioDrawingOptions;
import com.taxonomy.dto.TaxonomyResponsibilityLookup.Assignment;
import com.taxonomy.export.VisioDiagramService;
import com.taxonomy.export.VisioPackageBuilder;
import com.taxonomy.extension.api.report.ReportRenderContext;
import com.taxonomy.reporting.api.decision.DecisionReportOptions;
import com.taxonomy.visio.VisioExportMetadata;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.*;

class VisioDrawingPdfTest {
    @TempDir Path temp;
    static final String PAGES = "visio/pages/pages.xml";
    private static final String RELS = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String OFFICE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String VISIO_REL = "http://schemas.microsoft.com/visio/2010/relationships/";

    private Path drawing(int count) throws IOException {
        Path input = temp.resolve("input.vsdx");
        var pages = new StringBuilder("<Pages xmlns='http://schemas.microsoft.com/office/visio/2012/main' xmlns:ref='" + OFFICE + "'>");
        var relationships = new StringBuilder("<Relationships xmlns='" + RELS + "'>");
        var types = new StringBuilder("<Types xmlns='http://schemas.openxmlformats.org/package/2006/content-types'>"
                + "<Default Extension='rels' ContentType='application/vnd.openxmlformats-package.relationships+xml'/>"
                + "<Default Extension='xml' ContentType='application/xml'/>");
        try (var zip = new ZipOutputStream(Files.newOutputStream(input))) {
            entry(zip,"_rels/.rels","<Relationships xmlns='" + RELS + "'><Relationship Id='doc' Type='"
                    + VISIO_REL + "document' Target='visio/document.xml'/><Relationship Id='manifest' Type='urn:manifest' Target='taxonomy/manifest.json'/></Relationships>");
            entry(zip, "taxonomy/manifest.json", "{}");
            entry(zip,"visio/document.xml","<document/>");
            entry(zip,"visio/_rels/document.xml.rels","<Relationships xmlns='" + RELS + "'><Relationship Id='pages' Type='"
                    + VISIO_REL + "pages' Target='pages/pages.xml'/></Relationships>");
            entry(zip,"visio/masters/shared.xml","<master/>");
            for (int i = 0; i < count; i++) {
                int number = count - i;
                String name = "sheet-" + number + ".xml";
                pages.append("<Page ID='").append(500 + i).append("'><Rel ref:id='sheet").append(number).append("'/></Page>");
                relationships.append("<Relationship Id='sheet").append(number).append("' Type='").append(VISIO_REL)
                        .append("page' Target='").append(name).append("'/>");
                types.append("<Override PartName='/visio/pages/").append(name).append("' ContentType='application/vnd.ms-visio.page+xml'/>");
                entry(zip,"visio/pages/" + name,"<geometry id='" + number + "'/>");
                entry(zip,"visio/pages/_rels/" + name + ".rels","<Relationships xmlns='" + RELS
                        + "'><Relationship Id='master' Type='" + VISIO_REL + "master' Target='../masters/shared.xml'/></Relationships>");
            }
            entry(zip,PAGES,pages.append("</Pages>").toString());
            entry(zip,"visio/pages/_rels/pages.xml.rels",relationships.append("</Relationships>").toString());
            entry(zip,"[Content_Types].xml",types.append("</Types>").toString());
        }
        return input;
    }

    private static void entry(ZipOutputStream zip, String name, String contents) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(contents.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    @Test void all160DrawingsRetainOnlyTheirOwnGeometryAndDependenciesInIndexOrder() throws Exception {
        var single = VisioDrawingPdf.pagePackages(drawing(160), temp);
        assertThat(single).hasSize(160);
        var parser = DocumentBuilderFactory.newNSInstance().newDocumentBuilder();
        for (int i = 0; i < single.size(); i++) {
            String name = "sheet-" + (160 - i) + ".xml";
            try (var zip = new ZipFile(single.get(i).toFile())) {
                assertThat(zip.stream().map(ZipEntry::getName).filter(p -> p.startsWith("visio/pages/sheet-"))).containsExactly("visio/pages/" + name);
                assertThat(zip.stream().map(ZipEntry::getName).filter(p -> p.startsWith("visio/pages/_rels/sheet-"))).containsExactly("visio/pages/_rels/" + name + ".rels");
                assertThat(zip.getEntry("visio/masters/shared.xml")).isNotNull();
                assertThat(zip.getEntry("taxonomy/manifest.json")).isNull();
                var rootLinks = parser.parse(zip.getInputStream(zip.getEntry("_rels/.rels"))).getElementsByTagNameNS(RELS,"Relationship");
                assertThat(rootLinks.getLength()).isOne();
                var pages = parser.parse(zip.getInputStream(zip.getEntry(PAGES))).getElementsByTagNameNS("*","Page");
                assertThat(pages.getLength()).isOne();
                assertThat(((Element)pages.item(0)).getAttribute("ID")).isEqualTo(Integer.toString(500 + i));
                var rels = parser.parse(zip.getInputStream(zip.getEntry("visio/pages/_rels/pages.xml.rels"))).getElementsByTagNameNS(RELS,"Relationship");
                assertThat(rels.getLength()).isOne();
                assertThat(((Element)rels.item(0)).getAttribute("Target")).isEqualTo(name);
                var types = parser.parse(zip.getInputStream(zip.getEntry("[Content_Types].xml"))).getElementsByTagNameNS("*","Override");
                assertThat(types.getLength()).isOne();
                assertThat(((Element)types.item(0)).getAttribute("PartName")).isEqualTo("/visio/pages/" + name);
            }
        }
    }
    @Test
    @EnabledIfSystemProperty(named="taxonomy.test.visio-pdf",matches="true")
    void installedRendererKeepsSourcePageSizesAndOrder() throws Exception {
        var beans = new DefaultListableBeanFactory();
        var drawings = new DecisionRationaleVisioRenderer(beans.getBeanProvider(VisioDiagramService.class),
                beans.getBeanProvider(VisioPackageBuilder.class));
        var context = ReportRenderContext.ofPayload(DecisionCompactReportTest.report(DecisionReportOptions.TreeLayout.AUTO,12));
        Path source = Files.write(temp.resolve("decisions.vsdx"),drawings.render(context).bytes());
        var rendered = new DecisionDrawingPdfRenderer(drawings).render(context);
        try (var zip = new ZipFile(source.toFile()); var pdf = Loader.loadPDF(rendered.bytes())) {
            var document = DocumentBuilderFactory.newNSInstance().newDocumentBuilder().parse(zip.getInputStream(zip.getEntry(PAGES)));
            var pages = document.getElementsByTagNameNS("*","Page");
            assertThat(pdf.getNumberOfPages()).isEqualTo(pages.getLength());
            for (int i=0;i<pages.getLength();i++) {
                var cells = ((Element) pages.item(i)).getElementsByTagNameNS("*","Cell");
                var bounds = pdf.getPage(i).getMediaBox();
                for (int j=0;j<cells.getLength();j++) {
                    Element cell = (Element) cells.item(j);
                    if (cell.getAttribute("N").equals("PageWidth") || cell.getAttribute("N").equals("PageHeight")) {
                        double actual = cell.getAttribute("N").equals("PageWidth") ? bounds.getWidth() : bounds.getHeight();
                        assertThat(actual).isCloseTo(72 * Double.parseDouble(cell.getAttribute("V")), within(0.25));
                    }
                }
            }
            assertThat(new PDFTextStripper().getText(pdf)).contains("CP-2");
        }
    }

    @Test
    @EnabledIfSystemProperty(named="taxonomy.test.visio-pdf", matches="true")
    void installedRendererKeepsLongApplicationOwnerTextInsideItsScreenCard() throws Exception {
        var assignment = new Assignment("app-ops", "Architecture Operations QA", "assigned-source");
        var node = new DiagramNode("UA-1544", "Strategic Deployment, Redeployment and RSOM Planning Applications", "UA",
                .72, false, 0, 3, false, null, false, assignment);
        var graph = new DiagramModel("Screen application", List.of(node), List.of(), new DiagramLayout("LR", false));
        var drawing = new VisioDiagramService().convert(graph, VisioExportMetadata.unbound(), null, VisioDrawingOptions.screen(false));
        var card = drawing.getPages().getFirst().getShapes().getFirst();
        Path source = Files.write(temp.resolve("application.vsdx"), new VisioPackageBuilder().build(drawing));
        byte[] rendered = VisioDrawingPdf.convert(source, "soffice", "pdfunite", "pdfinfo");
        try (var pdf = Loader.loadPDF(rendered)) {
            var positions = new ArrayList<TextPosition>();
            var text = new PDFTextStripper() {
                @Override protected void writeString(String value, List<TextPosition> glyphs) throws IOException {
                    positions.addAll(glyphs);
                    super.writeString(value, glyphs);
                }
            };
            assertThat(text.getText(pdf).replaceAll("\\s+", " "))
                    .contains(node.label(), "Owner: " + assignment.name());
            assertThat(positions).isNotEmpty();
            double pageHeight = pdf.getPage(0).getMediaBox().getHeight();
            double left = (card.getX() - card.getWidth() / 2) * 72;
            double right = (card.getX() + card.getWidth() / 2) * 72;
            double top = pageHeight - (card.getY() + card.getHeight() / 2) * 72;
            double bottom = pageHeight - (card.getY() - card.getHeight() / 2) * 72;
            assertThat(positions).allSatisfy(glyph -> {
                assertThat((double) glyph.getXDirAdj()).isGreaterThan(left + 2);
                assertThat((double) (glyph.getXDirAdj() + glyph.getWidthDirAdj())).isLessThan(right - 2);
                assertThat((double) (glyph.getYDirAdj() - glyph.getHeightDir())).isGreaterThan(top + 2);
                assertThat((double) glyph.getYDirAdj()).isLessThan(bottom - 2);
            });
        }
    }

    @Test void rejectsMorePagesThanTheNativeProfileSupports() throws Exception {
        assertThatThrownBy(() -> VisioDrawingPdf.pagePackages(drawing(161),temp))
                .isInstanceOf(IOException.class).hasMessageContaining("160");
    }

    @Test void rejectsAnEmptyIndexBeforeRendering() throws Exception {
        var input = drawing(0);
        assertThatThrownBy(()->VisioDrawingPdf.pagePackages(input,temp)).isInstanceOf(IOException.class);
    }
}
