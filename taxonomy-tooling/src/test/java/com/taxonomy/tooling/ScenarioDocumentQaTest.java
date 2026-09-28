package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioDocumentQaTest {
    @Test
    void reformulationExportsHaveMeasuredBudgetsWithNoEmptyBodyAllowance() throws Exception {
        assertThat(ScenarioDocumentQa.checkText("reformulation-revision.docx", xml(page("Source").repeat(25)), "", List.of("Source"), true))
                .containsEntry("pages", 25);
        assertThat(ScenarioDocumentQa.checkText("reformulation-adoption.docx", xml(page("Source").repeat(26)), "", List.of("Source"), true))
                .containsEntry("pages", 26);
        assertThatThrownBy(() -> ScenarioDocumentQa.checkText("reformulation-revision.docx", xml(page("Source").repeat(29)), "", List.of("Source"), true))
                .hasMessageContaining("limit 28");
        assertThatThrownBy(() -> ScenarioDocumentQa.checkText("reformulation-adoption.docx", xml(page("Source").repeat(30)), "", List.of("Source"), true))
                .hasMessageContaining("limit 29");
    }
    @Test
    void joinsContinuedParagraphsWithoutRunningFurnitureAndKeepsUnicode() throws Exception {
        String bbox = xml(page("Öffentliche Warnungen") + page("für Bürger"));
        assertThat(ScenarioDocumentQa.checkText("decision.docx", bbox, "unused",
                List.of("Öffentliche Warnungen für Bürger"), true))
                .containsEntry("pages", 2).containsEntry("contentAssertions", 1);
        assertThatThrownBy(() -> ScenarioDocumentQa.checkText("decision.docx", bbox, "",
                List.of("Öffentliche Warnungen für Bürger und Abonnenten"), true))
                .hasMessageContaining("missing text");
    }

    @Test
    void rejectsHeaderOnlyPagesAndPaginationRegression() throws Exception {
        assertThatThrownBy(() -> ScenarioDocumentQa.checkText("decision.docx", xml(page("")), "",
                List.of("Running header"), true)).hasMessageContaining("empty page bodies [1]");
        assertThatThrownBy(() -> ScenarioDocumentQa.checkText("decision.docx", xml(page("Text").repeat(75)), "",
                List.of("Text"), true)).hasMessageContaining("grew to 75 pages");
    }

    @Test
    void completeWordFixturesHaveSeparateMeasuredPaginationBudgets() throws Exception {
        assertThat(ScenarioDocumentQa.checkText("decision.docx",xml(page("Text").repeat(71)),"",List.of("Text"),true)).containsEntry("pages",71);
        assertThat(ScenarioDocumentQa.checkText("report.docx",xml(page("Text").repeat(10)),"",List.of("Text"),true)).containsEntry("pages",10);
        assertThatThrownBy(() -> ScenarioDocumentQa.checkText("report.docx",xml(page("Text").repeat(13)),"",List.of("Text"),true)).hasMessageContaining("limit 12");
    }

    @Test
    void rejectsRationaleLabelsSeparatedFromTheirExplanation() {
        assertThatThrownBy(() -> ScenarioDocumentQa.checkText("decision.docx",
                xml(page("Other text Comparative rationale") + page("Explanation")), "",
                List.of("Explanation"), true)).hasMessageContaining("orphan rationale heading on page 1");
    }

    @Test
    void checksDrawLabelsInReadingOrderAndRejectsForeignDoctype() throws Exception {
        assertThat(ScenarioDocumentQa.checkText("architecture.vsdx", xml(page("mixed label order")),
                "Public warning service", List.of("Public warning service"), false))
                .containsEntry("pages", 1);
        assertThatThrownBy(() -> ScenarioDocumentQa.checkText("decision.docx",
                "<!DOCTYPE html [<!ENTITY secret SYSTEM 'file:///does-not-exist'>]>" + xml(page("Text")),
                "", List.of("Text"), true)).isInstanceOf(java.io.IOException.class);
    }

    @Test
    void wordSourceChecksRejectMixedSnapshotsAndMissingGraphEvidence() throws Exception {
        var source=java.util.Map.<String,Object>of("snapshotId","snapshot-1","diagram",java.util.Map.of(
                "nodes",List.of(java.util.Map.of("id","A"),java.util.Map.of("id","B")),
                "edges",List.of(java.util.Map.of("id","R1"))));
        String body="<w:document xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'><w:body><w:p><w:r><w:t>A B R1</w:t></w:r></w:p></w:body></w:document>";
        String props="<Properties><property name='taxonomy.snapshot.id'><lpwstr>snapshot-1</lpwstr></property><property name='taxonomy.graph.sha256'><lpwstr>"+"a".repeat(64)+"</lpwstr></property></Properties>";
        var parts=java.util.Map.of("word/document.xml",body.getBytes(java.nio.charset.StandardCharsets.UTF_8),"docProps/custom.xml",props.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(ScenarioDocumentQa.checkFrozenWordSource("report.docx",parts,source,null)).isEqualTo("a".repeat(64));
        assertThatThrownBy(()->ScenarioDocumentQa.checkFrozenWordSource("decision.docx",parts,source,"b".repeat(64))).hasMessageContaining("graph hash");
        var incomplete=new java.util.HashMap<>(parts);incomplete.put("word/document.xml",body.replace("R1","").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(()->ScenarioDocumentQa.checkFrozenWordSource("report.docx",incomplete,source,null)).hasMessageContaining("R1");
    }


    @Test
    void nativeVisioRelationshipCoverageRequiresRenderedKeyDirectionAndTypeOnTheSamePage() throws Exception {
        var source = java.util.Map.<String,Object>of("diagram", java.util.Map.of("nodes", List.of(java.util.Map.of("id", "a"), java.util.Map.of("id", "b")), "edges", List.of(
                java.util.Map.of("id", "edge-α", "sourceId", "a", "targetId", "b", "relationType", "SUPPORTS"))));
        String shape = "<Shape><Section N='Property'>";
        for (var pair : java.util.Map.of("taxonomy.id", "edge-α", "taxonomy.sourceId", "a", "taxonomy.targetId", "b",
                "taxonomy.type", "SUPPORTS", "taxonomy.displayKey", "R1", "taxonomy.captionDisposition", "READABLE_DETAIL").entrySet())
            shape += "<Row><Cell N='Label' V='" + pair.getKey() + "'/><Cell N='Value' V='" + pair.getValue() + "'/></Row>";
        shape += "</Section><Text>R1 N1 → N2\nSUPPORTS</Text></Shape>";
        var parts = java.util.Map.of("visio/pages/page1.xml", ("<PageContents><Shapes>" + shape + "</Shapes></PageContents>").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(ScenarioDocumentQa.checkVisioRelationships(parts, source, "R1 N1 → N2 SUPPORTS\f")).containsEntry("renderedRelationships", 1);
        assertThatThrownBy(() -> ScenarioDocumentQa.checkVisioRelationships(parts, source, "R1 N1 → N2\fSUPPORTS"))
                .hasMessageContaining("rendered relationship");
        assertThatThrownBy(() -> ScenarioDocumentQa.checkVisioRelationships(parts, source, "R1 N2 → N1 SUPPORTS"))
                .hasMessageContaining("rendered relationship");
        assertThatThrownBy(() -> ScenarioDocumentQa.checkVisioRelationships(parts, source, "R1 N1 ← N2 SUPPORTS"))
                .hasMessageContaining("rendered relationship");
        var wrongCaption = java.util.Map.of("visio/pages/page1.xml", ("<PageContents><Shapes>" + shape.replace("N1 → N2", "N2 → N1") + "</Shapes></PageContents>").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> ScenarioDocumentQa.checkVisioRelationships(wrongCaption, source, "R1 N2 → N1 SUPPORTS"))
                .hasMessageContaining("caption semantics");
        var missing = java.util.Map.of("visio/pages/page1.xml", "<PageContents/>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> ScenarioDocumentQa.checkVisioRelationships(missing, source, "R1 N1 N2 SUPPORTS"))
                .hasMessageContaining("canonical relationship");
    }

    @Test
    void cliVisioOnlyFlagKeepsTheArtifactArgumentAndDefaultsToAllDocuments() {
        var selected = TaxonomyTooling.Arguments.parse(new String[]{"--visio-only", "--artifacts", "actual"});
        assertThat(selected.flag("visio-only")).isTrue();
        assertThat(selected.required("artifacts")).isEqualTo("actual");
        assertThat(TaxonomyTooling.Arguments.parse(new String[]{"--artifacts", "actual"}).flag("visio-only")).isFalse();
    }

    @Test
    void cliReformulationOnlyFlagKeepsTheArtifactArgument() {
        var selected = TaxonomyTooling.Arguments.parse(new String[]{"--reformulation-only", "--artifacts", "actual"});
        assertThat(selected.flag("reformulation-only")).isTrue();
        assertThat(selected.required("artifacts")).isEqualTo("actual");
        assertThat(TaxonomyTooling.Arguments.parse(new String[]{"--artifacts", "actual"}).flag("reformulation-only")).isFalse();
    }

    @Test
    void failedRerenderCannotLeaveAnEarlierSuccessManifest(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var manifest = root.resolve("reformulation-document-quality.json");
        java.nio.file.Files.writeString(manifest, "previous success");
        assertThatThrownBy(() -> ScenarioDocumentQa.inspectReformulations(root, "unused-renderer"))
                .hasMessageContaining("No completed reformulation lifecycle artifacts");
        assertThat(java.nio.file.Files.exists(manifest)).isFalse();
    }

    @Test
    void rerenderRemovesOnlyItsOwnGeneratedEvidence(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var generated = List.of("revision.pdf", "revision.txt", "revision.bbox.html", "revision-01.png", "revision-99.png");
        var preserved = List.of("adoption.pdf", "adoption-01.png", "revision-notes.png", "revision.docx", "revision.json");
        for (String name : java.util.stream.Stream.concat(generated.stream(), preserved.stream()).toList()) {
            java.nio.file.Files.writeString(root.resolve(name), name);
        }
        ScenarioDocumentQa.clearReformulationRender(root, "revision");
        for (String name : generated) assertThat(java.nio.file.Files.exists(root.resolve(name))).as(name).isFalse();
        for (String name : preserved) assertThat(java.nio.file.Files.readString(root.resolve(name))).isEqualTo(name);
    }

    private static String xml(String pages) {
        return "<html xmlns='http://www.w3.org/1999/xhtml'><body><doc>" + pages + "</doc></body></html>";
    }

    private static String page(String body) {
        return "<page width='595' height='842'><word yMin='24' yMax='35'>Running header</word>"
                + "<word yMin='51' yMax='62'>" + body + "</word>"
                + "<word yMin='796' yMax='808'>Page footer</word></page>";
    }
}
