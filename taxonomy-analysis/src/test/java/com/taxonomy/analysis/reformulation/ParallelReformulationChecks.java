package com.taxonomy.analysis.reformulation;

import com.sun.net.httpserver.HttpServer;
import com.taxonomy.analysis.service.*;
import com.taxonomy.reformulation.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real gateway/budget/parser/engine; only remote HTTP answers are authored. */
public final class ParallelReformulationChecks {
    private static final String ORIGINAL = "Record the beginning and end of work. No browser. Maximum delay: 2 seconds.";
    private ParallelReformulationChecks() {}

    static void independentSubtreesOverlapAndPreserveTheSerialDocument() throws Exception {
        var json = new ObjectMapper();
        String catalogue;
        try (var input = ParallelReformulationChecks.class.getResourceAsStream("/reformulation/parallel-catalogue.json")) {
            if (input == null) throw new AssertionError("Missing real catalogue fixture");
            catalogue = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        var tree = json.readTree(catalogue);
        var fork = tree.get(0).path("children").get(0);
        String left = fork.path("children").get(0).path("code").asText();
        String right = fork.path("children").get(1).path("code").asText();
        var scores = new TreeMap<String,Integer>();
        for (var child : fork.path("children")) scores.put(child.path("children").get(0).path("code").asText(), 50);
        var scope = new ReformulationBaseline.Scope("test-repository", "test-workspace", "draft", 1L, 1L);
        var baseline = ReformulationBaseline.freeze(new ReformulationBaseline.Source(scope, 1L, ORIGINAL),
                new ReformulationBaseline.Snapshot(scope, "parallel-fixture", 1L, json.writeValueAsString(Map.of("scores", scores))),
                Map.of("catalogue", catalogue), "en", "parallel-test");
        var expectOverlap = new AtomicBoolean();
        var overlap = new AtomicBoolean(true);
        var entered = new CountDownLatch(2);
        var rightComplete = new CountDownLatch(1);
        var active = new AtomicInteger();
        var maximum = new AtomicInteger();
        var calls = new AtomicInteger();
        var errors = new ConcurrentLinkedQueue<Throwable>();
        var remote = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var httpWorkers = Executors.newFixedThreadPool(2);
        remote.setExecutor(httpWorkers);
        remote.createContext("/v1/chat/completions", exchange -> {
            int count = active.incrementAndGet(); maximum.accumulateAndGet(count, Math::max); calls.incrementAndGet();
            try {
                check(exchange.getRequestHeaders().getFirst("Authorization") == null, "Unexpected credentials");
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String prompt = json.readTree(body).at("/messages/0/content").asText();
                check(prompt.contains(ORIGINAL), "Original was not passed intact");
                check(!prompt.contains("VALIDATION_ERRORS"), "Unexpected response repair");
                var input = json.readTree(prompt.substring(prompt.indexOf("INPUT_DATA_JSON\n") + 16));
                String id = input.path("nodeId").asText();
                if (expectOverlap.get() && (id.equals(left) || id.equals(right))) {
                    entered.countDown();
                    if (!entered.await(3, TimeUnit.SECONDS)) overlap.set(false);
                    // Force right result to be parsed before left; order is not decided by timing.
                    if (id.equals(left) && !rightComplete.await(3, TimeUnit.SECONDS)) overlap.set(false);
                }
                var statementIds = new LinkedHashSet<String>(); var questionIds = new LinkedHashSet<String>();
                input.path("directContributions").forEach(s -> statementIds.add(s.path("id").asText()));
                input.path("openDecisions").forEach(q -> questionIds.add(q.path("id").asText()));
                input.path("children").forEach(c -> {
                    c.path("statementProposals").forEach(s -> statementIds.add(s.path("id").asText()));
                    c.path("preservedStatementIds").forEach(s -> statementIds.add(s.asText()));
                    c.path("questionProposals").forEach(q -> questionIds.add(q.path("id").asText()));
                    c.path("preservedQuestionIds").forEach(q -> questionIds.add(q.asText()));
                });
                if (id.equals(fork.path("code").asText())) {
                    check(input.path("children").size() == 2, "Parent ran before both children");
                    check(questionIds.size() == 2, "Parent lost child questions");
                }
                List<?> additions = List.of(); List<?> questions = List.of();
                if (id.equals(left) || id.equals(right)) {
                    additions = List.of(Map.of("wording", "Proposed contribution " + id, "provenance", "MODEL_ADDITION",
                            "sourceSpans", List.of(), "architectureLinks", List.of(id), "questionDependencies", List.of("new-question:0"),
                            "conditionalValidity", "Subject to the unanswered decision"));
                    questions = List.of(Map.ofEntries(Map.entry("subject", "time recording"), Map.entry("dimension", "correction"),
                            Map.entry("scope", id), Map.entry("wording", "Who corrects records in " + id + "?"),
                            Map.entry("rationale", "Not specified in the original"), Map.entry("affectedStatementIds", List.of("new-statement:0")),
                            Map.entry("sourceSpans", List.of()), Map.entry("nodeIds", List.of(id)), Map.entry("edgeIds", List.of()),
                            Map.entry("answerSchema", json.readTree("{\"kind\":\"TEXT\",\"options\":[],\"unit\":null,\"minimum\":null,\"maximum\":null}")),
                            Map.entry("prerequisites", List.of()), Map.entry("consequences", "Clarify correction responsibilities")));
                }
                var answer = Map.of("summary", "Summary " + id, "statementProposals", additions, "preservedStatementIds", statementIds,
                        "questionProposals", questions, "preservedQuestionIds", questionIds, "uncoveredSourceRefs", List.of(), "conflictCandidates", List.of());
                byte[] bytes = json.writeValueAsBytes(Map.of("choices", List.of(Map.of("message", Map.of("content", json.writeValueAsString(answer))))));
                exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (Throwable failure) {
                errors.add(failure); exchange.sendResponseHeaders(500, -1);
            } finally { active.decrementAndGet(); exchange.close(); }
        });
        remote.start();
        var config = new LlmProviderConfig(null);
        set(config, "llmProviderConfig", "LOCAL_ONNX"); // A lost override must fail, never call a cloud service.
        set(config, "customLlmUrl", "http://127.0.0.1:" + remote.getAddress().getPort() + "/v1/chat/completions");
        set(config, "customLlmModel", "parallel-contract");
        set(config, "customLlmApiKey", "");
        config.setRequestProvider(LlmProvider.CUSTOM_OPENAI);
        try {
            var http = new SimpleClientHttpRequestFactory(); http.setReadTimeout(Duration.ofSeconds(10)); http.setConnectTimeout(Duration.ofSeconds(2));
            var registry = new LlmGatewayRegistry(config, new RestTemplate(http), json, null, http, null,
                    new AiPromptBudgetPolicy(new AiTargetCatalogService(config)));
            var nodes = new NodeReformulationService(registry, config, json);
            var serial = new FrozenReformulationEngine(nodes, json).synthesize(baseline, List.of(), List.of());
            check(errors.isEmpty(), "Bad authored HTTP response: " + errors);
            calls.set(0); maximum.set(0); expectOverlap.set(true);
            var completionOrder = new CopyOnWriteArrayList<String>();
            var steps = ReformulationStepExecutor.of((kind, input, type, work) -> {
                Object result = work.get();
                if (input instanceof NodeSynthesisInput node) {
                    completionOrder.add(node.nodeId());
                    if (node.nodeId().equals(right)) rightComplete.countDown();
                }
                return result;
            });
            var parallel = engine(nodes, json, 2).synthesize(baseline, List.of(), List.of(), List.of(), steps);
            check(errors.isEmpty(), "Bad authored HTTP response: " + errors);
            check(overlap.get() && maximum.get() == 2, "Independent subtrees never overlap");
            check(completionOrder.indexOf(right) < completionOrder.indexOf(left), "Reversed child completion was not exercised");
            check(parallel.equals(serial), "Scheduling changed statement/question IDs, details, or document order");
            check(calls.get() == 4, "Expected exactly four node calls, got " + calls.get());
            check(config.getActiveProvider() == LlmProvider.CUSTOM_OPENAI, "Caller provider override was changed");
            check(parallel.statements().stream().anyMatch(s -> s.wording().equals(ORIGINAL)), "Original was lost");
            System.out.println("PARALLEL_REFORMULATION_OK calls=" + calls.get() + " maxConcurrent=" + maximum.get());
        } finally { config.clearRequestProvider(); remote.stop(0); httpWorkers.shutdownNow(); }
    }

    private static FrozenReformulationEngine engine(NodeReformulationService nodes, ObjectMapper json, int width) throws Exception {
        try { return FrozenReformulationEngine.class.getConstructor(NodeReformulationService.class, ObjectMapper.class, int.class).newInstance(nodes, json, width); }
        catch (NoSuchMethodException beforeFeature) { return new FrozenReformulationEngine(nodes, json); }
    }
    private static void set(Object instance, String name, Object value) throws Exception {
        var f = instance.getClass().getDeclaredField(name); f.setAccessible(true); f.set(instance, value);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception { independentSubtreesOverlapAndPreserveTheSerialDocument(); }
}
