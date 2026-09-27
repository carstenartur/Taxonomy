package com.taxonomy.acceptance;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;

import static org.assertj.core.api.Assertions.*;

class ScenarioLlmPlaybackTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void civilianRepliesRejectUnknownFullSourceChildrenEdgesAndAnswerValues() throws Exception {
        var fixture = ReformulationCivilianCorpus.flood();
        var input = json.createObjectNode().put("nodeId", "BP-1060")
                .put("preservationContract", "Preserve all original anchors and child IDs verbatim; additions are unreviewed.");
        String original = fixture.at("/requirement/text").asText();
        input.putObject("baseline").put("originalText", original).put("originalTextHash", ScenarioLlmPlayback.sha256(original)).put("snapshotId", "selected-snapshot");
        input.putArray("children"); input.putArray("answers"); input.putArray("openDecisions"); input.putArray("directContributions");
        input.putObject("boundaryEdges").put("edge-1", "{\"id\":1,\"snapshotId\":\"selected-snapshot\",\"sourceCode\":\"BP-1017\",\"targetCode\":\"IP-1116\",\"relationType\":\"CONSUMES\",\"reviewStatus\":\"PROPOSED\"}");
        String prefix = com.taxonomy.analysis.reformulation.ReformulationPromptBuilder.interactiveTemplate() + "\nINPUT_DATA_JSON\n";
        assertThat(new ScenarioLlmPlayback(fixture).respond(prefix + input)).contains("choices");
        var wrongSource = input.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) wrongSource.path("baseline")).put("originalText", original + " changed")
                .put("originalTextHash", ScenarioLlmPlayback.sha256(original + " changed"));
        var wrongChild = input.deepCopy(); wrongChild.withArray("children").addObject().put("nodeId", "BP-9999");
        var missingEdge = input.deepCopy(); missingEdge.putObject("boundaryEdges");
        var wrongEdge = input.deepCopy(); wrongEdge.withObject("boundaryEdges").put("edge-1", input.at("/boundaryEdges/edge-1").asText().replace("IP-1116", "IP-9999"));
        var answer = input.deepCopy().put("preservationContract", "Only reword this affected section.");
        answer.withArray("openDecisions").addObject().put("id", "q-known");
        answer.withArray("answers").addObject().put("id", "a-1").put("questionId", "q-known").put("state", "ANSWERED")
                .putArray("values").add("Silently remove official warnings");
        for (var unknown : java.util.List.of(wrongSource, wrongChild, missingEdge, wrongEdge, answer)) {
            var playback = new ScenarioLlmPlayback(fixture);
            assertThatThrownBy(() -> playback.respond(prefix + unknown)).isInstanceOf(IllegalArgumentException.class);
            assertThat(playback.failures()).hasSize(1);
            assertThatThrownBy(() -> playback.verifyReformulationCoverage(0)).isInstanceOf(AssertionError.class);
        }
    }

    @Test void civilianAnswerIdentityStateAndHistoryAreExactInNodeAndReconciliationPrompts() throws Exception {
        var fixture = ReformulationCivilianCorpus.flood();
        String original = fixture.at("/requirement/text").asText();
        var input = json.createObjectNode().put("nodeId", "BP-1060").put("preservationContract", "Only reword this affected section.");
        input.putObject("baseline").put("originalText", original).put("originalTextHash", ScenarioLlmPlayback.sha256(original)).put("snapshotId", "selected-snapshot");
        input.putArray("children"); input.putArray("directContributions");
        input.putObject("boundaryEdges").put("edge-1", "{\"id\":1,\"snapshotId\":\"selected-snapshot\",\"sourceCode\":\"BP-1017\",\"targetCode\":\"IP-1116\",\"relationType\":\"CONSUMES\",\"reviewStatus\":\"PROPOSED\"}");
        input.set("openDecisions", json.readTree("""
                [{"id":"shared","key":{"subject":"stale-observation","dimension":"presentation","scope":"shared-source-choice"},"state":"ANSWERED","answerSchema":{"kind":"SINGLE_CHOICE"}},
                 {"id":"acquire","key":{"subject":"acquisition","dimension":"stale-age","scope":"flood-ingestion"},"state":"DEFERRED","answerSchema":{"kind":"NUMBER"}}]
                """));
        input.set("answers", json.readTree("""
                [{"id":"answer","questionId":"shared","state":"ANSWERED","values":["Retain last observation with timestamp"],"disposition":"ANSWER","otherText":null,"rationale":"Human civilian acceptance decision","supersedes":[]},
                 {"id":"defer","questionId":"acquire","state":"DEFERRED","values":[],"disposition":"DEFER","otherText":null,"rationale":"Obtain evidence for a maximum age","supersedes":[]}]
                """));
        var mutations = new ArrayList<tools.jackson.databind.node.ObjectNode>();
        var wrongQuestion = input.deepCopy(); ((tools.jackson.databind.node.ObjectNode) wrongQuestion.at("/answers/0")).put("questionId", "acquire"); mutations.add(wrongQuestion);
        var duplicate = input.deepCopy(); duplicate.withArray("answers").add(duplicate.at("/answers/0").deepCopy()); mutations.add(duplicate);
        var deferValue = input.deepCopy(); ((tools.jackson.databind.node.ArrayNode) deferValue.at("/answers/1/values")).add("invented"); mutations.add(deferValue);
        var wrongState = input.deepCopy(); ((tools.jackson.databind.node.ObjectNode) wrongState.at("/openDecisions/0")).put("state", "OPEN"); mutations.add(wrongState);
        var wrongScope = input.deepCopy(); ((tools.jackson.databind.node.ObjectNode) wrongScope.at("/openDecisions/0/key")).put("scope", "foreign"); mutations.add(wrongScope);
        var wrongDisposition = input.deepCopy(); ((tools.jackson.databind.node.ObjectNode) wrongDisposition.at("/answers/0")).put("disposition", "DEFER"); mutations.add(wrongDisposition);
        var wrongOther = input.deepCopy(); ((tools.jackson.databind.node.ObjectNode) wrongOther.at("/answers/0")).put("otherText", "invented"); mutations.add(wrongOther);
        var wrongRationale = input.deepCopy(); ((tools.jackson.databind.node.ObjectNode) wrongRationale.at("/answers/0")).put("rationale", "foreign answer"); mutations.add(wrongRationale);
        var wrongHistory = input.deepCopy(); ((tools.jackson.databind.node.ArrayNode) wrongHistory.at("/answers/0/supersedes")).add("unknown"); mutations.add(wrongHistory);
        for (boolean reconcile : java.util.List.of(false, true)) {
            String prefix = reconcile ? com.taxonomy.analysis.reformulation.ReconcilePromptBuilder.template() + "\nRECONCILIATION_DATA_JSON\n"
                    : com.taxonomy.analysis.reformulation.ReformulationPromptBuilder.interactiveTemplate() + "\nINPUT_DATA_JSON\n";
            var scopes = new ArrayList<>(mutations); scopes.addFirst(input);
            for (int index = 0; index < scopes.size(); index++) {
                var scope = scopes.get(index).deepCopy();
                if (reconcile) {
                    scope.set("questions", scope.remove("openDecisions"));
                    var sections = scope.putArray("sections");
                    for (String id : java.util.List.of("BP-1060","BP-1327","BP-1000","BP","IP-1102","IP-1005","IP-1000","IP")) sections.addObject().put("id", id);
                }
                var playback = new ScenarioLlmPlayback(fixture);
                if (index == 0) assertThat(playback.respond(prefix + scope)).contains("choices");
                else {
                    assertThatThrownBy(() -> playback.respond(prefix + scope)).as("mutation %s, reconcile=%s", index, reconcile).isInstanceOf(IllegalArgumentException.class);
                    assertThat(playback.failures()).hasSize(1);
                    assertThatThrownBy(() -> playback.verifyReformulationCoverage(0)).isInstanceOf(AssertionError.class);
                }
            }
        }
    }

    @Test void multilineSourceIsMatchedInFullIncludingLaterParagraphs() throws Exception {
        var fixture = (tools.jackson.databind.node.ObjectNode) ScenarioLlmPlayback.flood().fixture();
        ((tools.jackson.databind.node.ObjectNode) fixture.path("requirement"))
                .put("text", fixture.at("/requirement/text").asText() + "\n\nDo not lose this later paragraph.");
        var playback = new ScenarioLlmPlayback(fixture);
        String prompt = prompt(playback, fixture.path("replies").get(0));
        assertThat(playback.respond(prompt)).contains("choices");
        assertThatThrownBy(() -> playback.respond(prompt.replace("later paragraph", "changed paragraph")))
                .hasMessageContaining("Unknown scenario requirement");
        assertThat(playback.failures()).hasSize(1);
    }

    @Test void independentRootCanBeIrrelevantWithoutChangingSiblingBudgets() throws Exception {
        var fixture = ScenarioLlmPlayback.flood().fixture().deepCopy();
        var rule = (tools.jackson.databind.node.ObjectNode) fixture.path("replies").get(1);
        ((tools.jackson.databind.node.ObjectNode) rule.at("/answers/BR")).put("score", 0);
        var playback = new ScenarioLlmPlayback(fixture);
        assertThat(json.readTree(json.readTree(playback.respond(prompt(playback, rule)))
                .at("/choices/0/message/content").asText()).at("/BR/score").asInt()).isZero();
        var siblings = (tools.jackson.databind.node.ObjectNode) fixture.path("replies").get(8);
        ((tools.jackson.databind.node.ObjectNode) siblings.at("/answers/BP-1017")).put("score", 99);
        assertThatThrownBy(() -> new ScenarioLlmPlayback(fixture)).hasMessageContaining("Budget mismatch");
    }

    @Test void reformulationScopesMatchOutOfOrderAndIgnoreProviderRepairSuffix() throws Exception {
        var fixture = reformulationFixture();
        var playback = new ScenarioLlmPlayback(fixture);
        var rules = new ArrayList<tools.jackson.databind.JsonNode>();
        fixture.path("reformulationReplies").forEach(rules::add);
        Collections.reverse(rules);
        for (var rule : rules) {
            String prompt = reformulationPrompt(rule);
            var response = playback.respond(prompt);
            assertThat(json.readTree(json.readTree(response).at("/choices/0/message/content").asText()))
                    .isEqualTo(rule.path("response"));
            assertThat(playback.respond(prompt + "\nVALIDATION_ERRORS: \"repair\"" )).isEqualTo(response);
        }
        assertThat(playback.calls()).hasSize(8);
        assertThat(playback.failures()).isEmpty();
        playback.verifyReformulationCoverage(2);
    }

    @Test void realNodePromptBuilderAndResponseParserUseTheSameRemoteBoundary() throws Exception {
        var fixture = reformulationFixture();
        var rule = fixture.path("reformulationReplies").get(0);
        String source = rule.path("originalText").asText();
        var baseline = new com.taxonomy.reformulation.ReformulationBaseline(
                new com.taxonomy.reformulation.ReformulationBaseline.Scope("repo", "workspace", "main", 1, 1),
                1, source, ScenarioLlmPlayback.sha256(source), "snapshot", "{}",
                java.util.Map.of("reformulationPrompt", com.taxonomy.analysis.reformulation.ReformulationPromptBuilder.interactiveTemplate()),
                "en", "contract");
        var input = new com.taxonomy.reformulation.NodeSynthesisInput(baseline, "BP-1017", "BP-1060",
                "Acquire Data", java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.Map.of(),
                java.util.List.of(), java.util.List.of(),
                "Preserve all original anchors and child IDs verbatim; additions are unreviewed.");
        var playback = new ScenarioLlmPlayback(fixture);
        String prompt = new com.taxonomy.analysis.reformulation.ReformulationPromptBuilder(json).build(input, null);
        String raw = json.readTree(playback.respond(prompt)).at("/choices/0/message/content").asText();
        var parsed = new com.taxonomy.analysis.reformulation.ReformulationResponseParser(json).parse(raw, input);
        assertThat(parsed.nodeId()).isEqualTo("BP-1017");
        assertThat(parsed.summary()).contains("no browser", "30 days");
    }

    @Test void reformulationUnknownIdentitiesRemainFatalAfterCallerCatchesFailure() throws Exception {
        var fixture = reformulationFixture();
        String node = reformulationPrompt(fixture.path("reformulationReplies").get(2));
        String reword = reformulationPrompt(fixture.path("reformulationReplies").get(1));
        for (String unknown : new String[]{node.replace("5 minutes", "6 minutes"),
                node.replace("BP-1060", "BP-9999"), node.replace("BP-1017", "BP-9998"),
                node.replace("edge-notice", "edge-other"), node.replace("CO-1048", "CO-9999"),
                reword.replace("No correction required", "Correction required"),
                reword.replace("ANSWERED", "DEFERRED"),
                node + "\nINPUT_DATA_JSON\n{}"}) {
            var playback = new ScenarioLlmPlayback(fixture);
            assertThatThrownBy(() -> playback.respond(unknown)).isInstanceOf(IllegalArgumentException.class);
            assertThat(playback.failures()).hasSize(1);
            assertThatThrownBy(() -> playback.verifyCoverage(0)).isInstanceOf(AssertionError.class);
            assertThatThrownBy(() -> playback.verifyRelationCoverage(0)).isInstanceOf(AssertionError.class);
            assertThatThrownBy(() -> playback.verifyReformulationCoverage(0)).isInstanceOf(AssertionError.class);
        }
    }

    @Test void reformulationCorpusRejectsBlankAnswerIdentity() throws Exception {
        var fixture = reformulationFixture();
        ((tools.jackson.databind.node.ObjectNode) fixture.at("/reformulationReplies/1/answers/0")).put("id", "");
        assertThatThrownBy(() -> new ScenarioLlmPlayback(fixture)).hasMessageContaining("answer identity");
    }

    @Test void parentArtifactAlreadyRetainsPromptsRoutedToAdoptedSource() throws Exception {
        var playback = new ScenarioLlmPlayback(ReformulationCivilianCorpus.flood());
        String original = playback.fixture().at("/requirement/text").asText();
        String adopted = original + " Explicitly adopted wording.";
        String initial = prompt(playback, playback.fixture().path("replies").get(0));
        playback.respond(initial); playback.registerAdoptedSource(adopted);
        String subsequent = initial.replace(original, adopted); playback.respond(subsequent);
        assertThat(playback.prompts()).containsExactly(initial, subsequent);
        assertThat(playback.calls()).hasSize(2);
        assertThat(playback.failures()).isEmpty();
    }

    @Test void duplicateReformulationScopesAreRejectedBeforeTransport() throws Exception {
        var fixture = reformulationFixture();
        var rules = (tools.jackson.databind.node.ArrayNode) fixture.path("reformulationReplies");
        var duplicate = (tools.jackson.databind.node.ObjectNode) rules.get(0).deepCopy();
        duplicate.put("id", "same-scope-different-id");
        rules.add(duplicate);
        assertThatThrownBy(() -> new ScenarioLlmPlayback(fixture))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate");
    }

    private tools.jackson.databind.node.ObjectNode reformulationFixture() throws Exception {
        var fixture = (tools.jackson.databind.node.ObjectNode) ScenarioLlmPlayback.flood().fixture();
        var replies = fixture.putArray("reformulationReplies");
        for (String name : new String[]{"time-recording", "cross-taxonomy"}) {
            try (var stream = getClass().getResourceAsStream("/scenarios/reformulation-" + name + ".json")) {
                var authored = json.readTree(stream);
                assertThat(authored.path("responseProvenance").asText()).contains("Authored", "not external");
                for (var rule : authored.path("rules")) {
                    var copy = (tools.jackson.databind.node.ObjectNode) rule.deepCopy();
                    copy.put("originalText", authored.at("/requirement/text").asText());
                    replies.add(copy);
                }
            }
        }
        return fixture;
    }

    private String reformulationPrompt(tools.jackson.databind.JsonNode rule) {
        var input = json.createObjectNode();
        input.putObject("baseline").put("originalText", rule.path("originalText").asText())
                .put("originalTextHash", ScenarioLlmPlayback.sha256(rule.path("originalText").asText()));
        input.put("nodeId", rule.path("nodeId").asText());
        input.put("preservationContract", rule.path("task").asText().equals("REWORD")
                ? "Only reword this affected section. Preserve human wording and all retained evidence."
                : "Preserve all original anchors and child IDs verbatim; additions are unreviewed.");
        var children = input.putArray("children");
        rule.path("children").forEach(child -> children.addObject().put("nodeId", child.asText()));
        var sections = input.putArray("sections");
        rule.path("sections").forEach(section -> sections.addObject().put("id", section.asText()));
        var edges = input.putObject("boundaryEdges");
        rule.path("boundaryEdges").properties().forEach(edge -> edges.put(edge.getKey(), edge.getValue().toString()));
        input.set("answers", rule.path("answers"));
        return (rule.path("task").asText().equals("RECONCILE")
                ? com.taxonomy.analysis.reformulation.ReconcilePromptBuilder.template() + "\nRECONCILIATION_DATA_JSON\n"
                : com.taxonomy.analysis.reformulation.ReformulationPromptBuilder.interactiveTemplate() + "\nINPUT_DATA_JSON\n")
                + json.writeValueAsString(input);
    }

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

    @org.junit.jupiter.api.TestFactory
    java.util.stream.Stream<org.junit.jupiter.api.DynamicTest> relationProtocolContracts() {
        return java.util.Arrays.stream(RelationScenarioPlaybackContract.class.getDeclaredMethods())
                .filter(method -> method.getName().startsWith("test"))
                .map(method -> org.junit.jupiter.api.DynamicTest.dynamicTest(method.getName(), () -> {
                    try { method.invoke(null); }
                    catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                }));
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
