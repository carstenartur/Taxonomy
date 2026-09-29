package com.taxonomy.acceptance;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;

/** Test-only semantic response corpus. No live fallback is possible. */
public final class ScenarioLlmPlayback {
    private static final Pattern KEYS = Pattern.compile("EXACTLY these keys: ([^\\r\\n]+)");
    private static final Pattern BUDGET = Pattern.compile("distribute the parent relevance score of (\\d+)");
    private static final Pattern REQUIREMENT = Pattern.compile("Business Requirement: (.*?)\\n\\s*\\n(?=(?:(?:[^\\r\\n]*Categories|Concrete Information Product candidates|Nodes to evaluate):\\r?\\n|Taxonomy root: [^\\r\\n]+\\r?\\n))", Pattern.DOTALL);
    private static final Set<String> ROOT_CODES = Set.of("BP", "BR", "CI", "CO", "CP", "CR", "IP", "UA");
    private static final String ROOT_PROMPT = "You assess the independent relevance of the offered C3 taxonomy root";
    private final ObjectMapper json = new ObjectMapper();
    private final JsonNode fixture;
    private final ScenarioRelationPlayback relationPlayback;
    private final ScenarioReformulationPlayback reformulationPlayback;
    private final Map<String, JsonNode> rules = new LinkedHashMap<>();
    private final List<Call> calls = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();
    private final List<String> prompts = new ArrayList<>();
    private final List<ScenarioLlmPlayback> adoptedSources = new ArrayList<>();
    private ResponsePause nextReformulationPause;

    /** Hold one validated remote response while the real HTTP client changes the proposal. */
    public synchronized ResponsePause pauseNextReformulation() {
        require(nextReformulationPause == null, "A provider response is already armed");
        return nextReformulationPause = new ResponsePause();
    }
    public static final class ResponsePause implements AutoCloseable {
        private final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.CountDownLatch released = new java.util.concurrent.CountDownLatch(1);
        public boolean awaitRequest() throws InterruptedException { return entered.await(40, java.util.concurrent.TimeUnit.SECONDS); }
        private void hold() {
            entered.countDown();
            try { require(released.await(60, java.util.concurrent.TimeUnit.SECONDS), "Paused provider response timed out"); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
        }
        @Override public void close() { released.countDown(); }
    }

    public ScenarioLlmPlayback(JsonNode fixture) {
        this.fixture = fixture.deepCopy();
        this.relationPlayback = new ScenarioRelationPlayback(this.fixture);
        this.reformulationPlayback = new ScenarioReformulationPlayback(this.fixture);
        require(fixture.path("schemaVersion").asInt() == 1, "Unsupported scenario schema");
        require(!fixture.at("/requirement/text").asText().isBlank(), "Missing requirement");
        Set<String> ids = new HashSet<>();
        for (JsonNode rule : fixture.path("replies")) {
            String id = rule.path("id").asText();
            require(!id.isBlank() && ids.add(id), "Duplicate or missing response ID: " + id);
            List<String> keys = strings(rule.path("keys"));
            require(!keys.isEmpty() && new HashSet<>(keys).size() == keys.size(), "Duplicate or missing keys: " + id);
            String task = rule.path("task").asText();
            require(Set.of("categories", "products").contains(task), "Unknown task: " + id);
            int total = 0;
            for (var entry : rule.path("answers").properties()) {
                var answer = entry.getValue();
                require(keys.contains(entry.getKey()), "Answer outside requested keys: " + id);
                int score = answer.path("score").asInt(-1);
                require(score >= 0 && score <= 100 && !answer.path("reason").asText().isBlank(), "Invalid answer: " + id);
                total += score;
            }
            require(!rule.path("excludedReason").asText().isBlank(), "Missing exclusion reason: " + id);
            boolean independentRoot = keys.size() == 1 && ROOT_CODES.contains(keys.getFirst());
            require(!task.equals("categories") || independentRoot || total == rule.path("parentScore").asInt(), "Budget mismatch: " + id);
            String signature = signature(task, rule.path("parentScore").asInt(-1), keys);
            require(rules.putIfAbsent(signature, rule.deepCopy()) == null, "Duplicate response scope: " + id);
        }
        require(!rules.isEmpty(), "Empty response corpus");
    }
    public static ScenarioLlmPlayback flood() throws Exception {
        try (var input = ScenarioLlmPlayback.class.getResourceAsStream("/scenarios/flood-information.json")) {
            return new ScenarioLlmPlayback(new ObjectMapper().readTree(input));
        }
    }
    public JsonNode fixture() { return fixture.deepCopy(); }

