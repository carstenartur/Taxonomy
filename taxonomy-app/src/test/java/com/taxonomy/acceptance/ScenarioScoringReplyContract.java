package com.taxonomy.acceptance;

import com.taxonomy.analysis.service.PromptTemplateService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Contracts for stored answers at the real prompt/provider boundary. */
final class ScenarioScoringReplyContract {
    private static final ObjectMapper JSON = new ObjectMapper();

    static void testMissingCandidateCannotBecomeAnInventedZero() throws Exception {
        var fixture = ScenarioLlmPlayback.flood().fixture().deepCopy();
        var rule = bpChildren(fixture);
        ((ObjectNode) rule.path("answers")).remove("BP-1055");
        assertThatThrownBy(() -> new ScenarioLlmPlayback(fixture))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Missing answer");
    }

    static void testExplicitlyUnsupportedChildrenDoNotForceASpuriousLeaf() throws Exception {
        var fixture = ScenarioLlmPlayback.flood().fixture().deepCopy();
        var rule = fixture.path("replies").valueStream()
                .filter(candidate -> candidate.path("id").asText().equals("BR-1094-categories")).findFirst().orElseThrow();
        for (var answer : rule.path("answers").properties()) ((ObjectNode) answer.getValue()).put("score", 0);
        ((ObjectNode) rule.at("/answers/BR-1223")).put("reason",
                "F2: Service oversight does not request strengthening local institutions or rule of law; the broader public-service role needs no invented child role.");
        var playback = new ScenarioLlmPlayback(fixture);
        var response = JSON.readTree(JSON.readTree(playback.respond(render(playback, rule, 55)))
                .at("/choices/0/message/content").asText());
        assertThat(response.size()).isEqualTo(rule.path("keys").size());
        assertThat(response.valueStream()).allSatisfy(answer -> assertThat(answer.path("score").asDouble()).isZero());
    }

    static void testFractionalPromptBudgetDoesNotMatchItsTruncatedInteger() throws Exception {
        var fixture = fractionalFixture();
        var rule = bpChildren(fixture);
        var playback = new ScenarioLlmPlayback(fixture);
        var prompt = render(playback, rule, 0.5);
        var answer = JSON.readTree(JSON.readTree(playback.respond(prompt))
                .at("/choices/0/message/content").asText());
        assertThat(answer.at("/BP-1017/score").asDouble()).isEqualTo(0.125);
        assertThat(answer.at("/BP-1866/score").asDouble()).isEqualTo(0.375);
        assertThatThrownBy(() -> playback.respond(render(playback, rule, 0)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown response scope");
    }

    static void testFractionalBudgetMismatchCannotDisappearThroughIntegerCoercion() throws Exception {
        var fixture = fractionalFixture();
        ((ObjectNode) bpChildren(fixture).at("/answers/BP-1866")).put("score", 0.3);
        assertThatThrownBy(() -> new ScenarioLlmPlayback(fixture))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Budget mismatch");
    }

    static void testQuotedPercentagesAreNotStoredNumericAssessments() throws Exception {
        var fixture = fractionalFixture();
        ((ObjectNode) bpChildren(fixture).at("/answers/BP-1017")).put("score", "0.125");
        assertThatThrownBy(() -> new ScenarioLlmPlayback(fixture))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Invalid answer");
    }

    static void testActualClassificationPromptAndReplyAreReviewableTogether() throws Exception {
        var playback = ScenarioLlmPlayback.flood();
        var rule = bpChildren(playback.fixture());
        var prompt = render(playback, rule, rule.path("parentScore").asDouble());
        var response = playback.respond(prompt);
        var call = JSON.valueToTree(playback.calls().getFirst());
        assertThat(call.path("prompt").asText()).isEqualTo(prompt);
        assertThat(call.path("response").asText()).isEqualTo(response);
        assertThat(call.path("promptSha256").asText()).isEqualTo(ScenarioLlmPlayback.sha256(prompt));
        assertThat(call.path("responseSha256").asText()).isEqualTo(ScenarioLlmPlayback.sha256(response));
    }

    private static JsonNode fractionalFixture() throws Exception {
        var fixture = ScenarioLlmPlayback.flood().fixture().deepCopy();
        var rule = (ObjectNode) bpChildren(fixture);
        rule.put("parentScore", 0.5);
        var answers = rule.putObject("answers");
        for (var key : rule.path("keys")) {
            answers.putObject(key.asText()).put("score", 0)
                    .put("reason", "Protocol contract: this candidate receives none of the bounded test allocation.");
        }
        ((ObjectNode) answers.path("BP-1017")).put("score", 0.125);
        ((ObjectNode) answers.path("BP-1866")).put("score", 0.375);
        return fixture;
    }

    private static JsonNode bpChildren(JsonNode fixture) {
        for (var rule : fixture.path("replies")) {
            if (rule.path("id").asText().equals("BP-1060-categories")) return rule;
        }
        throw new AssertionError("Missing analytical-support prompt scope");
    }

    private static String render(ScenarioLlmPlayback playback, JsonNode rule, double budget) {
        var templates = new PromptTemplateService();
        templates.loadDefaults();
        var keys = new ArrayList<String>();
        rule.path("keys").forEach(key -> keys.add(key.asText()));
        return templates.renderPrompt(keys.getFirst().split("-")[0], playback.fixture().at("/requirement/text").asText(),
                String.join("\n", keys.stream().map(key -> key + ": " + key).toList()) + "\n",
                budget, String.join(", ", keys));
    }
}
