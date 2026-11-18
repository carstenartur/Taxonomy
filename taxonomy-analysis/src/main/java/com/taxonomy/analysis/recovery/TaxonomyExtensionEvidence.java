package com.taxonomy.analysis.recovery;

import com.taxonomy.dto.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

/** Exact retained evidence and work membership; never a second assessment algorithm. */
final class TaxonomyExtensionEvidence {
    private TaxonomyExtensionEvidence() { }

    static AnalysisScope union(AnalysisScope source, AnalysisScope requested, List<TaxonomyNodeDto> tree) {
        var roots = new TreeSet<String>();
        tree.forEach(root -> { if (source.selects(root.getCode()) || requested.selects(root.getCode())) roots.add(root.getCode()); });
        requested.validateRoots(tree.stream().map(TaxonomyNodeDto::getCode).collect(java.util.stream.Collectors.toSet()));
        return new AnalysisScope(roots, requested.mode());
    }

    static Map<String, String> membership(List<TaxonomyNodeDto> tree) {
        var result = new LinkedHashMap<String, String>();
        tree.forEach(root -> collect(root, root.getCode(), result));
        return Map.copyOf(result);
    }

    private static void collect(TaxonomyNodeDto node, String root, Map<String, String> result) {
        if (result.putIfAbsent(node.getCode(), root) != null) throw conflict("Ambiguous retained catalogue membership");
        if (node.getChildren() != null) node.getChildren().forEach(child -> collect(child, root, result));
    }

    static boolean requested(AnalysisScope scope, Map<String, String> membership, List<String> nodes) {
        if (scope == null) return true;
        if (nodes.isEmpty()) throw conflict("A node assessment requires catalogue identities");
        for (String node : nodes) {
            String root = membership.get(node);
            if (root == null) throw conflict("A retained question is outside its catalogue");
            if (!scope.selects(root)) return false;
        }
        return true;
    }

    static AnalysisResult seed(ObjectMapper mapper, AnalysisResult source, AnalysisRequest request) {
        var result = mapper.readValue(mapper.writeValueAsString(source), AnalysisResult.class);
        var report = result.getRelationSearchReport();
        var relationWarnings = new HashSet<String>();
        if (report != null) {
            relationWarnings.addAll(report.warnings());
            report.result().unfinished().stream().filter(item -> !item.question().isBlank())
                    .map(item -> item.sourceId() + ": " + item.question()).forEach(relationWarnings::add);
        }
        result.setWarnings(result.getWarnings().stream().filter(warning -> !relationWarnings.contains(warning)
                && !warning.startsWith("RELATION_SEARCH_PARTIAL:") && !warning.startsWith("RELATION_EVALUATION_FAILED:")).toList());
        result.setRelationSearchReport(null);
        result.setArchitectureView(null);
        result.setProvisionalRelations(List.of());
        result.setCopilot(null);
        result.setErrorMessage(null);
        result.setRecovery(null);
        result.setAnalysisOperationId(null);
        result.setAnalysisDurationMillis(null);
        result.setStatus("IN_PROGRESS");
        result.setAnalysisScope(request.getAnalysisScope());
        result.setModelBinding(request.getModelBinding());
        return result;
    }

    static boolean noWork(String state, AnalysisRequest source, AnalysisRequest target, AnalysisResult result,
            List<TaxonomyNodeDto> tree) {
        if (!"COMPLETED".equals(state) || !"SUCCESS".equals(result.getStatus())
                || source.getAnalysisScope().mode() != target.getAnalysisScope().mode()
                || source.isIncludeArchitectureView() != target.isIncludeArchitectureView()
                || !Objects.equals(source.getMaxArchitectureNodes(), target.getMaxArchitectureNodes())
                || result.getAnalysisCoverage() == null || result.getAnalysisCoverage().hasOpenEvaluations()) return false;
        if (source.getAnalysisScope().includesRelations()
                && (result.getRelationSearchReport() == null || !result.getRelationSearchReport().isSearchExhausted())) return false;
        for (var root : tree) {
            if (source.getAnalysisScope().selects(root.getCode()) != target.getAnalysisScope().selects(root.getCode())) return false;
            if (source.getAnalysisScope().selects(root.getCode())) {
                var assessed = result.getAnalysisCoverage().nodes().get(root.getCode());
                if (assessed == null || assessed.state() == AnalysisCoverage.State.UNKNOWN
                        || assessed.descendants() != AnalysisCoverage.Descendants.COMPLETE) return false;
            }
        }
        return true;
    }

    static AnalysisResult completedCopy(ObjectMapper mapper, AnalysisResult source, AnalysisRequest target) {
        var result = mapper.readValue(mapper.writeValueAsString(source), AnalysisResult.class);
        result.setRecovery(null); result.setAnalysisOperationId(null); result.setAnalysisDurationMillis(0L);
        result.setAnalysisScope(target.getAnalysisScope()); result.setModelBinding(target.getModelBinding());
        return result;
    }

    static void preserve(AnalysisResult previous, AnalysisResult result) {
        var raw = new LinkedHashMap<>(previous.getRawScores());
        result.getRawScores().forEach((code, value) -> {
            Double accepted = raw.putIfAbsent(code, value);
            if (accepted != null && !accepted.equals(value)) throw conflict("An extension changed an accepted node assessment");
        });
        var reasons = new LinkedHashMap<>(previous.getReasons());
        result.getReasons().forEach((code, value) -> {
            String accepted = reasons.putIfAbsent(code, value);
            if (accepted != null && !accepted.equals(value)) throw conflict("An extension changed an accepted node explanation");
        });
        result.setRawScores(raw);
        result.setReasons(reasons);
    }

    static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }
}
