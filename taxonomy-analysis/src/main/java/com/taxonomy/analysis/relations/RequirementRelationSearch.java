package com.taxonomy.analysis.relations;

import com.taxonomy.dto.RelationSearchReport;
import com.taxonomy.analysis.assessment.ChildAssessmentContract;
import com.taxonomy.analysis.recovery.AnalysisCheckpointSession;
import com.taxonomy.dto.RelationSearchProgress.Step;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.service.RelationCompatibilityMatrix;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Function;
import static com.taxonomy.dto.RelationSearchModel.*;

/** One bounded analysis session; no writes, catalogue entities or cross-user caches. */
public final class RequirementRelationSearch {
    public interface InputCatalogue extends Catalogue {
        Node find(String id);
        List<Node> roots();
    }
    public record Options(Limits limits, int maxSources) {
        public Options {
            Objects.requireNonNull(limits);
            if (maxSources < 1 || maxSources > 256) throw new IllegalArgumentException("maxSources must be 1..256");
        }
    }
    private final InputCatalogue catalogue;
    private final RelationCompatibilityMatrix rules;
    private final Function<String, String> completion;
    private final String provider;
    private final Runnable checkpoint;

    public RequirementRelationSearch(InputCatalogue catalogue, RelationCompatibilityMatrix rules,
                                     Function<String, String> complete, Runnable checkpoint) {
        this(catalogue, rules, complete, checkpoint, "unspecified");
    }
    public RequirementRelationSearch(InputCatalogue catalogue, RelationCompatibilityMatrix rules,
                                     Function<String, String> complete, Runnable checkpoint, String provider) {
        this.catalogue = Objects.requireNonNull(catalogue);
        this.rules = Objects.requireNonNull(rules);
        this.completion = Objects.requireNonNull(complete);
        this.provider = Objects.requireNonNull(provider);
        this.checkpoint = Objects.requireNonNull(checkpoint);
    }

    /** No remote assessment is possible with an embedding-only provider. */
    public static RelationSearchReport unassessed(String original, int maxCalls, String reason) {
        if (original == null || original.isBlank()) throw new IllegalArgumentException("Missing original requirement");
        return new RelationSearchReport(1, sha256(original),
                "relation-downwalk-v1/root-compatibility-profile/contribution-round-robin",
                List.of(), new Result(List.of(), List.of(), List.of(), 0, 0, 0),
                0, maxCalls, 0, List.of(reason), reason);
    }

