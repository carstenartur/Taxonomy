package com.taxonomy.analysis.service;

import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;
import tools.jackson.databind.json.JsonMapper;

/** Opt-in completion controls; a null schema requests an ordinary bounded text completion. */
public record LlmCompletionOptions(String schemaName, Map<String, Object> schema, int maxOutputTokens) {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public LlmCompletionOptions {
        if (maxOutputTokens < 1 || maxOutputTokens > 1_000_000) {
            throw new IllegalArgumentException("maxOutputTokens must be between 1 and 1000000");
        }
        if (schema != null && (schemaName == null || !schemaName.matches("[A-Za-z0-9_-]{1,64}"))) {
            throw new IllegalArgumentException("schemaName must contain 1 to 64 letters, digits, underscores or dashes");
        }
        if (schema != null) schema = Collections.unmodifiableMap(new LinkedHashMap<>(schema));
    }

    /**
     * Conservative input-size evidence for prompt packing and the final character/byte guard.
     * This includes the serialized schema envelope; it is not an exact tokenizer count.
     * The original prompt remains the prepared-prompt diagnostic evidence.
     */
    public String budgetText(String prompt) {
        if (schema == null) return prompt == null ? "" : prompt;
        return (prompt == null ? "" : prompt) + "\n" + JSON.writeValueAsString(Map.of(
                "response_format", Map.of("type", "json_schema", "json_schema", Map.of(
                        "name", schemaName, "strict", true, "schema", schema))));
    }
}
