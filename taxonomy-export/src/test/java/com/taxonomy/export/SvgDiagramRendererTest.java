package com.taxonomy.export;

import com.taxonomy.diagram.DiagramEdge;
import com.taxonomy.diagram.DiagramLayout;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
import com.taxonomy.diagram.DiagramRelationMetadata;
import com.taxonomy.diagram.DiagramScene;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SvgDiagramRendererTest {

    private final LayeredDiagramLayoutService layout = new LayeredDiagramLayoutService();
    private final SvgDiagramRenderer renderer = new SvgDiagramRenderer();

    @Test
    void rendersStandaloneScriptFreeSvgFromTheScene() {
        DiagramScene scene = layout.layout(new DiagramModel(
                "Secure <architecture>",
                List.of(
                        new DiagramNode("CP-1", "Command & Control", "Capabilities", 0.91, true, 1),
                        new DiagramNode("CR-1", "Secure exchange", "Core Services", 0.84, false, 3)),
                List.of(new DiagramEdge(
                        "edge-1", "CP-1", "CR-1", "REALIZED_BY", 0.8, "impact")),
                new DiagramLayout("LR", true)));

        String svg = renderer.render(scene);

        assertThat(svg)
                .startsWith("<?xml version=")
                .contains("<svg xmlns=\"http://www.w3.org/2000/svg\"")
                .contains("Secure &lt;architecture&gt;")
                .contains("Command &amp; Control")
                .contains("REALIZED_BY")
                .doesNotContain("<script")
                .doesNotContain("window.print");
    }

    @Test
    void relationshipEvidenceIsVisibleWithoutReadingTitlesOrMetadata() throws Exception {
        Document document = parse(renderer.render(WorkingViewPdfRendererTest.scene(2)));

        assertThat(visibleText(document)).contains("NODE-0", "NODE-1", "EDGE-END", "80%",
                "LABEL-0000-END", "LABEL-0001-END", "Relation ID: 9223372036854775807",
                "LLM_SUPPORTED", "Confidence: 0.125", "Seed type: FRAMEWORK_SEED",
                "INCLUSION-END", "DERIVATION-END", "PRESENCE-END", "CONTRIBUTION-END",
                "ORIGINAL-QUOTE-END", "CONTRIBUTION-CONDITION-END", "VERIFIED", "ALTERNATIVE",
                "DECISION-CONTRIBUTION-END", "DECISION-QUOTE-END", "DECISION-CONDITION-END",
                "ALTERNATIVE-GROUP-END", "RATIONALE-END", "QUESTION-END");
    }

    @Test
    void legacyRelationshipReasonsRemainVisibleWithoutChangingTheFrozenGraph() throws Exception {
        var graph = new DiagramModel("Legacy evidence", List.of(
                new DiagramNode("A", "Selected source", "Capabilities", .8, true, 1),
                new DiagramNode("B", "Selected target", "Services", .7, false, 2)),
                List.of(new DiagramEdge("legacy-edge", "A", "B", "SERVES", .42)), new DiagramLayout("LR", true));
        DiagramScene scene = layout.layout(graph);
        var supplemental = Map.of(
                "legacy-edge", Map.of(
                        "presenceReason", "The original mapping explains this selection. ".repeat(70) + "LEGACY-SELECTION-END",
                        "decisionComment", "The original reviewer recorded this assessment. ".repeat(70) + "LEGACY-REVIEW-END"),
                "excluded-edge", Map.of("presenceReason", "EXCLUDED-RELATION-REASON-MUST-NOT-LEAK"));

        String visible = visibleText(parse(renderer.render(scene, Map.of(), supplemental)));

        assertThat(visible).contains("42%", "Recorded selection reason", "Recorded review comment",
                "LEGACY-SELECTION-END", "LEGACY-REVIEW-END").doesNotContain("EXCLUDED-RELATION-REASON-MUST-NOT-LEAK");
        assertThat(graph.edges().getFirst().metadata()).isNull();
        assertThat(scene.edges().getFirst().metadata()).isNull();
    }

    @Test
    void aHundredNodesAndFifteenRelationshipsKeepCompleteReasonsInsideReadableEvidenceColumns() throws Exception {
        String hundredWords = "Saved evidence supports this selection and retains its original rationale. ".repeat(10);
        var nodes = new ArrayList<DiagramNode>();
        var edges = new ArrayList<DiagramEdge>();
        var reasons = new LinkedHashMap<String, String>();
        for (int index = 0; index < 100; index++) {
            nodes.add(new DiagramNode("NODE-" + index, "Full label LABEL-" + index + "-END",
                    "Capabilities", index == 0 ? .00875 : .875, index == 0, 1));
            reasons.put("NODE-" + index, hundredWords + "NODE-REASON-" + index + "-END");
        }
        var base = WorkingViewPdfRendererTest.scene(2).edges().getFirst().metadata();
        for (int index = 0; index < 15; index++) {
            var metadata = new DiagramRelationMetadata((long) index, base.origin(), base.confidence(),
                    hundredWords + "EDGE-REASON-" + index + "-END", base.derivationReason(), base.presenceReason(),
                    base.seedType(), base.requirementEvidence());
            edges.add(new DiagramEdge("EDGE-" + index, "NODE-0", "NODE-" + (index + 1), "SUPPORTS", .875, "impact", metadata));
        }
        reasons.put("EXCLUDED-NODE", "EXCLUDED-REASON-MUST-NOT-LEAK");
        Map<String, String> originalReasons = Map.copyOf(reasons);
        DiagramScene scene = layout.layout(new DiagramModel("Complete evidence", nodes, edges, new DiagramLayout("LR", true)));
        Document document = parse(renderer.render(scene, reasons));
        String visible = visibleText(document);

        assertThat(visible).contains("0.875%", "87.5%", "Elements: 100", "Relationships: 15")
                .doesNotContain("EXCLUDED-REASON-MUST-NOT-LEAK");
        for (int index = 0; index < 100; index++) assertThat(visible).contains("NODE-REASON-" + index + "-END", "LABEL-" + index + "-END");
        for (int index = 0; index < 15; index++) assertThat(visible).contains("EDGE-REASON-" + index + "-END");
        assertThat(reasons).isEqualTo(originalReasons);

        double width = Double.parseDouble(document.getDocumentElement().getAttribute("width"));
        double height = Double.parseDouble(document.getDocumentElement().getAttribute("height"));
        assertThat(height).isGreaterThan(scene.height()).isLessThan(width * 3);
        assertThat(width).isLessThan(height * 3);
        var targets = new HashSet<String>();
        var entries = new HashSet<String>();
        var groups = document.getElementsByTagNameNS("http://www.w3.org/2000/svg", "g");
        for (int index = 0; index < groups.getLength(); index++) {
            var group = (Element) groups.item(index);
            if (!"architecture-evidence".equals(group.getAttribute("class"))) continue;
            entries.add(group.getAttribute("data-evidence-kind") + ":" + group.getAttribute("data-evidence-id"));
            if (group.hasAttribute("id")) targets.add(group.getAttribute("id"));
            var texts = group.getElementsByTagNameNS("http://www.w3.org/2000/svg", "text");
            for (int line = 0; line < texts.getLength(); line++) {
                var text = (Element) texts.item(line);
                double x = Double.parseDouble(text.getAttribute("x"));
                double y = Double.parseDouble(text.getAttribute("y"));
                assertThat(Double.parseDouble(text.getAttribute("font-size"))).isGreaterThanOrEqualTo(14);
                assertThat(x).isGreaterThanOrEqualTo(14);
                assertThat(x + Double.parseDouble(text.getAttribute("textLength"))).isLessThanOrEqualTo(width - 13.99);
                assertThat(y).isGreaterThan(scene.height());
                assertThat(y + 4).isLessThan(height);
            }
        }
        assertThat(entries).hasSize(115).contains("node:NODE-0", "relationship:EDGE-14");
        assertThat(targets).hasSize(115);
        var links = document.getElementsByTagNameNS("http://www.w3.org/2000/svg", "a");
        for (int index = 0; index < links.getLength(); index++) {
            String reference = ((Element) links.item(index)).getAttributeNS("http://www.w3.org/1999/xlink", "href");
            assertThat(targets).contains(reference.substring(1));
        }
    }

    @Test
    void refusesToPretendAnEmptySceneIsAnArchitecture() {
        DiagramScene empty = layout.layout(
                new DiagramModel("Empty", List.of(), List.of(), new DiagramLayout("LR", true)));

        assertThatThrownBy(() -> renderer.render(empty))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one node");
    }

    private static Document parse(String svg) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(svg)));
    }

    private static String visibleText(Document document) {
        StringBuilder text = new StringBuilder();
        var elements = document.getElementsByTagNameNS("http://www.w3.org/2000/svg", "text");
        for (int index = 0; index < elements.getLength(); index++) {
            text.append(elements.item(index).getTextContent()).append(' ');
        }
        return text.toString();
    }
}
