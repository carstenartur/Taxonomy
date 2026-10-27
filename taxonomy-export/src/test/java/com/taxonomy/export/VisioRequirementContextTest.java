package com.taxonomy.export;

import com.taxonomy.diagram.DiagramLayout;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
import com.taxonomy.visio.VisioDocument;
import com.taxonomy.visio.VisioExportMetadata;
import com.taxonomy.visio.VisioPage;
import com.taxonomy.visio.VisioProperty;
import com.taxonomy.visio.VisioShape;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VisioRequirementContextTest {

    @Test
    void overviewContainsEditableSourceAndCreationContext() throws Exception {
        var document = convert(metadata("The application records working time and explains corrections."));
        var overview = document.getPages().getFirst();
        var context = overview.getShapes().stream().filter(VisioRequirementContextTest::contextBlock).toList();
        assertThat(context).hasSize(1);
        assertThat(context.getFirst().getText()).contains("REQ-42", "Working time", "Alice Author",
                "2026-10-20T08:00:00Z", "2026-10-21T09:00:00Z", "Bob Exporter",
                "2026-10-27T10:00:00Z", "Taxonomy Architecture Analyzer", "1.5.3-test",
                "The application records working time", "workspace-a", "reviewed");
        assertThat(context.getFirst().getProperties()).containsEntry("taxonomy.requirementUrl",
                VisioProperty.text("https://taxonomy.example/taxonomy/projects/7/requirements/42"));
        assertThat(overview.getShapes().stream().filter(s -> s.getProperties().containsKey("taxonomy.id")))
                .hasSize(1);
        assertThat(document.getPages().stream().filter(VisioRequirementContextTest::requirementPage)).hasSize(1);
        assertThat(new VisioPackageBuilder().build(document)).isNotEmpty();
    }

    @Test
    void completeOriginalTextContinuesAcrossNativePagesWithoutLosingWords() throws Exception {
        String source = IntStream.range(0, 400).mapToObj(i ->
                "Absatz " + i + ": Überstunden, 日本語 und Korrekturen müssen nachvollziehbar bleiben.")
                .collect(Collectors.joining("\n\n")) + "\nORIGINAL-REQUIREMENT-END";
        var document = convert(metadata(source));
        var pages = document.getPages().stream().filter(VisioRequirementContextTest::requirementPage).toList();
        assertThat(pages.size()).isGreaterThan(1);
        assertThat(pages).allSatisfy(p -> assertThat(p.isEvidencePage()).isTrue());
        String visible = pages.stream().flatMap(p -> p.getShapes().stream())
                .filter(s -> s.getProperties().containsKey("taxonomy.requirementContent"))
                .map(VisioShape::getText).collect(Collectors.joining("\n"));
        assertThat(List.of(visible.split("\\s+"))).containsSubsequence(List.of(source.split("\\s+")));
        assertThat(visible).contains("ORIGINAL-REQUIREMENT-END").doesNotContain("…");
        assertThat(document.getProperties().get("taxonomy.requirementText").value()).isEqualTo(source);
        assertThat(new VisioPackageBuilder().build(document)).isNotEmpty();
    }

    @Test
    void unavailableHistoricalMetadataDoesNotBorrowExportersIdentityOrDate() {
        var values = metadata("An older requirement version.");
        values.remove("taxonomy.requirementVersionCreatedBy");
        values.remove("taxonomy.requirementVersionCreatedAt");
        values.remove("taxonomy.requirementUrl");
        values.put("taxonomy.requirementUrlStatus", VisioProperty.text("Public base URL is not configured."));
        var document = convert(values);
        var blocks = document.getPages().getFirst().getShapes().stream()
                .filter(VisioRequirementContextTest::contextBlock).toList();
        assertThat(blocks).hasSize(1);
        var block = blocks.getFirst();
        assertThat(block.getText()).contains("Not recorded", "Bob Exporter", "Public base URL is not configured.");
        assertThat(block.getProperties()).doesNotContainKey("taxonomy.requirementUrl");
        assertThat(block.getText()).doesNotContain("Version author: Bob Exporter");
    }

    @Test
    void originalTextWithMarkupAndFormulaLookingContentRemainsLiteral() throws Exception {
        String source = "<script>alert('source')</script> & =HYPERLINK(\"https://example.invalid\",\"Text\")";
        var document = convert(metadata(source));
        String visible = document.getPages().stream().filter(VisioRequirementContextTest::requirementPage)
                .flatMap(p -> p.getShapes().stream()).filter(s -> s.getProperties().containsKey("taxonomy.requirementContent"))
                .map(VisioShape::getText).collect(Collectors.joining("\n"));
        assertThat(visible).contains("<script>alert('source')</script>", "=HYPERLINK");
        String page = VisioPackageBuilderTest.readEntry(new VisioPackageBuilder().build(document), "visio/pages/page2.xml");
        assertThat(page)
                .doesNotContain("F=\"=HYPERLINK", "<script>")
                .contains("&lt;script&gt;");
    }

    @Test
    void excessiveParagraphCountFailsExplicitlyInsteadOfDiscardingOriginalText() {
        var values = metadata("X\n".repeat(16000));
        assertThatThrownBy(() -> convert(values)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requirement").hasMessageContaining("capacity");
    }

    @Test
    void oversizedSourceIsRejectedBeforePresentationPagination() {
        assertThatThrownBy(() -> convert(metadata("word ".repeat(6554))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("32767");
    }

    @Test
    void sourceLinkStillResolvesWhenDiagramTitleMatchesARequirementPageName() throws Exception {
        var document = convert(metadata("The complete original source."), "Requirement 1");
        var sourcePage = document.getPages().stream().filter(VisioRequirementContextTest::requirementPage)
                .findFirst().orElseThrow();
        assertThat(document.getPages().stream().map(VisioPage::getName)).doesNotHaveDuplicates();
        var context = document.getPages().getFirst().getShapes().stream()
                .filter(VisioRequirementContextTest::contextBlock).findFirst().orElseThrow();
        assertThat(context.getProperties().get("taxonomy.evidencePage").value()).isEqualTo(sourcePage.getName());
        String page = VisioPackageBuilderTest.readEntry(new VisioPackageBuilder().build(document), "visio/pages/page1.xml");
        assertThat(page).contains("N=\"SubAddress\" V=\"" + sourcePage.getName() + "\"");
    }

    private static boolean contextBlock(VisioShape shape) {
        return shape.getProperties().containsKey("taxonomy.contextBlock");
    }

    private static boolean requirementPage(VisioPage page) {
        return page.getShapes().stream().anyMatch(shape ->
                shape.getProperties().containsKey("taxonomy.requirementContent"));
    }

    private static VisioDocument convert(Map<String, VisioProperty> properties) {
        return convert(properties, "Requirement architecture");
    }

    private static VisioDocument convert(Map<String, VisioProperty> properties, String title) {
        var graph = new DiagramModel(title, List.of(
                new DiagramNode("fixture-node", "Application", "UserApplication", .8, false, 0, 1,
                        false, null, false)), List.of(), new DiagramLayout("LR", false));
        return new VisioDiagramService().convert(graph,
                new VisioExportMetadata(properties, Map.of(), Map.of(), List.of()));
    }

    private static Map<String, VisioProperty> metadata(String requirement) {
        var values = new LinkedHashMap<String, VisioProperty>();
        Map.ofEntries(
                Map.entry("requirementText", requirement), Map.entry("requirementKey", "REQ-42"),
                Map.entry("requirementTitle", "Working time"), Map.entry("projectKey", "P7"),
                Map.entry("requirementVersionId", "15"), Map.entry("requirementVersionCreatedBy", "Alice Author"),
                Map.entry("requirementVersionCreatedAt", "2026-10-20T08:00:00Z"),
                Map.entry("snapshotCreatedAt", "2026-10-21T09:00:00Z"), Map.entry("snapshotId", "snapshot-42"),
                Map.entry("exportedBy", "Bob Exporter"), Map.entry("exportedAt", "2026-10-27T10:00:00Z"),
                Map.entry("generatorName", "Taxonomy Architecture Analyzer"), Map.entry("generatorVersion", "1.5.3-test"),
                Map.entry("workspaceId", "workspace-a"), Map.entry("branch", "reviewed"),
                Map.entry("requirementUrl", "https://taxonomy.example/taxonomy/projects/7/requirements/42"))
                .forEach((key, value) -> values.put("taxonomy." + key, VisioProperty.text(value)));
        return values;
    }
}