    public RelationSearchReport search(String original, Map<String, Integer> scores, Options options) {
        if (original == null || original.isBlank()) throw new IllegalArgumentException("Missing original requirement");
        Objects.requireNonNull(scores); Objects.requireNonNull(options);
        long start = System.nanoTime();
        var previous = AnalysisCheckpointSession.previousRelations();
        if (previous != null && !previous.originalSha256().equals(sha256(original)))
            throw new IllegalArgumentException("Saved relation evidence belongs to another requirement");
        Budget budget = new Budget(options);
        RelationWorkPlan plan = null;
        List<SourceAssessment> sources = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Result result = new Result(List.of(), List.of(), List.of(), 0, 0, 0);
        String stop = "";
        try {
            checkpoint.run();
            // Catalogue validity is a precondition for extraction, not a late
            // engine concern after the caller has already paid for model calls.
            List<Node> offeredRoots = List.copyOf(catalogue.roots());
            ChildAssessmentContract.validateCandidates(offeredRoots.stream().map(Node::id).toList());
            List<Node> roots = offeredRoots.stream().sorted(Comparator.comparing(Node::id)).toList();
            List<Node> nodes = sourceNodes(scores, warnings);
            plan = new RelationWorkPlan(nodes, roots, rules, options.limits().maxCalls());
            plan.restore(previous);
            budget.plan = plan;
            var protocol = new RelationSearchProtocol(budget::complete, provider);
            assessSources(original, nodes, options, protocol, plan, sources, warnings, batch -> { });
            List<Intent> intents = intents(sources, roots, warnings);
            Limits l = options.limits();
            plan.expect(intents);
            var engine = new RelationSearchEngine(node -> {
                List<Node> children = catalogue.children(node);
                if (children.stream().anyMatch(child -> !child.root().equals(node.root()))) {
                    throw new IllegalStateException("CATALOGUE_ROOT_MISMATCH: child belongs to another taxonomy");
                }
                return children;
            }, protocol::evaluate, checkpoint, plan, true);
            result = engine.search(original, intents, l);
        } catch (RelationSearchEngine.InterruptedSearchException interrupted) {
            result = interrupted.partialResult();
            stop = failureReason(interrupted.getCause());
        } catch (RuntimeException failure) {
            stop = failureReason(failure);
        }
        if (previous != null) {
            var retainedSources = new LinkedHashMap<String, SourceAssessment>();
            previous.sources().forEach(source -> retainedSources.put(source.node().id(), source));
            sources.forEach(source -> retainedSources.put(source.node().id(), source));
            sources = new ArrayList<>(retainedSources.values());
            var edges = new LinkedHashSet<>(previous.result().edges()); edges.addAll(result.edges());
            var trace = new LinkedHashSet<>(previous.result().trace()); trace.addAll(result.trace());
            result = new Result(List.copyOf(edges), result.unfinished(), List.copyOf(trace),
                    result.calls(), result.visitedBatches(), result.durationMillis());
        }
        if (plan != null) plan.finish(warnings.isEmpty() && stop.isEmpty() && result.searchExhausted());
        var progress = plan == null ? null : plan.snapshot();
        var tasks = plan == null ? List.<com.taxonomy.dto.RelationSearchProgress.Task>of() : plan.tasks();
        if (progress == null && previous != null && previous.progress() != null) {
            var p = previous.progress(); tasks = previous.tasks();
            progress = new com.taxonomy.dto.RelationSearchProgress(p.totalSources(), p.assessedSources(), p.totalSearches(),
                    p.completedSearches(), p.unresolvedSearches(), p.pendingSearches(), budget.calls,
                    options.limits().maxCalls(), p.verifiedRelations(), Step.PAUSED, null, p.taxonomies());
        }
        return new RelationSearchReport(2, sha256(original), "relation-downwalk-v2/complete-plan/resumable-exchanges",
                sources, result, budget.calls, options.limits().maxCalls(),
                (System.nanoTime() - start) / 1_000_000, warnings, stop,
                progress, tasks);
    }

