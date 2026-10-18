package com.taxonomy.analysis.relations;

import com.taxonomy.model.RelationType;
import com.taxonomy.relations.service.RelationCompatibilityMatrix;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.taxonomy.dto.RelationSearchModel.*;
import static org.assertj.core.api.Assertions.*;

/** Exercises the transmitted prompts for every allowed endpoint family of the affected types. */
class RelationMeaningMatrixTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ORIGINAL = "Use the requested function for the authorised role, information or system. "
            + "Erhalte die Zuständigkeit und die Schutzbedingung unverändert.";
    private static final List<String> ROOTS = List.of("BP", "BR", "CI", "CM", "CO", "CP", "CR", "IP", "SY", "UA");
    private static final RelationCompatibilityMatrix MATRIX = new RelationCompatibilityMatrix();

    static Stream<Arguments> allowedEndpointFamilies() {
        return Stream.of(RelationType.SUPPORTS, RelationType.USES, RelationType.DEPENDS_ON, RelationType.REALIZES)
                .flatMap(type -> ROOTS.stream().flatMap(source -> MATRIX.allowedTargetRoots(source, type).stream().sorted()
                        .flatMap(target -> Stream.of(Direction.values()).flatMap(direction -> Stream.of(Phase.values())
                                .map(phase -> Arguments.of(type, source, target, direction, phase))))));
    }

    @ParameterizedTest(name = "{0} {1}->{2} {3} {4}")
    @MethodSource("allowedEndpointFamilies")
    void allowedEndpointFamiliesKeepTheirFunctionAndCanonicalDirection(RelationType type, String sourceRoot,
            String targetRoot, Direction direction, Phase phase) {
        assertThat(MATRIX.isCompatible(sourceRoot, targetRoot, type)).isTrue();
        Query query = query(type, sourceRoot, targetRoot, direction, phase);
        Decision proposal = proposal(query.candidates().getFirst());
        List<String> sent = new ArrayList<>();
        List<Decision> decisions = new RelationSearchProtocol(prompt -> {
            sent.add(prompt);
            return JSON.writeValueAsString(Map.of("decisions", List.of(phase == Phase.NAVIGATE ? proposal : Map.of(
                    "targetId", proposal.targetId(), "outcome", "VERIFIED", "rationale", proposal.rationale(), "question", ""))));
        }).evaluate(query);
        assertThat(sent).hasSize(1);
        String prompt = sent.getFirst();
        String meaning = prompt.lines().filter(line -> line.startsWith("Repository relation meaning (")).findFirst().orElseThrow();
        switch (type) {
            case SUPPORTS -> assertThat(meaning)
                    .as("The allowed target can also be a business role")
                    .doesNotContain("target business process")
                    .contains("source supplies a requested function that supports the canonical target");
            case USES -> assertThat(meaning)
                    .as("The allowed target can also be an information product or system")
                    .doesNotContain("target service")
                    .contains("source uses the canonical target for the scoped requested function")
                    .contains("not automatically a required dependency");
            case DEPENDS_ON -> assertThat(meaning)
                    .as("The allowed endpoints can also be systems and components")
                    .doesNotContain("source service", "target service")
                    .contains("source depends on the canonical target to perform the requested function")
                    .contains("generic technical usefulness alone is insufficient");
            case REALIZES -> assertThat(meaning)
                    .contains("source capability is made real by the target service",
                            "passive Capability -> Service convention", "not the active UML Realization convention");
            default -> throw new AssertionError("Unhandled tested relation type " + type);
        }
        assertThat(prompt).contains("OUTGOING = source contribution -> candidate",
                "INCOMING = candidate -> source contribution", "Apply this also during VERIFY");
        var input = JSON.readTree(prompt.substring(prompt.indexOf("INPUT\n") + 6));
        assertThat(input.path("type").asString()).isEqualTo(type.name());
        assertThat(input.path("direction").asString()).isEqualTo(direction.name());
        assertThat(input.path("phase").asString()).isEqualTo(phase.name());
        assertThat(input.path("original").asString()).isEqualTo(ORIGINAL);
        var contributionSource = input.path("contribution").path("source");
        var candidate = input.path("candidates").get(0);
        assertThat(contributionSource.path("root").asString()).isEqualTo(direction == Direction.OUTGOING ? sourceRoot : targetRoot);
        assertThat(candidate.path("root").asString()).isEqualTo(direction == Direction.OUTGOING ? targetRoot : sourceRoot);
        assertThat(contributionSource.path("description").asString()).isEqualTo(query.contribution().source().description());
        assertThat(candidate.path("description").asString()).isEqualTo(query.candidates().getFirst().description());
        assertThat(input.path("contribution").path("quote").asString()).isEqualTo(ORIGINAL);
        assertThat(input.path("contribution").path("condition").asString()).isEqualTo(query.contribution().condition());
        if (phase == Phase.VERIFY) assertThat(input.path("proposal")).isEqualTo(JSON.valueToTree(proposal));
        assertThat(decisions).containsExactly(new Decision(proposal.targetId(), phase == Phase.VERIFY ? Outcome.VERIFIED : Outcome.MATCH,
                proposal.contribution(), proposal.quote(), proposal.necessity(), proposal.condition(), proposal.alternativeGroup(),
                proposal.rationale(), proposal.question()));
        assertThat(RelationSearchProtocol.requestCharacters(prompt))
                .as("The actual request includes the response schema and UTF-8 bytes")
                .isGreaterThan(prompt.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(16_000);
    }

    @Test void changedMeaningsVersionBothThePromptAndRecoveryPolicy() {
        String prompt = RelationSearchProtocol.decisionPrompt(
                query(RelationType.SUPPORTS, "CR", "BR", Direction.OUTGOING, Phase.NAVIGATE), Map.of());
        assertThat(prompt).contains("Repository relation meaning (relation-semantics-v2): SUPPORTS.")
                .doesNotContain("relation-semantics-v1");
        assertThat(new RequirementRelationSearchService(null, null, null, null).recoveryPolicyFingerprint())
                .startsWith("relation-downwalk-v7/relation-semantics-v2/relation-source-context-v1/");
    }

    private static Query query(RelationType type, String sourceRoot, String targetRoot, Direction direction, Phase phase) {
        Node canonicalSource = new Node(sourceRoot + "-source", sourceRoot, "Canonical source " + sourceRoot,
                "The source retains its complete catalogue function and limitations.", false);
        Node canonicalTarget = new Node(targetRoot + "-target", targetRoot, "Canonical target " + targetRoot,
                "The target retains its complete catalogue function and limitations.", false);
        Node source = direction == Direction.OUTGOING ? canonicalSource : canonicalTarget;
        Node candidate = direction == Direction.OUTGOING ? canonicalTarget : canonicalSource;
        Contribution contribution = new Contribution(source, "Carry out the explicitly requested scoped function.",
                ORIGINAL, "Use only the authorised role and existing information.");
        return new Query(ORIGINAL, contribution, type.name(), direction, phase, List.of(candidate),
                phase == Phase.VERIFY ? proposal(candidate) : null);
    }

    private static Decision proposal(Node candidate) {
        return new Decision(candidate.id(), Outcome.MATCH, "Provide the requested functional connection.", ORIGINAL,
                Necessity.OPTIONAL, "If this additional view is selected.", "", "The original supports this bounded functional view.", "");
    }
}
