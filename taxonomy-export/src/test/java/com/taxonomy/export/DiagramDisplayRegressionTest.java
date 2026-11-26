package com.taxonomy.export;

import com.taxonomy.diagram.DiagramCanonicalEvidence;
import com.taxonomy.diagram.DiagramScene;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DiagramDisplayRegressionTest {
    @Test void standaloneSnapshotAndCanonicalExportsKeepVisibleContextAndUnmodifiedGeometry() throws Exception {
        var graph = new com.taxonomy.diagram.DiagramModel("archview.policy.title.defaultImpact", List.of(
                new com.taxonomy.diagram.DiagramNode("CP-1122", "IT Services Capabilities", "Capabilities", .7, true, 2),
                new com.taxonomy.diagram.DiagramNode("CI-1151", "Education and Training Services", "COI Services", .85, false, 3)),
                List.of(new com.taxonomy.diagram.DiagramEdge("fixture-relation", "CP-1122", "CI-1151", "SUPPORTS", .6, "impact")),
                new com.taxonomy.diagram.DiagramLayout("LR", true));
        var scene = new LayeredDiagramLayoutService().layout(graph);
        String summary = "RELATION_SEARCH_PARTIAL: 24/24 evaluation attempts; 231 unfinished search batches. 42 unassessed sources; 298 pending and 2 unresolved source/target searches.";
        String question = "CI-1151: Which training records must remain available after completion?";
        var metadata = new ArchitecturePdfRenderer.SnapshotMetadata("QA-REPORT-FIXTURE", "REQ-RENDER-FIXTURE", "fixture-snapshot",
                "MOCK", "fixture-branch", "a".repeat(40), List.of(summary, question), "PARTIAL");
        var canonical = new DiagramCanonicalEvidence("fixture-view", List.of(
                new DiagramCanonicalEvidence.AnalysisSource("fixture-view", "fixture-snapshot", "PARTIAL")), Map.of());
        String provenance = "fixture-repository / fixture-workspace / fixture-branch\nCheckpoint " + "b".repeat(40);
        var output = java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/qa-report-display"));
        for (var locale : List.of(Locale.ENGLISH, Locale.GERMAN)) {
            String tag = locale.getLanguage();
            var context = DiagramExportContext.snapshot(metadata, locale);
            String svg = new SvgDiagramRenderer().render(scene, context, Map.of("CI-1151", "Original stored evidence remains unchanged."), Map.of(), locale);
            String text = visible(svg);
            assertThat(text).contains("fixture-snapshot", "MOCK", "fixture-branch", "a".repeat(40), "PARTIAL", question,
                    "24/24", "231", "42", "298", "Original stored evidence remains unchanged.");
            assertThat(svg).contains("class=\"architecture-graph\" transform=\"translate(0,");
            for (var node : scene.nodes()) assertThat(svg).contains(String.format(Locale.ROOT,
                    "data-node-id=\"%s\" transform=\"translate(%.2f,%.2f)\"", node.id(), node.x(), node.y()));
            String canonicalSvg = new SvgDiagramRenderer().renderCanonical(scene, provenance, canonical, locale);
            assertThat(visible(canonicalSvg)).contains("fixture-repository / fixture-workspace / fixture-branch", "b".repeat(40), "fixture-view", "PARTIAL");
            java.nio.file.Files.writeString(output.resolve("snapshot-" + tag + ".svg"), svg, java.nio.charset.StandardCharsets.UTF_8);
            java.nio.file.Files.writeString(output.resolve("canonical-" + tag + ".svg"), canonicalSvg, java.nio.charset.StandardCharsets.UTF_8);
            java.nio.file.Files.write(output.resolve("snapshot-" + tag + ".pdf"), new ArchitecturePdfRenderer().render(scene, metadata, Map.of(), Map.of(), locale));
            java.nio.file.Files.write(output.resolve("canonical-" + tag + ".pdf"), new ArchitecturePdfRenderer().renderCanonical(scene, provenance, canonical, locale));
        }
        assertThat(scene.title()).isEqualTo("archview.policy.title.defaultImpact");
        assertThat(metadata.warnings()).containsExactly(summary, question);
    }

    @Test void exactDiagnosticsKeepUnknownMessagesAndOriginalSuffixes() {
        String family = "IP-1038 · The product family is relevant, but none of its 8 catalogued products reached the suitability threshold of 50.";
        assertThat(GeneratedDiagnosticText.display(family + " Original suffix: $5 and \\quotes.", true))
                .isEqualTo("IP-1038 · Die Produktfamilie ist relevant, aber keines ihrer 8 katalogisierten Produkte erreichte die Eignungsschwelle von 50. Original suffix: $5 and \\quotes.");
        assertThat(GeneratedDiagnosticText.display("Human note: " + family, true)).isEqualTo("Human note: " + family);
        assertThat(GeneratedDiagnosticText.display(family, false)).isEqualTo(family);
        assertThat(GeneratedDiagnosticText.display("ROOT_SOURCE_UNRESOLVED BR: Original custom explanation.", true))
                .isEqualTo("ROOT_SOURCE_UNRESOLVED BR: Original custom explanation.");
    }

    @Test void resolvesOnlyKnownCanonicalTitleAliasesInVisibleSvgAndPdfMetadata() throws Exception {
        var titles = Map.of("defaultImpact", List.of("Architecture Impact View", "Architektur-Wirkungsansicht"),
                "leafOnly", List.of("Leaf-Only Showcase View", "Nur-Blatt-Präsentationsansicht"),
                "clustering", List.of("Clustered Impact View", "Gruppierte Wirkungsansicht"),
                "trace", List.of("Scoring Trace", "Scoring-Nachverfolgung"));
        var original = WorkingViewPdfRendererTest.scene(2);
        var evidence = new DiagramCanonicalEvidence("saved-view", List.of(), Map.of());
        for (var entry : titles.entrySet()) for (var locale : List.of(Locale.ENGLISH, Locale.GERMAN)) {
            String alias = "archview.policy.title." + entry.getKey();
            var scene = new DiagramScene(alias, original.width(), original.height(), original.direction(), original.nodes(), original.edges());
            String expected = entry.getValue().get(locale.equals(Locale.GERMAN) ? 1 : 0);
            String svg = new SvgDiagramRenderer().renderCanonical(scene, evidence, locale);
            assertThat(visible(svg)).contains(expected).doesNotContain(alias);
            try (var pdf = Loader.loadPDF(new ArchitecturePdfRenderer().renderCanonical(scene, "saved-repository / saved-workspace / saved-branch", evidence, locale))) {
                assertThat(pdf.getDocumentInformation().getTitle()).isEqualTo(expected);
                assertThat(new PDFTextStripper().getText(pdf)).contains(expected).doesNotContain(alias);
            }
            assertThat(scene.title()).isEqualTo(alias);
        }
        for (String title : List.of("Authored archview.policy.title.trace title", "archview.policy.title.future")) {
            var scene = new DiagramScene(title, original.width(), original.height(), original.direction(), original.nodes(), original.edges());
            assertThat(new SvgDiagramRenderer().renderCanonical(scene, evidence, Locale.GERMAN)).contains(title);
        }
    }

    @Test void localizesRecordedSystemDiagnosticsWithoutRewritingOriginalEvidence() throws Exception {
        String diagnostic = "ROOT_SOURCE_UNRESOLVED BR: identify a concrete required contribution below this catalogue container.";
        String partial = "RELATION_SEARCH_PARTIAL: 24/24 evaluation attempts; 231 unfinished search batches. 42 unassessed sources; 298 pending and 2 unresolved source/target searches. Unmodified suffix.";
        var metadata = new ArchitecturePdfRenderer.SnapshotMetadata("project", "requirement", "snapshot", "MOCK", "branch", "commit", List.of(diagnostic, partial, "CI-1151: Original open question?"), "PARTIAL");
        try (var pdf = Loader.loadPDF(new ArchitecturePdfRenderer().render(WorkingViewPdfRendererTest.scene(2), metadata,
                Map.of("NODE-0", diagnostic), Map.of(), Locale.GERMAN))) {
            String text = new PDFTextStripper().getText(pdf).replaceAll("\\s+", " ");
            assertThat(text).contains("konkreten erforderlichen Beitrag", "24/24 Bewertungsversuche", "42 unbewertete Quellen",
                    "298 ausstehende und 2 ungeklärte", "Unmodified suffix.", "CI-1151: Original open question?", diagnostic);
        }
        assertThat(metadata.warnings()).containsExactly(diagnostic, partial, "CI-1151: Original open question?");
    }

    static String visible(String svg) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(svg)));
        var texts = document.getElementsByTagName("text");
        var result = new StringBuilder();
        for (int index = 0; index < texts.getLength(); index++) result.append(texts.item(index).getTextContent()).append('\n');
        return result.toString();
    }
}
