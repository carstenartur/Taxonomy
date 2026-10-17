package com.taxonomy.analysis.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.core.StreamReadFeature;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Complete stateless Responses contract; no text/token ratio or hidden server state. */
final class OpenAiResponsesTokenContract {
    static final String ADAPTER = "openai.responses.gpt-4o-mini-2024-07-18.v1";
    static final String MODEL = "gpt-4o-mini-2024-07-18";
    static final String COUNT_URL = "https://api.openai.com/v1/responses/input_tokens";
    static final String GENERATION_URL = "https://api.openai.com/v1/responses";
    static final int MAX_REQUEST_BYTES = 1_048_576;
    static final int MAX_COUNT_RESPONSE_BYTES = 16_384;
    static final int MAX_GENERATION_RESPONSE_BYTES = 2_097_152;
    static final long MAX_OPERATION_MILLIS = 30_000;
    record Prepared(String countBody, String generationBody) {}
    static Prepared prepare(LlmProvider provider, String endpoint, String model,
                            ProviderRateQuotas.TokenBudget budget, String prompt, ObjectMapper json) {
        return prepare(provider, endpoint, model, budget, prompt, json, null);
    }
    static Prepared prepare(LlmProvider provider, String endpoint, String model,
                            ProviderRateQuotas.TokenBudget budget, String prompt, ObjectMapper json,
                            LlmCompletionOptions options) {
        requireContract(provider, endpoint, model, budget);
        if (prompt == null || prompt.isBlank() || prompt.length() > MAX_REQUEST_BYTES) throw rejected();
        var input = List.of(Map.of("role", "user", "content", List.of(Map.of("type", "input_text", "text", prompt))));
        var count = new LinkedHashMap<String, Object>();
        count.put("model", MODEL); count.put("input", input); count.put("truncation", "disabled");
        if (options != null && options.schema() != null) {
            count.put("text", Map.of("format", Map.of("type", "json_schema", "name", options.schemaName(),
                    "strict", true, "schema", options.schema())));
        }
        var generation = new LinkedHashMap<String, Object>(count);
        generation.put("max_output_tokens", options == null ? budget.maximumOutputTokens()
                : Math.min(options.maxOutputTokens(), budget.maximumOutputTokens()));
        generation.put("store", false);
        if (options != null) generation.put("temperature", 0);
        String countBody = json.writeValueAsString(count), generationBody = json.writeValueAsString(generation);
        if (countBody.getBytes(StandardCharsets.UTF_8).length > MAX_REQUEST_BYTES
                || generationBody.getBytes(StandardCharsets.UTF_8).length > MAX_REQUEST_BYTES) throw rejected();
        return new Prepared(countBody, generationBody);
    }
    static void requireContract(LlmProvider provider, String endpoint, String model, ProviderRateQuotas.TokenBudget budget) {
        if (provider != LlmProvider.OPENAI || !LlmProviderConfig.OPENAI_URL.equals(endpoint)
                || !LlmProviderConfig.OPENAI_MODEL.equals(model) || budget == null || !ADAPTER.equals(budget.adapterId())
                || budget.maximumOutputTokens() > 16_384 || budget.maximumTokens() > 128_000)
            throw new ProviderRateQuotas.UnavailableException("Unsupported complete Responses token contract", null);
    }
    static long validateCount(String body, ProviderRateQuotas.TokenBudget budget, ObjectMapper json) {
        var root = read(body, MAX_COUNT_RESPONSE_BYTES, json);
        var count = root.get("input_tokens");
        if (root.size() != 2 || !"response.input_tokens".equals(root.path("object").asText())
                || count == null || !count.isIntegralNumber() || !count.canConvertToLong()
                || count.longValue() < 0 || count.longValue() > budget.inputUpperBound()) throw rejected();
        return count.longValue();
    }
    static String extractText(String body, ObjectMapper json) {
        var root = read(body, MAX_GENERATION_RESPONSE_BYTES, json);
        var output = root.get("output");
        if ("response".equals(root.path("object").asText())
                && ("incomplete".equals(root.path("status").asText()) || "failed".equals(root.path("status").asText()))) {
            var partial = new StringBuilder();
            if (output != null && output.isArray()) for (var item : output) {
                var parts = item.path("content");
                if (!"message".equals(item.path("type").asText())
                        || !"assistant".equals(item.path("role").asText()) || !parts.isArray()) continue;
                for (var part : parts) if ("output_text".equals(part.path("type").asText()) && part.path("text").isString()) {
                    partial.append(part.path("text").stringValue());
                }
            }
            throw new LlmIncompleteResponseException(partial.toString(), "failed".equals(root.path("status").asText())
                    ? "error" : root.path("incomplete_details").path("reason").asText());
        }
        if (!"response".equals(root.path("object").asText()) || !"completed".equals(root.path("status").asText())
                || output == null || !output.isArray() || output.size() != 1) throw rejected();
        var message = output.get(0); var content = message.get("content");
        if (!"message".equals(message.path("type").asText()) || !"assistant".equals(message.path("role").asText())
                || content == null || !content.isArray() || content.isEmpty()) throw rejected();
        var result = new StringBuilder();
        for (var part : content) {
            var text = part.get("text");
            if (!"output_text".equals(part.path("type").asText()) || text == null || !text.isString()) throw rejected();
            result.append(text.stringValue());
        }
        if (result.isEmpty()) throw rejected();
        return result.toString();
    }
    private static JsonNode read(String body, int limit, ObjectMapper json) {
        if (body == null || body.length() > limit || body.getBytes(StandardCharsets.UTF_8).length > limit) throw rejected();
        try {
            var root = json.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
            if (root == null || !root.isObject()) throw rejected();
            return root;
        } catch (LlmProviderException failure) { throw failure; }
        catch (RuntimeException invalid) { throw rejected(); }
    }
    private static LlmProviderException rejected() {
        return new LlmProviderException(LlmProviderException.Reason.REQUEST_REJECTED,
                "Complete Responses token contract could not be established");
    }
}
