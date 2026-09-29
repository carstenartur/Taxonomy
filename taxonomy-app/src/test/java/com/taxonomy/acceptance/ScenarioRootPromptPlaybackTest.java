package com.taxonomy.acceptance;

import com.taxonomy.analysis.service.PromptTemplateService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/** Real root template and strict scenario transport must agree on the assessment boundary. */
class ScenarioRootPromptPlaybackTest {
    private final ObjectMapper json = new ObjectMapper();

    private static JsonNode rootRule(ScenarioLlmPlayback playback) {
        for (var rule : playback.fixture().path("replies"))
            if (rule.path("keys").size() == 1 && "BP".equals(rule.path("keys").get(0).asText())) return rule;
        throw new AssertionError("Missing BP root fixture");
    }

    private static String rendered(ScenarioLlmPlayback playback) {
        var templates = new PromptTemplateService();
        templates.loadDefaults();
        return templates.renderRootPrompt("BP", playback.fixture().at("/requirement/text").asText(),
                "BP: Business Processes\n");
    }

    @Test
    void realRootPromptMatchesFixtureAndPreservesAuthoredScore() throws Exception {
        var playback = ScenarioLlmPlayback.flood();
        var prompt = rendered(playback);
        var answer = json.readTree(json.readTree(playback.respond(prompt))
                .at("/choices/0/message/content").asText());
        assertEquals(rootRule(playback).path("answers").path("BP").path("score").asInt(),
                answer.path("BP").path("score").asInt());
        assertTrue(playback.failures().isEmpty());
    }

    @Test
    void renderedRootPromptStillRejectsChangedFullSourceAndUnknownScope() throws Exception {
        var playback = ScenarioLlmPlayback.flood();
        var prompt = rendered(playback);
        var original = playback.fixture().at("/requirement/text").asText();
        assertEquals("Unknown scenario requirement", assertThrows(IllegalArgumentException.class,
                () -> playback.respond(prompt.replace(original, original + " changed"))).getMessage());
        assertEquals("Unknown root question", assertThrows(IllegalArgumentException.class,
                () -> playback.respond(prompt.replace("EXACTLY these keys: BP", "EXACTLY these keys: BP-9999"))).getMessage());
        assertEquals(2, playback.failures().size());
    }

    @Test
    void renderedRootPromptRetainsLaterRequirementParagraph() throws Exception {
        var fixture = (tools.jackson.databind.node.ObjectNode) ScenarioLlmPlayback.flood().fixture();
        var source = fixture.at("/requirement/text").asText() + "\n\nRetain this later paragraph.";
        ((tools.jackson.databind.node.ObjectNode) fixture.path("requirement")).put("text", source);
        var playback = new ScenarioLlmPlayback(fixture);
        var prompt = rendered(playback);
        assertDoesNotThrow(() -> playback.respond(prompt));
        assertEquals("Unknown scenario requirement", assertThrows(IllegalArgumentException.class,
                () -> playback.respond(prompt.replace("later paragraph", "changed paragraph"))).getMessage());
    }

    @Test
    void everyAuthoredScenarioRootUsesItsOwnValidatedReply() throws Exception {
        for (var fixture : java.util.List.of(ScenarioLlmPlayback.flood().fixture(),
                ReformulationScenarioCorpus.scenario("flood"),
                ReformulationScenarioCorpus.scenario("time-recording"),
                ReformulationScenarioCorpus.scenario("cross-taxonomy"))) {
            var playback = new ScenarioLlmPlayback(fixture);
            var templates = new PromptTemplateService();
            templates.loadDefaults();
            for (var rule : fixture.path("replies")) {
                if (rule.path("keys").size() != 1) continue;
                String code = rule.path("keys").get(0).asText();
                if (!java.util.Set.of("BP", "BR", "CI", "CO", "CP", "CR", "IP", "UA").contains(code)) continue;
                String prompt = templates.renderRootPrompt(code, fixture.at("/requirement/text").asText(),
                        code + ": " + code + "\n");
                var answer = json.readTree(json.readTree(playback.respond(prompt))
                        .at("/choices/0/message/content").asText());
                assertEquals(rule.path("answers").path(code).path("score").asInt(),
                        answer.path(code).path("score").asInt(), fixture.path("id").asText() + "/" + code);
            }
            assertTrue(playback.failures().isEmpty(), fixture.path("id").asText());
        }
    }

    @Test
    void adoptedRequirementRoutesRenderedRootQuestionToItsBoundSource() throws Exception {
        var playback = new ScenarioLlmPlayback(ReformulationScenarioCorpus.flood());
        String original = playback.fixture().at("/requirement/text").asText();
        String adopted = original + " Explicitly adopted wording.";
        playback.registerAdoptedSource(adopted);
        var templates = new PromptTemplateService();
        templates.loadDefaults();
        String prompt = templates.renderRootPrompt("BP", adopted, "BP: Business Processes\n");
        assertDoesNotThrow(() -> playback.respond(prompt));
        assertEquals(1, playback.calls().size());
        assertTrue(playback.failures().isEmpty());
    }

    @Test
    void existingChildBudgetPromptStillMatchesItsFixture() throws Exception {
        var playback = ScenarioLlmPlayback.flood();
        JsonNode rule = null;
        for (var candidate : playback.fixture().path("replies")) {
            if (candidate.path("task").asText().equals("categories")
                    && candidate.path("keys").get(0).asText().startsWith("BP-")) {
                rule = candidate;
                break;
            }
        }
        assertNotNull(rule);
        var codes = new java.util.ArrayList<String>();
        rule.path("keys").forEach(code -> codes.add(code.asText()));
        var templates = new PromptTemplateService();
        templates.loadDefaults();
        String prompt = templates.renderPrompt("BP", playback.fixture().at("/requirement/text").asText(),
                String.join("\n", codes.stream().map(code -> code + ": " + code).toList()) + "\n",
                rule.path("parentScore").asInt(), String.join(", ", codes));
        assertDoesNotThrow(() -> playback.respond(prompt));
        assertTrue(playback.failures().isEmpty());
    }
}
