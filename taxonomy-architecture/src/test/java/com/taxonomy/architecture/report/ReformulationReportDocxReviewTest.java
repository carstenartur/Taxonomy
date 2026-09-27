package com.taxonomy.architecture.report;

import com.taxonomy.diagram.DiagramLayout;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.export.reformulation.FrozenReformulationArchitecture;
import com.taxonomy.export.reformulation.ReformulationReportRenderer;
import com.taxonomy.reformulation.DecisionQuestion;
import com.taxonomy.reformulation.ValidationReport;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReformulationReportDocxReviewTest {
    private final ReformulationReportDocxRenderer renderer = new ReformulationReportDocxRenderer();

    private FrozenReformulationArchitecture emptyGraph(List<String> gaps) {
        return new FrozenReformulationArchitecture(new DiagramModel("Frozen", List.of(), List.of(),
                new DiagramLayout("LR", true)), Map.of("Snapshot", "saved"), gaps, List.of(), true, List.of(), List.of());
    }

    private ReformulationReportRenderer.Input input(List<DecisionQuestion> questions) {
        return new ReformulationReportRenderer.Input("en", false, Map.of(), "Original", "Proposal",
                List.of(), List.of(), questions, List.of(), new ValidationReport(List.of()), "{}");
    }

    private String text(ReformulationReportRenderer.Input report, FrozenReformulationArchitecture graph) throws Exception {
        try (var doc = new XWPFDocument(new ByteArrayInputStream(renderer.render(report, graph)))) {
            return new XWPFWordExtractor(doc).getText();
        }
    }

    @Test void originDiscoveriesUseReadableFieldsInsteadOfRecordDump() throws Exception {
        var discovery = new DecisionQuestion.Discovery("saved location", "saved context", "saved rationale",
                List.of(), List.of(), List.of());
        var schema = new DecisionQuestion.AnswerSchema(DecisionQuestion.AnswerSchema.Kind.TEXT,
                List.of(), null, null, null);
        var origin = new DecisionQuestion.Origin("prior", new DecisionQuestion.Key("subject", "choice", "scope"),
                "Prior wording", List.of(discovery), List.of(), schema, List.of(), List.of(), "prior consequence",
                DecisionQuestion.State.OPEN);
        var question = new DecisionQuestion("current", origin.key(), "Current wording", List.of(), List.of(), schema,
                List.of(), List.of(), "consequence", DecisionQuestion.State.OPEN, List.of(), List.of(origin), List.of());
        String actual = text(input(List.of(question)), emptyGraph(List.of()));
        assertThat(actual).contains("Origin question: prior", "saved location", "saved context", "saved rationale")
                .doesNotContain("Discovery[", "sourceSpans=", "nodeIds=");
    }

    @Test void emptyGraphStillDisplaysSavedGapInventory() throws Exception {
        assertThat(text(input(List.of()), emptyGraph(List.of("Saved unresolved gap"))))
                .contains("No architecture graph recorded", "Saved unresolved gap");
    }

    @Test void standaloneWordUsesSafePageMarginsAndConsistentDefaultFont() throws Exception {
        try (var doc = new XWPFDocument(new ByteArrayInputStream(renderer.render(input(List.of()), emptyGraph(List.of()))))) {
            var section = doc.getDocument().getBody().getSectPr();
            assertThat(section).isNotNull();
            assertThat(section.getPgSz().getW()).isEqualTo(BigInteger.valueOf(11906));
            assertThat((BigInteger) section.getPgMar().getTop()).isGreaterThanOrEqualTo(BigInteger.valueOf(1000));
            assertThat((BigInteger) section.getPgMar().getBottom()).isGreaterThanOrEqualTo(BigInteger.valueOf(1000));
            assertThat(doc.getStyles().getDefaultRunStyle().getRPr().getRFontsArray(0).getAscii()).isEqualTo("Aptos");
        }
    }

    @Test void germanVisibleMetadataAndEmptyValidationAreLocalized() throws Exception {
        var german = new ReformulationReportRenderer.Input("de", true,
                Map.of("Adopted at", "2026-01-01", "Source version", "3"), "Original", "Vorschlag",
                List.of(), List.of(), List.of(), List.of(), new ValidationReport(List.of()), "{}");
        var architecture = new FrozenReformulationArchitecture(new DiagramModel("Frozen", List.of(), List.of(),
                new DiagramLayout("LR", true)), Map.of("Includes provisional relations", "false"),
                List.of(), List.of(), true, List.of(), List.of());
        assertThat(text(german, architecture)).contains("Übernommen am: 2026-01-01", "Quellversion: 3",
                "Vorläufige Beziehungen enthalten: false", "Keine Prüfbefunde gespeichert")
                .doesNotContain("Adopted at:", "Source version:", "Includes provisional relations:");
    }
}
