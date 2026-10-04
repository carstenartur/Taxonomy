package com.taxonomy.analysis.dag;

import java.util.Collection;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Deterministic, stable identity of one executable unit.
 *
 * <p>The same operation, task family and root selection always produce the same
 * identifier, so a redelivered or republished task is recognised as the same unit
 * and its durable effect can be applied idempotently.</p>
 */
public record AnalysisTaskId(String value) {

    private static final Pattern OPERATION = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Pattern VALUE = Pattern.compile("[A-Za-z0-9._-]{1,128}:[a-z]{1,32}:([A-Za-z0-9_.+-]{1,1024}|\\*)");

    public AnalysisTaskId {
        Objects.requireNonNull(value, "value");
        if (!VALUE.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid analysis task identifier");
        }
    }

    /** Validate an operation identifier before it is embedded in a task identity. */
    public static String requireOperationId(String operationId) {
        Objects.requireNonNull(operationId, "operationId");
        if (!OPERATION.matcher(operationId).matches()) {
            throw new IllegalArgumentException("Invalid analysis operation identifier");
        }
        return operationId;
    }

    public static AnalysisTaskId subtaxonomy(String operationId, TaxonomyShardRoot root) {
        Objects.requireNonNull(root, "root");
        return new AnalysisTaskId(requireOperationId(operationId) + ":"
                + AnalysisTaskType.SUBTAXONOMY_ANALYSIS.token() + ":" + root.code());
    }

    /**
     * Relation identity over a target root set; order-independent. An empty set
     * denotes the operation's complete scored evidence ({@code *}).
     */
    public static AnalysisTaskId relation(String operationId, Collection<TaxonomyShardRoot> targetRoots) {
        if (targetRoots == null || targetRoots.isEmpty()) {
            return new AnalysisTaskId(requireOperationId(operationId) + ":"
                    + AnalysisTaskType.RELATION_ANALYSIS.token() + ":*");
        }
        String targets = new TreeSet<>(targetRoots).stream()
                .map(TaxonomyShardRoot::code).collect(Collectors.joining("+"));
        return new AnalysisTaskId(requireOperationId(operationId) + ":"
                + AnalysisTaskType.RELATION_ANALYSIS.token() + ":" + targets);
    }

    public String operationId() {
        return value.substring(0, value.indexOf(':'));
    }

    @Override
    public String toString() {
        return value;
    }
}
