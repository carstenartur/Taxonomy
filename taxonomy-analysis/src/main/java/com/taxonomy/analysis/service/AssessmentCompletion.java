package com.taxonomy.analysis.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.dto.RootAssessment.Status;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Constrains the generated decision shape without supplying any application scores. */
final class AssessmentCompletion {
    static final String ROOT_SCHEMA = "root_assessment_v1";
    static final String CHILD_SCHEMA = "child_assessments_v1";

    private AssessmentCompletion() { }

    static LlmCompletionOptions forRoot(String code, int maxOutputTokens) {
        var emptyQuestion = Map.of("type", "string", "enum", List.of(""));
        var alternatives = List.of(
                decision(Status.RELEVANT, Map.of("type", "number", "exclusiveMinimum", 0, "maximum", 100), emptyQuestion),
                decision(Status.IRRELEVANT, Map.of("type", "number", "enum", List.of(0)), emptyQuestion),
                decision(Status.UNRESOLVED, Map.of("type", "null"), Map.of("type", "string", "minLength", 1, "maxLength", 1000)));
        return new LlmCompletionOptions(ROOT_SCHEMA, object(Map.of(code, Map.of("anyOf", alternatives))), maxOutputTokens);
    }

    static LlmCompletionOptions forChildren(List<TaxonomyNode> nodes, int maxOutputTokens) {
        var assessment = new LinkedHashMap<String, Object>();
        assessment.put("score", Map.of("type", "number", "minimum", 0, "maximum", 100));
        assessment.put("reason", Map.of("type", "string", "minLength", 1));
        var properties = new LinkedHashMap<String, Object>();
        for (var node : nodes) {
            properties.put(node.getCode(), Map.of("$ref", "#/$defs/assessment"));
        }
        var schema = new LinkedHashMap<>(object(properties));
        schema.put("$defs", Map.of("assessment", object(assessment)));
        return new LlmCompletionOptions(CHILD_SCHEMA, schema, maxOutputTokens);
    }

    private static Map<String, Object> decision(Status status, Map<String, ?> score, Map<String, ?> question) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("status", Map.of("type", "string", "enum", List.of(status.name())));
        fields.put("score", score);
        fields.put("reason", Map.of("type", "string", "minLength", 1, "maxLength", 2000));
        fields.put("question", question);
        return object(fields);
    }

    private static Map<String, Object> object(Map<String, ?> fields) {
        return Map.of("type", "object", "properties", fields,
                "required", List.copyOf(fields.keySet()), "additionalProperties", false);
    }
}
