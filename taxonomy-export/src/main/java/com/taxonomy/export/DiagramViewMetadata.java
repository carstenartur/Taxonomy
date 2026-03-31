package com.taxonomy.export;

import java.util.ArrayList;
import java.util.List;

/**
 * Human-readable metadata describing the active diagram view policy.
 *
 * <p>Generated from a {@link DiagramSelectionConfig} by the
 * {@link #fromConfig(DiagramSelectionConfig, String)} factory method.
 * The UI displays this as a title and small legend/info block so
 * users can see which policy is active without inspecting raw
 * configuration values.</p>
 *
 * @param policyKey           internal key (e.g. "defaultImpact", "clustering")
 * @param viewTitle           human-readable title (e.g. "Architecture Impact View")
 * @param viewDescription     short description of the active policy
 * @param containmentEnabled  whether the policy collapses / clusters intermediate nodes
 * @param activeRules         list of human-readable active rule descriptions
 */
public record DiagramViewMetadata(
        String policyKey,
        String viewTitle,
        String viewDescription,
        boolean containmentEnabled,
        List<String> activeRules
) {

    private static final String DEFAULT_IMPACT_KEY = "defaultImpact";
    private static final String LEAF_ONLY_KEY = "leafOnly";
    private static final String CLUSTERING_KEY = "clustering";
    private static final String TRACE_KEY = "trace";

    /**
     * Generates view metadata from a policy key and its configuration.
     *
     * @param config    the active diagram selection configuration
     * @param policyKey the policy key (e.g. "defaultImpact", "clustering", "trace", "leafOnly")
     * @return metadata with human-readable title, description, and active rules
     */
    public static DiagramViewMetadata fromConfig(DiagramSelectionConfig config, String policyKey) {
        String key = policyKey != null ? policyKey : DEFAULT_IMPACT_KEY;
        String title = resolveTitle(key);
        String description = resolveDescription(key);
        boolean containment = config.collapseRedundantParentChild() || config.allowIntermediateAsClusters();
        List<String> rules = buildActiveRules(config);
        return new DiagramViewMetadata(key, title, description, containment, rules);
    }

    private static String resolveTitle(String policyKey) {
        return switch (policyKey) {
            case LEAF_ONLY_KEY -> "Leaf-Only Showcase View";
            case CLUSTERING_KEY -> "Clustered Impact View";
            case TRACE_KEY -> "Scoring Trace";
            default -> "Architecture Impact View";
        };
    }

    private static String resolveDescription(String policyKey) {
        return switch (policyKey) {
            case LEAF_ONLY_KEY ->
                    "Shows only the deepest leaf nodes; intermediate parents are suppressed and edges re-routed.";
            case CLUSTERING_KEY ->
                    "Intermediate nodes become visual containers grouping their children; single-child intermediates are collapsed.";
            case TRACE_KEY ->
                    "Full hierarchy preserved for traceability — nothing is suppressed.";
            default ->
                    "Relevant nodes with score-based filtering; roots and scaffolding suppressed, intermediates clustered.";
        };
    }

    private static List<String> buildActiveRules(DiagramSelectionConfig config) {
        List<String> rules = new ArrayList<>();
        if (config.suppressRootNodes()) {
            rules.add("Root nodes suppressed");
        }
        if (config.suppressScaffoldingNodes()) {
            rules.add("Scaffolding nodes suppressed");
        }
        if (config.collapseRedundantParentChild()) {
            rules.add("Single-child intermediates collapsed");
        }
        if (config.allowIntermediateAsClusters()) {
            rules.add("Multi-child intermediates as clusters");
        }
        if (config.leafOnlyMode()) {
            rules.add("Leaf-only mode");
        }
        if (config.minRelevance() > 0.0) {
            rules.add("Min relevance: " + (int) (config.minRelevance() * 100) + "%");
        }
        rules.add("Max nodes: " + config.maxNodes());
        rules.add("Max edges: " + config.maxEdges());
        return List.copyOf(rules);
    }
}
