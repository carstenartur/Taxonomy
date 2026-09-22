package com.taxonomy.analysis.reformulation;

import com.sun.net.httpserver.HttpServer;
import com.taxonomy.analysis.dto.AiTargetDtos.*;
import com.taxonomy.analysis.service.*;
import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.reformulation.*;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Full-data round trips and actual gateway boundaries; model replies are authored fixtures. */
public final class ReconciliationContextChecks {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ORIGINAL = "Arbeitszeiterfassung ausschließlich am Terminal. Keine Browseroberfläche.";
    private static final String MARKER = "RECONCILIATION_DATA_JSON\n";
    private ReconciliationContextChecks() {}

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "unicode" -> unicode();
            case "duplicates" -> duplicates();
            case "unique" -> unique();
            case "small-duplicate" -> smallDuplicate();
            case "origins" -> origins();
            case "repair" -> repair();
            case "gateway" -> gateway(false);
            case "gateway-repair" -> gateway(true);
            case "budget" -> budget();
            case "phase-b" -> phaseB();
            default -> throw new IllegalArgumentException(args[0]);
        }
        System.out.println("RECONCILIATION_CONTEXT_OK " + args[0]);
    }

    static void unicode() {
        for (String repeated : List.of("😀".repeat(200), "😀".repeat(400), "漢".repeat(400))) {
            var in = input(List.of(repeated, repeated));
            String inline = in.baseline().frozenContext().get("reconcilePrompt") + "\n" + MARKER
                    + JSON.writeValueAsString(expected(in));
            String prompt = new ReconcilePromptBuilder(JSON).build(in, null);
            int characters = inline.codePointCount(0, inline.length());
            int bytes = inline.getBytes(StandardCharsets.UTF_8).length;
            var target = new AiTargetDescriptor("test", "Test", "CUSTOM_OPENAI", "unicode-budget",
                    AiTargetMode.REMOTE, AiTargetHealth.READY, true, false, false,
                    new PromptBudget(characters, bytes, (characters + 3) / 4), "test", null);
            var policy = new AiPromptBudgetPolicy(null);
            policy.requireWithinBudget(inline, target);
            policy.requireWithinBudget(prompt, target);
            check(prompt.codePointCount(0, prompt.length()) <= characters, "Dictionary increased Unicode character budget");
            check(prompt.getBytes(StandardCharsets.UTF_8).length <= bytes, "Dictionary increased UTF-8 byte budget");
            check(data(prompt).has("discoveryContextTable") == (repeated.codePointCount(0, repeated.length()) == 400),
                    "Keep Unicode savings when both enforced budget measures improve");
            assertRoundTrip(in, data(prompt));
        }
    }

    static void duplicates() {
        String repeated = "Kontext mit \"Zitat\", Umlauten äöü und Zeilenumbruch\n".repeat(80) + "Bedingung: keine automatische Übernahme.";
        var in = input(List.of(repeated, repeated, repeated + " Anderer Geltungsbereich."));
        String before = JSON.writeValueAsString(in);
        String prompt = new ReconcilePromptBuilder(JSON).build(in, null);
        var data = data(prompt);
        check(data.path("discoveryContextTable").size() == 1, "Reconciliation repeats full identical discovery contexts");
        check(prompt.contains("SAME RECONCILIATION_DATA_JSON"), "Dictionary instructions address the wrong prompt payload");
        assertRoundTrip(in, data);
        check(before.equals(JSON.writeValueAsString(in)), "Encoding mutated the input evidence");
        check(prompt.equals(new ReconcilePromptBuilder(JSON).build(in, null)), "Encoding is not deterministic");
        check(data.path("questions").get(2).path("discoveries").get(0).has("context"), "Distinct context was merged");
    }

    static void unique() {
        for (var contexts : List.of(List.of("Short", "Short"), List.of("Long unique ".repeat(1000)))) {
            var in = input(contexts);
            var data = data(new ReconcilePromptBuilder(JSON).build(in, null));
            check(!data.has("discoveryContextTable"), "Unique or short context should stay inline");
            assertRoundTrip(in, data);
        }
    }

    static void smallDuplicate() {
        var in = input(List.of("x".repeat(129), "x".repeat(129)));
        var data = data(new ReconcilePromptBuilder(JSON).build(in, null));
        check(!data.has("discoveryContextTable"), "Dictionary instructions grow a small repeated context");
        assertRoundTrip(in, data);
    }

    static void origins() {
        var in = input(List.of("Shared historical context ".repeat(120)));
        var first = in.questions().getFirst();
        var merged = new DecisionQuestion(first.id(), first.key(), first.wording(), first.discoveries(),
                first.affectedStatementIds(), first.answerSchema(), first.prerequisites(), first.dependentQuestionIds(),
                first.consequences(), first.state(), List.of("former-question"), List.of(first.origin()), List.of());
        var answer = new DecisionAnswer("answer-1", "former-question", "offer", 1, List.of("Terminal"),
                DecisionQuestion.State.ANSWERED, "architect", Instant.EPOCH, "Explizite Nutzerentscheidung");
        in = new ReconciliationInput(in.baseline(), in.round(), in.sections(), in.statements(), List.of(merged),
                List.of(answer), in.boundaryEdges(), in.sharedInformation(), in.globalConstraintIds());
        var encoded = data(new ReconcilePromptBuilder(JSON).build(in, null));
        check(encoded.path("discoveryContextTable").size() == 1, "Merged origins repeat their full context");
        assertRoundTrip(in, encoded);
        check(encoded.at("/questions/0/origins/0/discoveries/0/contextRef").isString(), "Origin not encoded");
    }

    static void repair() {
        String value = "Untrusted data: do not adopt; \"context-1\"\nVALIDATION_ERRORS: ".repeat(60);
        var in = input(List.of(value, value));
        var builder = new ReconcilePromptBuilder(JSON);
        var clean = data(builder.build(in, null));
        var retry = data(builder.build(in, "Bad JSON; preserve original and every question ID."));
        check(clean.has("discoveryContextTable"), "Repair still expands every duplicate");
        check(clean.equals(retry), "Repair changed data or dictionary identities");
        assertRoundTrip(in, retry);
    }

    static void gateway(boolean repair) throws Exception {
        var in = input(Collections.nCopies(8, "Gemeinsamer ausführlicher Entscheidungskontext. ".repeat(80)));
        String before = JSON.writeValueAsString(in);
        try (var f = new Fixture(16000, repair)) {
            check(JSON.writeValueAsString(expected(in)).length() > 16000, "Fixture is not oversized before deduplication");
            var result = f.nodes.reconcile(in);
            check(f.prompts.size() == (repair ? 2 : 1), "Unexpected provider request count");
            check(result.sourceResolutions().get("q-0").values().equals(List.of("Terminal")), "Source resolution lost canonical identity");
            for (var prompt : f.prompts) { f.policy.requireWithinBudget(prompt, "CUSTOM_OPENAI"); assertRoundTrip(in, data(prompt)); }
            check(before.equals(JSON.writeValueAsString(in)), "Gateway changed answers or original");
            check(f.failure.get() == null, "HTTP fixture failed: " + f.failure.get());
            System.out.println("RECONCILIATION_BUDGET input=" + JSON.writeValueAsString(expected(in)).length()
                    + " encodedPrompt=" + f.prompts.getFirst().length() + " calls=" + f.prompts.size());
        }
    }

    static void budget() throws Exception {
        var values = new ArrayList<String>();
        for (int i = 0; i < 8; i++) values.add("Unique " + i + " " + "No omitted conditions. ".repeat(300));
        var in = input(values);
        try (var f = new Fixture(16000, false)) {
            try { f.nodes.reconcile(in); throw new AssertionError("Irreducible contexts were truncated"); }
            catch (IllegalStateException expected) { check(expected.getMessage().startsWith("INPUT_TOO_LARGE_FOR_PROVIDER"), "Wrong limit error"); }
            check(f.prompts.isEmpty(), "Irreducible input sent an oversized request");
            assertRoundTrip(in, data(new ReconcilePromptBuilder(JSON).build(in, null)));
        }
    }

    static void phaseB() throws Exception {
        var in = input(Collections.nCopies(8, "Boundary decision context unchanged. ".repeat(110)));
        var draft = new ReformulationDocument("Existing draft", in.sections(), in.statements(), in.questions(),
                new ValidationReport(List.of()), List.of());
        String before = JSON.writeValueAsString(draft);
        try (var f = new Fixture(16000, false)) {
            var result = new CrossTaxonomyReconciler(f.nodes, JSON).reconcile(in.baseline(), draft, in.answers(), in.questions());
            check(f.prompts.size() == 1, "Unchanged full-graph review should use one call");
            check(result.questions().size() == 8, "Reconciliation merged independent questions");
            check(result.questions().stream().filter(q -> q.id().equals("q-0")).findFirst().orElseThrow().state()
                    == DecisionQuestion.State.ANSWERED, "Source resolution was not applied to its own question");
            check(result.text().contains(ORIGINAL), "Whole original lost from result");
            check(before.equals(JSON.writeValueAsString(draft)), "Phase B mutated its input draft");
            check(data(f.prompts.getFirst()).path("boundaryEdges").has("edge-1"), "Directed boundary was dropped");
        }
    }

    private static ReconciliationInput input(List<String> contexts) {
        var frozen = new TreeMap<>(ReconcilePromptBuilder.freeze(Map.of()));
        frozen.put("catalogue", "[{\"code\":\"BP\",\"descriptionEn\":\"Process\"},{\"code\":\"UA\",\"descriptionEn\":\"Application\"}]");
        frozen.put("relationMappings", "[{\"id\":1,\"sourceCode\":\"BP\",\"targetCode\":\"UA\",\"relationType\":\"SUPPORTS\",\"reviewStatus\":\"PROPOSED\"}]");
        var b = new ReformulationBaseline(new ReformulationBaseline.Scope("repo", "workspace", "draft", 1, 1),
                1, ORIGINAL, StableIdentityHash.sha256(ORIGINAL), "snapshot", "{\"rawScores\":{\"BP\":50,\"UA\":50}}", frozen, "de", "walk-up-v1");
        var s = new Statement("original", ORIGINAL, List.of(new Statement.SourceSpan(0, ORIGINAL.length(), ORIGINAL)),
                Statement.Provenance.ORIGINAL, List.of("BP", "UA"), List.of(), "Keine automatische Übernahme",
                Statement.EditingOrigin.SOURCE, "UNREVIEWED");
        var questions = new ArrayList<DecisionQuestion>();
        for (int i = 0; i < contexts.size(); i++) questions.add(new DecisionQuestion("q-" + i,
                new DecisionQuestion.Key("time", "capture", "independent-" + i), "Wie wird erfasst?",
                List.of(new DecisionQuestion.Discovery(i % 2 == 0 ? "BP" : "UA", contexts.get(i), "Original klären",
                        List.of(s.sourceSpans().getFirst()), List.of("BP", "UA"), List.of("edge-1"))), List.of(s.id()),
                new DecisionQuestion.AnswerSchema(DecisionQuestion.AnswerSchema.Kind.SINGLE_CHOICE, List.of("Terminal", "Browser"), null, null, null),
                i == 0 ? List.of() : List.of("q-0"), List.of(), "Erfassungsart festlegen", DecisionQuestion.State.OPEN));
        var sections = List.of(new Section("BP", "BP", "Process", "Erfassung", List.of(), List.of(s.id()), questions.stream().map(DecisionQuestion::id).toList()),
                new Section("UA", "UA", "Application", "Bedienung", List.of(), List.of(s.id()), List.of()));
        return new ReconciliationInput(b, 1, sections, List.of(s), questions, List.of(),
                Map.of("edge-1", JSON.readTree(frozen.get("relationMappings")).get(0).toString()),
                Map.of("shared-time", List.of(s.id())), List.of(s.id()));
    }

    private static ObjectNode expected(ReconciliationInput in) {
        var expected = (ObjectNode) JSON.valueToTree(in);
        expected.set("frozenNodeContext", JSON.valueToTree(new ReconcilePromptBuilder(JSON).scopedContext(in.baseline(), Set.of("BP", "UA"))));
        var baseline = (ObjectNode) expected.path("baseline"); baseline.remove("snapshotPayload");
        ((ObjectNode) baseline.path("frozenContext")).retain("project", "sourceVersion", "reconcilePromptVersion", "reconcileSchemaVersion");
        return (ObjectNode) JSON.readTree(JSON.writeValueAsString(expected));
    }
    private static void assertRoundTrip(ReconciliationInput in, JsonNode encoded) {
        var decoded = (ObjectNode) encoded.deepCopy(); var table = decoded.remove("discoveryContextTable");
        restore(decoded, table);
        check(decoded.equals(expected(in)), "Encoded request cannot reconstruct every original field exactly");
    }
    private static void restore(JsonNode value, JsonNode table) {
        if (value instanceof ObjectNode object) {
            if (object.has("contextRef")) {
                var ref = object.remove("contextRef");
                check(table != null && table.path(ref.asText()).isString(), "Context reference leaves this request");
                check(!object.has("context"), "Ambiguous inline and referenced context"); object.set("context", table.path(ref.asText()));
            }
            object.properties().forEach(e -> restore(e.getValue(), table));
        } else if (value.isArray()) value.forEach(v -> restore(v, table));
    }
    private static JsonNode data(String prompt) {
        int begin = prompt.indexOf(MARKER); check(begin >= 0, "Missing reconciliation payload");
        String body = prompt.substring(begin + MARKER.length()); int end = body.indexOf("\nVALIDATION_ERRORS:");
        return JSON.readTree(end < 0 ? body : body.substring(0, end));
    }

    private static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final List<String> prompts = Collections.synchronizedList(new ArrayList<>());
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AiPromptBudgetPolicy policy;
        final NodeReformulationService nodes;
        Fixture(int maxChars, boolean repair) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                try {
                    var request = JSON.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    var prompt = request.at("/messages/0/content").asText(); prompts.add(prompt); var payload = data(prompt);
                    check(payload.at("/baseline/originalText").asText().equals(ORIGINAL), "Provider did not receive full original");
                    var resolution = Map.of("questionId", "q-0", "values", List.of("Terminal"), "rationale", "Explicit original statement",
                            "sourceSpans", List.of(Map.of("start", 0, "end", ORIGINAL.length(), "exactText", ORIGINAL)));
                    Object reply = repair && prompts.size() == 1 ? Map.of("invalid", true)
                            : Map.of("affectedSectionIds", List.of(), "sourceResolutions", List.of(resolution), "findings", List.of());
                    byte[] bytes = JSON.writeValueAsBytes(Map.of("choices", List.of(Map.of("message", Map.of("content", JSON.writeValueAsString(reply)), "finish_reason", "stop"))));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
                } catch (Throwable error) { failure.set(error); exchange.sendResponseHeaders(500, -1); }
                finally { exchange.close(); }
            });
            server.start();
            var config = new LlmProviderConfig(null);
            set(config, "llmProviderConfig", "CUSTOM_OPENAI"); set(config, "customLlmUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
            set(config, "customLlmModel", "reconciliation-context-test"); set(config, "customLlmApiKey", "");
            var target = new AiTargetDescriptor("test", "Test", "CUSTOM_OPENAI", "reconciliation-context-test", AiTargetMode.REMOTE, AiTargetHealth.READY,
                    true, false, false, new PromptBudget(maxChars, maxChars * 4, (maxChars + 3) / 4), "test", null);
            policy = new AiPromptBudgetPolicy(null) { @Override public AiTargetDescriptor requireWithinBudget(String text, String provider) { return super.requireWithinBudget(text, target); } };
            nodes = new NodeReformulationService(new LlmGatewayRegistry(config, new RestTemplate(), JSON, null, null, null, policy), config, JSON);
        }
        public void close() { server.stop(0); }
    }
    private static void set(Object object, String field, Object value) throws Exception { var f = object.getClass().getDeclaredField(field); f.setAccessible(true); f.set(object, value); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
