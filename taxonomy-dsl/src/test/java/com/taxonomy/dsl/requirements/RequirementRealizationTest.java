package com.taxonomy.dsl.requirements;

import com.taxonomy.dsl.command.ArchitectureCommand;
import com.taxonomy.dsl.command.ArchitectureDslCommands;
import com.taxonomy.dsl.command.ArchitectureSemanticPatch;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequirementRealizationTest {
    private static final Instant TIME = Instant.parse("2026-10-29T09:00:00Z");

    static String source(String text, int version) {
        return """
                # Original document annotation
                element recorder type Component {
                  title: "Recorder";
                }
                requirement PROJECT__REQ-1 {
                  title: "Observe events";
                  text: "%s";
                  x-project-key: "PROJECT";
                  x-requirement-key: "REQ-1";
                  x-version: %d;
                  x-content-hash: "%s";
                  x-foreign-evidence: "retain me";
                }
                """.formatted(text, version, RequirementRealization.textHash(text));
    }

    private static RequirementRealization.Review review(String text) {
        return new RequirementRealization.Review(1, RequirementRealization.textHash(text), "COMPLETE",
                "FOLLOWUP", "alice", TIME, "Recorder implements the reviewed requirement", List.of("element:recorder"));
    }

    @Test void explicitReviewBindsExactVersionTextAndActualDependency() {
        String original = source("Record events", 1);
        assertThat(RequirementRealization.evaluate(original, "PROJECT__REQ-1").state()).isEqualTo(RequirementRealization.State.OPEN);
        String recorded = RequirementRealization.record(original, "PROJECT__REQ-1", review("Record events"));
        var assessment = RequirementRealization.evaluate(recorded, "PROJECT__REQ-1");
        assertThat(assessment.state()).isEqualTo(RequirementRealization.State.COMPLETE);
        assertThat(assessment.reviewed().requirementVersion()).isEqualTo(1);
        assertThat(assessment.reviewed().actor()).isEqualTo("alice");
        assertThat(assessment.reviewed().dependencies()).extracting(RequirementRealization.Dependency::id).containsExactly("element:recorder");
        assertThat(recorded).startsWith("# Original document annotation\n").contains("x-foreign-evidence: \"retain me\"");
        assertThat(RequirementTrace.contributions(ArchitectureSemanticPatch.index(recorded).get("requirement:PROJECT__REQ-1")))
                .containsExactly(new RequirementTrace.Contribution("FOLLOWUP", 1, RequirementRealization.textHash("Record events")));
    }

    @Test void changedAndMissingDependenciesRequireAReviewWithoutDiscardingOldEvidence() {
        String reviewed = RequirementRealization.record(source("Record events", 1), "PROJECT__REQ-1", review("Record events"));
        String changed = new ArchitectureDslCommands().apply(reviewed,
                new ArchitectureCommand.UpdateArchitectureElement("recorder", "Component", Map.of("title", "Changed recorder"))).dsl();
        var assessment = RequirementRealization.evaluate(changed, "PROJECT__REQ-1");
        assertThat(assessment.state()).isEqualTo(RequirementRealization.State.REVIEW_REQUIRED);
        assertThat(assessment.changedDependencies()).containsExactly("element:recorder");
        assertThat(assessment.reviewed().occurredAt()).isEqualTo(TIME);
        String missing = ArchitectureSemanticPatch.replace(reviewed, ArchitectureSemanticPatch.index(reviewed).get("element:recorder"), null);
        assertThat(RequirementRealization.evaluate(missing, "PROJECT__REQ-1").state()).isEqualTo(RequirementRealization.State.REVIEW_REQUIRED);
    }

    @Test void retainedEvidenceBecomesStaleForANewTextVersionAndExactWhitespaceMatters() {
        String reviewed = RequirementRealization.record(source("Record events", 1), "PROJECT__REQ-1", review("Record events"));
        String next = source("Record every event", 2);
        var generated = ArchitectureSemanticPatch.index(next).get("requirement:PROJECT__REQ-1");
        var old = ArchitectureSemanticPatch.index(reviewed).get("requirement:PROJECT__REQ-1");
        next = ArchitectureSemanticPatch.replace(next, generated, RequirementRealization.retain(generated, old));
        assertThat(RequirementRealization.evaluate(next, "PROJECT__REQ-1").state()).isEqualTo(RequirementRealization.State.REVIEW_REQUIRED);
        assertThat(RequirementRealization.textHash("Record events ")).isNotEqualTo(RequirementRealization.textHash("Record events"));
        assertThatThrownBy(() -> RequirementRealization.record(source("Record events ", 1), "PROJECT__REQ-1", review("Record events")))
                .isInstanceOf(ArchitectureDslCommands.CommandProblem.class).hasMessageContaining("exact text changed");
    }

    @Test void malformedOrUnknownEvidenceNeverBecomesACompletionAndIsRetained() {
        String reviewed = RequirementRealization.record(source("Record events", 1), "PROJECT__REQ-1", review("Record events"));
        String unknown = reviewed.replace("x-realization-schema: 1", "x-realization-schema: 99");
        assertThat(RequirementRealization.evaluate(unknown, "PROJECT__REQ-1").state()).isEqualTo(RequirementRealization.State.INVALID_EVIDENCE);
        String malformed = reviewed.replace(TIME.toString(), "not-an-instant");
        assertThat(RequirementRealization.evaluate(malformed, "PROJECT__REQ-1").state()).isEqualTo(RequirementRealization.State.INVALID_EVIDENCE);
        assertThatThrownBy(() -> RequirementRealization.record(source("Record events", 1), "PROJECT__REQ-1",
                new RequirementRealization.Review(1, RequirementRealization.textHash("Record events"), "COMPLETE", "FOLLOWUP", "alice", TIME, "No evidence", List.of())))
                .isInstanceOf(ArchitectureDslCommands.CommandProblem.class);
    }

    @Test void semanticReviewCanBeUndoneWithoutChangingTheBusinessRequirement() {
        String before = source("Record events", 1);
        var commands = new ArchitectureDslCommands();
        String after = commands.apply(before, new ArchitectureCommand.ReviewRequirementRealization("PROJECT__REQ-1", review("Record events"))).dsl();
        assertThat(commands.inverse(after, before, after).dsl()).isEqualTo(before);
    }
}
