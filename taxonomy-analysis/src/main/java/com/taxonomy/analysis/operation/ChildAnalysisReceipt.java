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
        return switch (kind) {
            case "NODE_CHILDREN", "LEAF_JUSTIFICATION" -> stored.path("result");
            default -> stored;
        };
    }

    /** Returns null when an independent parent or retained architecture source needs a separate authorized remapping. */
    public static JsonNode restoreResult(String kind, String sourceId, String targetId, JsonNode stored) {
        validate(kind, sourceId, stored);
        if ("FOCUS_INVESTIGATION".equals(kind) || stored.path("source").hasNonNull("parentId")) return null;
        if ("LEAF_JUSTIFICATION".equals(kind)) return stored;
        var restored = (tools.jackson.databind.node.ObjectNode) stored.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) restored.get("result")).put("analysisOperationId", targetId);
        validate(kind, targetId, restored);
        return restored;
    }

    public static void validate(String kind, String operationId, JsonNode stored) {
        if (stored == null || !stored.isObject()) throw invalid();
        switch (kind) {
            case "NODE_CHILDREN" -> {
                allowed(stored, Set.of("result", "parentBinding", "source"));
                var source = source(stored, operationId);
                JsonNode result = stored.path("result");
                allowed(result, Set.of("scores", "reasons", "prompt", "rawResponse", "provider", "durationMs", "error",
                        "analysisOperationId", "analysisProviderId", "analysisReasonLength", "analysisLanguage"));
                if (!result.path("scores").isObject() || !result.path("reasons").isObject()
                        || !result.path("durationMs").isIntegralNumber() || !result.path("prompt").isString()
                        || !result.path("rawResponse").isString() || !result.path("prompt").asString().isEmpty()
                        || !result.path("rawResponse").asString().isEmpty()) throw invalid();
                var node = JSON.treeToValue(result, AnalyzeNodeChildrenResult.class);
                if (node.scores().values().stream().anyMatch(score -> !com.taxonomy.dto.AnalysisPercentages.valid(score))
                        || node.durationMs() < 0 || node.analysisOperationId() == null || node.analysisProviderId() == null
                        || (source.parentId() == null && node.analysisLanguage() == null) || node.analysisReasonLength() == null
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
                    if (parent.requirement().projectId() != null || parent.requirement().requirementId() != null
                            || parent.requirement().snapshotId() != null || parent.requirement().requirementVersionId() != null)
                        throw invalid();
                    if (source.parentId() != null || !operationId.equals(node.analysisOperationId()) || !parent.provider().equals(node.analysisProviderId())
                            || parent.analysisLanguage() != node.analysisLanguage() || parent.reasonLength() != node.analysisReasonLength())
                        throw invalid();
                } else if (!node.analysisOperationId().equals(source.parentId())) throw invalid();
            }
            case "LEAF_JUSTIFICATION" -> {
                allowed(stored, Set.of("result", "source"));
                var source = source(stored, operationId);
                allowed(stored.path("result"), Set.of("nodeCode", "justification"));
                var leaf = JSON.treeToValue(stored.path("result"), JustifyLeafResult.class);
                if (!source.nodeCode().equals(leaf.nodeCode())) throw invalid();
            }
            case "FOCUS_INVESTIGATION" -> {
                allowed(stored, Set.of("source", "context", "sourceId", "sourceHash", "method", "outcome", "modelCalls",
                        "candidateCount", "suggestions"));
                if (stored.hasNonNull("context") == stored.hasNonNull("source") || !stored.path("sourceId").isString()
                        || !stored.path("sourceHash").asString("").matches("[a-f0-9]{64}")
                        || !Set.of("ASSESSED", "FAILED").contains(stored.path("outcome").asString(""))
                        || !stored.path("method").isString() || stored.path("modelCalls").asInt(-1) != 1
                        || !stored.path("candidateCount").isIntegralNumber() || stored.path("candidateCount").asInt(-1) < 1
                        || stored.path("candidateCount").asInt() > 24 || !stored.path("suggestions").isArray()
                        || stored.path("suggestions").size() > stored.path("candidateCount").asInt()) throw invalid();
                if (stored.hasNonNull("context"))
                    allowed(stored.get("context"), Set.of("repositoryId", "workspaceScopeKey", "branch", "commit", "revision", "actor", "writeMode"));
                else allowed(stored.get("source"), Set.of("kind", "repositoryId", "workspaceScopeKey", "branch", "version",
                        "projectId", "requirementId", "requirementVersionId", "snapshotId"));
                for (var item : stored.path("suggestions")) {
                    allowed(item, Set.of("id", "sourceId", "targetId", "relationType", "rationale", "evidence", "properties"));
                    if (!item.path("evidence").isArray() || !item.path("properties").isObject()) throw invalid();
                    allowed(item.get("properties"), Set.of("provenance","x-focus-rationale","x-focus-evidence","x-focus-method",
                            "x-focus-source-hash","x-focus-revision","x-focus-continuation-id","x-focus-continuation-version",
                            "x-focus-execution-catalogue","x-focus-operation-id","x-focus-source-kind","x-focus-source-version",
                            "x-analysis-branch","x-analysis-catalogue-fingerprint","x-analysis-commit","x-analysis-created-at",
                            "x-analysis-model","x-analysis-project-id","x-analysis-provider","x-analysis-repository-id",
                            "x-analysis-requirement-id","x-analysis-requirement-version-id","x-analysis-snapshot-id",
                            "x-analysis-status","x-analysis-workspace-scope"));
                    for (var evidence : item.path("evidence")) allowed(evidence, Set.of("elementId","field","quote"));
                }
            }
            default -> throw invalid();
        }
    }

    private static AnalysisNodeReceipts.Source source(JsonNode stored, String operationId) {
        var value = stored.path("source");
        allowed(value, Set.of("nodeCode", "fingerprint", "parentId"));
        var source = JSON.treeToValue(value, AnalysisNodeReceipts.Source.class);
        if (operationId.equals(source.parentId())) throw invalid();
        return source;
    }

    private static void allowed(JsonNode value, Set<String> fields) {
        if (!value.isObject() || !fields.containsAll(value.propertyNames())) throw invalid();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid child analysis receipt"); }
}
