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

    /** Version-two relation unit: one deterministic, bounded item of a persisted source plan. */
    public static AnalysisTaskId relationWork(String operationId, TaxonomyShardRoot target, int ordinal) {
        if (target == null || !target.defaultCatalogueRoot() || ordinal < 0 || ordinal >= 512)
            throw new IllegalArgumentException("Relation work requires a catalogue root and ordinal 0..511");
        return new AnalysisTaskId(requireOperationId(operationId) + ":" + AnalysisTaskType.RELATION_ANALYSIS.token()
                + ":" + target.code() + ".w" + String.format(java.util.Locale.ROOT, "%06d", ordinal));
    }

    /** Empty for the original whole-target-set relation contract. */
    public java.util.OptionalInt relationWorkOrdinal() {
        String suffix = value.substring(value.lastIndexOf(':') + 1);
        if (!suffix.matches("[A-Z]{2}\\.w[0-9]{6}")) return java.util.OptionalInt.empty();
        int ordinal = Integer.parseInt(suffix.substring(4));
        return ordinal < 512 ? java.util.OptionalInt.of(ordinal) : java.util.OptionalInt.empty();
    }

    public boolean matchesRelation(String operationId, java.util.List<TaxonomyShardRoot> roots, int schemaVersion) {
        if (equals(relation(operationId, roots))) return true;
        var ordinal = relationWorkOrdinal();
        return schemaVersion == 2 && roots.size() == 1 && roots.getFirst().defaultCatalogueRoot()
                && ordinal.isPresent() && equals(relationWork(operationId, roots.getFirst(), ordinal.getAsInt()));
    }

    @Override
    public String toString() {
        return value;
    }
}
