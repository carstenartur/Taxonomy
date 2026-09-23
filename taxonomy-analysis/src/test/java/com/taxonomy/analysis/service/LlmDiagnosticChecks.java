package com.taxonomy.analysis.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Executable production-boundary checks; only the remote provider reply is a fixture. */
public final class LlmDiagnosticChecks {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final WorkspaceContext SCOPE = new WorkspaceContext("owner", "workspace", "draft");
    private static final String REPLY = "please supply additional details.\nNo JSON was returned. <script>not executable</script>";

    private LlmDiagnosticChecks() { }

    public static void main(String[] args) throws Exception {
        for (String name : args) {
            switch (name) {
                case "failure" -> failedReplyRemainsInspectable();
                case "bounded" -> diagnosticLengthsAndLimits();
                case "transport" -> transportFailureIsNotAnEmptySuccess();
                case "non-json" -> nonJsonIsActionableAndNotSuccess();
                case "quoted-json" -> quotedBracesAndFencesRemainIntact();
                case "malformed" -> malformedOuterObjectIsNotSalvaged();
                default -> throw new IllegalArgumentException(name);
            }
            System.out.println("PASS " + name);
        }
    }

    static void failedReplyRemainsInspectable() throws Exception {
        AnalysisProgressRegistry registry = new AnalysisProgressRegistry(new StandardEnvironment());
        String id = UUID.randomUUID().toString();
        try (var reservation = registry.reserve(id, "owner", SCOPE, null);
             var handle = reservation.open()) {
            LlmCallDetail detail = service(REPLY).analyzeSingleBatchDetailed("Civilian requirement", List.of(node()), 100);
            require(REPLY.equals(detail.getRawResponse()), "Service must preserve the exact invalid reply");
            require(detail.getError() != null, "Non-JSON must remain a failed analysis");
            require(detail.getScores().equals(Map.of("IP", 0)), "No fabricated success scores");
            handle.finish("PARTIAL");
            var snapshot = registry.snapshot(id, "owner", SCOPE);
            require("FAILED".equals(snapshot.calls().getFirst().status()), "Call must be visibly failed");
            var saved = registry.callDetail(id, 1, "owner", SCOPE);
            Map<?, ?> fields = JSON.readValue(JSON.writeValueAsString(saved), Map.class);
            require(detail.getError().equals(fields.get("error")), "Diagnostic endpoint loses the actual parse error");
            require(REPLY.equals(saved.response()), "Failed response must survive terminal completion");
            require(saved.prompt().contains("Civilian requirement"), "Prompt must survive failure");
            expectHidden(() -> registry.callDetail(id, 1, "another-owner", SCOPE));
            expectHidden(() -> registry.callDetail(id, 1, "owner", new WorkspaceContext("owner", "other", "draft")));
        }
    }

    static void malformedJsonKeepsVisibleFailureAndOriginalEvidence() {
        String raw = "```json\n{\n  \"IP\": {\"score\": 100, \"reason\": \"complete child\"}\n```";
        var registry = new AnalysisProgressRegistry(new StandardEnvironment());
        String id = UUID.randomUUID().toString();
        try (var reservation = registry.reserve(id, "owner", SCOPE, null);
             var handle = reservation.open()) {
            var detail = service(raw).analyzeSingleBatchDetailed("Civilian requirement", List.of(node()), 100);
            require(detail.getError() != null && detail.getError().contains("Invalid JSON in LLM response"),
                    "The actual service must expose the concise parser diagnostic");
            require(raw.equals(detail.getRawResponse()), "No repaired or shortened answer may replace raw evidence");
            handle.finish("PARTIAL");
            var snapshot = registry.snapshot(id, "owner", SCOPE);
            require("PARTIAL".equals(snapshot.status()), "Malformed response is not a completed analysis");
            require("FAILED".equals(snapshot.calls().getFirst().status()), "The log call must remain failed");
            require(snapshot.calls().getFirst().startedAt() > 0, "Server start time must survive failure");
            var saved = registry.callDetail(id, 1, "owner", SCOPE);
            require(raw.equals(saved.response()) && detail.getError().equals(saved.error()),
                    "The diagnostic API must retain both the raw answer and its format error");
        }
    }

    static void diagnosticLengthsAndLimits() throws Exception {
        AnalysisProgressRegistry registry = new AnalysisProgressRegistry(new StandardEnvironment());
        String id = UUID.randomUUID().toString();
        try (var reservation = registry.reserve(id, "owner", SCOPE, null);
             var handle = reservation.open()) {
            LlmCallDetail source = new LlmCallDetail();
            source.setPrompt("p".repeat(20_000));
            source.setRawResponse("response\n".repeat(2_000));
            source.setError("error".repeat(4_000));
            source.setScores(Map.of());
            AnalysisRunControl.call("GEMINI", "IP", () -> source);
            var saved = registry.callDetail(id, 1, "owner", SCOPE);
            Map<?, ?> fields = JSON.readValue(JSON.writeValueAsString(saved), Map.class);
            require(Integer.valueOf(20_000).equals(fields.get("promptLength")), "Missing original prompt length");
            require(Integer.valueOf(18_000).equals(fields.get("responseLength")), "Missing original response length");
            require(saved.truncated() && saved.prompt().length() <= AnalysisProgressRegistry.MAX_TEXT
                    && saved.response().length() <= AnalysisProgressRegistry.MAX_TEXT, "Existing memory limits must remain");
            require(fields.get("error") instanceof String error && error.length() <= 1024, "Error evidence must also be bounded");
        }
    }