    /**
     * Extract source contributions once, from an immutable, complete source cohort.
     * The caller persists this scalar plan before dispatching target work. The
     * supplied catalogue must be bound to that cohort's exact frozen authority.
     * A missing root result must remain a prerequisite, not a synthetic zero score.
     */
    public RelationSearchDistribution.Plan prepare(String preparationId, String original,
            Map<String, Integer> scores, List<String> sourceResultIds, Options options, boolean includeRelations) {
        if (original == null || original.isBlank()) throw new IllegalArgumentException("Missing original requirement");
        Objects.requireNonNull(scores); Objects.requireNonNull(options); Objects.requireNonNull(sourceResultIds);
        long started = System.nanoTime();
        List<Node> nodes = List.of(), roots = List.of();
        List<SourceAssessment> sources = new ArrayList<>();
        List<RelationSearchDistribution.Item> items = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String stop = "";
        int[] charged = {0, 0}; // Immutable operation charges: calls, source nodes, including durable replay.
        if (includeRelations) try {
            checkpoint.run();
            List<Node> offered = List.copyOf(catalogue.roots());
            ChildAssessmentContract.validateCandidates(offered.stream().map(Node::id).toList());
            roots = offered.stream().sorted(Comparator.comparing(Node::id)).toList();
            nodes = sourceNodes(scores, warnings);
            var progress = new RelationWorkPlan(nodes, roots, rules, options.limits().maxCalls());
            var protocol = new RelationSearchProtocol((step, ids, prompt) -> completion.apply(prompt), provider);
            assessSources(original, nodes, options, protocol, progress, sources, warnings, batch -> {
                if (charged[0] >= options.limits().maxCalls())
                    throw new AnalysisCheckpointSession.DeferredException("CALL_BUDGET");
                if (charged[1] + batch.size() > options.maxSources())
                    throw new AnalysisCheckpointSession.DeferredException("SOURCE_LIMIT");
                charged[0]++; charged[1] += batch.size(); progress.calls(charged[0]);
            });
            List<Intent> intents = intents(sources, roots, warnings);
            Map<String, Integer> bounds = new HashMap<>();
            int cap = Math.min(options.limits().maxCalls(), options.limits().maxWorkItems()) + 1;
            for (Intent intent : intents) {
                Node root = intent.roots().getFirst();
                int bound = bounds.computeIfAbsent(root.root(), unused ->
                        queryBound(List.of(root), 0, Set.of(), options.limits(), cap, new HashMap<>()));
                Contribution contribution = intent.contribution();
                int sourceIndex = -1, contributionIndex = -1;
                for (int n = 0; n < sources.size(); n++) {
                    int at = sources.get(n).contributions().indexOf(contribution);
                    if (at >= 0) { sourceIndex = n; contributionIndex = at; break; }
                }
                items.add(new RelationSearchDistribution.Item(items.size(), sourceIndex, contributionIndex,
                        intent.type(), intent.direction(), com.taxonomy.analysis.dag.TaxonomyShardRoot.of(root.root()), bound));
            }
        } catch (RuntimeException failure) { stop = failureReason(failure); }
        return new RelationSearchDistribution.Plan(1, preparationId, sha256(original), sourceResultIds, options,
                nodes, roots, sources, items, charged[0], (System.nanoTime() - started) / 1_000_000,
                warnings, stop, includeRelations);
    }

    /**
     * Evaluate a persisted grant using only its frozen target shard. Source
     * catalogue reads and contribution extraction are deliberately absent here.
     * Admission through RelationSearchDistribution.ready is the coordinator's
     * durable responsibility; the worker enforces the admitted local allowance.
     */
    public RelationSearchDistribution.WorkResult evaluate(String original, RelationSearchDistribution.Plan plan,
                                                          RelationSearchDistribution.Work work) {
        Objects.requireNonNull(plan); Objects.requireNonNull(work);
        if (original == null || !sha256(original).equals(plan.originalSha256())
                || !work.preparationId().equals(plan.preparationId()) || work.ordinal() >= plan.items().size())
            throw new IllegalArgumentException("Foreign relation work or original requirement");
        var item = plan.items().get(work.ordinal());
        if (!work.targetRoot().equals(item.targetRoot()) || work.maxCalls() > item.maxQueries()
                || work.maxWorkItems() > item.maxQueries()
                || work.maxCalls() > plan.options().limits().maxCalls() - plan.sourceCalls()
                || work.maxWorkItems() > plan.options().limits().maxWorkItems())
            throw new IllegalArgumentException("Invalid relation work grant");
        Intent intent = RelationSearchDistribution.intent(plan, item);
        Node target = intent.roots().getFirst();
        List<Node> offered = List.copyOf(catalogue.roots());
        ChildAssessmentContract.validateCandidates(offered.stream().map(Node::id).toList());
        if (offered.stream().noneMatch(target::equals))
            throw new IllegalArgumentException("Missing or different frozen relation target shard");
        int[] calls = {0};
        var protocol = new RelationSearchProtocol((step, ids, prompt) -> {
            if (step == Step.SOURCES) throw new IllegalStateException("Prepared source extraction cannot repeat");
            return completion.apply(prompt);
        }, provider);
        Limits limits = plan.options().limits();
        var engine = new RelationSearchEngine(node -> {
            if (!node.root().equals(work.targetRoot().code())) throw new IllegalArgumentException("Foreign target node");
            List<Node> children = List.copyOf(catalogue.children(node));
            if (children.stream().anyMatch(child -> !child.root().equals(work.targetRoot().code())))
                throw new IllegalStateException("CATALOGUE_ROOT_MISMATCH: child belongs to another taxonomy");
            return children;
        }, query -> {
            if (calls[0] >= work.maxCalls()) throw new AnalysisCheckpointSession.DeferredException("CALL_BUDGET");
            if (calls[0] >= work.maxWorkItems()) throw new AnalysisCheckpointSession.DeferredException("WORK_LIMIT");
            calls[0]++;
            return protocol.evaluate(query);
        }, checkpoint, new RelationSearchEngine.Observer() { }, true);
        Result result = new Result(List.of(), List.of(), List.of(), 0, 0, 0);
        String stop = "";
        try {
            result = engine.search(original, List.of(intent), limits);
        } catch (RelationSearchEngine.InterruptedSearchException interrupted) {
            result = interrupted.partialResult(); stop = failureReason(interrupted.getCause());
        } catch (RuntimeException failure) { stop = failureReason(failure); }
        return new RelationSearchDistribution.WorkResult(work, result, calls[0], stop);
    }

