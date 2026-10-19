package com.taxonomy.acceptance;

import com.taxonomy.analysis.relations.RelationSearchProtocol;
import com.taxonomy.architecture.pipeline.ArchitectureViewContext;
import com.taxonomy.architecture.pipeline.EvidenceRelationProjection;
import com.taxonomy.dto.RelationSearchReport;
import com.taxonomy.dto.RelationSearchModel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Actual authored raw replies must satisfy endpoint binding before the expensive browser scenario. */
class ScenarioPlaybackEvidenceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void everyClaimPreservesItsSelectedCounterpartTextAndQuoteInBothDirections() throws Exception {
        var corpus = capture();
        var checks = new ArrayList<Executable>();
        for (var claim : corpus.fixture().at("/relationPlayback/claims")) {
            List<Edge> edges = corpus.edges().get(key(claim));
            for (Edge edge : edges) {
                String role = edge.direction() == Direction.OUTGOING ? "target" : "source";
                JsonNode counterpart = part(corpus.fixture(), claim, role);
                String label = key(claim) + " " + edge.direction();
                checks.add(() -> assertEquals(counterpart.path("text").asText(), edge.evidence().contribution(), label));
                checks.add(() -> assertEquals(counterpart.path("quote").asText(), edge.evidence().quote(), label));
                checks.add(() -> assertEquals(claim.path("necessity").asText(), edge.evidence().necessity().name(), label));
                checks.add(() -> assertEquals(claim.path("condition").asText(), edge.evidence().condition(), label));
                checks.add(() -> assertEquals(claim.path("alternativeGroup").asText(), edge.evidence().alternativeGroup(), label));
            }
        }
        assertAll(checks);
    }

    @Test void everySelectedAuthoredRelationPassesTheOriginalIndependentEndpointGate() throws Exception {
        var corpus = capture();
        List<Edge> all = corpus.edges().values().stream().flatMap(List::stream).toList();
        var checks = new ArrayList<Executable>();
        for (var claim : corpus.fixture().at("/relationPlayback/claims")) {
            if (!claim.path("necessity").asText().equals("REQUIRED")) continue;
            checks.add(() -> ScenarioArchitectureQuality.verifyAuthoredRelationshipEvidence(
                    claim.path("sourceId").asText(), claim.path("type").asText(), claim.path("targetId").asText(),
                    corpus.edges().get(key(claim)), all, corpus.sources()));
        }
        assertEquals(36, checks.size(), "Keep every relation selected by the full scenario");
        assertAll(checks);
    }

    @Test void authoredLocalContributionsRetainAllTwentyIndependentObligations() throws Exception {
        var corpus = capture();
        JsonNode reference;
        try (var input = getClass().getResourceAsStream("/scenarios/flood-quality-reference.json")) {
            assertNotNull(input); reference = JSON.readTree(input);
        }
        Map<String, String> clauses = new LinkedHashMap<>();
        reference.at("/requirement/clauses").properties().forEach(entry -> clauses.put(entry.getKey(), entry.getValue().asText()));
        var witnesses = new ArrayList<ScenarioArchitectureQuality.Witness>();
        corpus.sources().values().forEach(source -> source.contributions().forEach(contribution -> witnesses.add(
                new ScenarioArchitectureQuality.Witness("ELEMENT", source.node().id(), source.node().id(),
                        contribution.text(), contribution.quote(), contribution.condition()))));
        var checks = new ArrayList<Executable>();
        for (var constraint : reference.path("coverageConstraints")) checks.add(() ->
                assertEquals("PASS", ScenarioArchitectureQuality.verifyConstraint(constraint, witnesses, clauses).get("verdict")));
        assertEquals(20, checks.size()); assertAll(checks);
    }

    @Test void verificationStillRejectsChangedCounterpartTextQuoteNecessityAndCondition() throws Exception {
        var playback = ScenarioLlmPlayback.flood(); var fixture = playback.fixture();
        var protocol = protocol(playback); String original = fixture.at("/requirement/text").asText();
        JsonNode claim = fixture.at("/relationPlayback/claims").valueStream().filter(value ->
                value.path("sourceId").asText().equals("BP-1535") && value.path("targetId").asText().equals("IP-2130")
                        && value.path("type").asText().equals("CONSUMES")).findFirst().orElseThrow();
        var source = node("BP-1535"); var target = node("IP-2130");
        var selected = protocol.contributions(original, List.of(source)).getFirst();
        var contribution = selected.contributions().stream().filter(value -> value.text().equals(part(fixture, claim, "source").path("text").asText())).findFirst().orElseThrow();
        var proposed = protocol.evaluate(new Query(original, contribution, "CONSUMES", Direction.OUTGOING,
                Phase.NAVIGATE, List.of(target), null)).getFirst();
        String changedQuote = original.equals(proposed.quote())
                ? "F2: Support administrative oversight of the service." : original;
        assertNotEquals(proposed.quote(), changedQuote);
        for (int mutation = 0; mutation < 4; mutation++) {
            var changed = new Decision(proposed.targetId(), Outcome.MATCH,
                    mutation == 0 ? "Unrelated endpoint function" : proposed.contribution(),
                    mutation == 1 ? changedQuote : proposed.quote(),
                    mutation == 2 ? Necessity.OPTIONAL : proposed.necessity(),
                    mutation == 3 ? "An invented condition" : proposed.condition(), proposed.alternativeGroup(), proposed.rationale(), "");
            assertEquals(Outcome.REJECT, protocol.evaluate(new Query(original, contribution, "CONSUMES", Direction.OUTGOING,
                    Phase.VERIFY, List.of(target), changed)).getFirst().outcome());
        }
    }

    @Test void actualProjectionRetainsEveryLocallyBoundObligationForAlreadyRelatedElements() throws Exception {
        var corpus = capture();
        List<Edge> edges = corpus.edges().values().stream().flatMap(List::stream).toList();
        String original = corpus.fixture().at("/requirement/text").asText();
        var report = new RelationSearchReport(1, ScenarioLlmPlayback.sha256(original), "protocol-binding-test",
                List.copyOf(corpus.sources().values()), new Result(edges, List.of(), List.of(), 0, 0, 0),
                0, 0, 0, List.of(), "");
        var context = new ArchitectureViewContext(Map.of(), original, 0, null);
        EvidenceRelationProjection.apply(context, report);
        assertEquals(36, context.getRelationships().size());
        Map<String, String> reasons = new LinkedHashMap<>();
        context.getElements().forEach(element -> reasons.put(element.getNodeCode(), element.getPresenceReason()));
        var witnesses = new ArrayList<ScenarioArchitectureQuality.Witness>();
        corpus.sources().values().forEach(source -> source.contributions().forEach(contribution -> {
            if (reasons.getOrDefault(source.node().id(), "").contains(contribution.text()))
                witnesses.add(new ScenarioArchitectureQuality.Witness("ELEMENT", source.node().id(), source.node().id(),
                        contribution.text(), contribution.quote(), reasons.get(source.node().id()).contains(contribution.condition())
                                ? contribution.condition() : ""));
        }));
        JsonNode reference;
        try (var input = getClass().getResourceAsStream("/scenarios/flood-quality-reference.json")) {
            assertNotNull(input); reference = JSON.readTree(input);
        }
        Map<String, String> clauses = new LinkedHashMap<>();
        reference.at("/requirement/clauses").properties().forEach(entry -> clauses.put(entry.getKey(), entry.getValue().asText()));
        var checks = new ArrayList<Executable>();
        for (var constraint : reference.path("coverageConstraints")) checks.add(() ->
                assertEquals("PASS", ScenarioArchitectureQuality.verifyConstraint(constraint, witnesses, clauses).get("verdict")));
        assertEquals(20, checks.size()); assertAll(checks);
    }

    private record Corpus(JsonNode fixture, Map<String, SourceAssessment> sources, Map<String, List<Edge>> edges) { }

    private static Corpus capture() throws Exception {
        var playback = ScenarioLlmPlayback.flood(); var fixture = playback.fixture();
        var protocol = protocol(playback); String original = fixture.at("/requirement/text").asText();
        Map<String, Node> nodes = new LinkedHashMap<>();
        for (var value : fixture.at("/relationPlayback/sourceContributions")) nodes.putIfAbsent(value.path("nodeId").asText(), node(value.path("nodeId").asText()));
        for (var value : fixture.at("/relationPlayback/sourceRejections")) nodes.putIfAbsent(value.path("nodeId").asText(), node(value.path("nodeId").asText()));
        assertEquals(75, nodes.size(), "Keep all independently assessed sources");
        Map<String, SourceAssessment> sources = new LinkedHashMap<>();
        for (var node : nodes.values()) sources.put(node.id(), protocol.contributions(original, List.of(node)).getFirst());
        Map<String, List<Edge>> edges = new LinkedHashMap<>();
        for (var claim : fixture.at("/relationPlayback/claims")) {
            var found = new ArrayList<Edge>();
            for (Direction direction : Direction.values()) {
                boolean outgoing = direction == Direction.OUTGOING;
                String from = claim.path(outgoing ? "sourceId" : "targetId").asText();
                String to = claim.path(outgoing ? "targetId" : "sourceId").asText();
                JsonNode authored = part(fixture, claim, outgoing ? "source" : "target");
                var contribution = sources.get(from).contributions().stream().filter(value -> value.text().equals(authored.path("text").asText())
                        && value.quote().equals(authored.path("quote").asText()) && value.condition().equals(authored.path("condition").asText())).findFirst().orElseThrow();
                var target = sources.get(to).node(); String type = claim.path("type").asText();
                var proposed = protocol.evaluate(new Query(original, contribution, type, direction, Phase.NAVIGATE, List.of(target), null)).getFirst();
                assertEquals(Outcome.MATCH, proposed.outcome(), key(claim));
                var verified = protocol.evaluate(new Query(original, contribution, type, direction, Phase.VERIFY, List.of(target), proposed)).getFirst();
                assertEquals(Outcome.VERIFIED, verified.outcome(), key(claim));
                assertEquals(proposed.contribution(), verified.contribution()); assertEquals(proposed.quote(), verified.quote());
                found.add(new Edge(contribution, target, type, direction, verified));
            }
            assertNull(edges.put(key(claim), found));
        }
        assertEquals(105, edges.size()); assertEquals(210, edges.values().stream().mapToInt(List::size).sum());
        assertTrue(playback.failures().isEmpty());
        return new Corpus(fixture, sources, edges);
    }

    private static JsonNode part(JsonNode fixture, JsonNode claim, String role) {
        return fixture.at("/relationPlayback/sourceContributions").valueStream().filter(value ->
                value.path("nodeId").asText().equals(claim.path(role + "Id").asText())
                        && value.path("contributionId").asText("default").equals(claim.path(role + "ContributionId").asText("default")))
                .findFirst().orElseThrow();
    }

    private static Node node(String id) { return new Node(id, id.substring(0, id.indexOf('-')), id, "", false); }
    private static String key(JsonNode claim) { return claim.path("sourceId").asText() + "|" + claim.path("type").asText() + "|" + claim.path("targetId").asText(); }
    private static RelationSearchProtocol protocol(ScenarioLlmPlayback playback) {
        return new RelationSearchProtocol(prompt -> JSON.readTree(playback.respond(prompt)).at("/choices/0/message/content").asText());
    }
}
