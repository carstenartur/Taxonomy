package com.taxonomy.shared.config;

import com.taxonomy.composition.plugins.PluginCatalogConfiguration;
import com.taxonomy.diagram.DiagramLayout;
import com.taxonomy.diagram.DiagramEdge;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
import com.taxonomy.diagram.DiagramRelationMetadata;
import com.taxonomy.diagram.DiagramScene;
import com.taxonomy.export.ArchitecturePdfRenderer;
import com.taxonomy.export.LayeredDiagramLayoutService;
import com.taxonomy.export.SvgDiagramRenderer;
import com.taxonomy.export.VisioDiagramService;
import com.taxonomy.export.VisioPackageBuilder;
import com.taxonomy.export.service.ExportFormatExtensionRegistry;
import com.taxonomy.export.service.SvgExportExtension;
import com.taxonomy.export.spi.ExportContext;
import com.taxonomy.export.spi.ExportFormatDescriptor;
import com.taxonomy.preferences.PreferencesService;
import com.taxonomy.dto.ArchitectureReport;
import com.taxonomy.dto.RelationOrigin;
import com.taxonomy.reporting.render.document.ArchitectureReportTextRenderer;
import com.taxonomy.shared.controller.CustomerStylesheetController;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.Color;
import java.io.StringReader;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Authored export fixtures: no catalogue or provider calls. */
class CustomerExportStylesTest {
    @TempDir Path directory;

