package com.taxonomy.reporting.render.document;

import com.taxonomy.reporting.api.decision.DecisionRationaleReport.Disposition;
import com.taxonomy.reporting.api.decision.DecisionReportOptions.TreeLayout;
import com.taxonomy.reporting.api.document.DecisionTreeOverview;
import com.taxonomy.reporting.api.document.DecisionTreeOverview.DecisionTreeRow;
import com.taxonomy.reporting.render.decision.DecisionReportLabels;

import javax.imageio.ImageIO;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Checks the real SVG text and matching raster-state color for an authored single-node tree. */
final class DecisionTreePercentageChecks {
    private DecisionTreePercentageChecks() { }

    static void check(String language, String kind) throws Exception {
        var labels = new DecisionReportLabels(language);
        Double score;
        Disposition disposition;
        String state;
        int fill;
        switch (kind) {
            case "zero" -> { score = 0d; disposition = Disposition.REJECTED; state = labels.rejected(); fill = 0xf0f3f6; }
            case "missing" -> { score = null; disposition = Disposition.NOT_EVALUATED; state = labels.notEvaluated(); fill = 0xfff4d6; }
            case "fraction" -> { score = .125; disposition = Disposition.LEAF_CANDIDATE; state = "0.125% · " + labels.leafCandidate(); fill = 0xe1f4f3; }
            case "tiny" -> { score = 1e-8; disposition = Disposition.LEAF_CANDIDATE; state = "<0.000001% · " + labels.leafCandidate(); fill = 0xe1f4f3; }
            case "legacy" -> { score = 85d; disposition = Disposition.LEAF_CANDIDATE; state = "85% · " + labels.leafCandidate(); fill = 0xe1f4f3; }
            default -> throw new IllegalArgumentException(kind);
        }
        var tree = new DecisionTreeOverview(List.of(new DecisionTreeRow(
                0, "fixture", "Function", score, disposition, null, null)), List.of());
        var panel = new DecisionTreeFigureRenderer().render(tree, language, TreeLayout.AUTO).getFirst();
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var svg = factory.newDocumentBuilder().parse(new ByteArrayInputStream(panel.svg().getBytes(StandardCharsets.UTF_8)));
        var text = svg.getElementsByTagNameNS("http://www.w3.org/2000/svg", "text");
        if (text.getLength() != 2) throw new AssertionError("Unexpected single-row layout");
        String expected = "fixture · Function  |  " + state;
        String actual = text.item(1).getTextContent();
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + " but was " + actual);
        var image = ImageIO.read(new ByteArrayInputStream(panel.png()));
        if (image == null || (image.getRGB(20, 80) & 0xffffff) != fill)
            throw new AssertionError("Raster state does not match the numeric evidence");
    }
}
