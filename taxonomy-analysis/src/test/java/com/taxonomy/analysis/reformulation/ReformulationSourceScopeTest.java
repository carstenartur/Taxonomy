package com.taxonomy.analysis.reformulation;

import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.reformulation.NodeSynthesisInput;
import com.taxonomy.reformulation.ReformulationBaseline;
import com.taxonomy.reformulation.Statement;
import com.taxonomy.reformulation.ValidationReport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReformulationSourceScopeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TRAINERS = "Ausbilder sehen den Bearbeitungsstand der ihnen zugeordneten Teilnehmer.";
    private static final String PARTICIPANTS = "Teilnehmer rufen die ihnen zugeordneten Inhalte online ab.";
    private static final String RESTRICTION = "Teilnehmer dürfen nur eigene Bearbeitungsstände sehen.";
    private static final String STATE = "Ausbilder stellen freigegebene Lehrinhalte bereit.";
    private static final String NEGATION = "It is false that trainers may approve content.";
    private static final String CONDITION = "Participants may export records only after review.";
    private static final String ACTORS = "Only the duty officer, never the trainers, may release approved content.";
    private static final String ORIGINAL = String.join(" ", TRAINERS, PARTICIPANTS, RESTRICTION, STATE, NEGATION, CONDITION, ACTORS);

    @Test
    void newFrozenPromptsTeachScopeWhileOldBaselinesKeepTheirReconciliationIdentity() {
        assertThat(ReformulationPromptBuilder.interactiveTemplate()).contains("exact actor", "approval workflow");
        var current = ReconcilePromptBuilder.freeze(Map.of("reformulationPromptVersion", ReformulationPromptBuilder.INTERACTIVE_PROMPT_VERSION));
        assertThat(current.get("reconcilePrompt")).contains("exact actor", "approval workflow");
        var prior = ReconcilePromptBuilder.freeze(Map.of("reformulationPromptVersion", "reformulation-node-v2"));
        assertThat(prior.get("reconcilePromptVersion")).isEqualTo("reformulation-reconcile-v2");
        assertThat(StableIdentityHash.sha256(prior.get("reconcilePrompt")))
                .isEqualTo("dd1e4d582bbb5823953f42e1e54d889f4223fe5a22208cfff7f0cd0640dfa11f");
    }

    @Test
    void paraphrasesExpandingRestrictionsOrTurningAStateIntoAnActionStayReviewable() {
        var trainerExclusivity = proposal("Ausbilder sehen ausschließlich zugeordnete Bearbeitungsstände.",
                "MODEL_ADDITION", null, List.of(TRAINERS, RESTRICTION));
        var contentExclusivity = proposal(PARTICIPANTS, "ORIGINAL", "Auf zugeordnete Inhalte begrenzt.", List.of(PARTICIPANTS));
        var approvalWorkflow = proposal("Ausbilder müssen die Lehrinhalte vor Veröffentlichung freigeben.",
                "MODEL_ADDITION", null, List.of(STATE));
        var exact = proposal(RESTRICTION, "ORIGINAL", null, List.of(RESTRICTION));

        var result = new ReformulationResponseParser(JSON).parse(response(trainerExclusivity, contentExclusivity, approvalWorkflow, exact), input());

        assertThat(result.statementProposals()).extracting(Statement::provenance).containsExactly(
                Statement.Provenance.MODEL_ADDITION, Statement.Provenance.MODEL_ADDITION,
                Statement.Provenance.MODEL_ADDITION, Statement.Provenance.ORIGINAL);
        assertThat(result.statementProposals().get(1).conditionalValidity()).isEqualTo("Auf zugeordnete Inhalte begrenzt.");
        var reviewIds = result.conflictCandidates().stream().filter(f -> f.kind() == ValidationReport.Kind.SEMANTIC_REVIEW
                && f.code().equals("SOURCE_FORMULATION_REVIEW")).flatMap(f -> f.statementIds().stream());
        assertThat(reviewIds).containsExactlyElementsOf(result.statementProposals().subList(0, 3).stream().map(Statement::id).toList());
    }

    @Test
    void disjointExactQuotesCannotBeSplicedIntoAnOriginalRestriction() {
        String subject = "Ausbilder ";
        String predicate = "dürfen nur eigene Bearbeitungsstände sehen.";
        var spliced = proposal(subject + predicate, "ORIGINAL", null, List.of(subject, predicate));

        assertThatThrownBy(() -> new ReformulationResponseParser(JSON).parse(response(spliced), input()))
                .hasMessageContaining("contiguous source passage");
    }

    @Test
    void incompleteExactQuotesKeepTheirOffsetsWithoutGainingIndependentSourceAuthority() {
        var clips = List.of("trainers may approve content.", "Participants may export records", "may release approved content.");
        var raw = Stream.concat(clips.stream(), Stream.of(NEGATION, CONDITION, ACTORS))
                .map(quote -> proposal(quote, "ORIGINAL", null, List.of(quote)))
                .toArray(tools.jackson.databind.JsonNode[]::new);

        var result = new ReformulationResponseParser(JSON).parse(response(raw), input());

        assertThat(result.statementProposals().subList(0, 3)).extracting(Statement::provenance)
                .containsOnly(Statement.Provenance.MODEL_ADDITION);
        assertThat(result.statementProposals().subList(3, 6)).extracting(Statement::provenance)
                .containsOnly(Statement.Provenance.ORIGINAL);
        assertThat(result.statementProposals()).allSatisfy(statement -> {
            assertThat(statement.sourceSpans()).singleElement().satisfies(span -> {
                assertThat(span.matches(ORIGINAL)).isTrue();
                assertThat(span.exactText()).isEqualTo(statement.wording());
            });
        });
        assertThat(result.conflictCandidates().stream().filter(f -> f.code().equals("SOURCE_FORMULATION_REVIEW"))
                .flatMap(f -> f.statementIds().stream())).containsExactlyElementsOf(result.statementProposals().subList(0, 3)
                        .stream().map(Statement::id).toList());
    }

    private static NodeSynthesisInput input() {
        var scope = new ReformulationBaseline.Scope("repository", "workspace", "main", 1, 1);
        var baseline = ReformulationBaseline.freeze(new ReformulationBaseline.Source(scope, 1, ORIGINAL),
                new ReformulationBaseline.Snapshot(scope, "snapshot", 1, "{}"), Map.of(), "de", "v1");
        return new NodeSynthesisInput(baseline, "BP", null, "Process", List.of(), List.of(), List.of(), Map.of(), List.of(), List.of(), "Preserve scope");
    }

    private static String response(tools.jackson.databind.JsonNode... statements) {
        return JSON.writeValueAsString(Map.of("summary", "Ausbildungsportal", "statementProposals", List.of(statements),
                "preservedStatementIds", List.of(), "questionProposals", List.of(), "preservedQuestionIds", List.of(),
                "uncoveredSourceRefs", List.of(), "conflictCandidates", List.of()));
    }

    private static tools.jackson.databind.JsonNode proposal(String wording, String provenance, String condition, List<String> quotes) {
        var result = JSON.createObjectNode().put("wording", wording).put("provenance", provenance);
        var sourceSpans = result.putArray("sourceSpans");
        for (String quote : quotes) {
            int start = ORIGINAL.indexOf(quote);
            sourceSpans.addObject().put("start", start).put("end", start + quote.length()).put("exactText", quote);
        }
        Stream.of("architectureLinks", "questionDependencies").forEach(result::putArray);
        if (condition == null) result.putNull("conditionalValidity");
        else result.put("conditionalValidity", condition);
        return result;
    }
}
