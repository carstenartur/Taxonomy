package com.taxonomy.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministically separates raw product suitability from comparable hierarchical relevance.
 *
 * <p>The legacy {@code scores} map remains the evidence returned by the scoring provider. Concrete
 * products retain that raw 0–100 suitability value, while generic consumers use
 * {@code effectiveScores}. A product's effective relevance is its direct family relevance
 * multiplied by its suitability:</p>
 *
 * <pre>effective = round(parent relevance × product suitability / 100)</pre>
 */
public final class AnalysisScoreSemantics {

    public static final int CURRENT_VERSION = 1;
    public static final String ROLE_PRODUCT = "PRODUCT";

    private static final int MAX_WARNINGS = 100;
    private static final String SUPPRESSED_WARNINGS_MESSAGE =
            "Additional score-semantics warnings were suppressed.";

    private AnalysisScoreSemantics() {
    }

    public static Derived derive(
            Map<String, Integer> rawScores,
            List<TaxonomyNodeDto> taxonomyTree) {
        Map<String, Integer> normalizedScores = normalizeScores(rawScores);
        Map<String, NodeContext> contexts = index(taxonomyTree);
        Map<String, AnalysisScoreDetail> details = new LinkedHashMap<>();
        Map<String, Integer> effectiveScores = new LinkedHashMap<>();
        Map<String, Integer> productSuitabilityScores = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();

        for (Map.Entry<String, Integer> entry : normalizedScores.entrySet()) {
            String code = entry.getKey();
            int rawScore = entry.getValue();
            NodeContext context = contexts.get(code);
            AnalysisScoreKind kind;
            String parentCode = context == null ? null : context.parentCode();
            Integer parentScore = parentCode == null ? null : normalizedScores.get(parentCode);
            int effectiveRelevance = rawScore;

            if (context == null) {
                kind = AnalysisScoreKind.HIERARCHICAL_RELEVANCE;
                addWarning(warnings, "Score semantics could not resolve taxonomy node " + code
                        + "; treating its value as hierarchical relevance.");
            } else if (ROLE_PRODUCT.equals(context.analysisRole())) {
                kind = AnalysisScoreKind.PRODUCT_SUITABILITY;
                productSuitabilityScores.put(code, rawScore);
                if (parentCode == null || parentScore == null) {
                    effectiveRelevance = 0;
                    addWarning(warnings, "Concrete product " + code
                            + " has no evaluated direct family score; its effective relevance is 0.");
                } else {
                    effectiveRelevance = effectiveProductRelevance(parentScore, rawScore);
                }
            } else if (parentCode == null) {
                kind = AnalysisScoreKind.ROOT_RELEVANCE;
            } else {
                kind = AnalysisScoreKind.HIERARCHICAL_RELEVANCE;
            }

            AnalysisScoreDetail detail = new AnalysisScoreDetail(
                    code, kind, rawScore, effectiveRelevance, parentCode, parentScore);
            details.put(code, detail);
            effectiveScores.put(code, effectiveRelevance);
        }

        return new Derived(effectiveScores, productSuitabilityScores, details, warnings);
    }

    public static int effectiveProductRelevance(int familyRelevance, int suitability) {
        int family = clamp(familyRelevance);
        int product = clamp(suitability);
        return clamp((int) Math.round(family * product / 100.0));
    }

    private static void addWarning(List<String> warnings, String warning) {
        if (warnings.size() < MAX_WARNINGS - 1) {
            warnings.add(warning);
        } else if (warnings.size() == MAX_WARNINGS - 1) {
            warnings.add(SUPPRESSED_WARNINGS_MESSAGE);
        }
    }

    static Map<String, Integer> normalizeScores(Map<String, Integer> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        source.entrySet().stream()
                .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank()
                        && entry.getValue() != null)
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String code = entry.getKey().strip();
                    if (result.containsKey(code)) {
                        throw new IllegalArgumentException(
                                "Score map contains multiple entries for canonical node code "
                                        + code);
                    }
                    result.put(code, clamp(entry.getValue()));
                });
        return result;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private static Map<String, NodeContext> index(List<TaxonomyNodeDto> roots) {
        if (roots == null || roots.isEmpty()) {
            return Map.of();
        }
        Map<String, NodeContext> result = new LinkedHashMap<>();
        Set<TaxonomyNodeDto> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<String> activeCodes = new LinkedHashSet<>();
        for (TaxonomyNodeDto root : roots) {
            visit(root, null, result, visited, activeCodes);
        }
        return result;
    }

    private static void visit(
            TaxonomyNodeDto node,
            String inheritedParentCode,
            Map<String, NodeContext> result,
            Set<TaxonomyNodeDto> visited,
            Set<String> activeCodes) {
        if (node == null || node.getCode() == null || node.getCode().isBlank()) {
            return;
        }
        String code = node.getCode().strip();
        if (!activeCodes.add(code)) {
            throw new IllegalArgumentException(
                    "The frozen taxonomy hierarchy contains a cycle at node code " + code);
        }
        if (!visited.add(node)) {
            activeCodes.remove(code);
            throw new IllegalArgumentException(
                    "The frozen taxonomy hierarchy reuses node " + code + " more than once");
        }
        try {
            String parentCode = firstNonBlank(node.getParentCode(), inheritedParentCode);
            String role = node.getAnalysisRole() == null
                    ? "CATEGORY" : node.getAnalysisRole().strip().toUpperCase(Locale.ROOT);
            NodeContext context = new NodeContext(parentCode, role);
            NodeContext previous = result.putIfAbsent(code, context);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "The frozen taxonomy hierarchy contains duplicate node code " + code);
            }
            List<TaxonomyNodeDto> children = node.getChildren() == null
                    ? List.of() : node.getChildren();
            for (TaxonomyNodeDto child : children) {
                visit(child, code, result, visited, activeCodes);
            }
        } finally {
            activeCodes.remove(code);
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.strip();
        }
        return second == null || second.isBlank() ? null : second.strip();
    }

    private record NodeContext(String parentCode, String analysisRole) {
    }

    public record Derived(
            Map<String, Integer> effectiveScores,
            Map<String, Integer> productSuitabilityScores,
            Map<String, AnalysisScoreDetail> scoreDetails,
            List<String> warnings) {

        public Derived {
            effectiveScores = immutableMap(effectiveScores);
            productSuitabilityScores = immutableMap(productSuitabilityScores);
            scoreDetails = scoreDetails == null
                    ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(scoreDetails));
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        private static <K, V> Map<K, V> immutableMap(Map<K, V> source) {
            return source == null
                    ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(source));
        }
    }
}
