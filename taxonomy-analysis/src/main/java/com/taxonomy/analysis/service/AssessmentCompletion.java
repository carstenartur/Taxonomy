package com.taxonomy.analysis.service;

import com.taxonomy.dto.RootAssessment.Status;
import com.taxonomy.dto.RequirementNodeAssessment.*;

import java.util.Arrays;
import static com.taxonomy.dto.RequirementNodeAssessment.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Constrains the generated decision shape without supplying any application scores. */
final class AssessmentCompletion {
    static final String ROOT_SCHEMA = "root_assessment_v2";
    static final String CHILD_SCHEMA = "child_assessments_v2";

    private AssessmentCompletion() { }

    static LlmCompletionOptions forRoot(String code, int maxOutputTokens) {
        var emptyQuestion = Map.of("type", "string", "enum", List.of(""));
        var alternatives = List.of(
                decision(Status.RELEVANT, Map.of("type", "number", "exclusiveMinimum", 0, "maximum", 100), emptyQuestion),
                decision(Status.IRRELEVANT, Map.of("type", "number", "enum", List.of(0)), emptyQuestion),
                decision(Status.UNRESOLVED, Map.of("type", "null"), Map.of("type", "string", "minLength", 1, "maxLength", 1000)));
        var schema = new LinkedHashMap<>(object(Map.of(code, Map.of("anyOf", alternatives))));
        schema.put("$defs", Map.of("passage", passage()));
        return new LlmCompletionOptions(ROOT_SCHEMA, schema, maxOutputTokens);
    }

    static LlmCompletionOptions forChildren(List<String> nodeCodes, int maxOutputTokens) {
        var assessment = new LinkedHashMap<String, Object>();
        assessment.put("score", Map.of("type", "number", "minimum", 0, "maximum", 100));
        assessment.put("reason", Map.of("type", "string", "minLength", 1));
        assessment.put("assessments", passages());
        var properties = new LinkedHashMap<String, Object>();
        for (var code : nodeCodes) {
            properties.put(code, Map.of("$ref", "#/$defs/assessment"));
        }
        var schema = new LinkedHashMap<>(object(properties));
        schema.put("$defs", Map.of("assessment", object(assessment), "passage", passage()));
        return new LlmCompletionOptions(CHILD_SCHEMA, schema, maxOutputTokens);
    }

    private static Map<String, Object> decision(Status status, Map<String, ?> score, Map<String, ?> question) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("status", Map.of("type", "string", "enum", List.of(status.name())));
        fields.put("score", score);
        fields.put("reason", Map.of("type", "string", "minLength", 1, "maxLength", 2000));
        fields.put("question", question);
        fields.put("assessments", passages());
        return object(fields);
    }

    private static Map<String, Object> passages() {
        return Map.of("type", "array", "maxItems", MAX_PER_NODE, "items", Map.of("$ref", "#/$defs/passage"));
    }

    private static Map<String, Object> passage() {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("requirementQuote", text(0, MAX_QUOTE_LENGTH));
        fields.put("subjectKinds", kinds(SubjectKind.class));
        fields.put("requirementKinds", kinds(RequirementKind.class));
        fields.put("contributionType", enumeration(ContributionType.class));
        fields.put("evidenceStatus", enumeration(EvidenceStatus.class));
        fields.put("evidenceBasis", enumeration(EvidenceBasis.class));
        fields.put("necessity", enumeration(Necessity.class));
        fields.put("scope", enumeration(Scope.class));
        fields.put("condition", text(0, MAX_CONDITION_LENGTH));
        fields.put("rationale", text(1, MAX_RATIONALE_LENGTH));
        fields.put("question", text(0, MAX_QUESTION_LENGTH));
        return object(fields);
    }

    private static Map<String, Object> text(int minimum, int maximum) {
        return Map.of("type", "string", "minLength", minimum, "maxLength", maximum);
    }

    private static Map<String, Object> kinds(Class<? extends Enum<?>> type) {
        return Map.of("type", "array", "minItems", 1, "maxItems", type.getEnumConstants().length, "items", enumeration(type));
    }

    private static Map<String, Object> enumeration(Class<? extends Enum<?>> type) {
        return Map.of("type", "string", "enum", Arrays.stream(type.getEnumConstants()).map(Enum::name).toList());
    }

    private static Map<String, Object> object(Map<String, ?> fields) {
        return Map.of("type", "object", "properties", fields,
                "required", List.copyOf(fields.keySet()), "additionalProperties", false);
    }
}
