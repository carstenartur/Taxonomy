package com.taxonomy.export;

import com.taxonomy.diagram.DiagramLayout;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
import com.taxonomy.diagram.DiagramScene;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.awt.Color;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerThemeRenderingTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "@import url(data:text/css;:root{--taxonomy-layer-cap-surface:#123456});",
            "@supports selector(example;:root{--taxonomy-layer-cap-surface:#123456}) {}",
            ".example[data-brand=x;:root{--taxonomy-layer-cap-surface:#123456}] {}"
    })
    void urlFunctionAndAttributePreludesCannotSmuggleRootRules(String prelude) {
        var theme = ExportTheme.fromStylesheet(prelude + ":root{--taxonomy-layer-app-surface:#abc}");
        String svg = new SvgDiagramRenderer(() -> theme).render(scene());
        assertThat(svg).contains("fill=\"#DBEAFE\"", "fill=\"#AABBCC\"")
                .doesNotContain("fill=\"#123456\"");
    }

    @Test
    void quotedAndFunctionValuesCannotSmuggleDeclarationsIntoRootColors() {
        var theme = ExportTheme.fromStylesheet("""
                :root {
                  --unrelated: "a; --taxonomy-layer-cap-surface: #123456;";
                  --another: url(a; --taxonomy-layer-svc-surface: #123456;);
                  --bracketed: [a; --taxonomy-layer-cap-surface: #123456;];
                  --escaped: a\\; --taxonomy-layer-svc-surface: #123456;
                  --taxonomy-layer-app-surface: #abc;
                }
                """);
        String svg = new SvgDiagramRenderer(() -> theme).render(scene());
        assertThat(svg).contains("fill=\"#DBEAFE\"", "fill=\"#FEF3C7\"", "fill=\"#AABBCC\"")
                .doesNotContain("fill=\"#123456\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"var(--brand)", "navy", "rgb(18, 52, 86)", "#1234", "#12345678", "#12", "#12345", "#1234567"})
    void unsupportedColorValuesKeepTheBundledFill(String value) {
        var theme = ExportTheme.fromStylesheet(":root { --taxonomy-layer-cap-surface: " + value + "; }");
        assertThat(new SvgDiagramRenderer(() -> theme).render(scene())).contains("fill=\"#DBEAFE\"");
    }

    @Test
    void svgCapturesOneThemeForEveryNodeAndItsInventory() {
        var calls = new AtomicInteger();
        var first = ExportTheme.fromStylesheet(":root{--taxonomy-layer-default-surface:#123456;--taxonomy-canvas-background:#102030}");
        var second = ExportTheme.fromStylesheet(":root{--taxonomy-layer-default-surface:#654321}");
        var renderer = new SvgDiagramRenderer(() -> calls.incrementAndGet() == 1 ? first : second);
        String svg = renderer.render(scene());
        assertThat(calls).hasValue(1);
        assertThat(svg).contains("fill=\"#102030\"").doesNotContain("fill=\"#654321\"");
        assertThat(svg).contains("Original fixture capability", "Original fixture service", "Original fixture application");
    }

    @Test
    void pdfCapturesOneThemeForTheDiagramAndAllEvidencePages() throws Exception {
        var calls = new AtomicInteger();
        var first = ExportTheme.fromStylesheet(":root{--taxonomy-canvas-background:#102030;--taxonomy-canvas-foreground:#102030}");
        var second = ExportTheme.fromStylesheet(":root{--taxonomy-canvas-background:#654321}");
        byte[] bytes = new ArchitecturePdfRenderer(() -> calls.incrementAndGet() == 1 ? first : second)
                .renderWorkingView(scene());
        assertThat(calls).hasValue(1);
        try (var document = Loader.loadPDF(bytes)) {
            assertThat(document.getNumberOfPages()).isGreaterThanOrEqualTo(2);
            var rasterizer = new PDFRenderer(document);
            for (int index = 0; index < document.getNumberOfPages(); index++) {
                var image = rasterizer.renderImage(index);
                assertThat(new Color(image.getRGB(2, 2))).isEqualTo(Color.decode("#102030"));
            }
            assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(document))
                    .contains("Original fixture capability", "Original fixture service", "Original fixture application");
        }
    }

    @Test
    void visioCapturesOneThemeForAllDrawingCopiesAndKeepsTheEvidenceManifest() throws Exception {
        var calls = new AtomicInteger();
        var first = ExportTheme.fromStylesheet(":root{--taxonomy-layer-cap-surface:#123456}");
        var second = ExportTheme.fromStylesheet(":root{--taxonomy-layer-cap-surface:#654321}");
        byte[] bytes = new VisioPackageBuilder(() -> calls.incrementAndGet() == 1 ? first : second)
                .build(new VisioDiagramService().convert(model()));
        assertThat(calls).hasValue(1);
        var parts = VisioHandoffContractTest.parts(bytes);
        assertThat(parts.get("visio/pages/page1.xml")).contains("N=\"FillForegnd\" V=\"#123456\"", "N=\"Color\" V=\"#FFFFFF\"");
        assertThat(parts.entrySet().stream().filter(entry -> entry.getKey().matches("visio/pages/page[0-9]+\\.xml")))
                .allSatisfy(entry -> assertThat(entry.getValue()).doesNotContain("#654321"));
        assertThat(parts.get("taxonomy/manifest.json"))
                .contains("Original fixture capability", "Original fixture service", "Original fixture application");
    }

    private static DiagramScene scene() {
        return new LayeredDiagramLayoutService().layout(model());
    }

    private static DiagramModel model() {
        return new DiagramModel("Authored customer theme fixture", List.of(
                new DiagramNode("fixture-capability", "Original fixture capability", "Capabilities", .125, true, 1),
                new DiagramNode("fixture-service", "Original fixture service", "Services", .5, false, 2),
                new DiagramNode("fixture-application", "Original fixture application", "Applications", .75, false, 3)),
                List.of(), new DiagramLayout("LR", true));
    }
}
