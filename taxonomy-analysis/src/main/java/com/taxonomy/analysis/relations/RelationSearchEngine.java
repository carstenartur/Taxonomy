package com.taxonomy.analysis.relations;

import java.util.*;
import static com.taxonomy.dto.RelationSearchModel.*;

/** Bounded, request-local hierarchy search. Navigation never implies an architectural edge. */
public final class RelationSearchEngine {
    private final Catalogue catalogue;
    private final Evaluator evaluator;
    private final Runnable checkpoint;

    public RelationSearchEngine(Catalogue catalogue, Evaluator evaluator, Runnable checkpoint) {
        this.catalogue = Objects.requireNonNull(catalogue);
        this.evaluator = Objects.requireNonNull(evaluator);
        this.checkpoint = Objects.requireNonNull(checkpoint);
    }

    /** Malformed provider output is distinct from a negative semantic decision. */
    public static final class InvalidResponseException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        public InvalidResponseException(String message) { super(message); }
    }

    /** Carries already verified results when cancellation, memory pressure or an upstream call stops work. */
    public static final class InterruptedSearchException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final transient Result partialResult;
        InterruptedSearchException(RuntimeException cause, Result partialResult) {
            super(cause.getMessage(), cause); this.partialResult = partialResult;
        }
        public Result partialResult() { return partialResult; }
    }

    public Result search(String original, List<Intent> intents, Limits limits) {
        Objects.requireNonNull(intents); Objects.requireNonNull(limits);
        if (original == null || original.isBlank()) throw new IllegalArgumentException("Missing original requirement");
        return new Run(original, limits).execute(List.copyOf(intents));
    }

    private record Work(Query query, int depth, Set<String> path) { }

    private final class Run {
        private final String original;
        private final Limits limits;
        private final long started = System.nanoTime();
        private final Deque<Work> queue = new ArrayDeque<>();
        private final Set<Query> scheduled = new HashSet<>();
        private final Set<Edge> edges = new LinkedHashSet<>();
        private final List<Unfinished> unfinished = new ArrayList<>();
        private final List<Trace> trace = new ArrayList<>();
        private int calls;
        private int visited;
        private List<Intent> seeds = List.of();
        private int nextSeed;

        Run(String original, Limits limits) { this.original = original; this.limits = limits; }

        Result execute(List<Intent> intents) {
            for (Intent intent : intents) {
                if (!original.contains(intent.contribution().quote())) {
                    throw new IllegalArgumentException("Contribution evidence is not an original quote");
                }
            }
            seeds = intents;
            while (!queue.isEmpty() || nextSeed < seeds.size()) {
                if (queue.isEmpty()) {
                    Intent seed = seeds.get(nextSeed++);
                    enqueue(seed.contribution(), seed.type(), seed.direction(), Phase.NAVIGATE,
                            seed.roots(), null, 0, Set.of());
                    if (queue.isEmpty()) continue;
                }
                Work work = queue.removeFirst();
                Query q = work.query();
                try { checkpoint.run(); }
                catch (RuntimeException stopped) { interrupt(work, stopped); }
                if (calls >= limits.maxCalls()) {
                    issue(q, "CALL_BUDGET", ""); drain("CALL_BUDGET"); break;
                }
                calls++;
                List<Decision> decisions;
                try {
                    decisions = validate(q, evaluator.evaluate(q));
                } catch (InvalidResponseException invalid) {
                    issue(q, "INVALID_RESPONSE", invalid.getMessage());
                    continue;
                } catch (RuntimeException stopped) {
                    interrupt(work, stopped);
                    throw new AssertionError("unreachable");
                }
                visited++;
                for (int i = 0; i < q.candidates().size(); i++) {
                    Node node = q.candidates().get(i);
                    Decision decision = decisions.get(i);
                    trace.add(new Trace(q.contribution().source().id(), q.type(), q.direction(),
                            q.phase(), node.id(), decision.outcome(), decision.rationale()));
                    switch (decision.outcome()) {
                        case REJECT -> { /* Pruned by policy; trace retains the negative assessment. */ }
                        case UNRESOLVED -> unfinished.add(new Unfinished(q.contribution().source().id(),
                                q.type(), q.direction(), List.of(node.id()), "UNRESOLVED", decision.question()));
                        case MATCH -> enqueue(q.contribution(), q.type(), q.direction(), Phase.VERIFY,
                                List.of(node), decision, work.depth(), work.path());
                        case VERIFIED -> edges.add(new Edge(q.contribution(), node, q.type(), q.direction(), decision));
                        case DESCEND -> descend(work, node);
                    }
                }
            }
            return snapshot();
        }

        private void descend(Work work, Node node) {
            Query q = work.query();
            if (work.depth() >= limits.maxDepth()) {
                unfinished.add(new Unfinished(q.contribution().source().id(), q.type(), q.direction(),
                        List.of(node.id()), "DEPTH_LIMIT", ""));
                return;
            }
            List<Node> children;
            try { children = List.copyOf(catalogue.children(node)); }
            catch (RuntimeException failed) { interrupt(work, failed); return; }
            if (children.isEmpty()) {
                unfinished.add(new Unfinished(q.contribution().source().id(), q.type(), q.direction(),
                        List.of(node.id()), "NO_CHILDREN", "Refinement was requested but the catalogue has no children."));
                return;
            }
            Set<String> path = new HashSet<>(work.path()); path.add(node.id());
            List<Node> safe = new ArrayList<>();
            for (Node child : children) {
                if (path.contains(child.id())) {
                    unfinished.add(new Unfinished(q.contribution().source().id(), q.type(), q.direction(),
                            List.of(child.id()), "CYCLE", ""));
                } else { safe.add(child); }
            }
            enqueue(q.contribution(), q.type(), q.direction(), Phase.NAVIGATE, safe, null,
                    work.depth() + 1, Set.copyOf(path));
        }

        private void enqueue(Contribution contribution, String type, Direction direction, Phase phase,
                             List<Node> candidates, Decision proposal, int depth, Set<String> path) {
            for (Query query : queries(contribution, type, direction, phase, candidates, proposal)) {
                if (scheduled.contains(query)) continue;
                if (scheduled.size() >= limits.maxWorkItems()) { issue(query, "WORK_LIMIT", ""); continue; }
                scheduled.add(query);
                Work work = new Work(query, depth, path);
                // Finish an admitted branch before admitting unrelated source roots.
                if (phase == Phase.VERIFY || depth > 0) queue.addFirst(work);
                else queue.addLast(work);
            }
        }

        private List<Query> queries(Contribution contribution, String type, Direction direction, Phase phase,
                                    List<Node> candidates, Decision proposal) {
            Map<String, Node> unique = new TreeMap<>();
            for (Node node : candidates) {
                Node previous = unique.putIfAbsent(node.id(), node);
                if (previous != null && !previous.equals(node)) throw new IllegalArgumentException("Conflicting catalogue identity " + node.id());
            }
            List<Node> ordered = List.copyOf(unique.values());
            List<Query> batches = new ArrayList<>();
            for (int i = 0; i < ordered.size(); i += limits.batchSize()) {
                batches.add(new Query(original, contribution, type, direction, phase,
                        ordered.subList(i, Math.min(i + limits.batchSize(), ordered.size())), proposal));
            }
            return batches;
        }

        private List<Decision> validate(Query q, List<Decision> response) {
            if (response == null || response.size() != q.candidates().size()) invalid("Every offered candidate requires exactly one decision");
            Map<String, Decision> byId = new HashMap<>();
            for (Decision d : response) {
                if (d == null || d.targetId() == null || d.outcome() == null || blank(d.rationale())
                        || byId.putIfAbsent(d.targetId(), d) != null) invalid("Missing or duplicate decision fields");
            }
            List<Decision> result = new ArrayList<>();
            for (Node node : q.candidates()) {
                Decision d = byId.get(node.id());
                if (d == null) invalid("Response contains an unknown or missing target ID");
                if ((q.phase() == Phase.NAVIGATE && d.outcome() == Outcome.VERIFIED)
                        || (q.phase() == Phase.VERIFY && (d.outcome() == Outcome.DESCEND || d.outcome() == Outcome.MATCH))) {
                    invalid("Decision is not permitted in this phase");
                }
                if (d.outcome() == Outcome.UNRESOLVED && blank(d.question())) invalid("Unresolved decision requires a question");
                if (d.outcome() == Outcome.MATCH || d.outcome() == Outcome.VERIFIED) {
                    if (node.container() || node.id().equals(q.contribution().source().id())) invalid("Not a concrete distinct endpoint");
                    if (blank(d.contribution()) || blank(d.quote()) || !original.contains(d.quote()) || d.necessity() == null) {
                        invalid("Positive relation requires a scoped contribution and exact original evidence");
                    }
                    if (d.necessity() == Necessity.ALTERNATIVE && blank(d.alternativeGroup())) invalid("Alternative requires an explicit choice group");
                    if (d.necessity() == Necessity.OPTIONAL && blank(d.condition())) invalid("Optional relation requires an explicit condition");
                    if (q.phase() == Phase.VERIFY && !sameClaim(q.proposal(), d)) invalid("Verification must not replace the proposed claim");
                }
                result.add(d);
            }
            return List.copyOf(result);
        }

        private void interrupt(Work current, RuntimeException stopped) {
            issue(current.query(), "INTERRUPTED", ""); drain("INTERRUPTED");
            throw new InterruptedSearchException(stopped, snapshot());
        }
        private void drain(String reason) {
            while (!queue.isEmpty()) issue(queue.removeFirst().query(), reason, "");
            Set<Query> reported = new HashSet<>();
            while (nextSeed < seeds.size()) {
                Intent seed = seeds.get(nextSeed++);
                for (Query query : queries(seed.contribution(), seed.type(), seed.direction(), Phase.NAVIGATE, seed.roots(), null)) {
                    if (!scheduled.contains(query) && reported.add(query)) issue(query, reason, "");
                }
            }
        }
        private void issue(Query q, String reason, String question) {
            unfinished.add(new Unfinished(q.contribution().source().id(), q.type(), q.direction(),
                    q.candidates().stream().map(Node::id).toList(), reason, question));
        }
        private Result snapshot() {
            return new Result(List.copyOf(edges), unfinished, trace, calls, visited,
                    (System.nanoTime() - started) / 1_000_000);
        }
    }

    private static boolean sameClaim(Decision a, Decision b) {
        return Objects.equals(a.targetId(), b.targetId()) && Objects.equals(a.contribution(), b.contribution())
                && Objects.equals(a.quote(), b.quote()) && a.necessity() == b.necessity()
                && Objects.equals(a.condition(), b.condition()) && Objects.equals(a.alternativeGroup(), b.alternativeGroup());
    }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static void invalid(String reason) { throw new InvalidResponseException(reason); }
}
