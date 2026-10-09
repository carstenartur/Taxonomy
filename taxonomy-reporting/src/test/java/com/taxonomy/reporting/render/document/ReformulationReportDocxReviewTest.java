package com.taxonomy.reporting.render.document;

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

    @Test void mergedOriginRetainsReviewStateAnswerSchemaAndDependenciesInBothLanguages() throws Exception {
        var schema = new DecisionQuestion.AnswerSchema(DecisionQuestion.AnswerSchema.Kind.SINGLE_CHOICE,
                List.of("manual", "automatic"), "days", 1d, 7d,
                Map.of("manual", DecisionQuestion.AnswerSchema.OptionMeaning.VALUE,
                        "automatic", DecisionQuestion.AnswerSchema.OptionMeaning.OTHER),
                List.of(List.of("manual", "automatic")),
                List.of(new DecisionQuestion.AnswerSchema.AnswerCondition("prior-gate", List.of("yes"))));
        var origin = new DecisionQuestion.Origin("prior-conflict",
                new DecisionQuestion.Key("processing", "channel", "BP-1"), "Which channel?", List.of(),
                List.of("saved-statement"), schema, List.of("prior-gate"), List.of("follow-up"),
                "Requires architectural choice", DecisionQuestion.State.CONFLICT);
        var question = new DecisionQuestion("merged", origin.key(), "Current wording", List.of(), List.of(),
                schema, List.of(), List.of(), "", DecisionQuestion.State.OPEN,
                List.of(), List.of(origin), List.of());
        for (var language : List.of("en", "de")) {
            var report = new ReformulationReportRenderer.Input(language, false, Map.of(), "Original", "Proposal",
                    List.of(), List.of(), List.of(question), List.of(), new ValidationReport(List.of()), "{}");
            String actual = text(report, emptyGraph(List.of()));
            assertThat(actual).contains("prior-conflict", "CONFLICT", "SINGLE_CHOICE", "manual", "automatic",
                    "saved-statement", "prior-gate", "follow-up", "Requires architectural choice", "BP-1",
                    "VALUE", "yes")
                    .contains(language.equals("de")
                            ? "Optionsbedeutungen: [automatic=OTHER, manual=VALUE]"
                            : "Option meanings: [automatic=OTHER, manual=VALUE]")
                    .contains(language.equals("de") ? "Ursprungsstatus:" : "Origin state:",
                            language.equals("de") ? "Vorausgesetzte Fragen:" : "Prerequisite questions:")
                    .doesNotContain("Origin[", "AnswerSchema[");
        }
    }

    @Test void currentQuestionsWithoutOriginsRetainCompleteAnswerContractInBothLanguages() throws Exception {
        var numeric = new DecisionQuestion.AnswerSchema(DecisionQuestion.AnswerSchema.Kind.NUMBER,
                List.of(), "ms", 10d, 100d);
        var meanings = new LinkedHashMap<String, DecisionQuestion.AnswerSchema.OptionMeaning>();
        meanings.put("manual", DecisionQuestion.AnswerSchema.OptionMeaning.VALUE);
        meanings.put("automatic", DecisionQuestion.AnswerSchema.OptionMeaning.OTHER);
        var conditional = new DecisionQuestion.AnswerSchema(DecisionQuestion.AnswerSchema.Kind.SINGLE_CHOICE,
                List.of("manual", "automatic"), null, null, null, meanings,
                List.of(List.of("manual", "automatic")),
                List.of(new DecisionQuestion.AnswerSchema.AnswerCondition("enabled", List.of("yes"))));
        var key = new DecisionQuestion.Key("processing", "timing", "BP-1");
        var timeout = new DecisionQuestion("timeout", key, "What timeout?", List.of(), List.of(), numeric,
                List.of(), List.of(), "", DecisionQuestion.State.OPEN, List.of(), List.of(), List.of());
        var channel = new DecisionQuestion("channel", key, "Which channel?", List.of(), List.of(), conditional,
                List.of("enabled"), List.of("follow-up"), "", DecisionQuestion.State.OPEN,
                List.of(), List.of(), List.of());
        for (var language : List.of("en", "de")) {
            var report = new ReformulationReportRenderer.Input(language, false, Map.of(), "Original", "Proposal",
                    List.of(), List.of(), List.of(timeout, channel), List.of(), new ValidationReport(List.of()), "{}");
            String actual = text(report, emptyGraph(List.of()));
            assertThat(actual).contains("Minimum: 10.0", "Maximum: 100.0")
                    .contains(language.equals("de") ? "Antworttyp: NUMBER" : "Answer type: NUMBER",
                            language.equals("de") ? "Einheit: ms" : "Unit: ms",
                            language.equals("de") ? "Optionen: [manual, automatic]" : "Options: [manual, automatic]",
                            language.equals("de") ? "Optionsbedeutungen: [automatic=OTHER, manual=VALUE]"
                                    : "Option meanings: [automatic=OTHER, manual=VALUE]",
                            language.equals("de") ? "Unvereinbare Optionen: [[manual, automatic]]"
                                    : "Incompatible options: [[manual, automatic]]",
                            language.equals("de") ? "Anwendbarkeit: [enabled → [yes]]" : "Applicability: [enabled → [yes]]",
                            language.equals("de") ? "Vorausgesetzte Fragen: [enabled]" : "Prerequisite questions: [enabled]",
                            language.equals("de") ? "Abhängige Fragen: [follow-up]" : "Dependent questions: [follow-up]")
                    .doesNotContain("Origin question:", "Ursprungsfrage:", "AnswerSchema[");
        }
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
