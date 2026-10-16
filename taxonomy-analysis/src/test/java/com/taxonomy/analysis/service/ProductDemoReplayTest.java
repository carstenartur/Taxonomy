package com.taxonomy.analysis.service;

import com.taxonomy.analysis.demo.DemoProviderIds;
import com.taxonomy.analysis.demo.DemoReplyBook;
import com.taxonomy.analysis.demo.DemoScenario;
import com.taxonomy.analysis.demo.DemoReplayProviders;
import com.taxonomy.analysis.demo.DemoScenarioCatalog;
import com.taxonomy.analysis.reformulation.FrozenReformulationEngine;
import com.taxonomy.analysis.reformulation.NodeReformulationService;
import com.taxonomy.analysis.relations.RelationSearchProtocol;
import com.taxonomy.dto.RelationSearchModel;
import com.taxonomy.reformulation.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The product demo replaces only the model reply; every reply passes the product's real parsers. */
class ProductDemoReplayTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final DemoScenarioCatalog catalog = new DemoScenarioCatalog();
    private final DemoScenario scenario = catalog.defaultScenario();
    private final DemoReplayProviders replays = new DemoReplayProviders(catalog);

    @Test void builtinCatalogueRegistersTheCommandAndExactlyTwoIterations() {
        assertThat(catalog.command()).isEqualTo("produktdemo");
        assertThat(scenario.id()).isEqualTo("time-tracking-v1");
        assertThat(scenario.r0Text()).isEqualTo("Wir benötigen unternehmensweit ein System zur Arbeitszeiterfassung der Mitarbeiter.");
        assertThat(scenario.iterations()).containsOnlyKeys("R0", "R1");
        assertThat(catalog.supports(DemoProviderIds.providerId(scenario.id(), 0))).isTrue();
        assertThat(catalog.supports(DemoProviderIds.providerId(scenario.id(), 1))).isTrue();
        assertThat(catalog.supports(DemoProviderIds.providerId(scenario.id(), 2))).as("no third iteration").isFalse();
        assertThat(catalog.supports(DemoProviderIds.providerId("unknown-scenario", 0))).isFalse();
    }

    @Test void rootAndChildRepliesPassTheRealScoreContract() {
        var book = new DemoReplyBook(scenario, "R0");
        String root = JSON.writeValueAsString(JSON.readTree(book.reply(scoringPrompt(true, scenario.r0Text(), 0, "UA"))));
        assertThat(JSON.readTree(root).path("UA").path("score").asInt()).isEqualTo(70);
        assertThat(book.reply(scoringPrompt(true, scenario.r0Text(), 0, "CI"))).contains("\"score\":0");

        // UA-1253 children are scaled to the exact parent budget offered by the product.
        var children = JSON.readTree(book.reply(scoringPrompt(false, scenario.r0Text(), 35,
                "UA-1604, UA-1605, UA-1606")));
        double sum = 0;
        for (var child : List.of("UA-1604", "UA-1605", "UA-1606")) sum += children.path(child).path("score").asDouble();
        assertThat(sum).isCloseTo(35d, within(1e-12d));
        assertThat(children.path("UA-1605").path("score").asInt()).isGreaterThan(children.path("UA-1604").path("score").asInt());
        assertThat(children.path("UA-1606").path("score").asInt()).isZero();
        assertThat(children.path("UA-1606").path("reason").asString()).isNotBlank();
    }

    @Test void fractionalParentBudgetKeepsPositiveDemoChildrenBelowOnePercent() {
        var book = new DemoReplyBook(scenario, "R0");
        var children = JSON.readTree(book.reply(scoringPrompt(false, scenario.r0Text(), 0.5,
                "UA-1604, UA-1605, UA-1606")));
        double sum = List.of("UA-1604", "UA-1605", "UA-1606").stream()
                .mapToDouble(code -> children.path(code).path("score").asDouble()).sum();

        assertThat(sum).isCloseTo(0.5d, within(1e-12d));
        assertThat(children.path("UA-1605").path("score").asDouble()).isPositive().isLessThan(0.5d);
    }

    @Test void missingReplyNeverCallsLiveProvider() {
        var gateway = gateway(0);
        // A child set the scenario does not author and an unknown prompt kind fail visibly.
        assertThatThrownBy(() -> gateway.sendHttpRequest(scoringPrompt(false, scenario.r0Text(), 40, "CO-1001, CO-1002"), ""))
                .isInstanceOf(LlmProviderException.class).hasMessageContaining("DEMO_REPLAY_MISSING_REPLY");
        assertThatThrownBy(() -> gateway.sendHttpRequest("Tell me a joke", ""))
                .isInstanceOf(LlmProviderException.class).hasMessageContaining("DEMO_REPLAY_UNKNOWN_PROMPT");
        assertThat(gateway.providerName()).isEqualTo("DEMO.TIME-TRACKING-V1.R0");
        assertThat(gateway).isNotInstanceOf(OpenAiCompatibleGateway.class).isNotInstanceOf(GeminiGateway.class);
    }

    @Test void iterationBindingRejectsTheOtherRequirementVersion() {
        var r1 = new DemoReplyBook(scenario, "R1");
        assertThatThrownBy(() -> r1.reply(scoringPrompt(true, scenario.r0Text(), 0, "UA")))
                .isInstanceOf(com.taxonomy.analysis.demo.DemoReplayException.class).hasMessageContaining("ITERATION_MISMATCH");
        String r1Text = String.join("\n", scenario.r1RequiredPhrases()) + "\n" + scenario.r0Text();
        var r0 = new DemoReplyBook(scenario, "R0");
        assertThatThrownBy(() -> r0.reply(scoringPrompt(true, r1Text, 0, "UA")))
                .hasMessageContaining("ITERATION_MISMATCH");
        assertThat(JSON.readTree(r1.reply(scoringPrompt(true, r1Text, 0, "BR"))).path("BR").path("score").asInt()).isEqualTo(25);
    }

    @Test void parallelCallsKeepScenarioAndIteration() throws Exception {
        var r0 = gateway(0);
        var r1 = gateway(1);
        String r1Text = String.join("\n", scenario.r1RequiredPhrases()) + "\n" + scenario.r0Text();
        var pool = Executors.newFixedThreadPool(8, runnable -> { var t = new Thread(runnable); t.setDaemon(true); return t; });
        try {
            var futures = new ArrayList<Future<int[]>>();
            for (int i = 0; i < 64; i++) {
                boolean second = i % 2 == 1;
                futures.add(pool.submit(() -> {
                    var gateway = second ? r1 : r0;
                    var reply = JSON.readTree(gateway.sendHttpRequest(scoringPrompt(true, second ? r1Text : scenario.r0Text(), 0, "BR"), ""));
                    return new int[] {second ? 1 : 0, reply.path("BR").path("score").asInt()};
                }));
            }
            for (var future : futures) {
                int[] result = future.get(30, TimeUnit.SECONDS);
                assertThat(result[1]).isEqualTo(result[0] == 1 ? 25 : 0);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void retryKeepsSameReplyWithoutDuplicateEffect() {
        var gateway = gateway(0);
        String prompt = scoringPrompt(false, scenario.r0Text(), 45, "CR-1011, CR-1047, CR-1048");
        String first = gateway.sendHttpRequest(prompt, "");
        String retried = gateway.sendHttpRequest(prompt, "");
        assertThat(retried).isEqualTo(first);
        assertThat(gateway(0).sendHttpRequest(prompt, "")).as("fresh worker after recovery").isEqualTo(first);
    }

    @Test void relationRepliesPassTheRealRelationProtocol() {
        var book = new DemoReplyBook(scenario, "R0");
        var protocol = new RelationSearchProtocol(book::reply);
        String original = scenario.r0Text();
        var source = new RelationSearchModel.Node("UA-1605", "UA", "Work Time Applications", "Work time", false);
        var other = new RelationSearchModel.Node("UA-1604", "UA", "Payroll Applications", "Payroll", false);
        var assessments = protocol.contributions(original, List.of(source, other));
        assertThat(assessments).hasSize(2);
        var selected = assessments.stream().filter(a -> a.node().id().equals("UA-1605")).findFirst().orElseThrow();
        assertThat(selected.contributions()).hasSize(1);
        var contribution = selected.contributions().getFirst();
        var cr = new RelationSearchModel.Node("CR", "CR", "Core Services", "Core", true);
        var ip = new RelationSearchModel.Node("IP", "IP", "Information Products", "Products", true);
        var navigate = protocol.evaluate(new RelationSearchModel.Query(original, contribution, "USES",
                RelationSearchModel.Direction.OUTGOING, RelationSearchModel.Phase.NAVIGATE, List.of(cr, ip), null));
        assertThat(navigate).extracting(d -> d.outcome().name()).containsExactly("DESCEND", "REJECT");
        var target = new RelationSearchModel.Node("CR-1036", "CR", "Human Resource Management Services", "HR", false);
        var match = protocol.evaluate(new RelationSearchModel.Query(original, contribution, "USES",
                RelationSearchModel.Direction.OUTGOING, RelationSearchModel.Phase.NAVIGATE, List.of(target), null));
        assertThat(match.getFirst().outcome()).isEqualTo(RelationSearchModel.Outcome.MATCH);
        var verified = protocol.evaluate(new RelationSearchModel.Query(original, contribution, "USES",
                RelationSearchModel.Direction.OUTGOING, RelationSearchModel.Phase.VERIFY, List.of(target), match.getFirst()));
        assertThat(verified.getFirst().outcome()).isEqualTo(RelationSearchModel.Outcome.VERIFIED);
    }

    /**
     * Complete demo flow through the real reformulation engine and parser: questions,
     * a rejected addition that never reappears, visible answers and the R1 consequences.
     */
    @Test void bottomUpReformulationReplayProducesQuestionsAnswersAndR1() {
        var json = new ObjectMapper();
        var baseline = baseline(scenario.r0Text(), 1, "R0");
        var engine = new FrozenReformulationEngine(nodeService(json, 0), json);
        var first = engine.synthesize(baseline, List.of(), List.of());

        assertThat(first.text()).startsWith(scenario.r0Text());
        assertThat(first.questions()).extracting(q -> q.key().subject())
                .containsExactlyInAnyOrder("F-01 Erfassungskanäle", "F-02 Offline-Erfassung", "F-03 Korrekturen",
                        "F-04 Anmeldung und Stammdaten", "F-05 Grenzen der Erfassung");
        assertThat(first.statements()).filteredOn(s -> s.provenance() == Statement.Provenance.ORIGINAL)
                .singleElement().satisfies(s -> assertThat(s.wording()).isEqualTo(scenario.r0Text()));
        String gps = scenario.demoReviews().getFirst().wording();
        var gpsStatement = first.statements().stream().filter(s -> s.wording().equals(gps)).findFirst().orElseThrow();
        assertThat(gpsStatement.provenance()).isEqualTo(Statement.Provenance.ARCHITECTURE_HYPOTHESIS);

        // Human review: reject the surveillance addition; answer the demo decisions visibly.
        var rejected = new Statement(gpsStatement.id(), gpsStatement.wording(), gpsStatement.sourceSpans(), gpsStatement.provenance(),
                gpsStatement.architectureLinks(), gpsStatement.questionDependencies(), gpsStatement.conditionalValidity(),
                Statement.EditingOrigin.HUMAN, "REJECTED");
        var statements = new ArrayList<Statement>();
        first.statements().forEach(s -> statements.add(s.id().equals(rejected.id()) ? rejected : s));
        var before = new ReformulationDocument(first.text(), first.sections(), statements, first.questions(), first.validation(),
                first.nodeResults());
        var answers = new ArrayList<DecisionAnswer>();
        var affectedSections = new LinkedHashSet<String>();
        for (var decision : scenario.demoDecisions()) {
            var authored = scenario.question(decision.question());
            var question = first.questions().stream().filter(q -> q.key().subject().equals(authored.subject())).findFirst().orElseThrow();
            answers.add(new DecisionAnswer("a-" + decision.question(), question.id(), "proposal", 1, decision.values(),
                    DecisionQuestion.State.ANSWERED, "demo", Instant.EPOCH, decision.rationale()));
            affectedSections.add(question.key().scope());
        }
        var second = engine.synthesizeAffected(baseline, before, answers,
                new ReformulationImpact(List.of(), List.copyOf(affectedSections), List.of(), List.of(), false));

        assertThat(second.text()).contains(scenario.r1RequiredPhrases().toArray(String[]::new));
        assertThat(second.text()).doesNotContain(gps).contains("Original (unverändert)\n" + scenario.r0Text());
        assertThat(second.statements()).filteredOn(s -> scenario.r1RequiredPhrases().contains(s.wording()))
                .hasSize(5).allSatisfy(s -> {
                    assertThat(s.provenance()).isEqualTo(Statement.Provenance.MODEL_ADDITION);
                    assertThat(s.questionDependencies()).hasSize(1);
                });
        assertThat(second.statements()).filteredOn(s -> s.wording().equals(gps))
                .allSatisfy(s -> assertThat(s.reviewState()).isEqualTo("REJECTED"));
        assertThat(second.questions()).hasSize(5);

        // The adopted text is now exactly answerable by the R1 book, and no longer by R0.
        var r1Book = new DemoReplyBook(scenario, "R1");
        assertThat(JSON.readTree(r1Book.reply(scoringPrompt(true, second.text(), 0, "BR"))).path("BR").path("score").asInt()).isEqualTo(25);
        assertThatThrownBy(() -> new DemoReplyBook(scenario, "R0").reply(scoringPrompt(true, second.text(), 0, "BR")))
                .hasMessageContaining("ITERATION_MISMATCH");
    }

    @Test void secondGenerationOnAdoptedSourceNeverProposesQuestionsAgain() {
        var json = new ObjectMapper();
        String r1Text = String.join("\n\n", scenario.r1RequiredPhrases()) + "\n\nOriginal (unverändert)\n" + scenario.r0Text();
        var document = new FrozenReformulationEngine(nodeService(json, 1), json)
                .synthesize(baseline(r1Text, 2, "R1"), List.of(), List.of());
        assertThat(document.questions()).isEmpty();
        assertThat(document.text()).contains(scenario.r1RequiredPhrases().toArray(String[]::new));
    }

    @Test void referenceDoesNotEnterPromptsOrReplaySelection() throws IOException {
        // Loading succeeds when the reference annotation is absent or corrupt.
        Function<String, InputStream> withoutReference = path -> path.endsWith("reference.json")
                ? new ByteArrayInputStream("{not json".getBytes(StandardCharsets.UTF_8))
                : getClass().getClassLoader().getResourceAsStream(path);
        var isolated = new DemoScenarioCatalog(withoutReference).defaultScenario();
        assertThat(isolated.fingerprint()).isEqualTo(scenario.fingerprint());
        try (var stream = getClass().getClassLoader().getResourceAsStream("demo-scenarios/time-tracking-v1/reference.json")) {
            String reference = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            var data = JSON.readTree(reference);
            var marker = data.path("forbiddenNodes").get(0).path("reason").path("en").asString();
            var book = new DemoReplyBook(isolated, "R0");
            assertThat(book.reply(scoringPrompt(true, scenario.r0Text(), 0, "CI"))).doesNotContain(marker);
        }
        var sources = new String(Objects.requireNonNull(DemoScenario.class.getResourceAsStream("DemoScenario.class")).readAllBytes(),
                StandardCharsets.ISO_8859_1);
        assertThat(sources).doesNotContain("reference.json");
        var book = new String(Objects.requireNonNull(DemoReplyBook.class.getResourceAsStream("DemoReplyBook.class")).readAllBytes(),
                StandardCharsets.ISO_8859_1);
        assertThat(book).doesNotContain("reference.json").doesNotContain("DemoReference");
    }

    @Test void demoProviderIsRegisteredOnlyForAuthoredIterationsAndNeverGlobally() {
        var config = new LlmProviderConfig(mock(com.taxonomy.catalog.service.LocalEmbeddingService.class), replays);
        var id = DemoProviderIds.providerId(scenario.id(), 0);
        assertThat(config.requireRegisteredProvider(id.value())).isEqualTo(id);
        assertThat(config.requiresApiKey(id)).isFalse();
        assertThat(config.isProviderConfigured(id)).isTrue();
        assertThat(config.getOpenAiCompatibleUrl(id)).isEqualTo("replay:demo-scenarios/time-tracking-v1");
        assertThat(config.captureProviderBinding(id.value())).as("no plugin lease or live configuration").isNull();
        assertThatThrownBy(() -> config.requireRegisteredProvider("DEMO.TIME-TRACKING-V1.R2"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(config.registeredProviderIds()).noneMatch(DemoProviderIds::isDemo);
        try (var ignored = config.withRequestProvider(id.value(), null)) {
            assertThat(config.getActiveProviderId()).isEqualTo(id);
            assertThat(config.getActiveProviderName()).isEqualTo("Scenario replay " + scenario.id() + " R0");
            assertThat(config.isMockMode()).isFalse();
        }
    }

    @Test void withoutReplayStrategyTheDemoNamespaceIsNotExecutable() {
        var config = new LlmProviderConfig(mock(com.taxonomy.catalog.service.LocalEmbeddingService.class));
        var id = DemoProviderIds.providerId(scenario.id(), 0);
        assertThat(config.isScenarioReplay(id)).isFalse();
        assertThatThrownBy(() -> config.requireRegisteredProvider(id.value())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void replayIdentityIsTheAuthoredContentAndIteration() {
        var r0 = replays.find(DemoProviderIds.providerId(scenario.id(), 0)).orElseThrow();
        var r1 = replays.find(DemoProviderIds.providerId(scenario.id(), 1)).orElseThrow();
        assertThat(r0.contentIdentity()).startsWith(scenario.fingerprint()).isNotEqualTo(r1.contentIdentity());
        assertThat(r0.model()).isEqualTo("scenario-replay-" + scenario.id() + "-v" + scenario.version() + "-r0");
        assertThat(replays.find(DemoProviderIds.providerId(scenario.id(), 2))).isEmpty();
        assertThat(replays.owns(DemoProviderIds.providerId(scenario.id(), 2))).isTrue();
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private LlmGateway gateway(int iteration) {
        return new ScenarioReplayGateway(replays.find(DemoProviderIds.providerId(scenario.id(), iteration)).orElseThrow());
    }

    private NodeReformulationService nodeService(ObjectMapper json, int iteration) {
        var id = DemoProviderIds.providerId(scenario.id(), iteration);
        var registry = mock(LlmGatewayRegistry.class);
        var config = mock(LlmProviderConfig.class);
        when(config.getActiveProviderId()).thenReturn(id);
        when(config.isProviderConfigured(id)).thenReturn(true);
        when(config.getApiKey(id)).thenReturn("");
        var gateway = gateway(iteration);
        when(registry.getGatewayById(id)).thenReturn(gateway);
        return new NodeReformulationService(registry, config, json);
    }

    /** Frozen R0/R1 catalogue context built only from the scenario's validated real codes. */
    private ReformulationBaseline baseline(String text, long version, String iteration) {
        var replies = scenario.iteration(iteration);
        var nodes = new LinkedHashMap<String, Map<String, Object>>();
        var roots = new ArrayList<Map<String, Object>>();
        for (String root : DemoScenario.ROOTS) {
            var node = node(root);
            nodes.put(root, node);
            roots.add(node);
        }
        // Expansions are authored parent→children, so walk them until stable.
        boolean added;
        do {
            added = false;
            for (var expansion : replies.expansions().entrySet()) {
                var parent = nodes.get(expansion.getKey());
                if (parent == null) continue;
                for (String child : expansion.getValue().keySet()) {
                    if (nodes.containsKey(child)) continue;
                    var node = node(child);
                    nodes.put(child, node);
                    @SuppressWarnings("unchecked") var children = (List<Map<String, Object>>) parent.get("children");
                    children.add(node);
                    added = true;
                }
            }
        } while (added);
        var scope = new ReformulationBaseline.Scope("repo", "ws", "main", 1, 1);
        var payload = JSON.writeValueAsString(Map.of("rawScores", replies.declaredScores()));
        return ReformulationBaseline.freeze(new ReformulationBaseline.Source(scope, version, text),
                new ReformulationBaseline.Snapshot(scope, "snap-" + iteration, version, payload),
                Map.of("catalogue", JSON.writeValueAsString(roots)), "de", "reformulation-v1");
    }

    private Map<String, Object> node(String code) {
        var node = new LinkedHashMap<String, Object>();
        node.put("code", code);
        node.put("descriptionEn", scenario.catalogueAnchors().getOrDefault(code, code));
        node.put("children", new ArrayList<Map<String, Object>>());
        return node;
    }

    private static String scoringPrompt(boolean root, String text, double parentScore, String keys) {
        return root
                ? "You assess the independent relevance of the offered C3 taxonomy root to the original business requirement.\n"
                + "This is one root assessment, not a distribution of a parent budget or a comparison with other roots.\n\n"
                + "Business Requirement: " + text + "\n\nRespond ONLY with a valid JSON object using EXACTLY these keys: " + keys + "\n"
                : "Business Requirement: " + text + "\nThe scores must sum to "
                + com.taxonomy.dto.AnalysisPercentages.canonical(parentScore) + ". A score of 0 means the child is not relevant.\n"
                + "Respond ONLY with a valid JSON object using EXACTLY these keys: " + keys + "\n";
    }
}
