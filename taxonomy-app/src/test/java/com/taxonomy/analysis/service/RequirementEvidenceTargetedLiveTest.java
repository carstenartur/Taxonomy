package com.taxonomy.analysis.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.tooling.InteractiveCompletionRelay;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Opt-in fresh component regression, using recorded inputs, never recorded answers.
 * This intentionally does not claim a traversal, a portfolio snapshot or end-to-end UI coverage.
 * A separate assistant must supply the actual relay requests without seeing the evaluation oracle.
 */
@Tag("real-llm")
class RequirementEvidenceTargetedLiveTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String OLD_POLICY = "\nMandatory passage evidence policy (requirement-node-assessment-v2).";
    private static final String NEXT_POLICY = "\nMandatory analysis output language";

    @Test
    @EnabledIfSystemProperty(named = "taxonomy.evidence.targeted", matches = "true")
    void freshRecordedChildCalls() throws Exception {
        run();
    }

    public static void main(String[] args) throws Exception {
        run();
    }

    @SuppressWarnings("unchecked")
    private static void run() throws Exception {
        Path output = Path.of(requiredProperty("taxonomy.evidence.targeted.output"));
        Path spool = Path.of(requiredProperty("taxonomy.evidence.targeted.spool"));
        Files.createDirectories(output);
        require(!Files.exists(output.resolve("session.json")) && !Files.exists(output.resolve("summary.json")),
                "Use a fresh output directory; do not overwrite a completed or interrupted run");
        if (Files.exists(spool)) {
            try (var entries = Files.list(spool)) {
                require(entries.findAny().isEmpty(), "Use an empty relay spool; never reuse previous answers");
            }
        }
        JsonNode manifest = JSON.readTree(output.resolve("manifest.json").toFile());
        require(manifest.path("scope").asText().equals("TARGETED_RECORDED_CHILD_CALLS"), "Explicit component scope required");
        var summaries = new ArrayList<Map<String, Object>>();
        var freshIds = new HashSet<String>();
        var previousIds = new HashSet<String>();
        manifest.required("calls").forEach(call -> previousIds.add(call.required("originalCompletionId").asText()));
        String model = "assistant-relay-session";
        try (var relay = InteractiveCompletionRelay.start(spool, 0, model, Duration.ofMinutes(5))) {
            String url = "http://127.0.0.1:" + relay.boundPort() + "/v1/chat/completions";
            var factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofSeconds(5));
            factory.setReadTimeout(Duration.ofMinutes(6));
            var parser = new LlmResponseParser(JSON);
            var gateway = new OpenAiCompatibleGateway(LlmProvider.CUSTOM_OPENAI, url, model, 120,
                    new RestTemplate(factory), JSON, parser, null, factory, null);
            write(output.resolve("session.json"), Map.of(
                    "scope", "TARGETED_RECORDED_CHILD_CALLS", "startedAt", Instant.now().toString(),
                    "endpoint", url, "model", model, "policy", PromptTemplateService.NODE_ASSESSMENT_POLICY,
                    "freshInference", true, "replayEnabled", false,
                    "limitations", "Fresh child-call component regressions; no full traversal, adoption, saved snapshot, UI verification or held-out benchmark."));
            for (JsonNode call : manifest.required("calls")) {
                String label = call.required("label").asText();
                require(label.matches("[a-z0-9-]+"), "Safe result label required");
                var result = new LinkedHashMap<String, Object>();
                result.put("scope", "RECORDED_CHILD_CALL");
                result.put("case", call.required("case").asText());
                result.put("label", label);
                result.put("originalCompletionId", call.required("originalCompletionId").asText());
                try {
                    JsonNode request = JSON.readTree(Path.of(call.required("originalHttpRequest").asText()).toFile());
                    JsonNode record = JSON.readTree(Path.of(call.required("originalProviderRecord").asText()).toFile());
                    require(request.path("messages").size() == 1, "One original user message required");
                    require(request.at("/messages/0/role").asText().equals("user"), "Original role must be preserved");
                    String originalPrompt = request.at("/messages/0/content").asText();
                    require(originalPrompt.equals(record.required("promptText").asText()), "Recorded inputs must agree");
                    int start = originalPrompt.indexOf(OLD_POLICY);
                    int end = originalPrompt.indexOf(NEXT_POLICY, Math.max(start, 0));
                    require(start >= 0 && end > start && originalPrompt.indexOf(OLD_POLICY, start + 1) < 0,
                            "Exactly one old evidence policy and its language boundary are required");
                    String prompt = originalPrompt.substring(0, start) + PromptTemplateService.nodeAssessmentContract()
                            + originalPrompt.substring(end);
                    require(!prompt.contains(OLD_POLICY) && prompt.contains(PromptTemplateService.NODE_ASSESSMENT_POLICY),
                            "Replace only the evidence policy with the actual production contract");
                    String requirement = call.required("requirement").asText();
                    require(originalPrompt.contains(requirement) && prompt.contains(requirement), "Keep original requirement bytes");
                    int tokens = request.required("max_tokens").asInt();
                    gateway.configureCompletionLimits(true, tokens);
                    JsonNode schema = request.at("/response_format/json_schema");
                    var options = new LlmCompletionOptions(schema.required("name").asText(),
                            JSON.convertValue(schema.required("schema"), Map.class), tokens);
                    var nodes = new ArrayList<TaxonomyNode>();
                    var offered = new ArrayList<String>();
                    call.required("candidateIds").forEach(id -> {
                        var node = new TaxonomyNode();
                        node.setCode(id.asText());
                        node.setTaxonomyRoot(id.asText().split("-", 2)[0]);
                        nodes.add(node);
                        offered.add(id.asText());
                    });
                    Set<String> schemaIds = new HashSet<>();
                    schema.at("/schema/properties").propertyNames().forEach(schemaIds::add);
                    require(schemaIds.equals(Set.copyOf(offered)), "Preserve all original offered candidates");
                    double budget = call.required("parentBudget").asDouble();
                    write(output.resolve(label + "-original-request.json"), request);
                    Files.writeString(output.resolve(label + "-prompt.txt"), prompt, StandardCharsets.UTF_8);
                    System.out.println("FRESH_REQUEST " + label + " candidates=" + offered.size());
                    String body = gateway.sendHttpRequest(prompt, null, options);
                    require(body != null, "Gateway must return a real HTTP response");
                    Files.writeString(output.resolve(label + "-response.json"), body, StandardCharsets.UTF_8);
                    JsonNode envelope = JSON.readTree(body);
                    String completionId = envelope.required("id").asText();
                    require(completionId.startsWith("chatcmpl-") && !previousIds.contains(completionId)
                            && freshIds.add(completionId), "Every completion must have a fresh unique identity");
                    require(envelope.required("model").asText().equals(model), "Expected relay model");
                    require(!envelope.has("usage"), "Do not invent token usage");
                    var parsed = parser.parseScoreParseResult(gateway.extractResponseText(body), nodes, budget,
                            ScoreAssessmentKind.CHILD_BUDGET, requirement);
                    result.put("completionId", completionId);
                    result.put("requirement", requirement);
                    result.put("requirementSha256", sha256(requirement));
                    result.put("promptSha256", sha256(prompt));
                    result.put("originalPromptSha256", sha256(originalPrompt));
                    result.put("candidateIds", offered);
                    result.put("parentBudget", budget);
                    result.put("scores", parsed.scores());
                    result.put("reasons", parsed.reasons());
                    result.put("nodeAssessments", parsed.nodeAssessments());
                    result.put("discrepancy", parsed.discrepancy());
                    String uuid = completionId.substring("chatcmpl-".length());
                    Path receiptFile = spool.resolve("receipts").resolve(uuid + ".json");
                    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                    while (!Files.isRegularFile(receiptFile) && System.nanoTime() < deadline) Thread.sleep(25);
                    JsonNode receipt = JSON.readTree(receiptFile.toFile());
                    require(receipt.required("outcome").asText().equals("delivered"), "Actual successful delivery receipt required");
                    require(receipt.required("provenance").asText().equals("assistant-relay"), "Actual relay provenance required");
                    require(receipt.required("deliveredSha256").asText().equals(sha256(body)), "Delivered byte hash must match");
                    JsonNode actualRequest = JSON.readTree(spool.resolve("requests").resolve(uuid + ".json").toFile());
                    require(actualRequest.at("/messages/0/content").asText().equals(prompt), "Actual transport must send current prompt");
                    require(actualRequest.path("response_format").equals(request.path("response_format")), "Original response schema unchanged");
                    result.put("receipt", receipt);
                    result.put("transportAndParserOutcome", "PASSED");
                    result.put("semanticOutcome", "REQUIRES_SEPARATE_REVIEW");
                    System.out.println("FRESH_PARSED " + label + " claims="
                            + parsed.nodeAssessments().values().stream().mapToInt(List::size).sum());
                } catch (Exception failure) {
                    result.put("transportAndParserOutcome", "FAILED");
                    result.put("failure", failure.getClass().getName() + ": " + failure.getMessage());
                    System.out.println("FRESH_FAILED " + label + " " + failure.getClass().getSimpleName());
                }
                write(output.resolve(label + "-result.json"), result);
                summaries.add(result);
            }
        }
        long passed = summaries.stream().filter(r -> "PASSED".equals(r.get("transportAndParserOutcome"))).count();
        write(output.resolve("summary.json"), Map.of(
                "scope", "TARGETED_RECORDED_CHILD_CALLS", "finishedAt", Instant.now().toString(),
                "calls", summaries.size(), "transportAndParserPassed", passed,
                "freshCompletionIds", freshIds, "semanticOutcome", "REQUIRES_SEPARATE_REVIEW"));
        require(passed == summaries.size() && passed == manifest.required("calls").size(), "Every planned component call must pass transport and parser checks");
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        require(value != null && !value.isBlank(), "Missing explicit opt-in input: " + name);
        return value;
    }

    private static void write(Path path, Object value) throws Exception {
        Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value), StandardCharsets.UTF_8);
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
