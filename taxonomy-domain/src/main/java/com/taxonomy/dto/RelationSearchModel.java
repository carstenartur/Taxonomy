package com.taxonomy.dto;

import java.util.List;
import java.util.Objects;

/** Immutable, request-local contracts. Scores rank work; they never prove an edge. */
public final class RelationSearchModel {
    private RelationSearchModel() { }

    public enum Direction { OUTGOING, INCOMING }
    public enum Phase { NAVIGATE, VERIFY }
    public enum Outcome { DESCEND, MATCH, REJECT, UNRESOLVED, VERIFIED }
    public enum Necessity { REQUIRED, OPTIONAL, ALTERNATIVE }

    public record Node(String id, String root, String name, String description, boolean container) {
        public Node {
            required(id, "node id"); required(root, "root"); required(name, "name");
            description = description == null ? "" : description;
        }
    }

    /** The exact original quote is distinct from the model's scoped interpretation. */
    public record Contribution(Node source, String text, String quote, String condition) {
        public Contribution {
            Objects.requireNonNull(source); required(text, "contribution"); required(quote, "quote");
            condition = condition == null ? "" : condition;
        }
    }

    public record SourceAssessment(Node node, List<Contribution> contributions, String rationale, String question) {
        public SourceAssessment { contributions = List.copyOf(contributions); }
    }

    public record Intent(Contribution contribution, String type, Direction direction, List<Node> roots) {
        public Intent {
            Objects.requireNonNull(contribution); required(type, "type"); Objects.requireNonNull(direction);
            roots = List.copyOf(roots);
        }
    }

    /** Zero calls is a supported dry budget; no remote request is made. */
    public record Limits(int maxCalls, int maxDepth, int batchSize, int maxWorkItems) {
        public Limits {
            if (maxCalls < 0 || maxCalls > 10_000 || maxDepth < 0 || maxDepth > 100
                    || batchSize < 1 || batchSize > 100 || maxWorkItems < 1 || maxWorkItems > 100_000) {
                throw new IllegalArgumentException("Invalid relation-search limits");
            }
        }
    }

    public record Query(String original, Contribution contribution, String type, Direction direction,
                        Phase phase, List<Node> candidates, Decision proposal) {
        public Query {
            required(original, "original"); Objects.requireNonNull(contribution); required(type, "type");
            Objects.requireNonNull(direction); Objects.requireNonNull(phase);
            candidates = List.copyOf(candidates);
            if (candidates.isEmpty()) throw new IllegalArgumentException("Empty candidate batch");
            if (phase == Phase.VERIFY && (candidates.size() != 1 || proposal == null)) {
                throw new IllegalArgumentException("Verification requires one concrete proposal");
            }
        }
    }

    /** Positive decisions retain required/optional/alternative semantics, never percentage shares. */
    public record Decision(String targetId, Outcome outcome, String contribution, String quote,
                           Necessity necessity, String condition, String alternativeGroup,
                           String rationale, String question) { }

    public record Edge(Contribution contribution, Node target, String type, Direction direction,
                       Decision evidence) {
        public String sourceId() { return direction == Direction.OUTGOING ? contribution.source().id() : target.id(); }
        public String targetId() { return direction == Direction.OUTGOING ? target.id() : contribution.source().id(); }
    }

    public record Unfinished(String sourceId, String type, Direction direction, List<String> targets,
                             String reason, String question) {
        public Unfinished { targets = List.copyOf(targets); }
    }

    public record Trace(String sourceId, String type, Direction direction, Phase phase,
                        String targetId, Outcome outcome, String rationale) { }

    public record Result(List<Edge> edges, List<Unfinished> unfinished, List<Trace> trace,
                         int calls, int visitedBatches, long durationMillis) {
        public Result {
            edges = List.copyOf(edges); unfinished = List.copyOf(unfinished); trace = List.copyOf(trace);
        }
        /** Exhausted under this pruning policy; not a proof of semantic completeness. */
        public boolean searchExhausted() { return unfinished.isEmpty(); }
    }

    @FunctionalInterface
    public interface Catalogue { List<Node> children(Node node); }

    @FunctionalInterface
    public interface Evaluator { List<Decision> evaluate(Query query); }

    private static void required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + field);
    }
}
