package com.taxonomy.composition.analysis.artemis;

import com.taxonomy.analysis.dag.AnalysisTaskMessage;
import com.taxonomy.analysis.dag.AnalysisTaskType;
import com.taxonomy.analysis.dag.TaxonomyShardRoot;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Broker destination names of the analysis task topology.
 *
 * <ul>
 *   <li>{@code <prefix>.subtaxonomy.<ROOT>} – anycast queue per root shard;</li>
 *   <li>{@code <prefix>.relation.<ROOT>} – relation work routed by its single target shard;</li>
 *   <li>{@code <prefix>.relation.general} – relation work over several or all roots
 *       (the current whole-evidence relation phase);</li>
 *   <li>{@code <prefix>.completion} – durable anycast coordinator queue;</li>
 *   <li>{@code <prefix>.progress} / {@code <prefix>.control} – multicast live events;</li>
 *   <li>{@code <prefix>.rejected} – malformed, unknown-schema or misrouted messages.</li>
 * </ul>
 *
 * Messages are never grouped per requirement, so independent roots run in parallel.
 */
public record AnalysisDestinations(String prefix) {

    public static final String DEFAULT_PREFIX = "taxonomy.analysis";
    private static final Pattern PREFIX = Pattern.compile("[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*){0,7}");

    public AnalysisDestinations {
        Objects.requireNonNull(prefix, "prefix");
        if (!PREFIX.matcher(prefix).matches()) {
            throw new IllegalArgumentException("Invalid analysis destination prefix");
        }
    }

    public String subtaxonomy(TaxonomyShardRoot root) {
        return prefix + ".subtaxonomy." + Objects.requireNonNull(root, "root").code();
    }

    public String relation(TaxonomyShardRoot root) {
        return prefix + ".relation." + Objects.requireNonNull(root, "root").code();
    }

    public String generalRelation() {
        return prefix + ".relation.general";
    }

    public String completion() {
        return prefix + ".completion";
    }

    public String progress() {
        return prefix + ".progress";
    }

    public String control() {
        return prefix + ".control";
    }

    public String rejected() {
        return prefix + ".rejected";
    }
    public String deadLetter() { return prefix + ".dlq"; }
    public String expiry() { return prefix + ".expiry"; }
    public String failed() { return prefix + ".failed"; }

    /** Queue that owns {@code task}: its family plus its single routing root. */
    public String queueFor(AnalysisTaskMessage task) {
        TaxonomyShardRoot root = task.routingRoot();
        if (task.taskType() == AnalysisTaskType.SUBTAXONOMY_ANALYSIS) return subtaxonomy(root);
        return root == null ? generalRelation() : relation(root);
    }
}
