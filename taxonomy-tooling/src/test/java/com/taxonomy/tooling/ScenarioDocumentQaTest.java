package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioDocumentQaTest {
    @Test
    void completeCapturedFloodFixtureSelectsItsReviewedDecisionPageBaseline() throws Exception {
        var run = completeFixture("run");
        var decision = completeFixture("decision");
        var architecture = completeFixture("architecture");

        assertThat(ScenarioDocumentQa.pageProfile(run, decision, architecture).decisionPages()).isEqualTo(168);
    }

    @Test
    void completeProfileRetainsAnExplicitUpperBoundAndAllRenderedContentGates() throws Exception {
        var profile = completeProfile();
        assertThat(ScenarioDocumentQa.checkText("decision.docx", xml(page("Source").repeat(168)), "",
                List.of("Source"), true, profile)).containsEntry("pages", 168);
        assertThatThrownBy(() -> ScenarioDocumentQa.checkText("decision.docx", xml(page("Source").repeat(169)), "",
                List.of("Source"), true, profile)).hasMessageContaining("limit 168");
        assertThatThrownBy(() -> ScenarioDocumentQa.checkText("decision.docx", xml(page("")), "",
                List.of("Running header"), true, profile)).hasMessageContaining("empty page bodies [1]");
        assertThatThrownBy(() -> ScenarioDocumentQa.checkText("decision.docx", xml(page("Source")), "",
                List.of("A missing source sentence"), true, profile)).hasMessageContaining("missing text");
        assertThatThrownBy(() -> ScenarioDocumentQa.checkText("decision.docx",
                xml(page("Source Decision result") + page("Explanation")), "", List.of("Source"), true, profile))
                .hasMessageContaining("orphan rationale heading on page 1");
        assertThatThrownBy(() -> ScenarioDocumentQa.checkText("decision.docx",
                "<!DOCTYPE html [<!ENTITY secret SYSTEM 'file:///does-not-exist'>]>" + xml(page("Source")), "",
                List.of("Source"), true, profile)).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> ScenarioDocumentQa.checkText("reformulation-revision.docx", xml(page("Source").repeat(29)), "",
                List.of("Source"), true, profile)).hasMessageContaining("limit 28");
        assertThatThrownBy(() -> ScenarioDocumentQa.checkText("reformulation-adoption.docx", xml(page("Source").repeat(30)), "",
                List.of("Source"), true, profile)).hasMessageContaining("limit 29");
    }

    @Test
    void completeCapturedArchitectureReportRetainsItsMeasuredStandaloneBound() throws Exception {
        var profile = completeProfile();
        assertThat(profile.architecturePages()).isEqualTo(54);
        assertThat(ScenarioDocumentQa.checkText("report.docx", xml(page("Source").repeat(54)), "",
                List.of("Source"), true, profile)).containsEntry("pages", 54);
        assertThatThrownBy(() -> ScenarioDocumentQa.checkText("report.docx", xml(page("Source").repeat(55)), "",
                List.of("Source"), true, profile)).hasMessageContaining("limit 54");
    }

    @Test
    void absentOrUnreviewedRunIdentityCannotRaiseLegacyPageLimits() throws Exception {
        var decision = completeFixture("decision");
        var architecture = completeFixture("architecture");
        var wrongScenario = completeFixture("run");
        wrongScenario.put("scenario", "another-scenario");
        var unknownFixture = completeFixture("run");
        unknownFixture.put("fixtureSha256", "0".repeat(64));
        for (var run : List.of(Map.of(), wrongScenario, unknownFixture)) {
            var profile = ScenarioDocumentQa.pageProfile(run, decision, architecture);
            assertThat(profile.decisionPages()).isEqualTo(74);
            assertThat(profile.architecturePages()).isEqualTo(12);
            assertThatThrownBy(() -> ScenarioDocumentQa.checkText("decision.docx", xml(page("Source").repeat(75)), "",
                    List.of("Source"), true, profile)).hasMessageContaining("limit 74");
            assertThatThrownBy(() -> ScenarioDocumentQa.checkText("report.docx", xml(page("Source").repeat(13)), "",
                    List.of("Source"), true, profile)).hasMessageContaining("limit 12");
        }
    }

    @Test
    void reviewedRunWithChangedRequirementCannotBorrowTheCompleteProfile() throws Exception {
        var run = completeFixture("run");
        for (String changed : List.of("decision", "architecture")) {
            var decision = completeFixture("decision");
            var architecture = completeFixture("architecture");
            if (changed.equals("decision")) decision.put("requirement", "An unrelated requirement");
            else architecture.put("requirementText", "An unrelated requirement");
            assertThatThrownBy(() -> ScenarioDocumentQa.pageProfile(run, decision, architecture))
                    .as(changed).hasMessageContaining("corpus mismatch: requirement");
        }
    }

    @Test
    void reviewedRunWithChangedCorpusInventoryFailsClosedInsteadOfUsingHigherLimits() throws Exception {
        var run = completeFixture("run");
        for (String changed : List.of("nodes", "edges", "scopes", "chapters", "rows", "reasons")) {
            var decision = completeFixture("decision");
            var architecture = completeFixture("architecture");
            var diagram = mutableObject(architecture.get("diagram"));
            var scope = mutableObject(decision.get("scope"));
            switch (changed) {
                case "nodes", "edges" -> mutableArray(diagram.get(changed)).removeFirst();
                case "scopes" -> {
                    var edge = mutableObject(mutableArray(diagram.get("edges")).getFirst());
                    var metadata = mutableObject(edge.get("metadata"));
                    mutableArray(metadata.get("requirementEvidence")).removeFirst();
                }
                case "chapters" -> mutableArray(decision.get("chapters")).removeFirst();
                case "rows" -> mutableArray(mutableObject(scope.get("decisionTree")).get("rows")).removeFirst();
                case "reasons" -> {
                    var reasons = mutableObject(scope.get("recordedReasons"));
                    reasons.remove(reasons.keySet().iterator().next());
                }
                default -> throw new AssertionError(changed);
            }
            assertThatThrownBy(() -> ScenarioDocumentQa.pageProfile(run, decision, architecture))
                    .as(changed).hasMessageContaining("corpus mismatch: graph, scoped evidence or decision inventory");
        }
    }

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
    void nativeVisioCaptionsRequireTheExactRecordedProvenanceOnTheSamePage() throws Exception {
        var cases = new java.util.LinkedHashMap<java.util.Map<String, Object>, String>();
        cases.put(java.util.Map.of(), "Origin unrecorded");
        var nullOrigin = new java.util.HashMap<String, Object>();
        nullOrigin.put("origin", null);
        cases.put(nullOrigin, "Origin unrecorded");
        cases.put(java.util.Map.of("origin", "LLM_SUPPORTED"), "AI-proposed");
        cases.put(java.util.Map.of("origin", "SUGGESTED_CANDIDATE"), "AI-proposed");
        cases.put(java.util.Map.of("origin", "TAXONOMY_SEED"), "Catalogue seed");
        cases.put(java.util.Map.of("origin", "PROPAGATED_TRACE"), "Propagated trace");
        cases.put(java.util.Map.of("origin", "IMPACT_DERIVED"), "Derived impact");
        cases.put(java.util.Map.of("origin", "IMPACT_DERIVED", "requirementEvidence", List.of(java.util.Map.of("sourceId", "a"))), "AI-proposed");
        cases.put(java.util.Map.of("includedBecause", "provisional (AI-suggested, not yet confirmed)"), "AI-proposed");
        for (var entry : cases.entrySet()) {
            var edge = new java.util.LinkedHashMap<String, Object>(java.util.Map.of(
                    "id", "e1", "sourceId", "a", "targetId", "b", "relationType", "SUPPORTS"));
            edge.put("metadata", entry.getKey());
            var source = java.util.Map.<String, Object>of("diagram", java.util.Map.of("nodes", List.of(
                    java.util.Map.of("id", "a"), java.util.Map.of("id", "b")), "edges", List.of(edge)));
            String caption = "R1 N1 → N2\nSUPPORTS\n" + entry.getValue();
            String shape = "<Shape><Section N='Property'>";
            for (var pair : java.util.Map.of("taxonomy.id", "e1", "taxonomy.sourceId", "a", "taxonomy.targetId", "b",
                    "taxonomy.type", "SUPPORTS", "taxonomy.displayKey", "R1", "taxonomy.captionDisposition", "READABLE_DETAIL",
                    "taxonomy.provenanceStatus", entry.getValue()).entrySet())
                shape += "<Row><Cell N='Label' V='" + pair.getKey() + "'/><Cell N='Value' V='" + pair.getValue() + "'/></Row>";
            String xml = "<PageContents><Shapes>" + shape + "</Section><Text>" + caption + "</Text></Shape></Shapes></PageContents>";
            var parts = java.util.Map.of("visio/pages/page1.xml", xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThat(ScenarioDocumentQa.checkVisioRelationships(parts, source, caption)).containsEntry("renderedRelationships", 1);
            for (String wrong : List.of(caption.replace("N1 → N2", "N2 → N1"), caption.replace("SUPPORTS", "REALIZES"),
                    caption.replace(entry.getValue(), "Human approved"), caption.replace("\n" + entry.getValue(), ""))) {
                var wrongParts = java.util.Map.of("visio/pages/page1.xml", xml.replace(caption, wrong).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                assertThatThrownBy(() -> ScenarioDocumentQa.checkVisioRelationships(wrongParts, source, wrong))
                        .hasMessageContaining("caption semantics");
            }
            var falseProperty = java.util.Map.of("visio/pages/page1.xml", xml.replace("V='" + entry.getValue() + "'", "V='Human approved'").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThatThrownBy(() -> ScenarioDocumentQa.checkVisioRelationships(falseProperty, source, caption))
                    .hasMessageContaining("provenance status");
            assertThatThrownBy(() -> ScenarioDocumentQa.checkVisioRelationships(parts, source, caption.replace("\n" + entry.getValue(), "\f" + entry.getValue())))
                    .hasMessageContaining("rendered relationship");
            String statusRow = "<Row><Cell N='Label' V='taxonomy.provenanceStatus'/><Cell N='Value' V='" + entry.getValue() + "'/></Row>";
            var missingProperty = java.util.Map.of("visio/pages/page1.xml", xml.replace(statusRow, "").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThatThrownBy(() -> ScenarioDocumentQa.checkVisioRelationships(missingProperty, source, caption))
                    .hasMessageContaining("provenance status");
            edge.put("metadata", null);
            assertThatThrownBy(() -> ScenarioDocumentQa.checkVisioRelationships(parts, source, caption))
                    .hasMessageContaining("provenance status");
            edge.put("metadata", java.util.Map.of("origin", "HUMAN_APPROVED"));
            assertThatThrownBy(() -> ScenarioDocumentQa.checkVisioRelationships(parts, source, caption))
                    .hasMessageContaining("Unknown relationship origin");
        }
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

    private static ScenarioDocumentQa.PageProfile completeProfile() throws Exception {
        return ScenarioDocumentQa.pageProfile(completeFixture("run"), completeFixture("decision"), completeFixture("architecture"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mutableObject(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> mutableArray(Object value) {
        return (List<Object>) value;
    }

    private static Map<String, Object> completeFixture(String name) throws Exception {
        String expectedSha256 = switch (name) {
            case "run" -> "3c87bbfebd5aef138246398f8231d5f8249750d0dd550d771e803118f15ef4c7";
            case "decision" -> "99ae0aa3be022e35ee888b1ad9c26caa5a5c85d6949a6501086feea47c67237a";
            case "architecture" -> "f69ed7cb262e2a208e6f5d9f832d6c34c73d614db353d5849c3ff6698cc4c981";
            default -> throw new AssertionError(name);
        };
        try (var resource = ScenarioDocumentQaTest.class.getResourceAsStream(
                "/scenario-documents/complete-flood-v1/" + name + ".json.gz")) {
            assertThat(resource).as("complete captured %s fixture", name).isNotNull();
            try (var input = new java.util.zip.GZIPInputStream(resource)) {
                byte[] bytes = input.readAllBytes();
                assertThat(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)))
                        .as("unmodified captured %s source", name).isEqualTo(expectedSha256);
                return FlatJson.parseObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            }
        }
    }

    private static String page(String body) {
        return "<page width='595' height='842'><word yMin='24' yMax='35'>Running header</word>"
                + "<word yMin='51' yMax='62'>" + body + "</word>"
                + "<word yMin='796' yMax='808'>Page footer</word></page>";
    }
}
