package com.taxonomy.acceptance;

import com.taxonomy.analysis.relations.RelationSearchProtocol;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static com.taxonomy.dto.RelationSearchModel.*;
import static org.assertj.core.api.Assertions.*;

class ScenarioRelationExclusionsTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Node APPLICATION = new Node("UA-1580", "UA", "Hydrographic Applications", "", false);
    private static final Node PICTURE = new Node("IP-1116", "IP", "Recognized Environmental Pictures", "", false);
    private static final Node WARNING = new Node("IP-2130", "IP", "Alerts and Warnings", "", false);

    @Test void missingNegativeAnswerCannotBecomeAGenericRejection() throws Exception {
        var fixture = fixtureWithRule(false, "IP-1069", true);
        var playback = new ScenarioLlmPlayback(fixture);
        assertThatThrownBy(() -> evaluate(playback, APPLICATION, "default", PICTURE, Direction.OUTGOING))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing authored relation decision: UA-1580@default:PRODUCES:OUTGOING:NAVIGATE:IP-1116");
        assertThat(playback.failures()).hasSize(1);
    }

    @Test void explicitUncertaintyRetainsItsAuthoredQuestion() throws Exception {
        var playback = new ScenarioLlmPlayback(fixtureWithRule(true, "IP-1116", false));
        var answer = evaluate(playback, APPLICATION, "default", PICTURE, Direction.OUTGOING);
        assertThat(answer.outcome()).isEqualTo(Outcome.UNRESOLVED);
        assertThat(answer.question()).isEqualTo("Does this application create a new information product or only display the published input?");
        assertThat(answer.rationale()).isEqualTo("The supplied input does not decide whether the selected application authors a new picture.");
    }

    @Test void uncertainConcreteCandidateIsExploredBeforeItsQuestionIsRecorded() throws Exception {
        var playback = new ScenarioLlmPlayback(fixtureWithRule(true, "IP-1116", false));
        var root = new Node("IP", "IP", "Information Products", "", true);
        var navigation = evaluate(playback, APPLICATION, "default", root, Direction.OUTGOING);
        assertThat(navigation.outcome()).isEqualTo(Outcome.DESCEND);
        assertThat(navigation.rationale()).contains("authors a new picture");
        assertThat(navigation.question()).isEmpty();
        assertThat(evaluate(playback, APPLICATION, "default", PICTURE, Direction.OUTGOING).outcome()).isEqualTo(Outcome.UNRESOLVED);
    }

    @Test void subtreeScopeUsesCatalogueAncestryAndDoesNotCoverEveryInformationProduct() throws Exception {
        var playback = new ScenarioLlmPlayback(fixtureWithRule(false, "IP-1069", true));
        var answer = evaluate(playback, APPLICATION, "default", WARNING, Direction.OUTGOING);
        assertThat(answer.outcome()).isEqualTo(Outcome.REJECT);
        assertThat(answer.rationale()).isEqualTo("F1 requests reading published warnings; it does not grant this application warning authorship.");
        assertThatThrownBy(() -> evaluate(playback, APPLICATION, "default", PICTURE, Direction.OUTGOING))
                .hasMessageContaining("Missing authored relation decision");
    }

    @Test void aReasonForOneDirectionCannotDecideTheOppositeDirection() throws Exception {
        var playback = new ScenarioLlmPlayback(fixtureWithRule(false, "IP-1116", false));
        assertThatThrownBy(() -> evaluate(playback, APPLICATION, "default", PICTURE, Direction.INCOMING))
                .hasMessageContaining("PRODUCES:INCOMING:NAVIGATE:IP-1116");
    }

    @Test void publicReadingExclusionDoesNotAnswerTheSeparateReliableDeliveryQuestion() throws Exception {
        var fixture = fixtureWithRule(false, "IP-2130", false);
        var rule = (ObjectNode) fixture.at("/relationPlayback/exclusions/rules/0");
        rule.putArray("sourceIds").add("BP-1535");
        rule.putArray("sourceContributionIds").add("public-information");
        var playback = new ScenarioLlmPlayback(fixture);
        var process = new Node("BP-1535", "BP", "Information Retrieval and Dissemination", "", false);
        assertThat(evaluate(playback, process, "public-information", WARNING, Direction.OUTGOING).outcome()).isEqualTo(Outcome.REJECT);
        assertThatThrownBy(() -> evaluate(playback, process, "reliable-delivery", WARNING, Direction.OUTGOING))
                .hasMessageContaining("BP-1535@reliable-delivery:PRODUCES:OUTGOING:NAVIGATE:IP-2130");
    }

    @Test void positiveMatchCannotHideAnAuthoredRejectionOfTheSameEndpoint() throws Exception {
        var fixture = fixtureWithRule(false, PICTURE.id(), false);
        rule(fixture, false).put("type", "CONSUMES");
        assertThatThrownBy(() -> new ScenarioLlmPlayback(fixture))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContainingAll("Conflicting authored relation decisions", "authored-test-scope",
                        "UA-1580:CONSUMES:IP-1116", "NAVIGATE", "IP-1116");
    }

    @Test void positiveMatchCannotHideAnAuthoredQuestionAtTheSameEndpoint() throws Exception {
        var fixture = fixtureWithRule(true, PICTURE.id(), false);
        rule(fixture, true).put("type", "CONSUMES");
        assertThatThrownBy(() -> new ScenarioLlmPlayback(fixture))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContainingAll("Conflicting authored relation decisions", "authored-test-scope",
                        "UA-1580:CONSUMES:IP-1116", "UNRESOLVED");
    }

    @Test void incomingVerificationCannotHideAnAuthoredRejection() throws Exception {
        var fixture = fixtureWithRule(false, APPLICATION.id(), false);
        var rule = rule(fixture, false);
        rule.put("type", "CONSUMES").put("direction", "INCOMING");
        rule.putArray("sourceIds").add(PICTURE.id());
        rule.putArray("phases").add("VERIFY");
        assertThatThrownBy(() -> new ScenarioLlmPlayback(fixture))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContainingAll("Conflicting authored relation decisions", "authored-test-scope",
                        "UA-1580:CONSUMES:IP-1116", "INCOMING", "VERIFY");
    }

    @Test void positiveDescendCannotHideARejectedAncestor() throws Exception {
        var fixture = fixtureWithRule(false, "IP-1000", false);
        rule(fixture, false).put("type", "CONSUMES");
        assertThatThrownBy(() -> new ScenarioLlmPlayback(fixture))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContainingAll("Conflicting authored relation decisions", "authored-test-scope",
                        "UA-1580:CONSUMES:IP-1116", "NAVIGATE", "IP-1000");
    }

    @Test void positiveDescendCannotHideARejectedSubtree() throws Exception {
        var fixture = fixtureWithRule(false, "IP-1000", true);
        rule(fixture, false).put("type", "CONSUMES");
        assertThatThrownBy(() -> new ScenarioLlmPlayback(fixture))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContainingAll("Conflicting authored relation decisions", "authored-test-scope",
                        "UA-1580:CONSUMES:IP-1116", "NAVIGATE");
    }

    @Test void positiveDescendCannotHideAnUnresolvedAncestor() throws Exception {
        var fixture = fixtureWithRule(true, "IP-1000", false);
        rule(fixture, true).put("type", "CONSUMES");
        assertThatThrownBy(() -> new ScenarioLlmPlayback(fixture))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContainingAll("Conflicting authored relation decisions", "authored-test-scope",
                        "UA-1580:CONSUMES:IP-1116", "UNRESOLVED", "IP-1000");
    }

    @Test void verificationOnlyRejectionOfAnotherEndpointDoesNotPruneNavigation() throws Exception {
        var fixture = fixtureWithRule(false, "IP-1000", false);
        rule(fixture, false).put("type", "CONSUMES").putArray("phases").add("VERIFY");
        var playback = new ScenarioLlmPlayback(fixture);
        assertThat(evaluate(playback, APPLICATION, "default", PICTURE, Direction.OUTGOING, "CONSUMES").outcome())
                .isEqualTo(Outcome.MATCH);
    }

    @Test void warningInputRejectionDoesNotConflictWithTheSeparateForwardedWarningOutput() throws Exception {
        var fixture = fixtureWithRule(false, "BP-1535", false);
        var rule = rule(fixture, false);
        rule.put("direction", "INCOMING");
        rule.putArray("sourceIds").add(WARNING.id());
        var playback = new ScenarioLlmPlayback(fixture);
        var dissemination = new Node("BP-1535", "BP", "Information Retrieval and Dissemination", "", false);
        assertThat(evaluate(playback, WARNING, "default", dissemination, Direction.INCOMING).outcome())
                .isEqualTo(Outcome.REJECT);
        assertThat(evaluate(playback, WARNING, "forwarded-warning-notification", dissemination, Direction.INCOMING).outcome())
                .isEqualTo(Outcome.MATCH);
    }

    @Test void openObservationInputIdentityRemainsLegitimateBesideThePublishedWarningInput() throws Exception {
        var fixture = fixtureWithRule(true, PICTURE.id(), false);
        var rule = rule(fixture, true);
        rule.put("type", "CONSUMES");
        rule.putArray("sourceIds").add("BP-1017");
        var playback = new ScenarioLlmPlayback(fixture);
        var acquisition = new Node("BP-1017", "BP", "Data Acquisition", "", false);
        assertThat(evaluate(playback, acquisition, "default", PICTURE, Direction.OUTGOING, "CONSUMES").outcome())
                .isEqualTo(Outcome.UNRESOLVED);
        assertThat(evaluate(playback, acquisition, "default", WARNING, Direction.OUTGOING, "CONSUMES").outcome())
                .isEqualTo(Outcome.MATCH);
    }

    private static ObjectNode rule(JsonNode fixture, boolean open) {
        return (ObjectNode) fixture.at("/relationPlayback/exclusions/" + (open ? "openScopes" : "rules") + "/0");
    }

    private static JsonNode fixtureWithRule(boolean open, String candidateId, boolean subtree) throws Exception {
        JsonNode fixture;
        try (var input = ScenarioLlmPlayback.class.getResourceAsStream("/scenarios/flood-information.json")) {
            fixture = JSON.readTree(input);
        }
        var exclusions = ((ObjectNode) fixture.path("relationPlayback")).putObject("exclusions");
        exclusions.put("schemaVersion", 1).put("scenarioId", fixture.path("id").asString())
                .put("requirementSha256", ScenarioLlmPlayback.sha256(fixture.at("/requirement/text").asString()));
        exclusions.putArray("rules"); exclusions.putArray("openScopes");
        var rule = exclusions.withArray(open ? "openScopes" : "rules").addObject();
        rule.put("id", "authored-test-scope").put("type", "PRODUCES").put("direction", "OUTGOING");
        rule.putArray("sourceIds").add(APPLICATION.id()); rule.putArray("sourceContributionIds").add("default");
        rule.putArray("phases").add("NAVIGATE"); rule.putArray("quoteIds").add("F1");
        rule.putArray("candidateIds"); rule.putArray("candidateSubtreeIds");
        rule.withArray(subtree ? "candidateSubtreeIds" : "candidateIds").add(candidateId);
        rule.put("rationale", open ? "The supplied input does not decide whether the selected application authors a new picture."
                : "F1 requests reading published warnings; it does not grant this application warning authorship.");
        if (open) rule.put("question", "Does this application create a new information product or only display the published input?");
        return fixture;
    }

    private static Decision evaluate(ScenarioLlmPlayback playback, Node source, String partId, Node target, Direction direction) {
        return evaluate(playback, source, partId, target, direction, "PRODUCES");
    }

    private static Decision evaluate(ScenarioLlmPlayback playback, Node source, String partId, Node target, Direction direction, String type) {
        String original = playback.fixture().at("/requirement/text").asString();
        JsonNode part = playback.fixture().at("/relationPlayback/sourceContributions").valueStream()
                .filter(value -> source.id().equals(value.path("nodeId").asString())
                        && partId.equals(value.path("contributionId").asString("default"))).findFirst().orElseThrow();
        var protocol = new RelationSearchProtocol(prompt -> JSON.readTree(playback.respond(prompt)).at("/choices/0/message/content").asString());
        var contribution = new Contribution(source, part.path("text").asString(), part.path("quote").asString(), part.path("condition").asString());
        return protocol.evaluate(new Query(original, contribution, type, direction, Phase.NAVIGATE, List.of(target), null)).getFirst();
    }
}
