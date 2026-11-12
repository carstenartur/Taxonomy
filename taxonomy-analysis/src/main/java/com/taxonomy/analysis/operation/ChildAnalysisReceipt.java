package com.taxonomy.analysis.operation;

import com.taxonomy.analysis.service.AnalysisFollowUpProviders.Binding;
import com.taxonomy.analysis.usecase.AnalyzeNodeChildrenResult;
import com.taxonomy.analysis.usecase.JustifyLeafResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.Set;

/** Restricts archive restoration and public projection to the concrete validated child-result contract. */
public final class ChildAnalysisReceipt {
    private static final ObjectMapper JSON = new ObjectMapper();
    private ChildAnalysisReceipt() { }

    public static JsonNode publicResult(String kind, JsonNode stored) {
        return "NODE_CHILDREN".equals(kind) ? stored.path("result") : stored;
    }

    public static void validate(String kind, String operationId, JsonNode stored) {
        if (stored == null || !stored.isObject()) throw invalid();
        switch (kind) {
            case "NODE_CHILDREN" -> {
                allowed(stored, Set.of("result", "parentBinding"));
                JsonNode result = stored.path("result");
                allowed(result, Set.of("scores", "reasons", "prompt", "rawResponse", "provider", "durationMs", "error",
                        "analysisOperationId", "analysisProviderId", "analysisReasonLength", "analysisLanguage"));
                if (!result.path("scores").isObject() || !result.path("reasons").isObject()
                        || !result.path("durationMs").isIntegralNumber() || !result.path("prompt").isString()
                        || !result.path("rawResponse").isString() || !result.path("prompt").asString().isEmpty()
                        || !result.path("rawResponse").asString().isEmpty()) throw invalid();
                var node = JSON.treeToValue(result, AnalyzeNodeChildrenResult.class);
                if (node.durationMs() < 0 || node.analysisOperationId() == null || node.analysisProviderId() == null
                        || node.analysisLanguage() == null || node.analysisReasonLength() == null
                        || node.error() != null && !"ANALYSIS_FAILED".equals(node.error())) throw invalid();
                if (stored.hasNonNull("parentBinding")) {
                    var pin = stored.path("parentBinding");
                    allowed(pin, Set.of("provider", "binding", "reasonLength", "analysisLanguage", "requirement"));
                    allowed(pin.path("requirement"), Set.of("projectId", "requirementId", "snapshotId", "textSha256", "requirementVersionId"));
                    if (pin.hasNonNull("binding")) {
                        allowed(pin.path("binding"), Set.of("plugin", "configurationRevision"));
                        allowed(pin.path("binding").path("plugin"), Set.of("id", "version", "artifactSha256"));
                    }
                    Binding parent = JSON.treeToValue(pin, Binding.class);
                    if (!operationId.equals(node.analysisOperationId()) || !parent.provider().equals(node.analysisProviderId())
                            || parent.analysisLanguage() != node.analysisLanguage() || parent.reasonLength() != node.analysisReasonLength())
                        throw invalid();
                }
            }
            case "LEAF_JUSTIFICATION" -> {
                allowed(stored, Set.of("nodeCode", "justification"));
                JSON.treeToValue(stored, JustifyLeafResult.class);
            }
            case "FOCUS_INVESTIGATION" -> {
                allowed(stored, Set.of("source", "context", "sourceId", "sourceHash", "method", "outcome", "modelCalls",
                        "candidateCount", "suggestions"));
                if (stored.hasNonNull("context") == stored.hasNonNull("source") || !stored.path("sourceId").isString()
                        || !stored.path("sourceHash").asString("").matches("[a-f0-9]{64}")
                        || !Set.of("ASSESSED", "FAILED").contains(stored.path("outcome").asString(""))
                        || !stored.path("method").isString() || stored.path("modelCalls").asInt(-1) != 1
                        || !stored.path("candidateCount").isIntegralNumber() || !stored.path("suggestions").isArray()) throw invalid();
                for (var item : stored.path("suggestions")) {
                    allowed(item, Set.of("id", "sourceId", "targetId", "relationType", "rationale", "evidence", "properties"));
                    if (!item.path("evidence").isArray() || !item.path("properties").isObject()) throw invalid();
                    for (var evidence : item.path("evidence")) allowed(evidence, Set.of("elementId","field","quote"));
                }
            }
            default -> throw invalid();
        }
    }

    private static void allowed(JsonNode value, Set<String> fields) {
        if (!value.isObject() || !fields.containsAll(value.propertyNames())) throw invalid();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid child analysis receipt"); }
}