    /** A conservative branch bound, capped above any spendable operation budget. */
    private int queryBound(List<Node> candidates, int depth, Set<String> path, Limits limits,
                           int cap, Map<String, List<Node>> childrenCache) {
        ChildAssessmentContract.validateCandidates(candidates.stream().map(Node::id).toList());
        int total = (candidates.size() + limits.batchSize() - 1) / limits.batchSize();
        for (Node node : candidates) {
            if (total >= cap) return cap;
            checkpoint.run();
            int branch = node.container() ? 0 : 1; // An admitted concrete match requires verification.
            if (depth < limits.maxDepth()) {
                List<Node> children = childrenCache.computeIfAbsent(node.id(), unused -> List.copyOf(catalogue.children(node)));
                ChildAssessmentContract.validateCandidates(children.stream().map(Node::id).toList());
                if (children.stream().anyMatch(child -> !child.root().equals(node.root())))
                    throw new IllegalStateException("CATALOGUE_ROOT_MISMATCH: child belongs to another taxonomy");
                Set<String> next = new HashSet<>(path); next.add(node.id());
                List<Node> safe = children.stream().filter(child -> !next.contains(child.id())).toList();
                if (!safe.isEmpty()) branch = Math.max(branch,
                        queryBound(safe, depth + 1, Set.copyOf(next), limits, cap, childrenCache));
            }
            total = Math.min(cap, total + branch);
        }
        return total;
    }

    private void assessSources(String original, List<Node> nodes, Options options, RelationSearchProtocol protocol,
                               RelationWorkPlan plan, List<SourceAssessment> sources, List<String> warnings,
                               java.util.function.Consumer<List<Node>> admission) {
        int size = options.limits().batchSize();
        for (int i = 0; i < nodes.size();) {
            checkpoint.run();
            int length = Math.min(size, options.maxSources() - i % options.maxSources());
            var batch = nodes.subList(i, Math.min(i + length, nodes.size()));
            plan.sourceBatch(batch.stream().map(Node::id).toList());
            try {
                admission.accept(batch);
                var assessments = protocol.contributions(original, batch);
                sources.addAll(assessments);
                for (var assessment : assessments) {
                    plan.assessed(assessment);
                    if (!assessment.question().isBlank()) warnings.add("SOURCE_UNRESOLVED "
                            + assessment.node().id() + ": " + assessment.question());
                }
            } catch (AnalysisCheckpointSession.DeferredException deferred) {
                warnings.add(deferred.getMessage() + ": " + (nodes.size() - i) + " sources remain unassessed.");
                break;
            } catch (RelationSearchEngine.InvalidResponseException invalid) {
                warnings.add("INVALID_SOURCE_RESPONSE " + batch.stream().map(Node::id).toList() + ": " + invalid.getMessage());
            }
            i += batch.size();
        }
    }