    @Test
    void sharedExportCatalogRendersStyledSvgWithoutOptionalFeatureConfiguration() throws Exception {
        Path css = directory.resolve("customer.css");
        Files.writeString(css, ":root { --taxonomy-layer-cap-surface: #17406f; }");
        Path plugins = Files.createDirectory(directory.resolve("plugins"));
        try (var context = configuredContext(css)) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("plugins",
                    Map.of("taxonomy.plugins.directory", plugins.toString(),
                            "taxonomy.plugins.cache-directory", directory.resolve("cache").toString())));
            context.register(PluginCatalogConfiguration.class, SvgExportExtension.class, ExportFormatExtensionRegistry.class);
            context.refresh();
            var registry = context.getBean(ExportFormatExtensionRegistry.class);
            assertThat(registry.listDescriptors()).extracting(ExportFormatDescriptor::id).containsExactly("svg");
            var result = registry.getRequired("svg").export(new ExportContext(model(),
                    Map.of("elementReasons", Map.of("fixture-capability", "Original shared evidence. CATALOG-EVIDENCE-END"))));
            assertThat(new String(result.bytes(), StandardCharsets.UTF_8))
                    .contains("fill=\"#17406F\"", "Original capability title", "CATALOG-EVIDENCE-END");
        }
    }

    @Test
    void existingSvgAndPdfBeansRenderCustomerColorsWithoutChangingRecordedContent() throws Exception {
        Path css = directory.resolve("customer.css");
        Files.writeString(css, """
                :root {
                  --taxonomy-layer-cap-surface: #17406f;
                  --taxonomy-canvas-background: #f0f4f8;
                  --taxonomy-canvas-foreground: #102030;
                }
                """);
        var scene = scene();
        var reasons = Map.of("fixture-capability", "Original evidence. CUSTOMER-EVIDENCE-END");
        try (var context = context(css)) {
            String svg = context.getBean(SvgDiagramRenderer.class).render(scene, reasons);
            var xml = parse(svg);
            var rects = xml.getElementsByTagNameNS("http://www.w3.org/2000/svg", "rect");
            assertThat(((Element) rects.item(0)).getAttribute("fill")).isEqualTo("#F0F4F8");
            assertThat(((Element) rects.item(1)).getAttribute("fill")).isEqualTo("#17406F");
            var node = (Element) xml.getElementsByTagNameNS("http://www.w3.org/2000/svg", "g").item(0);
            var texts = node.getElementsByTagNameNS("http://www.w3.org/2000/svg", "text");
            for (int index = 0; index < texts.getLength(); index++) {
                assertThat(((Element) texts.item(index)).getAttribute("fill")).isEqualTo("#FFFFFF");
            }
            assertThat(xml.getDocumentElement().getTextContent())
                    .contains("Original capability title", "12.5%", "CUSTOMER-EVIDENCE-END");
            assertThat(svg).isEqualTo(context.getBean(SvgDiagramRenderer.class).render(scene, reasons));

            byte[] pdf = context.getBean(ArchitecturePdfRenderer.class).renderWorkingView(scene, reasons);
            try (var document = Loader.loadPDF(pdf)) {
                var image = new PDFRenderer(document).renderImage(0);
                assertThat(new Color(image.getRGB(2, 2))).isEqualTo(Color.decode("#F0F4F8"));
                var box = scene.nodes().getFirst();
                assertThat(new Color(image.getRGB((int) box.x() + 36, (int) box.y() + 90)))
                        .isEqualTo(Color.decode("#17406F"));
                assertThat(new PDFTextStripper().getText(document))
                        .contains("Original capability title", "12.5%", "CUSTOMER-EVIDENCE-END");
                assertThat(document.getDocumentInformation().getSubject()).isEqualTo("Client-provided working view");
            }
            assertThat(scene.nodes().getFirst().label()).isEqualTo("Original capability title");
            assertThat(reasons).containsExactlyEntriesOf(Map.of("fixture-capability", "Original evidence. CUSTOMER-EVIDENCE-END"));
        }
    }

    @Test
    void nextExportReadsUpdatedFileAndUnreadableFileFallsBackToBundledAppearance() throws Exception {
        Path css = directory.resolve("customer.css");
        Files.writeString(css, ":root { --taxonomy-layer-cap-surface: #17406f; }");
        try (var context = context(css)) {
            var renderer = context.getBean(SvgDiagramRenderer.class);
            assertThat(renderer.render(scene())).contains("fill=\"#17406F\"");
            Files.writeString(css, ":root { --taxonomy-layer-cap-surface: #6f1234; }");
            assertThat(renderer.render(scene())).contains("fill=\"#6F1234\"").doesNotContain("fill=\"#17406F\"");
            Files.delete(css);
            assertThat(renderer.render(scene())).contains("fill=\"#DBEAFE\"").doesNotContain("fill=\"#6F1234\"");
        }
    }

    @Test
    void selfContainedHtmlUsesValidatedCustomerSurfacesAndRetainsOriginalLanguageAndEvidence() throws Exception {
        Path css = directory.resolve("customer.css");
        Files.writeString(css, """
                :root {
                  --taxonomy-surface-background: #f0f4f8;
                  --taxonomy-surface-foreground: #102030;
                  --taxonomy-navigation-background: #17406f;
                }
                body { display: none; }
                """);
        var report = new ArchitectureReport();
        report.setBusinessText("Unveränderte Anforderung. CUSTOMER-REQUIREMENT-END");
        try (var context = context(css)) {
            String html = context.getBean(ArchitectureReportTextRenderer.class).renderHtml(report, "de",
                    Map.of("fixture-capability", "Original evidence. CUSTOMER-EVIDENCE-END"));
            assertThat(html).contains("html lang=\"de\"", "CUSTOMER-REQUIREMENT-END", "CUSTOMER-EVIDENCE-END",
                    "background:#F0F4F8", "color:#102030", "#17406F");
            assertThat(html).doesNotContain("display: none", "/css/customer-theme.css");
        }
    }

    @ParameterizedTest
    @CsvSource({"#123456,#FFFFFF", "#eeeeee,#0f172a", "#777777,#000000"})
    void svgNodeTextRemainsReadableOnDarkLightAndMidtoneCustomerFills(String fill, String foreground) throws Exception {
        Path css = directory.resolve("customer.css");
        Files.writeString(css, ":root { --taxonomy-layer-cap-surface: " + fill + "; }");
        try (var context = context(css)) {
            var xml = parse(context.getBean(SvgDiagramRenderer.class).render(scene()));
            var node = (Element) xml.getElementsByTagNameNS("http://www.w3.org/2000/svg", "g").item(0);
            var label = (Element) node.getElementsByTagNameNS("http://www.w3.org/2000/svg", "text").item(1);
            assertThat(label.getAttribute("fill")).isEqualToIgnoringCase(foreground);
        }
    }

    @Test
    void unsupportedCssSemanticsAndInjectedStringsCannotChangeStandaloneSvg() throws Exception {
        Path css = directory.resolve("customer.css");
        Files.writeString(css, """
                /* :root { --taxonomy-layer-cap-surface: #123456; } */
                .example::before { content: ':root { --taxonomy-layer-cap-surface: #123456; }'; }
                @media print { :root { --taxonomy-layer-cap-surface: #123456; } }
                :root[data-bs-theme="dark"] { --taxonomy-layer-cap-surface: #123456; }
                :root { --taxonomy-layer-cap-surface: url(https://invalid.example/INJECTED-CONTENT); }
                """);
        try (var context = context(css)) {
            String svg = context.getBean(SvgDiagramRenderer.class).render(scene());
            assertThat(svg).contains("fill=\"#DBEAFE\"")
                    .doesNotContain("#123456", "invalid.example", "INJECTED-CONTENT");
        }
    }

    @Test
    @ResourceLock("java.util.Locale.default")
    void relationColorKeepsProposalDashesAndUsesLocaleIndependentTokensAndCoordinates() throws Exception {
        Path css = directory.resolve("customer.css");
        Files.writeString(css, """
                :root { --taxonomy-relation-fulfills: #17406f !important; }
                :root { --taxonomy-relation-fulfills: #123456; }
                """);
        var metadata = new DiagramRelationMetadata(42L, RelationOrigin.LLM_SUPPORTED, .125,
                "Recorded proposal rationale", null, null, null, List.of());
        var model = new DiagramModel("Authored relation fixture", List.of(
                new DiagramNode("fixture-source", "Original source", "Capabilities", .125, true, 1),
                new DiagramNode("fixture-target", "Original target", "Services", .5, false, 2)),
                List.of(new DiagramEdge("fixture-edge", "fixture-source", "fixture-target", "FULFILLS", .125,
                        "impact", metadata)), new DiagramLayout("LR", true));
        Locale previous = Locale.getDefault();
        try (var context = context(css)) {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            var xml = parse(context.getBean(SvgDiagramRenderer.class).render(new LayeredDiagramLayoutService().layout(model)));
            var line = (Element) xml.getElementsByTagNameNS("http://www.w3.org/2000/svg", "line").item(0);
            assertThat(line.getAttribute("stroke")).isEqualTo("#17406F");
            assertThat(line.getAttribute("stroke-dasharray")).isEqualTo("6 4");
            assertThat(line.getAttribute("x1")).contains(".").doesNotContain(",");
            assertThat(xml.getDocumentElement().getTextContent()).contains("AI-proposed", "Recorded proposal rationale", "12.5%");
            assertThat(model.edges().getFirst().metadata()).isEqualTo(metadata);
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void visioPackageContainsCustomerShapeAndConnectorStylesWithUnchangedEvidence() throws Exception {
        Path css = directory.resolve("customer.css");
        Files.writeString(css, """
                :root { --taxonomy-layer-cap-surface: #17406f; --taxonomy-relation-supports: #27834a; }
                """);
        var metadata = new DiagramRelationMetadata(42L, RelationOrigin.LLM_SUPPORTED, .125,
                "Original relationship evidence. VSDX-EVIDENCE-END", null, null, null, List.of());
        var model = new DiagramModel("Authored native style fixture", List.of(
                new DiagramNode("fixture-source", "Original source", "Capabilities", .125, true, 1),
                new DiagramNode("fixture-target", "Original target", "Services", .5, false, 2)),
                List.of(new DiagramEdge("fixture-edge", "fixture-source", "fixture-target", "SUPPORTS", .125,
                        "impact", metadata)), new DiagramLayout("LR", true));
        try (var context = context(css)) {
            var document = context.getBean(VisioDiagramService.class).convert(model);
            var builder = context.getBean(VisioPackageBuilder.class);
            byte[] first = builder.build(document);
            assertThat(builder.build(document)).isEqualTo(first);
            byte[] unstyled = new VisioPackageBuilder().build(new VisioDiagramService().convert(model));
            assertThat(zipPart(first, "taxonomy/manifest.json")).isEqualTo(zipPart(unstyled, "taxonomy/manifest.json"));
            String page = zipPart(first, "visio/pages/page1.xml");
            var source = shape(parse(page), "fixture-source");
            assertThat(cell(source, "FillForegnd")).isEqualTo("#17406F");
            assertThat(cell(source, "Color")).isEqualTo("#FFFFFF");
            var connector = shape(parse(page), "fixture-edge");
            assertThat(cell(connector, "LineColor")).isEqualTo("#27834A");
            assertThat(cell(connector, "LinePattern")).isEqualTo("2");
            assertThat(page).contains("VSDX-EVIDENCE-END", "LLM_SUPPORTED", "0.125");
            assertThat(page).doesNotContain("Dashed purple");
            assertThat(model.edges().getFirst().metadata()).isEqualTo(metadata);
        }
    }

    private static AnnotationConfigApplicationContext context(Path css) {
        var context = configuredContext(css);
        context.register(ArchitectureReportTextRenderer.class);
        context.refresh();
        return context;
    }

    private static AnnotationConfigApplicationContext configuredContext(Path css) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("customer",
                Map.of("taxonomy.ui.custom-stylesheet", css.toString())));
        // Preferences are irrelevant to rendering an already selected graph.
        context.registerBean(PreferencesService.class, () -> org.mockito.Mockito.mock(PreferencesService.class));
        context.register(CustomerStylesheetController.class, ExportConfig.class);
        return context;
    }

    private static DiagramScene scene() {
        return new LayeredDiagramLayoutService().layout(model());
    }

    private static DiagramModel model() {
        return new DiagramModel("Authored theme fixture", List.of(
                new DiagramNode("fixture-capability", "Original capability title", "Capabilities", .125, true, 1)),
                List.of(), new DiagramLayout("LR", true));
    }

    private static org.w3c.dom.Document parse(String svg) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(svg)));
    }

    private static String zipPart(byte[] bytes, String path) throws Exception {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals(path)) return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("Missing package part " + path);
    }

    private static Element shape(org.w3c.dom.Document document, String canonicalId) {
        var shapes = document.getElementsByTagNameNS("http://schemas.microsoft.com/office/visio/2012/main", "Shape");
        for (int index = 0; index < shapes.getLength(); index++) {
            var shape = (Element) shapes.item(index);
            var rows = shape.getElementsByTagNameNS("http://schemas.microsoft.com/office/visio/2012/main", "Row");
            for (int row = 0; row < rows.getLength(); row++) {
                var cells = ((Element) rows.item(row)).getElementsByTagNameNS("http://schemas.microsoft.com/office/visio/2012/main", "Cell");
                boolean identity = false, matches = false;
                for (int cell = 0; cell < cells.getLength(); cell++) {
                    var value = (Element) cells.item(cell);
                    identity |= value.getAttribute("N").equals("Label") && value.getAttribute("V").equals("taxonomy.id");
                    matches |= value.getAttribute("N").equals("Value") && value.getAttribute("V").equals(canonicalId);
                }
                if (identity && matches) return shape;
            }
        }
        throw new AssertionError("Missing canonical shape " + canonicalId);
    }

    private static String cell(Element shape, String name) {
        var cells = shape.getElementsByTagNameNS("http://schemas.microsoft.com/office/visio/2012/main", "Cell");
        for (int index = 0; index < cells.getLength(); index++) {
            var cell = (Element) cells.item(index);
            if (cell.getAttribute("N").equals(name)) return cell.getAttribute("V");
        }
        throw new AssertionError("Missing style cell " + name);
    }
}
