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
    private static final Pattern REQUIREMENT = Pattern.compile("Business Requirement: (.*?)\\n\\s*\\n", Pattern.DOTALL);
    private final ObjectMapper json = new ObjectMapper();
    private final JsonNode fixture;
    private final Map<String, JsonNode> rules = new LinkedHashMap<>();
    private final List<Call> calls = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();

    public ScenarioLlmPlayback(JsonNode fixture) {
        this.fixture = fixture.deepCopy();
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
            require(!task.equals("categories") || total == rule.path("parentScore").asInt(), "Budget mismatch: " + id);
            String signature = signature(task, rule.path("parentScore").asInt(-1), keys);
            require(rules.putIfAbsent(signature, rule.deepCopy()) == null, "Duplicate response scope: " + id);
        }
        require(!rules.isEmpty(), "Empty response corpus");
    }
    public static ScenarioLlmPlayback flood() throws Exception {
        try (var input = ScenarioLlmPlayback.class.getResourceAsStream("/scenarios/civilian-flood.json")) {
            return new ScenarioLlmPlayback(new ObjectMapper().readTree(input));
        }
    }
    public JsonNode fixture() { return fixture.deepCopy(); }

    public synchronized String respond(String prompt) {
        try {
            String requirement = unique(REQUIREMENT, prompt, "requirement");
            require(normalize(requirement).equals(normalize(fixture.at("/requirement/text").asText())), "Unknown scenario requirement");
            String keyText = unique(KEYS, prompt, "requested keys");
            List<String> keys = Arrays.stream(keyText.split(",")).map(String::strip).toList();
            require(new HashSet<>(keys).size() == keys.size(), "Duplicate requested keys");
            boolean products = prompt.contains("Score every candidate independently from 0 to 100");
            int budget = products ? -1 : Integer.parseInt(unique(BUDGET, prompt, "category task and budget"));
            require(!products || !BUDGET.matcher(prompt).find(), "Ambiguous task");
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
        } catch (IllegalArgumentException failure) {
            failures.add(failure.getMessage());
            throw failure;
        }
    }

    public synchronized List<Call> calls() { return List.copyOf(calls); }
    public synchronized List<String> failures() { return List.copyOf(failures); }
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