    private List<Intent> intents(List<SourceAssessment> sources, List<Node> roots, List<String> warnings) {
    List<List<Intent>> routesByContribution = new ArrayList<>();
    for (SourceAssessment assessment : sources) for (Contribution contribution : assessment.contributions()) {
        List<Intent> intents = new ArrayList<>();
        for (RelationType type : RelationType.values()) {
            Set<String> allowed = rules.allowedTargetRoots(contribution.source().root(), type);
            List<Node> outgoing = roots.stream().filter(n -> allowed.contains(n.root())).toList();
            for (Node root : outgoing) intents.add(new Intent(contribution, type.name(), Direction.OUTGOING, List.of(root)));
            List<Node> incoming = roots.stream().filter(n -> rules.allowedTargetRoots(n.root(), type)
                    .contains(contribution.source().root())).toList();
            for (Node root : incoming) intents.add(new Intent(contribution, type.name(), Direction.INCOMING, List.of(root)));
        }
        if (intents.isEmpty()) warnings.add("NO_STRUCTURAL_ROUTE " + contribution.source().id()
                + ": the current root profile cannot route this contribution; not evidence of absence.");
        routesByContribution.add(intents);
    }
    // Complete an admitted branch, then give the next contribution a turn.
    // One high-ranked source must not spend the entire budget on its types.
    List<Intent> intents = new ArrayList<>();
    int rounds = routesByContribution.stream().mapToInt(List::size).max().orElse(0);
    for (int round = 0; round < rounds; round++) {
        for (List<Intent> routes : routesByContribution) {
            if (round < routes.size()) intents.add(routes.get(round));
        }
    }
        return List.copyOf(intents);
    }

    private final class Budget {
        private final Options options;
        private int calls, newSources, newWork;
        private RelationWorkPlan plan;
        Budget(Options options) { this.options = options; }
        String complete(Step step, List<String> nodes, String prompt) {
            if (calls >= options.limits().maxCalls()) throw new AnalysisCheckpointSession.DeferredException("CALL_BUDGET");
            if (step == Step.SOURCES && newSources + nodes.size() > options.maxSources())
                throw new AnalysisCheckpointSession.DeferredException("SOURCE_LIMIT");
            if (step != Step.SOURCES && newWork >= options.limits().maxWorkItems())
                throw new AnalysisCheckpointSession.DeferredException("WORK_LIMIT");
            if (step == Step.SOURCES) newSources += nodes.size();
            else newWork++;
            calls++; plan.calls(calls);
            return completion.apply(prompt);
        }
    }

    private List<Node> sourceNodes(Map<String, Integer> scores, List<String> warnings) {
        List<Map.Entry<String,Integer>> positive = scores.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() > 0)
                .sorted(Map.Entry.<String,Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .toList();
        if (positive.isEmpty()) warnings.add("SOURCE_DISCOVERY_REQUIRED: no positively assessed concrete source is available; "
                + "this is not proof that the requirement has no architectural dependencies.");
        List<Node> nodes = new ArrayList<>();
        Set<String> concreteRoots = new HashSet<>();
        Set<String> containerRoots = new TreeSet<>();
        int missing = 0;
        for (var entry : positive) {
            checkpoint.run();
            Node node = catalogue.find(entry.getKey());
            if (node == null) { missing++; continue; }
            if (node.container()) { containerRoots.add(node.root()); continue; }
            concreteRoots.add(node.root()); nodes.add(node);
        }
        if (missing > 0) warnings.add("MISSING_SOURCE: " + missing + " scored IDs are absent from the catalogue.");
        containerRoots.removeAll(concreteRoots);
        for (String root : containerRoots) warnings.add("ROOT_SOURCE_UNRESOLVED " + root
                + ": identify a concrete required contribution below this catalogue container.");
        return List.copyOf(nodes);
    }

    private static String failureReason(Throwable failure) {
        // Do not copy provider exception messages which can contain credentials or request bodies.
        if (failure instanceof com.taxonomy.analysis.service.AnalysisStoppedException stopped) {
            return stopped.reason().name() + ": completed evidence retained; no further model evaluations.";
        }
        return "RELATION_SEARCH_STOPPED: " + failure.getClass().getSimpleName();
    }
    private static String sha256(String original) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
