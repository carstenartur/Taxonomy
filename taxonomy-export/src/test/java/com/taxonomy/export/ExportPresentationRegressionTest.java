package com.taxonomy.export;

import com.taxonomy.diagram.*;
import com.taxonomy.visio.VisioPage;
import com.taxonomy.visio.converter.VisioPageContentsConverter;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.XppDriver;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Authored offline diagram fixtures, not evidence from a live catalogue or model. */
class ExportPresentationRegressionTest {
    @Test
    void visioShowsCanonicalIdentityAndHierarchyDepthWithoutDependingOnShapeDataUi() {
        var document = new VisioDiagramService().convert(model());
        assertThat(document.getPages().getFirst().getShapes()).allSatisfy(shape -> {
            assertThat(shape.getText()).contains(shape.getProperties().get("taxonomy.id").value());
            assertThat(shape.getText()).contains("Depth 3");
        });
    }

    @Test
    void visioKeepsMultilineLabelsReadableWithoutRelationshipDetailPages() throws Exception {
        var labels = List.of("Information Management Capabilities",
                "Situational Awareness Information Products",
                "Information Dissemination Capabilities",
                "Message-Oriented Middleware Services");
        var nodes = new ArrayList<DiagramNode>();
        for (int i = 0; i < labels.size(); i++) {
            nodes.add(new DiagramNode("fixture-" + i, labels.get(i), "Capabilities",
                    .5, i == 0, 1, 3, false, null, false));
        }
        var document = new VisioDiagramService().convert(new DiagramModel(
                "Multiline labels", nodes, List.of(), new DiagramLayout("LR", true)));
        assertThat(document.getPages()).hasSize(2);
        assertThat(document.getLosses()).noneMatch(loss -> loss.field().equals("displayLabel"));
        for (var page : document.getPages()) {
            assertThat(page.isRelationshipDetail()).isFalse();
            assertThat(page.getShapes()).allSatisfy(shape -> {
                assertThat(shape.getText().replace("\n", " ").replaceAll("\\s+", " "))
                        .contains(shape.getProperties().get("taxonomy.label").value());
                assertThat(shape.getText()).doesNotContain("…").contains("Depth 3");
                assertThat(shape.getText().lines().count()).isLessThanOrEqualTo(5);
            });
        }
        var parts = VisioHandoffContractTest.parts(new VisioPackageBuilder().build(document));
        String overview = parts.get("visio/pages/page1.xml").replaceAll("\\s+", " ");
        for (String label : labels) assertThat(overview).contains(label);
    }

    @Test
    void visioUsesTaxonomyColorsEvenWhenEveryElementIsAnAnchor() throws Exception {
        var document = new VisioDiagramService().convert(model());
        var stream = new XStream(new XppDriver());
        stream.setMode(XStream.NO_REFERENCES);
        stream.alias("PageContents", VisioPage.class);
        stream.registerConverter(new VisioPageContentsConverter());
        String xml = stream.toXML(document.getPages().getFirst());
        // Same pale taxonomy palette as the existing vector PDF, not an approval color.
        assertThat(xml).contains("#DBEAFE", "#DCFCE7");
        assertThat(xml).doesNotContain("#4CAF50");
    }

    @Test
    void pdfKeepsWideGlyphsAndTransliteratedLabelsInsideTheActualBox() throws Exception {
        String label = "WWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWW ÄÖÜß ÄÖÜß";
        var node = new DiagramSceneNode("fixture-wide", label, "Communications Services",
                .005, true, 8, 3, false, null, false, 100, 100, 180, 84);
        var scene = new DiagramScene("Export fixture", 760, 420, "LR", List.of(node), List.of());
        try (var document = Loader.loadPDF(new ArchitecturePdfRenderer().render(scene, "fixture"))) {
            var positions = new ArrayList<TextPosition>();
            var reader = new PDFTextStripper() {
                @Override protected void writeString(String value, List<TextPosition> values) throws IOException {
                    for (var p : values) {
                        // Page margin 32 + existing header 54 + node y 100.
                        if (p.getYDirAdj() >= 186 && p.getYDirAdj() <= 270) positions.add(p);
                    }
                    super.writeString(value, values);
                }
            };
            reader.setEndPage(1); // Inventory text uses its own page coordinates.
            String text = reader.getText(document);
            assertThat(text).contains("fixture-wide", "Depth 3", "0.5%");
            assertThat(positions).isNotEmpty().allSatisfy(p -> {
                assertThat(p.getXDirAdj()).isGreaterThanOrEqualTo(132);
                assertThat(p.getXDirAdj() + p.getWidthDirAdj()).isLessThanOrEqualTo(312);
            });
        }
    }

    @Test
    void pdfIncludesScopedElementAndRelationshipCounts() throws Exception {
        var scene = new LayeredDiagramLayoutService().layout(model());
        try (var document = Loader.loadPDF(new ArchitecturePdfRenderer().render(scene, "fixture"))) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("Elements: 2", "Containers: 0", "Relationships: 1");
            assertThat(text).contains("Capabilities: 1", "Business Processes: 1");
        }
    }

    @Test
    void unreadablePosterScaleFailsExplicitlyInsteadOfClippingMinimumSizeText() {
        var node = new DiagramSceneNode("fixture", "Working time", "Capabilities",
                .01, true, 1, 3, false, null, false, 50, 50, 238, 82);
        var scene = new DiagramScene("Too wide", 100_000, 420, "LR", List.of(node), List.of());
        assertThatThrownBy(() -> new ArchitecturePdfRenderer().render(scene, "fixture"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("readable");
    }

    private static DiagramModel model() {
        return new DiagramModel("Export fixture", List.of(
                new DiagramNode("fixture-capability", "Time capture", "Capabilities", .01, true, 1, 3, false, null, false),
                new DiagramNode("fixture-process", "Record working time", "Business Processes", .005, true, 2, 3, false, null, false)),
                List.of(new DiagramEdge("fixture-relation", "fixture-capability", "fixture-process", "REALIZED_BY", .5, "impact")),
                new DiagramLayout("LR", true));
    }
}