    /** Test driver registers only text read back after its explicit authenticated adoption command. */
    public synchronized void registerAdoptedSource(String source) {
        require(fixture.path("scenarioReformulation").asBoolean(false), "Only the scenario workflow binds adoption sources");
        require(source.contains(fixture.at("/requirement/text").asText()) && !source.equals(fixture.at("/requirement/text").asText()),
                "Adopted fixture must retain the complete original and add reviewed offer text");
        require(adoptedSources.stream().noneMatch(p -> p.fixture.at("/requirement/text").asText().equals(source)), "Duplicate adoption source");
        var next = (tools.jackson.databind.node.ObjectNode) fixture.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) next.path("requirement")).put("text", source);
        adoptedSources.add(new ScenarioLlmPlayback(next));
    }

    public synchronized String respond(String prompt) {
        prompts.add(prompt);
        try {
            if (!adoptedSources.isEmpty()) {
                String source = requestSource(prompt);
                for (var next : adoptedSources) if (normalize(source).equals(normalize(next.fixture.at("/requirement/text").asText())))
                    return next.respond(prompt);
            }
            if (ScenarioReformulationPlayback.accepts(prompt)) {
                var reply = reformulationPlayback.respond(prompt);
                var pause = nextReformulationPause; nextReformulationPause = null;
                if (pause != null) pause.hold();
                String body = json.writeValueAsString(Map.of("choices", List.of(Map.of("message",
                        Map.of("role", "assistant", "content", reply.content())))));
                calls.add(new Call(reply.id(), sha256(prompt), sha256(body)));
                return body;
            }
            if (prompt.startsWith(ScenarioRelationPlayback.PREFIX)) {
                var reply = relationPlayback.respond(prompt);
                String body = json.writeValueAsString(Map.of("choices", List.of(Map.of("message",
                        Map.of("role", "assistant", "content", reply.content())))));
                calls.add(new Call(reply.id(), sha256(prompt), sha256(body)));
                return body;
            }
            String requirement = unique(REQUIREMENT, prompt, "requirement");
            require(normalize(requirement).equals(normalize(fixture.at("/requirement/text").asText())), "Unknown scenario requirement");
            String keyText = unique(KEYS, prompt, "requested keys");
            List<String> keys = Arrays.stream(keyText.split(",")).map(String::strip).toList();
            require(new HashSet<>(keys).size() == keys.size(), "Duplicate requested keys");
            boolean products = prompt.contains("Score every candidate independently from 0 to 100");
            boolean root = prompt.startsWith(ROOT_PROMPT);
            require(!(products && root), "Ambiguous task");
            if (root) {
                require(keys.size() == 1 && ROOT_CODES.contains(keys.getFirst()), "Unknown root question");
                require(prompt.contains("\n" + keys.getFirst() + ": "), "Missing offered root node");
            }
            int budget = products ? -1 : root ? 100 : Integer.parseInt(unique(BUDGET, prompt, "category task and budget"));
            require(!(products || root) || !BUDGET.matcher(prompt).find(), "Ambiguous task");
            JsonNode rule = rules.get(signature(products ? "products" : "categories", budget, keys));
            require(rule != null, "Unknown response scope: " + String.join(",", keys));
            var answer = json.createObjectNode();
            for (String key : new TreeSet<>(keys)) {
                JsonNode explicit = rule.path("answers").get(key);
                answer.set(key, explicit == null
                        ? json.createObjectNode().put("score", 0).put("reason", rule.path("excludedReason").asText())
                        : explicit.deepCopy());
            }
            String body = json.writeValueAsString(Map.of("choices", List.of(Map.of("message",
                    Map.of("role", "assistant", "content", json.writeValueAsString(answer))))));
            calls.add(new Call(rule.path("id").asText(), sha256(prompt), sha256(body)));
            return body;
        } catch (RuntimeException failure) {
            failures.add(failure.getMessage());
            throw failure instanceof IllegalArgumentException invalid ? invalid
                    : new IllegalArgumentException("Invalid scenario request", failure);
        }
    }

    public synchronized List<Call> calls() { var all = new ArrayList<>(calls); adoptedSources.forEach(p -> all.addAll(p.calls())); return List.copyOf(all); }
    public synchronized List<String> prompts() { return List.copyOf(prompts); }
    public synchronized List<String> failures() { return List.copyOf(failures); }
    private String requestSource(String prompt) {
        for (String marker : List.of("\nINPUT_DATA_JSON\n", "\nRECONCILIATION_DATA_JSON\n")) {
            int at = prompt.indexOf(marker);
            if (at >= 0) return json.readTree(prompt.substring(at + marker.length()).split("\\nVALIDATION_ERRORS", 2)[0]).at("/baseline/originalText").asText();
        }
        if (prompt.startsWith(ScenarioRelationPlayback.PREFIX)) return json.readTree(prompt.substring(prompt.indexOf("INPUT\n") + 6)).path("original").asText();
        return unique(REQUIREMENT, prompt, "requirement");
    }
    public synchronized void reject(String reason) {
        failures.add(reason);
        throw new IllegalArgumentException(reason);
    }

    public synchronized void verifyCoverage(int repetitions) {
        if (!failures.isEmpty()) throw new AssertionError("Unmatched LLM calls: " + failures);
        for (var rule : rules.values()) {
            String id = rule.path("id").asText();
            long actual = calls.stream().filter(call -> call.ruleId().equals(id)).count();
            if (actual != repetitions) throw new AssertionError(id + ": expected " + repetitions + " calls, got " + actual);
        }
    }

    public synchronized void verifyRelationCoverage(int repetitions) {
        if (!failures.isEmpty()) throw new AssertionError("Unmatched LLM calls: " + failures);
        relationPlayback.verifyCoverage(repetitions);
        Map<String, Long> frequencies = calls.stream().filter(call -> call.ruleId().startsWith("relation:"))
                .collect(java.util.stream.Collectors.groupingBy(Call::ruleId, java.util.stream.Collectors.counting()));
        for (var entry : frequencies.entrySet()) {
            if (entry.getValue() != repetitions) {
                throw new AssertionError("Unstable repeated relation query " + entry.getKey() + ": " + entry.getValue());
            }
        }
    }

    public synchronized void verifyReformulationCoverage(int repetitions) {
        if (!failures.isEmpty()) throw new AssertionError("Unmatched LLM calls: " + failures);
        for (var rule : fixture.path("reformulationReplies")) {
            String id = rule.path("id").asText();
            long actual = calls.stream().filter(call -> call.ruleId().equals(id)).count();
            if (actual != repetitions) throw new AssertionError(id + ": expected " + repetitions + " calls, got " + actual);
        }
    }

    private static String signature(String task, int budget, List<String> keys) {
        return task + ":" + budget + ":" + String.join(",", new TreeSet<>(keys));
    }
    private static List<String> strings(JsonNode node) {
        var result = new ArrayList<String>();
        node.forEach(value -> result.add(value.asText()));
        return result;
    }
    private static String unique(Pattern pattern, String prompt, String label) {
        var matcher = pattern.matcher(prompt);
        require(matcher.find(), "Missing " + label);
        String value = matcher.group(1);
        require(!matcher.find(), "Ambiguous " + label);
        return value;
    }
    private static String normalize(String text) { return text.replaceAll("\\s+", " ").strip(); }
    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message);
    }
    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
    public record Call(String ruleId, String promptSha256, String responseSha256) { }
}