    static void transportFailureIsNotAnEmptySuccess() throws Exception {
        AnalysisProgressRegistry registry = new AnalysisProgressRegistry(new StandardEnvironment());
        String id = UUID.randomUUID().toString();
        try (var reservation = registry.reserve(id, "owner", SCOPE, null);
             var handle = reservation.open()) {
            try {
                AnalysisRunControl.call("GEMINI", "IP", () -> { throw new IllegalStateException("private transport details"); });
                throw new AssertionError("Expected transport exception");
            } catch (IllegalStateException expected) {
                var saved = registry.callDetail(id, 1, "owner", SCOPE);
                Map<?, ?> fields = JSON.readValue(JSON.writeValueAsString(saved), Map.class);
                require("IllegalStateException".equals(fields.get("error")), "Transport failure kind is discarded");
                require(saved.response().isEmpty(), "Do not invent a provider response");
            }
        }
    }

    static void nonJsonIsActionableAndNotSuccess() throws Exception {
        LlmCallDetail detail = service(REPLY).analyzeSingleBatchDetailed("Civilian requirement", List.of(node()), 100);
        require(detail.getError() != null && detail.getError().contains("JSON object")
                && detail.getError().contains("LLM communication log"), "Non-JSON error must identify the contract and diagnostic location");
        require(REPLY.equals(detail.getRawResponse()), "The actionable message must not replace the raw reply");
        require(!detail.getError().contains("please supply"), "Do not leak response contents into generic error messages");
    }

    static void quotedBracesAndFencesRemainIntact() throws Exception {
        var parser = new LlmResponseParser(JSON);
        for (String reason : List.of("Use {a} then {b}", "Preserve ```json and ``` literally", "A quote: \" and a backslash: \\", "Only one } brace")) {
            String json = JSON.writeValueAsString(Map.of("IP", Map.of("score", 100, "reason", reason)));
            String wrapped = "Here is the result:\n```json\n" + json + "\n```\n";
            require(json.equals(parser.extractJson(wrapped)), "JSON extraction corrupts quoted braces or code fences: " + reason);
            var result = parser.parseScoreParseResult(wrapped, List.of(node()), 100);
            require(result.scores().equals(Map.of("IP", 100)) && reason.equals(result.reasons().get("IP")), "Valid score/reason must survive extraction");
            var independent = parser.parseIndependentScoreParseResult(wrapped, List.of(node()), 50);
            require(independent.scores().equals(Map.of("IP", 100)), "Product scoring uses the same safe extraction");
        }
    }

    static void malformedOuterObjectIsNotSalvaged() throws Exception {
        var parser = new LlmResponseParser(JSON);
        String malformed = "{\"IP\":{\"score\":100,\"reason\":\"valid inner object\"}";
        boolean rejected = false;
        try { parser.parseScoreParseResult(malformed, List.of(node()), 100); }
        catch (Exception expected) { rejected = true; }
        require(rejected, "A truncated outer response must not be reinterpreted as a successful inner object");
    }

    private static LlmService service(String reply) {
        var config = new LlmProviderConfig(null) {
            @Override public LlmProvider getActiveProvider() { return LlmProvider.GEMINI; }
            @Override public String getActiveProviderName() { return "GEMINI"; }
            @Override public String getApiKey(LlmProvider provider) { return "fixture-not-a-credential"; }
        };
        var parser = new LlmResponseParser(JSON);
        var gateways = new LlmGatewayRegistry(config, new RestTemplate(), JSON, null, null, null) {
            @Override public LlmGateway getGateway(LlmProvider provider) {
                return new LlmGateway() {
                    @Override public String providerName() { return "GEMINI"; }
                    @Override public String sendHttpRequest(String prompt, String key) {
                        return JSON.writeValueAsString(Map.of("candidates", List.of(Map.of("content",
                                Map.of("parts", List.of(Map.of("text", reply)))))));
                    }
                    @Override public String extractResponseText(String body) { return parser.extractGeminiText(body); }
                };
            }
        };
        var templates = new PromptTemplateService();
        templates.setTemplate("IP", "Requirement:\n{{BUSINESS_TEXT}}\nReturn JSON for {{EXPECTED_KEYS}}.\n{{NODE_LIST}}");
        return new LlmService(config, gateways, JSON, null, templates, null, null);
    }

    private static TaxonomyNode node() {
        TaxonomyNode node = new TaxonomyNode();
        node.setCode("IP"); node.setNameEn("Information Products"); node.setTaxonomyRoot("IP");
        return node;
    }
    private static void expectHidden(Runnable call) {
        try { call.run(); throw new AssertionError("Expected scope denial"); }
        catch (ResponseStatusException expected) { require(expected.getStatusCode().value() == 404, "No scope information leak"); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
