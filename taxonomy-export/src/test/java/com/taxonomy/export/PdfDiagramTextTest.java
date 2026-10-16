package com.taxonomy.export;

import com.taxonomy.diagram.DiagramScene;
import com.taxonomy.diagram.DiagramSceneNode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PdfDiagramTextTest {
    private static final PDType1Font FONT = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

    @Test
    void measuresTransliteratedGlyphsAndKeepsEveryLineWithinItsBudget() throws Exception {
        for (String input : List.of("ÄÖÜß ".repeat(12), "W".repeat(100), "small words ".repeat(15))) {
            var lines = PdfDiagramText.wrap(input, FONT, 10, 90, 2);
            assertTrue(lines.size() <= 2);
            for (String line : lines) assertTrue(PdfDiagramText.width(FONT, 10, line) <= 90, line);
            assertTrue(lines.getLast().endsWith("..."));
        }
        assertEquals(List.of("Short"), PdfDiagramText.wrap("Short", FONT, 10, 90, 2));
        assertEquals("AeOeUess", PdfDiagramText.printable("ÄÖÜß"));
        assertEquals("n/a", PdfDiagramText.printable(null));
        assertEquals("n/a", PdfDiagramText.printable("  "));
    }

    @Test
    void unlimitedWrappingDoesNotTruncateLongUnspacedWords() throws Exception {
        String value = "W".repeat(150);
        var lines = PdfDiagramText.wrap(value, FONT, 10, 90, Integer.MAX_VALUE);
        assertEquals(value, String.join("", lines));
        assertTrue(lines.size() > 2);
    }

    @Test
    void rejectsImpossibleBoundsInsteadOfWritingOutsideTheBox() {
        assertThrows(IllegalArgumentException.class, () -> PdfDiagramText.wrap("Wide", FONT, 10, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> PdfDiagramText.wrap("Wide", FONT, 0, 10, 1));
        assertThrows(IllegalArgumentException.class, () -> PdfDiagramText.wrap("Wide", FONT, 10, 10, 0));
        assertThrows(IllegalArgumentException.class, () -> PdfDiagramText.wrap("Wide", FONT, 10, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> PdfDiagramText.wrap("Wide", FONT, 10, 1, 2));
    }

    @Test
    void percentageFormattingNeverTurnsAPositiveFractionIntoZero() {
        assertEquals("0%", DiagramPresentation.percent(0));
        assertEquals("100%", DiagramPresentation.percent(1));
        assertEquals("0.5%", DiagramPresentation.percent(.005));
        assertEquals("0.125%", DiagramPresentation.percent(.00125));
        assertEquals("<0.000001%", DiagramPresentation.percent(Double.MIN_VALUE));
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -.1, 1.1})
            assertEquals("n/a", DiagramPresentation.percent(invalid));
    }

    @Test
    void knownTaxonomyFamiliesAndUnknownTypesHaveExplicitPresentationColors() {
        assertEquals("#F1F5F9", DiagramPresentation.fill(null));
        assertEquals("#F1F5F9", DiagramPresentation.fill("unmapped fixture type"));
        for (String type : List.of("Capabilities", "CP")) assertEquals("#DBEAFE", DiagramPresentation.fill(type));
        for (String type : List.of("Business Processes", "Business Roles", "BP", "BR")) assertEquals("#DCFCE7", DiagramPresentation.fill(type));
        for (String type : List.of("Core Services", "COI Services", "Services", "CR", "CI")) assertEquals("#FEF3C7", DiagramPresentation.fill(type));
        for (String type : List.of("User Applications", "Applications", "UA")) assertEquals("#F3E8FF", DiagramPresentation.fill(type));
        for (String type : List.of("Information Products", "IP")) assertEquals("#FFE4E6", DiagramPresentation.fill(type));
        for (String type : List.of("Communications Services", "CO")) assertEquals("#CFFAFE", DiagramPresentation.fill(type));
    }

    @Test
    void inventorySeparatesContainersAndDoesNotDoubleCountRepeatedIdentities() {
        var container = new DiagramSceneNode("container", "Group", "Capabilities", 0, false, 1, 1, false, null, true, 0, 0, 238, 82);
        var element = new DiagramSceneNode("element", "Function", null, .01, true, 1, 2, false, "container", false, 0, 100, 238, 82);
        var scene = new DiagramScene("Fixture", 760, 420, "LR", List.of(container, element, element), List.of());
        var summary = String.join("\n", DiagramPresentation.inventorySummary(scene));
        assertTrue(summary.contains("Elements: 1 | Containers: 1 | Relationships: 0"));
        assertTrue(summary.contains("Unclassified: 1"));
        assertTrue(summary.contains("Human confirmation is not recorded"));
    }

    @Test
    void completeInventoryPaginatesAndRetainsTheLastUnshortenedLabel() throws Exception {
        var nodes = new ArrayList<DiagramSceneNode>();
        for (int i = 0; i < 100; i++) nodes.add(new DiagramSceneNode("fixture-" + i,
                "Unabridged label " + i + " with a deliberately long description in the inventory",
                "Capabilities", .005, true, 1, 3, false, null, false, 40, 100 * i, 238, 82));
        var scene = new DiagramScene("Fixture", 760, 10_100, "LR", nodes, List.of());
        try (var document = Loader.loadPDF(new ArchitecturePdfRenderer().render(scene, "fixture"))) {
            assertTrue(document.getNumberOfPages() > 2);
            var reader = new PDFTextStripper(); reader.setStartPage(2);
            String text = reader.getText(document).replaceAll("\\s+", " ");
            assertTrue(text.contains("Elements: 100"));
            assertTrue(text.contains("Unabridged label 99 with a deliberately long description in the inventory"));
        }
    }
}
