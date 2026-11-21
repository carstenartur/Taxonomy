package com.taxonomy.analysis.reformulation;

import com.taxonomy.reformulation.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratedDraftTextTest {
    private static final String FIRST = "Ausbilder stellen freigegebene Inhalte bereit; Teilnehmer rufen zugeordnete Inhalte ab.";
    private static final String SECOND = "Teilnehmer dürfen nur eigene Bearbeitungsstände sehen.";
    private static final String THIRD = "Operative Systeme werden nicht angebunden.";
    private static final String SOURCE = String.join(" ", FIRST, SECOND, THIRD);

    @Test
    void generatedDraftShowsWholeSourceOnceAndKeepsEveryQuotationAndSummaryAsEvidence() {
        var fixture = fixture(SOURCE);
        var records = List.of(source("source", SOURCE, SOURCE, Statement.EditingOrigin.SOURCE, "ACCEPTED"),
                source("quote-1", FIRST, SOURCE, Statement.EditingOrigin.MODEL, "UNREVIEWED"),
                source("quote-2", FIRST, SOURCE, Statement.EditingOrigin.MODEL, "UNREVIEWED"),
                source("quote-3", SECOND, SOURCE, Statement.EditingOrigin.MODEL, "UNREVIEWED"));
        var decisions = List.of(question("logs", "Wer darf die Protokolle einsehen?", DecisionQuestion.State.OPEN));

        var result = reconcile(fixture, records, decisions, List.of(), List.of());

        assertThat(result.text()).isEqualTo(String.join("\n\n", FIRST, SECOND, THIRD, "[OPEN] Wer darf die Protokolle einsehen?"));
        assertThat(result.statements()).containsExactlyElementsOf(records);
        assertThat(result.sections()).anySatisfy(s -> {
            assertThat(s.title()).isEqualTo("Technical taxonomy title");
            assertThat(s.summary()).isEqualTo("Unreviewed roll-up for reviewers");
            assertThat(s.statementIds()).containsExactly("source", "quote-1", "quote-2", "quote-3");
        });
        assertThat(result.questions()).containsExactlyElementsOf(decisions);
        assertThat(result.validation().findings()).noneMatch(f -> f.kind() == ValidationReport.Kind.STRUCTURAL_LOSS);
        var targeted = new FrozenReformulationEngine(null, fixture.json).synthesizeAffected(fixture.baseline(), result,
                List.of(), ReformulationImpact.empty());
        assertThat(targeted.text()).isEqualTo(result.text());
        assertThat(targeted.sections()).isEqualTo(result.sections());
        assertThat(targeted.statements()).isEqualTo(records);
    }

    @Test
    void draftKeepsHumanApprovedUnreviewedAndConditionalWordingAndDecisionEvidence() {
        var fixture = fixture(SOURCE);
        String shared = "Optional reporting uses the recorded role.";
        var records = List.of(source("source", SOURCE, SOURCE, Statement.EditingOrigin.SOURCE, "UNREVIEWED"),
                source("human-evidence", FIRST, SOURCE, Statement.EditingOrigin.HUMAN, "UNREVIEWED"),
                new Statement("approved", "The duty officer may access the confirmed export.", List.of(), Statement.Provenance.MODEL_ADDITION,
                        List.of("BP"), List.of(), "After written authorization", Statement.EditingOrigin.HUMAN, "ACCEPTED"),
                new Statement("local", shared, List.of(), Statement.Provenance.MODEL_ADDITION,
                        List.of("BP"), List.of("resolved"), "During the local pilot", Statement.EditingOrigin.MODEL, "UNREVIEWED"),
                new Statement("remote", shared, List.of(), Statement.Provenance.MODEL_ADDITION,
                        List.of("AP"), List.of("conflicting"), "During the remote pilot", Statement.EditingOrigin.MODEL, "UNREVIEWED"),
                new Statement("dependent", "Use the selected log role.", List.of(), Statement.Provenance.MODEL_ADDITION,
                        List.of("BP"), List.of("open"), null, Statement.EditingOrigin.MODEL, "UNREVIEWED"),
                new Statement("rejected", "Unrestricted access was rejected.", List.of(), Statement.Provenance.MODEL_ADDITION,
                        List.of(), List.of(), null, Statement.EditingOrigin.HUMAN, "REJECTED"));
        var decisions = List.of(question("resolved", "What is the approved format?", DecisionQuestion.State.ANSWERED),
                question("conflicting", "Which role owns review?", DecisionQuestion.State.CONFLICT),
                question("open", "Who can view the log?", DecisionQuestion.State.OPEN));
        var previous = answer("previous-format", "resolved", "Obsolete format");
        var approved = new DecisionAnswer("approved-format", "resolved", "offer", 1, List.of("PDF"), DecisionQuestion.State.ANSWERED,
                "architect", Instant.EPOCH.plusSeconds(1), "Reviewed correction", null, "CORRECTION", List.of(previous.id()));
        var answers = List.of(previous, approved, answer("owner-1", "conflicting", "Role A"), answer("owner-2", "conflicting", "Role B"));
        var finding = new ValidationReport.Finding(ValidationReport.Kind.CONFLICT, "ROLE_CONFLICT", "The owners disagree.", List.of(), List.of());

        var result = reconcile(fixture, records, decisions, answers, List.of(finding));

        assertThat(result.text()).contains("[ORIGINAL] " + FIRST, "The duty officer may access the confirmed export.",
                "After written authorization", "[MODEL_ADDITION] " + shared, "During the local pilot", "During the remote pilot",
                "[MODEL_ADDITION] Use the selected log role.\nAbhängig von: Who can view the log?",
                "[ANSWERED] What is the approved format? — Human decision: PDF", "[CONFLICT] Which role owns review?",
                "Human decision: Role A", "Human decision: Role B", "[OPEN] Who can view the log?", "[CONFLICT] The owners disagree.")
                .doesNotContain("Unrestricted access was rejected.", "Technical taxonomy title", "Unreviewed roll-up", "Obsolete format");
        assertThat(result.text().split(Pattern.quote(shared), -1)).hasSize(3);
        assertThat(result.statements()).containsExactlyElementsOf(records);
        assertThat(result.questions()).containsExactlyInAnyOrderElementsOf(decisions);
        var targeted = new FrozenReformulationEngine(null, fixture.json).synthesizeAffected(fixture.baseline(), result,
                answers, ReformulationImpact.empty());
        assertThat(targeted.text()).isEqualTo(result.text());
    }

    @Test
    void rejectedFullOriginalCannotReturnThroughADuplicateOrATargetedRefresh() {
        var fixture = fixture(SOURCE);
        var records = List.of(source("rejected-source", SOURCE, SOURCE, Statement.EditingOrigin.SOURCE, "REJECTED"),
                source("replayed-source", SOURCE, SOURCE, Statement.EditingOrigin.MODEL, "UNREVIEWED"));
        var decisions = List.of(question("open", "Who can view the log?", DecisionQuestion.State.OPEN));

        var result = reconcile(fixture, records, decisions, List.of(), List.of());

        assertThat(result.text()).isEqualTo("[OPEN] Who can view the log?");
        assertThat(result.statements()).containsExactlyElementsOf(records);
        assertThat(result.validation().findings()).anyMatch(f -> f.code().equals("ORIGINAL_NOT_VISIBLE"));
        var legacy = new ReformulationDocument("Legacy text", result.sections(), result.statements(), result.questions(),
                new ValidationReport(List.of()), List.of());
        var targeted = new FrozenReformulationEngine(null, fixture.json).synthesizeAffected(fixture.baseline(), legacy,
                List.of(), ReformulationImpact.empty());
        assertThat(targeted.text()).isEqualTo(result.text());
        assertThat(targeted.validation().findings()).anyMatch(f -> f.code().equals("ORIGINAL_NOT_VISIBLE"));
    }

    @Test
    void formattedSourceAndUnprovedQuotesKeepTheirOriginalWording() {
        String text = "| Role | Limit. Only here. |\n| trainer | read |";
        var fixture = fixture(text);
        var records = List.of(source("source", text, text, Statement.EditingOrigin.SOURCE, "UNREVIEWED"),
                new Statement("unproved", "Only here.", List.of(), Statement.Provenance.ORIGINAL, List.of(), List.of(),
                        null, Statement.EditingOrigin.MODEL, "UNREVIEWED"));

        var result = reconcile(fixture, records, List.of(), List.of(), List.of());

        assertThat(result.text()).isEqualTo(text + "\n\n[ORIGINAL] Only here.");
        assertThat(result.statements()).containsExactlyElementsOf(records);
    }

    @Test
    void paragraphBreaksKeepTitlesAndInitialsWithTheNamedActor() {
        for (String language : List.of("de", "en")) {
            String first = language.equals("de") ? "Die Freigabe liegt bei Prof. Dr. A. Müller."
                    : "The approval belongs to Mrs. T. Morgan.";
            String second = language.equals("de") ? "Der Inhalt bleibt geschützt." : "The material remains protected.";
            var base = fixture(first + " " + second).baseline();
            var selected = new ReformulationBaseline(base.scope(), base.sourceVersionId(), base.originalText(), base.originalTextHash(),
                    base.snapshotId(), base.snapshotPayload(), base.frozenContext(), language, base.algorithmVersion());

            assertThat(GeneratedDraftText.render(selected, List.of(), List.of(), List.of(), List.of()))
                    .isEqualTo(first + "\n\n" + second);
        }
    }

    private static CrossTaxonomyReconciliationTest fixture(String source) {
        var fixture = new CrossTaxonomyReconciliationTest();
        fixture.originalOverride = source;
        return fixture;
    }

    private static ReformulationDocument reconcile(CrossTaxonomyReconciliationTest fixture, List<Statement> statements,
            List<DecisionQuestion> questions, List<DecisionAnswer> answers, List<ValidationReport.Finding> findings) {
        var section = new Section("BP", "BP", "Technical taxonomy title", "Unreviewed roll-up for reviewers", List.of(),
                statements.stream().map(Statement::id).toList(), questions.stream().map(DecisionQuestion::id).toList());
        var phaseA = new ReformulationDocument("Archived phase A body", List.of(section), statements, questions,
                new ValidationReport(findings), List.of());
        return fixture.reconciler().reconcile(fixture.baseline(), phaseA, answers, questions);
    }

    private static Statement source(String id, String wording, String full, Statement.EditingOrigin origin, String state) {
        int start = full.indexOf(wording);
        return new Statement(id, wording, List.of(new Statement.SourceSpan(start, start + wording.length(), wording)),
                Statement.Provenance.ORIGINAL, List.of("BP"), List.of(), null, origin, state);
    }

    private static DecisionQuestion question(String id, String wording, DecisionQuestion.State state) {
        return new DecisionQuestion(id, new DecisionQuestion.Key(id, "wording", "BP"), wording, List.of(), List.of(),
                new DecisionQuestion.AnswerSchema(DecisionQuestion.AnswerSchema.Kind.TEXT, List.of(), null, null, null),
                List.of(), List.of(), "A genuine decision", state);
    }

    private static DecisionAnswer answer(String id, String question, String value) {
        return new DecisionAnswer(id, question, "offer", 1, List.of(value), DecisionQuestion.State.ANSWERED,
                "architect", Instant.EPOCH, "Reviewed reason");
    }
}
