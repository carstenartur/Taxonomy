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
 * <p>Titles, descriptions, and rule labels are returned as <strong>i18n
 * message keys</strong> (e.g. {@code "archview.policy.title.defaultImpact"})
 * so the display layer can resolve them via its translation service.</p>
 *
 * @param policyKey           internal key (e.g. "defaultImpact", "clustering")
 * @param viewTitle           i18n key for the view title
 * @param viewDescription     i18n key for the short description
 * @param containmentEnabled  whether the policy collapses / clusters intermediate nodes
 * @param activeRules         list of i18n keys for active rule descriptions
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
     * @return metadata with i18n keys for title, description, and active rules
     */
    public static DiagramViewMetadata fromConfig(DiagramSelectionConfig config, String policyKey) {
        String key = policyKey != null ? policyKey : DEFAULT_IMPACT_KEY;
        String titleKey = "archview.policy.title." + key;
        String descKey = "archview.policy.desc." + key;
        boolean containment = config.collapseRedundantParentChild() || config.allowIntermediateAsClusters();
        List<String> rules = buildActiveRules(config);
        return new DiagramViewMetadata(key, titleKey, descKey, containment, rules);
    }

    private static List<String> buildActiveRules(DiagramSelectionConfig config) {
        List<String> rules = new ArrayList<>();
        if (config.suppressRootNodes()) {
            rules.add("archview.rule.suppress.roots");
        }
        if (config.suppressScaffoldingNodes()) {
            rules.add("archview.rule.suppress.scaffolding");
        }
        if (config.collapseRedundantParentChild()) {
            rules.add("archview.rule.collapse.single.child");
        }
        if (config.allowIntermediateAsClusters()) {
            rules.add("archview.rule.cluster.multi.child");
        }
        if (config.leafOnlyMode()) {
            rules.add("archview.rule.leaf.only");
        }
        if (config.minRelevance() > 0.0) {
            rules.add("archview.rule.min.relevance");
        }
        return List.copyOf(rules);
    }
}
