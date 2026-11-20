package com.taxonomy.reporting.render.document;

import com.taxonomy.dto.*;
import com.taxonomy.diagram.*;
import com.taxonomy.export.LayeredDiagramLayoutService;
import com.taxonomy.reporting.api.document.ArchitectureReportDocument;
import com.taxonomy.reporting.api.document.DecisionTreeOverview;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureReportSafetyAndParityTest {
    @Test
    void humanFormatsRetainArchitectureScopeOwnershipAndGapEvidence() throws Exception {
        var report = fixture();
        report.setViewContext(new ViewContext("retained-graph-commit", "retained-graph-branch", null, false, false, false));
        var item = new RequirementElementView();
        item.setNodeCode("CP-match");
        item.setTitle("Matched capability");
        item.setTaxonomySheet("CP");
        item.setRelevance(.75);
        item.setResponsibility(new TaxonomyResponsibilityLookup.Assignment("unit-42", "Operations & Review", "CP"));
        var view = new RequirementArchitectureView();
        view.setAnchors(List.of(new RequirementAnchor("CP-match", 75, "RETAINED-ANCHOR-RATIONALE")));
        view.setTotalAnchors(1);
        view.setTotalElements(1);
        view.setMaxHopDistance(2);
        view.setIncludedElements(List.of(item));
        report.setArchitectureView(view);
        var gaps = new GapAnalysisView();
        gaps.setJudgments(List.of(new ObligationJudgment("source-rule", "CP-match", "CP", "SERVES",
                java.util.Set.of("BP"), 2, 4, 1, ObligationProfile.Severity.WARNING,
                ObligationJudgment.Status.VIOLATED, "RETAINED-GAP-JUDGMENT")));
        report.setGapAnalysis(gaps);
        var reasons = Map.of("CP-match", "SAVED-NODE-REASON-END");
        var text = new ArchitectureReportTextRenderer();
        String markdown = text.renderMarkdown(report, "en", reasons);
        String html = text.renderHtml(report, "en", reasons);
        byte[] bytes = new ArchitectureReportDocxRenderer().render(report, "en", reasons);
        try (var document = new XWPFDocument(new ByteArrayInputStream(bytes));
                var extractor = new XWPFWordExtractor(document)) {
            for (String content : List.of("unit-42", "retained-graph-branch", "retained-graph-commit",
                    "RETAINED-ANCHOR-RATIONALE", "1 / 2..4", "RETAINED-GAP-JUDGMENT", "SAVED-NODE-REASON-END")) {
                assertThat(markdown).contains(content);
                assertThat(html).contains(content);
                assertThat(extractor.getText()).contains(content);
            }
        }
        assertThat(html).contains("Operations &amp; Review", "<svg");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void embeddedSvgLinksToTheRecordedNodeAndRelationshipEvidence(boolean explicitlySupplied) throws Exception {
        var report = fixture();
        var view = new RequirementArchitectureView();
        var source = new RequirementElementView();
        source.setNodeCode("source");
        source.setTitle("Source node");
        source.setTaxonomySheet("CP");
        var target = new RequirementElementView();
        target.setNodeCode("target");
        target.setTitle("Target node");
        target.setTaxonomySheet("BP");
        view.setIncludedElements(List.of(source, target));

        var relation = new RequirementRelationshipView();
        relation.setSourceCode("source");
        relation.setTargetCode("target");
        relation.setRelationType("SUPPORTS");
        relation.setPresenceReason("Reason for this edge");
        relation.setRecordedConfidence(0d);
        view.setIncludedRelationships(List.of(relation));
        report.setArchitectureView(view);
        report.setNodeReasons(Map.of("source", "Reason in the saved document"));
        var renderer = new ArchitectureReportTextRenderer();
        String expected = explicitlySupplied ? "Explicitly supplied reason" : "Reason in the saved document";
        String html = explicitlySupplied ? renderer.renderHtml(report, "en", Map.of("source", expected)) : renderer.renderHtml(report);

        var fragment = html.substring(html.indexOf("<svg"), html.indexOf("</svg>") + "</svg>".length());
        var svg = javax.xml.parsers.DocumentBuilderFactory.newNSInstance().newDocumentBuilder()
                .parse(new org.xml.sax.InputSource(new java.io.StringReader(fragment)));
        assertThat(linkedSvgText(svg, "data-edge-id", "e1")).contains("source -> target", "Reason for this edge", "Confidence: 0.0");
        assertThat(linkedSvgText(svg, "data-node-id", "source")).contains(expected);
        assertThat(linkedSvgText(svg, "data-node-id", "target")).doesNotContain(expected, "Explanation:");
    }

    private static String linkedSvgText(org.w3c.dom.Document svg, String attribute, String id) throws Exception {
        var xpath = javax.xml.xpath.XPathFactory.newInstance().newXPath();
        String href = xpath.evaluate("//*[@" + attribute + "='" + id + "']/parent::*/@*[local-name()='href']", svg);
        assertThat(href).startsWith("#");
        return xpath.evaluate("//*[@id='" + href.substring(1) + "']", svg);
    }

    @Test
    void retainedMatchedAndIncompletePatternSupportSurvivesEveryReportFormat() throws Exception {
        var report = fixture(); var patterns = new PatternDetectionView();
        var matched = new DetectedPattern(); matched.setPatternName("Matched retained pattern"); matched.setCompleteness(100);
        matched.setExpectedSteps(List.of("MATCHED-EXPECTED-STEP"));
        matched.setPresentSteps(List.of("MATCHED-EDGE-EVIDENCE [only if authorized]"));
        var incomplete = new DetectedPattern(); incomplete.setPatternName("Incomplete retained pattern"); incomplete.setCompleteness(50);
        incomplete.setExpectedSteps(List.of("INCOMPLETE-EXPECTED-STEP"));
        incomplete.setPresentSteps(List.of("INCOMPLETE-EDGE-EVIDENCE <retained qualifier>"));
        incomplete.setMissingSteps(List.of("UNKNOWN-REMAINING-STEP"));
        patterns.setMatchedPatterns(List.of(matched)); patterns.setIncompletePatterns(List.of(incomplete));
        report.setPatternDetection(patterns);
        String markdown = new ArchitectureReportTextRenderer().renderMarkdown(report);
        String html = new ArchitectureReportTextRenderer().renderHtml(report);
        byte[] bytes = new ArchitectureReportDocxRenderer().render(report);
        try (var doc = new XWPFDocument(new ByteArrayInputStream(bytes)); var extractor = new XWPFWordExtractor(doc)) {
            for (String content : List.of("MATCHED-EDGE-EVIDENCE", "INCOMPLETE-EDGE-EVIDENCE", "only if authorized",
                    "retained qualifier", "MATCHED-EXPECTED-STEP", "INCOMPLETE-EXPECTED-STEP", "UNKNOWN-REMAINING-STEP")) {
                assertThat(markdown).contains(content); assertThat(html).contains(content); assertThat(extractor.getText()).contains(content);
            }
            assertThat(html).doesNotContain("<retained qualifier>");
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"COMPLETED", "PARTIAL", "SKIPPED_PARTIAL", "SKIPPED_SCOPE"})
    void retainedCopilotStatusAndEvidenceNotesSurviveEveryReportFormat(String state) throws Exception {
        var report = fixture();
        var gaps = new GapAnalysisView(); gaps.getNotes().add("RETAINED-GAP-QUALIFICATION");
        var patterns = new PatternDetectionView(); patterns.getNotes().add("RETAINED-PATTERN-QUALIFICATION");
        report.setGapAnalysis(gaps); report.setPatternDetection(patterns);
        report.setCopilot(new AnalysisCopilotResult(1, state, gaps, patterns, report.getRecommendation(),
                List.of("RETAINED-REPORT-QUALIFICATION")));
        String markdown = new ArchitectureReportTextRenderer().renderMarkdown(report);
        String html = new ArchitectureReportTextRenderer().renderHtml(report);
        byte[] bytes = new ArchitectureReportDocxRenderer().render(report);
        String expected = switch (state) {
            case "COMPLETED" -> "Saved report complete";
            case "PARTIAL" -> "Saved report partial";
            case "SKIPPED_PARTIAL" -> "Not assessed: analysis incomplete";
            default -> "Not assessed: outside the selected scope";
        };
        try (var doc = new XWPFDocument(new ByteArrayInputStream(bytes)); var extractor = new XWPFWordExtractor(doc)) {
            for (String content : List.of(expected, "RETAINED-GAP-QUALIFICATION", "RETAINED-PATTERN-QUALIFICATION", "RETAINED-REPORT-QUALIFICATION")) {
                assertThat(markdown).contains(content);
                assertThat(html).contains(content);
                assertThat(extractor.getText()).contains(content);
            }
        }
    }

    @Test
    void htmlEscapesRequirementAndTableValuesBeforeFormatting() {
        var report = fixture();
        report.setBusinessText("Literal <svg onload=\"window.__qa_marker=1\"></svg> & <requirement> **bold** `**literal** <code>`");
        report.getRecommendation().getMatchedElements().getFirst()
                .setTitle("<img src=x onerror=alert(1)> | second cell");
        String html = new ArchitectureReportTextRenderer().renderHtml(report);
        assertThat(html).doesNotContain("<svg", "<img", "<requirement>", "<code><strong>");
        assertThat(html).contains("&lt;svg onload=&quot;window.__qa_marker=1&quot;&gt;&lt;/svg&gt;",
                "&amp; &lt;requirement&gt;", "<strong>bold</strong>", "<code>**literal** &lt;code&gt;</code>",
                "<td>&lt;img src=x onerror=alert(1)&gt; | second cell</td>");
    }

    @Test
    void htmlKeepsSafeLinksAndTreatsUnsafeDestinationsAsText() {
        String markdown = "[safe **label**](https://example.org/a?x=1&y=2) "
                + "[mail](mailto:team@example.org) [nested](https://example.org/a_(b)) [bad](javascript:alert) "
                + "[data](data:text/html,evil) [encoded](java&#x73;cript:alert) "
                + "`[literal](https://example.org) <tag> **plain**`";
        String html = new ArchitectureReportTextRenderer().markdownToHtml(markdown);
        assertThat(html).contains("<a href=\"https://example.org/a?x=1&amp;y=2\">safe <strong>label</strong></a>",
                "<a href=\"mailto:team@example.org\">mail</a>",
                "<a href=\"https://example.org/a_(b)\">nested</a>",
                "[bad](javascript:alert)", "[data](data:text/html,evil)",
                "[encoded](java&amp;#x73;cript:alert)",
                "<code>[literal](https://example.org) &lt;tag&gt; **plain**</code>");
        assertThat(html).doesNotContain("href=\"javascript:", "href=\"data:", "href=\"java&");
    }

    @Test
    void htmlPreservesEscapedTablePipesAndCodeWithoutFormattingThem() {
        String html = new ArchitectureReportTextRenderer().markdownToHtml(
                "| Label | Code |\n|---|---|\n| **A** \\| B | `<svg> | **literal**` |\n\n"
                        + "```html\n<script>alert(1)</script>\n```\n");
        assertThat(html).contains("<td><strong>A</strong> | B</td>",
                "<td><code>&lt;svg&gt; | **literal**</code></td>",
                "&lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(html).doesNotContain("<script>", "<svg>");
    }

    @Test
    void htmlKeepsDelimiterCharactersInNodeIdsAndCodeLiteral() {
        var report = fixture();
        report.getRecommendation().getMatchedElements().getFirst().setNodeCode("CP-`tick`|<id>");
        String html = new ArchitectureReportTextRenderer().renderHtml(report);
        assertThat(html).contains("<td><code>CP-`tick`|&lt;id&gt;</code></td>",
                "<td>Matched capability</td>");
        assertThat(new ArchitectureReportTextRenderer().markdownToHtml("`literal\\|pipe`"))
                .contains("<code>literal\\|pipe</code>");
    }

    @Test
    void unmatchedBacktickInRecommendationTitleCannotMergeFollowingCells() {
        var report = fixture();
        report.getRecommendation().getMatchedElements().getFirst().setTitle("Literal `tick");
        assertMatchedRow(report, "Literal `tick");
    }

    @Test
    void literalBackslashBeforePipeCannotSplitRecommendationCell() {
        var report = fixture();
        report.getRecommendation().getMatchedElements().getFirst().setTitle("Literal \\| separator");
        assertMatchedRow(report, "Literal \\| separator");
    }

    @Test
    void tableCodeKeepsLiteralBackslashesPipesAndMarkup() {
        var report = fixture();
        report.getRecommendation().getMatchedElements().getFirst()
                .setTitle("`code\\path\\|<type>` **bold**");
        assertMatchedRow(report, "<code>code\\path\\|&lt;type&gt;</code> <strong>bold</strong>");
    }

    private static void assertMatchedRow(ArchitectureReport report, String titleHtml) {
        String html = new ArchitectureReportTextRenderer().renderHtml(report);
        int start = html.indexOf("<tr><td><code>CP-match</code>");
        assertThat(start).isGreaterThanOrEqualTo(0);
        String row = html.substring(start, html.indexOf("</tr>", start));
        assertThat(row.split("<td>", -1)).hasSize(6); // five distinct cells
        assertThat(row).contains("<td>" + titleHtml + "</td>",
                "<td>CP</td>", "<td>0.125%</td>", "<td>Matched reason</td>");
    }

    @Test
    void htmlRetainsAuthoredHeadingBoldCodeListAndFooterFormatting() {
        assertThat(new ArchitectureReportTextRenderer().markdownToHtml(
                "## Heading **title**\n\n**standalone bold**\n- **item** `literal <type>`\n*footer*\n"))
                .contains("<h2>Heading <strong>title</strong></h2>",
                        "<p><strong>standalone bold</strong></p>",
                        "<li><strong>item</strong> <code>literal &lt;type&gt;</code></li>",
                        "<p><em>footer</em></p>");
    }

    @Test
    void wordAndTextKeepMatchedProposedAndEverySuggestedRelation() throws Exception {
        var report = fixture();
        String markdown = new ArchitectureReportTextRenderer().renderMarkdown(report);
        String html = new ArchitectureReportTextRenderer().renderHtml(report);
        assertThat(markdown).contains("| `CP-proposal` | Gap proposal | CP | 0.0% | Proposed reason |");
        byte[] bytes = new ArchitectureReportDocxRenderer().render(report);
        try (var doc = new XWPFDocument(new ByteArrayInputStream(bytes));
                var extractor = new XWPFWordExtractor(doc)) {
            String word = extractor.getText();
            for (String content : List.of("Matched Elements", "Proposed Elements", "Suggested Relations",
                    "High-score matches; human approval is not recorded.",
                    "CP-match", "CP-proposal", "Matched reason", "Proposed reason",
                    "SERVES", "UNIQUE-SUGGESTION-REASON", "DEPENDS_ON", "SECOND-SUGGESTION-REASON")) {
                assertThat(markdown).contains(content);
                assertThat(html).contains(content);
                assertThat(word).contains(content);
            }
            assertThat(word).contains("0.125%", "0.0%", "CP-zero");
            assertThat(doc.getTables()).anySatisfy(table -> {
                assertThat(table.getRow(0).getTableCells()).extracting(c -> c.getText())
                        .containsExactly("Source", "Target", "Type", "Short rationale");
                assertThat(table.getText()).contains("UNIQUE-SUGGESTION-REASON", "SECOND-SUGGESTION-REASON");
            });
            writeReview("recommendation-parity.docx", bytes);
        }
    }

    @Test
    void textAndWordRetainFullRelationshipIdentityAndReasonsEvenWithoutElements() throws Exception {
        var report = fixture();
        var relation = new RequirementRelationshipView();
        relation.setRelationId(912345L);
        relation.setSourceCode("CP-source-full");
        relation.setTargetCode("BP-target-full");
        relation.setRelationType("SUPPORTS");
        relation.setIncludedBecause("Included reason");
        relation.setDerivationReason("Derived reason");
        relation.setPresenceReason("Presence reason");
        relation.setOrigin(RelationOrigin.LLM_SUPPORTED);
        relation.setConfidence(.75);
        var view = new RequirementArchitectureView();
        view.setIncludedRelationships(List.of(relation));
        report.setArchitectureView(view);
        String markdown = new ArchitectureReportTextRenderer().renderMarkdown(report);
        String html = new ArchitectureReportTextRenderer().renderHtml(report);
        try (var doc = new XWPFDocument(new ByteArrayInputStream(new ArchitectureReportDocxRenderer().render(report)));
                var extractor = new XWPFWordExtractor(doc)) {
            for (String content : List.of("912345", "CP-source-full", "BP-target-full", "SUPPORTS",
                    "Included reason", "Derived reason", "Presence reason", "AI-proposed", "LLM_SUPPORTED", "Confidence: 0.75")) {
                assertThat(markdown).contains(content);
                assertThat(html).contains(content);
                assertThat(extractor.getText()).contains(content);
            }
        }
    }

    @Test
    void frozenWordRetainsRecordedRelationshipProvenance() throws Exception {
        var metadata = new DiagramRelationMetadata(912345L, RelationOrigin.LLM_SUPPORTED, .75,
                "Included reason", "Derived reason", "Presence reason", null, List.of());
        var edge = new DiagramEdge("relation-full-id", "CP-source-full", "BP-target-full", "SUPPORTS", .125, "impact", metadata);
        var graph = new DiagramModel("Saved architecture", List.of(
                new DiagramNode("CP-source-full", "Source", "Capability", .125, true, 0),
                new DiagramNode("BP-target-full", "Target", "Process", 0, false, 1)),
                List.of(edge), new DiagramLayout("LR", true));
        var scene = new LayeredDiagramLayoutService().layout(graph);
        // The fixture captures the metadata in both evidence representations.
        scene = new DiagramScene(scene.title(), scene.width(), scene.height(), scene.direction(), scene.nodes(),
                scene.edges().stream().map(e -> new DiagramSceneEdge(e.id(), e.sourceId(), e.targetId(),
                        e.relationType(), e.relevance(), e.relationCategory(), e.sourceX(), e.sourceY(),
                        e.targetX(), e.targetY(), metadata)).toList());
        var evidence = new ArchitectureReportDocument.SnapshotEvidence(1L, 2L, 3L, 1, "snapshot-1",
                "repo", "workspace", "main", "commit", "MOCK", "model", "taxonomy-sha",
                ArchitectureReportDocument.graphSha256(graph));
        var report = ArchitectureReportDocuments.from("Saved report", "en", "Requirement", "Scope", "Recommendation",
                List.of(), graph, scene, DecisionTreeOverview.from(List.of()), evidence);
        byte[] bytes = new ArchitectureReportDocxRenderer().render(report);
        try (var doc = new XWPFDocument(new ByteArrayInputStream(bytes));
                var extractor = new XWPFWordExtractor(doc)) {
            assertThat(extractor.getText()).contains("relation-full-id", "912345", "CP-source-full", "BP-target-full",
                    "SUPPORTS", "AI-proposed", "LLM_SUPPORTED", "Confidence: 0.75",
                    "Included reason", "Derived reason", "Presence reason", "Relationships - AI-proposed: 1");
            writeReview("saved-provenance.docx", bytes);
        }
    }

    @Test
    void pendingRelationDetailsRemainConsistentInTextAndWord() throws Exception {
        var report = fixture();
        var proposal = new RelationProposalDto();
        proposal.setId(778899L);
        proposal.setSourceCode("pending-source");
        proposal.setTargetCode("pending-target");
        proposal.setRelationType("PENDING_SUPPORTS");
        proposal.setStatus("PENDING_REVIEW");
        proposal.setConfidence(.00125);
        proposal.setRationale("Pending rationale");
        proposal.setProvenance("Pending provenance");
        proposal.setExplanationBasis("Pending explanation basis");
        report.setPendingProposals(List.of(proposal));
        String markdown = new ArchitectureReportTextRenderer().renderMarkdown(report);
        String html = new ArchitectureReportTextRenderer().renderHtml(report);
        byte[] bytes = new ArchitectureReportDocxRenderer().render(report);
        try (var doc = new XWPFDocument(new ByteArrayInputStream(bytes));
                var extractor = new XWPFWordExtractor(doc)) {
            for (String content : List.of("778899", "pending-source", "pending-target", "PENDING_SUPPORTS",
                    "PENDING_REVIEW", "Pending rationale", "Pending provenance", "Pending explanation basis", "0.125%")) {
                assertThat(markdown).contains(content);
                assertThat(html).contains(content);
                assertThat(extractor.getText()).contains(content);
            }
            writeReview("pending-relation-parity.docx", bytes);
        }
    }

    private static void writeReview(String name, byte[] bytes) throws Exception {
        String review = System.getProperty("qa.report.reviewDir");
        if (review != null) {
            var dir = java.nio.file.Files.createDirectories(java.nio.file.Path.of(review));
            java.nio.file.Files.write(dir.resolve(name), bytes);
        }
    }

    private static ArchitectureReport fixture() {
        var report = new ArchitectureReport();
        report.setBusinessText("Requirement with literal <requirement>");
        report.setScores(Map.of("CP-zero", 0d, "CP-fraction", .125));
        var recommendation = new ArchitectureRecommendation();
        recommendation.setMatchedElements(List.of(new RecommendedElement(
                "CP-match", "Matched capability", "CP", .125, "Matched reason")));
        recommendation.setProposedElements(List.of(new RecommendedElement(
                "CP-proposal", "Gap proposal", "CP", 0, "Proposed reason")));
        recommendation.setSuggestedRelations(List.of(
                new SuggestedRelation("CP-match", "CP-proposal", "SERVES", "UNIQUE-SUGGESTION-REASON"),
                new SuggestedRelation("CP-proposal", "CP-match", "DEPENDS_ON", "SECOND-SUGGESTION-REASON")));
        report.setRecommendation(recommendation);
        return report;
    }
}
