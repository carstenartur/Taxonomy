package com.taxonomy.acceptance;

import com.taxonomy.analysis.relations.RelationSearchProtocol;
import com.taxonomy.architecture.pipeline.ArchitectureViewContext;
import com.taxonomy.architecture.pipeline.EvidenceRelationProjection;
import com.taxonomy.dto.RelationSearchReport;
import com.taxonomy.dto.RelationSearchModel.*;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.service.RelationCompatibilityMatrix;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.*;

/** Actual authored raw replies must satisfy endpoint binding before the expensive browser scenario. */
class ScenarioPlaybackEvidenceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void everyScopedContributionHasAnExplicitAnswerAtEveryCompatibleNavigationRoot() throws Exception {
        var corpus = capture(); var playback = new ScenarioLlmPlayback(corpus.fixture());
        var protocol = protocol(playback); var rules = new RelationCompatibilityMatrix();
        String original = corpus.fixture().at("/requirement/text").asText();
        var checks = new ArrayList<Executable>();
        for (var source : corpus.sources().values()) for (var contribution : source.contributions()) {
            for (var type : RelationType.values()) for (String root : List.of("BP", "BR", "CP", "CI", "CO", "CR", "IP", "UA")) {
                for (Direction direction : Direction.values()) {
                    boolean compatible = direction == Direction.OUTGOING
                            ? rules.allowedTargetRoots(source.node().root(), type).contains(root)
                            : rules.allowedTargetRoots(root, type).contains(source.node().root());
                    if (!compatible) continue;
                    var query = new Query(original, contribution, type.name(), direction, Phase.NAVIGATE,
                            List.of(new Node(root, root, root, "", true)), null);
                    checks.add(() -> assertTrue(List.of(Outcome.REJECT, Outcome.DESCEND)
                                    .contains(protocol.evaluate(query).getFirst().outcome()),
                            "A navigation category cannot become a concrete relationship endpoint"));
                }
            }
        }
        assertFalse(checks.isEmpty()); assertAll(checks);
        assertTrue(playback.failures().isEmpty());
    }

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

    @Test void splitRoleBranchesHaveAuthoredAnswersForTheFullCatalogueFailures() throws Exception {
        var corpus = capture(); var playback = new ScenarioLlmPlayback(corpus.fixture());
        var protocol = protocol(playback); String original = corpus.fixture().at("/requirement/text").asText();
        var checks = new ArrayList<Executable>();
        for (String scope : List.of(
                "BP-1017 default SUPPORTS INCOMING CI",
                "CP-1122 account-lifecycle REALIZES OUTGOING CR-1011",
                "CP-1122 default REALIZES OUTGOING CR-1047",
                "CR-1006 account-authentication REALIZES INCOMING CP-1023",
                "CR-1006 default REALIZES INCOMING CP-1059",
                "CR-1006 default SUPPORTS OUTGOING BP-1147",
                "CP-1122 account-lifecycle FULFILLS INCOMING CR-1011",
                "CP-1122 default FULFILLS INCOMING CR-1047",
                "BP-1017 default CONSUMES OUTGOING IP-1102",
                "BP-1017 latest-observation CONSUMES OUTGOING IP-1069",
                "BP-1535 subscriber-delivery CONSUMES OUTGOING IP-1680",
                "BP-1535 subscriber-delivery CONSUMES OUTGOING IP-1811",
                "BP-1535 subscriber-delivery CONSUMES OUTGOING IP-1304",
                "BP-1535 public-information CONSUMES OUTGOING IP-1069",
                "CR-1006 default USES INCOMING BP-1147",
                "BP-1017 default USES OUTGOING CI",
                "BP-1536 deletion-lifecycle USES OUTGOING CR",
                "CR-1006 account-authentication FULFILLS OUTGOING CP-1023",
                "CR-1006 default FULFILLS OUTGOING CP-1059")) {
            String[] fields = scope.split(" ");
            var authored = corpus.fixture().at("/relationPlayback/sourceContributions").valueStream().filter(value ->
                    value.path("nodeId").asText().equals(fields[0]) && value.path("contributionId").asText("default").equals(fields[1])).findFirst().orElseThrow();
            var part = corpus.sources().get(fields[0]).contributions().stream().filter(value ->
                    value.text().equals(authored.path("text").asText()) && value.quote().equals(authored.path("quote").asText())).findFirst().orElseThrow();
            String root = fields[4].split("-")[0];
            var candidate = new Node(fields[4], root, fields[4], "", root.equals(fields[4]));
            var query = new Query(original, part, fields[2], Direction.valueOf(fields[3]), Phase.NAVIGATE, List.of(candidate), null);
            checks.add(() -> assertTrue(List.of(Outcome.REJECT, Outcome.DESCEND).contains(protocol.evaluate(query).getFirst().outcome()), scope));
        }
        assertEquals(19, checks.size()); assertAll(checks);
        assertTrue(playback.failures().isEmpty());
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

    @Test void everyAuthoredOpenIdentityHasItsOwnDeliveredF5ObservationInputWitness() throws Exception {
        var value = identityCorpus();
        var checks = new ArrayList<Executable>();
        for (var expected : value.reference().path("openIdentityQuestions")) checks.add(() -> {
            var result = verifyOriginalOpenIdentityGate(value, expected, value.witnesses());
            assertEquals(1, result.size());
            assertEquals(expected.path("id").asText(), result.getFirst().get("id"));
            assertEquals("OPEN_NONCORE_INPUT_IDENTITY", result.getFirst().get("status"));
        });
        assertEquals(7, checks.size());
        assertEquals(14, value.observations().values().stream().mapToInt(List::size).sum());
        assertAll(checks);
    }

    @Test void originalOpenIdentityGateStillRejectsLostF5BindingMissingUncertaintyAndContradictoryRecognizedInput() throws Exception {
        var value = identityCorpus();
        JsonNode expected = value.reference().path("openIdentityQuestions").get(0);
        assertEquals("X03", expected.path("id").asText());
        var witness = value.witnesses().stream().filter(item -> item.endpointId().equals("BP-1017")
                && item.quote().startsWith("F5:")).findFirst().orElseThrow();
        assertEquals(1, verifyOriginalOpenIdentityGate(value, expected, List.of(witness)).size());
        for (var changed : List.of(
                new ScenarioArchitectureQuality.Witness("ELEMENT", "BP-1017", "BP-1017", witness.text(),
                        value.reference().at("/requirement/clauses/F1").asText(), witness.condition()),
                new ScenarioArchitectureQuality.Witness("ELEMENT", "BP-1017", "BP-1017", "Acquire the latest observation batch.",
                        witness.quote(), witness.condition()),
                new ScenarioArchitectureQuality.Witness("ELEMENT", "BP-1017", "BP-1017", witness.text()
                        + " The incoming observation batch is an already recognized environmental picture.", witness.quote(), witness.condition()))) {
            assertThrows(AssertionError.class, () -> verifyOriginalOpenIdentityGate(value, expected, List.of(changed)));
        }
    }

    @Test void publicF1RolesDoNotDuplicateTheFourDistinctF5InputIdentityQuestions() throws Exception {
        var corpus = capture(); var playback = new ScenarioLlmPlayback(corpus.fixture());
        var protocol = protocol(playback); String original = corpus.fixture().at("/requirement/text").asText();
        var checks = new ArrayList<Executable>();
        for (String source : List.of("BP-1529", "BP-1534", "CI-1096", "CP-1020")) {
            JsonNode authored = corpus.fixture().at("/relationPlayback/sourceContributions").valueStream().filter(value ->
                    value.path("nodeId").asText().equals(source) && value.path("contributionId").asText("default").equals("default"))
                    .findFirst().orElseThrow();
            assertTrue(authored.path("quote").asText().startsWith("F1:"));
            var part = corpus.sources().get(source).contributions().stream().filter(value ->
                    value.text().equals(authored.path("text").asText()) && value.quote().equals(authored.path("quote").asText()))
                    .findFirst().orElseThrow();
            checks.add(() -> {
                var answer = protocol.evaluate(new Query(original, part, source.equals("CP-1020") ? "REQUIRES" : "CONSUMES",
                        Direction.OUTGOING, Phase.NAVIGATE, List.of(corpus.sources().get("IP-1116").node()), null)).getFirst();
                assertEquals(Outcome.REJECT, answer.outcome(), source + " F1 role must not duplicate its distinct F5 identity question");
                assertTrue(answer.question().isEmpty());
            });
        }
        assertAll(checks);
    }

    private record IdentityCorpus(JsonNode reference, Corpus corpus, List<Edge> edges,
                                  ArchitectureViewContext context, List<ScenarioArchitectureQuality.Witness> witnesses,
                                  Map<String, List<Unfinished>> observations) { }

    private static IdentityCorpus identityCorpus() throws Exception {
        var corpus = capture(); var playback = new ScenarioLlmPlayback(corpus.fixture());
        var protocol = protocol(playback); String original = corpus.fixture().at("/requirement/text").asText();
        JsonNode reference;
        try (var input = ScenarioPlaybackEvidenceTest.class.getResourceAsStream("/scenarios/flood-quality-reference.json")) {
            assertNotNull(input); reference = JSON.readTree(input);
        }
        assertEquals(List.of("X03", "X08", "X09", "X10", "X11", "X12", "X13"),
                reference.path("openIdentityQuestions").valueStream().map(value -> value.path("id").asText()).toList());
        var observations = new LinkedHashMap<String, List<Unfinished>>();
        for (var expected : reference.path("openIdentityQuestions")) {
            var observed = new ArrayList<Unfinished>();
            for (Direction direction : Direction.values()) {
                String source = expected.path(direction == Direction.OUTGOING ? "sourceId" : "targetId").asText();
                String target = expected.path(direction == Direction.OUTGOING ? "targetId" : "sourceId").asText();
                JsonNode scope = corpus.fixture().at("/relationPlayback/exclusions/openScopes").valueStream().filter(value ->
                        value.path("sourceIds").valueStream().anyMatch(id -> id.asText().equals(source))
                                && value.path("candidateIds").valueStream().anyMatch(id -> id.asText().equals(target))
                                && value.path("type").asText().equals(expected.path("type").asText())
                                && value.path("direction").asText().equals(direction.name())).findFirst().orElseThrow();
                String partId = scope.path("sourceContributionIds").get(0).asText();
                JsonNode authored = corpus.fixture().at("/relationPlayback/sourceContributions").valueStream().filter(value ->
                        value.path("nodeId").asText().equals(source)
                                && value.path("contributionId").asText("default").equals(partId)).findFirst().orElseThrow();
                var part = corpus.sources().get(source).contributions().stream().filter(value ->
                        value.text().equals(authored.path("text").asText()) && value.quote().equals(authored.path("quote").asText()))
                        .findFirst().orElseThrow();
                var answer = protocol.evaluate(new Query(original, part, expected.path("type").asText(), direction,
                        Phase.NAVIGATE, List.of(corpus.sources().get(target).node()), null)).getFirst();
                assertEquals(Outcome.UNRESOLVED, answer.outcome());
                assertEquals(expected.path("question").asText(), answer.question());
                observed.add(new Unfinished(source, expected.path("type").asText(), direction, List.of(target), "UNRESOLVED", answer.question()));
            }
            observations.put(expected.path("id").asText(), List.copyOf(observed));
        }
        List<Edge> edges = corpus.edges().values().stream().flatMap(List::stream).toList();
        var report = new RelationSearchReport(1, ScenarioLlmPlayback.sha256(original), "authored-open-identity-test",
                List.copyOf(corpus.sources().values()), new Result(edges,
                observations.values().stream().flatMap(List::stream).toList(), List.of(), 0, 0, 0), 0, 0, 0, List.of(), "");
        var context = new ArchitectureViewContext(Map.of(), original, 0, null);
        EvidenceRelationProjection.apply(context, report);
        var witnesses = new ArrayList<ScenarioArchitectureQuality.Witness>();
        var presence = new LinkedHashMap<String, String>();
        context.getElements().forEach(element -> presence.put(element.getNodeCode(), element.getPresenceReason()));
        // Exactly the full quality gate's local delivered text/condition filter, without mirroring its meaning predicates.
        corpus.sources().values().forEach(source -> source.contributions().forEach(part -> {
            String local = presence.getOrDefault(source.node().id(), "");
            if (local.contains(part.text())) witnesses.add(new ScenarioArchitectureQuality.Witness("ELEMENT", source.node().id(),
                    source.node().id(), part.text(), part.quote(), local.contains(part.condition()) ? part.condition() : ""));
        }));
        return new IdentityCorpus(reference, corpus, edges, context, witnesses, observations);
    }

    /** Invoke the unchanged private quality gate rather than create a second interpretation predicate. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> verifyOriginalOpenIdentityGate(IdentityCorpus value, JsonNode expected,
            List<ScenarioArchitectureQuality.Witness> witnesses) throws Exception {
        Class<?> quality = ScenarioArchitectureQuality.class;
        var referenceMethod = quality.getDeclaredMethod("reference"); referenceMethod.setAccessible(true);
        Object reference = referenceMethod.invoke(null);
        Class<?> openType = Class.forName(quality.getName() + "$OpenIdentity");
        var openConstructor = openType.getDeclaredConstructor(JsonNode.class, List.class); openConstructor.setAccessible(true);
        Object open = openConstructor.newInstance(expected, value.observations().get(expected.path("id").asText()));
        Class<?> searchType = Class.forName(quality.getName() + "$SearchEvidence");
        var searchConstructor = searchType.getDeclaredConstructor(Map.class, List.class); searchConstructor.setAccessible(true);
        Object search = searchConstructor.newInstance(value.corpus().sources(), List.of(open));
        Class<?> tripleType = Class.forName(quality.getName() + "$Triple");
        var tripleConstructor = tripleType.getDeclaredConstructor(String.class, String.class, String.class); tripleConstructor.setAccessible(true);
        Set<Object> projected = new LinkedHashSet<>();
        for (var relation : value.context().getRelationships()) projected.add(tripleConstructor.newInstance(
                relation.getSourceCode(), relation.getRelationType(), relation.getTargetCode()));
        var gate = quality.getDeclaredMethod("verifyOpenIdentityWitnesses", searchType, List.class, reference.getClass(), Set.class, List.class);
        gate.setAccessible(true);
        try { return (List<Map<String, Object>>) gate.invoke(null, search, witnesses, reference, projected, value.edges()); }
        catch (java.lang.reflect.InvocationTargetException failure) {
            if (failure.getCause() instanceof AssertionError assertion) throw assertion;
            throw failure;
        }
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
