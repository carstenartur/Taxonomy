package com.taxonomy.acceptance;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;

import static org.assertj.core.api.Assertions.*;

class ScenarioLlmPlaybackTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void matchesSemanticScopesInAnyOrderAndKeepsRawProviderEnvelope() throws Exception {
        var playback = ScenarioLlmPlayback.flood();
        var fixture = playback.fixture();
        var rules = new ArrayList<tools.jackson.databind.JsonNode>();
        fixture.get("replies").forEach(rules::add);
        Collections.reverse(rules);
        for (var rule : rules) {
            String request = prompt(playback, rule);
            String first = playback.respond(request);
            assertThat(playback.respond(request)).isEqualTo(first);
            var envelope = json.readTree(first);
            var answer = json.readTree(envelope.at("/choices/0/message/content").asText());
            assertThat(answer.size()).isEqualTo(rule.get("keys").size());
            rule.get("answers").properties().forEach(entry ->
                    assertThat(answer.get(entry.getKey())).isEqualTo(entry.getValue()));
        }
        assertThat(playback.calls()).hasSize(rules.size() * 2);
        playback.verifyCoverage(2);
    }

    @Test void rejectsUnknownScenarioKeysBudgetAndTaskWithoutLiveFallback() throws Exception {
        var playback = ScenarioLlmPlayback.flood();
        String known = prompt(playback, playback.fixture().get("replies").get(0));
        for (String unknown : new String[]{known.replace("CIV-FLOOD-001", "OTHER"),
                known.replace("these keys: BP", "these keys: BP, XX-9999"),
                known.replace("score of 100", "score of 99"),
                known.replace("distribute the parent relevance score", "summarise the parent relevance score")}) {
            assertThatThrownBy(() -> playback.respond(unknown))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(playback.failures()).hasSize(4);
        assertThatThrownBy(() -> playback.verifyCoverage(1)).isInstanceOf(AssertionError.class);
    }

    @Test void rejectsDuplicateResponseScopesAtLoadTime() throws Exception {
        var fixture = ScenarioLlmPlayback.flood().fixture().deepCopy();
        ((tools.jackson.databind.node.ArrayNode) fixture.get("replies"))
                .add(fixture.get("replies").get(0).deepCopy());
        assertThatThrownBy(() -> new ScenarioLlmPlayback(fixture))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate");
    }

    static String prompt(ScenarioLlmPlayback playback, tools.jackson.databind.JsonNode rule) {
        var keys = new ArrayList<String>();
        rule.get("keys").forEach(key -> keys.add(key.asText()));
        Collections.reverse(keys);
        return (rule.get("task").asText().equals("products")
                ? "You are evaluating concrete Information Products. Score every candidate independently from 0 to 100.\n"
                : "Analyse and distribute the parent relevance score of " + rule.get("parentScore").asInt() + "\n")
                + "Business Requirement: " + playback.fixture().at("/requirement/text").asText()
                + "\n\nNodes to evaluate:\n"
                + "Respond ONLY with a valid JSON object using EXACTLY these keys: " + String.join(", ", keys)
                + "\nEach value must be an object.";
    }
}
