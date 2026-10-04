package com.taxonomy.analysis.dag;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Explicit, immutable task graph of one analysis operation.
 *
 * <p>Tasks are stored in a deterministic topological order: the sub-taxonomy
 * tasks in the requested root order, followed by relation tasks whose
 * prerequisites are the sub-taxonomy tasks they read. Supported plans are
 * "all roots", "selected roots" and "no relations".</p>
 */
public record AnalysisTaskGraph(String operationId, List<Node> tasks) {

    /** One executable unit and the units whose results it requires. */
    public record Node(AnalysisTaskId id, AnalysisTaskType type, List<TaxonomyShardRoot> roots,
                       List<AnalysisTaskId> prerequisites) {
        public Node {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(type, "type");
            roots = List.copyOf(roots);
            prerequisites = List.copyOf(prerequisites);
        }
    }

    public AnalysisTaskGraph {
        AnalysisTaskId.requireOperationId(operationId);
        tasks = List.copyOf(tasks);
        Set<AnalysisTaskId> seen = new HashSet<>();
        for (Node node : tasks) {
            if (!node.id().operationId().equals(operationId)) {
                throw new IllegalArgumentException("Task belongs to another operation: " + node.id());
            }
            if (!seen.containsAll(node.prerequisites())) {
                throw new IllegalArgumentException("Task graph is not topologically ordered: " + node.id());
            }
            if (!seen.add(node.id())) {
                throw new IllegalArgumentException("Duplicate task identity: " + node.id());
            }
        }
    }

    /**
     * Plan one sub-taxonomy task per root (in the given order) and, when requested,
     * one relation task that reads all of them.
     */
    public static AnalysisTaskGraph plan(String operationId, Collection<TaxonomyShardRoot> orderedRoots,
                                         boolean includeRelations) {
        List<Node> nodes = new ArrayList<>();
        Set<TaxonomyShardRoot> distinct = new HashSet<>();
        for (TaxonomyShardRoot root : orderedRoots) {
            if (!distinct.add(root)) throw new IllegalArgumentException("Duplicate root: " + root);
            nodes.add(new Node(AnalysisTaskId.subtaxonomy(operationId, root),
                    AnalysisTaskType.SUBTAXONOMY_ANALYSIS, List.of(root), List.of()));
        }
        if (includeRelations) {
            nodes.add(relationNode(operationId, List.copyOf(orderedRoots),
                    nodes.stream().map(Node::id).toList()));
        }
        return new AnalysisTaskGraph(operationId, nodes);
    }

    /** Relation-only plan for operations whose scoring was produced outside this graph. */
    public static AnalysisTaskGraph relationsOnly(String operationId, Collection<TaxonomyShardRoot> targetRoots) {
        return new AnalysisTaskGraph(operationId,
                List.of(relationNode(operationId, List.copyOf(targetRoots), List.of())));
    }

    private static Node relationNode(String operationId, List<TaxonomyShardRoot> targets,
                                     List<AnalysisTaskId> prerequisites) {
        List<TaxonomyShardRoot> sorted = targets.stream().sorted().toList();
        return new Node(AnalysisTaskId.relation(operationId, sorted), AnalysisTaskType.RELATION_ANALYSIS,
                sorted, prerequisites);
    }

    public List<Node> tasksOf(AnalysisTaskType type) {
        return tasks.stream().filter(node -> node.type() == type).toList();
    }

    public List<TaxonomyShardRoot> subtaxonomyRoots() {
        return tasksOf(AnalysisTaskType.SUBTAXONOMY_ANALYSIS).stream()
                .map(node -> node.roots().get(0)).toList();
    }

    public boolean includesRelations() {
        return !tasksOf(AnalysisTaskType.RELATION_ANALYSIS).isEmpty();
    }

    public Optional<Node> node(AnalysisTaskId id) {
        return tasks.stream().filter(node -> node.id().equals(id)).findFirst();
    }

    public int totalTasks() {
        return tasks.size();
    }

    /** Tasks of {@code type} whose prerequisites are complete and that were not yet dispatched. */
    public List<Node> ready(AnalysisTaskType type, Set<AnalysisTaskId> completed, Set<AnalysisTaskId> dispatched) {
        return tasksOf(type).stream()
                .filter(node -> !dispatched.contains(node.id()))
                .filter(node -> completed.containsAll(node.prerequisites()))
                .toList();
    }

    /** Root → task lookup in plan order. */
    public Map<TaxonomyShardRoot, AnalysisTaskId> subtaxonomyTaskIds() {
        Map<TaxonomyShardRoot, AnalysisTaskId> ids = new LinkedHashMap<>();
        tasksOf(AnalysisTaskType.SUBTAXONOMY_ANALYSIS).forEach(node -> ids.put(node.roots().get(0), node.id()));
        return ids;
    }
}
