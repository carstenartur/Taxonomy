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
    public record Options(Limits limits, int maxSources, int maxPromptCharacters) {
        public Options(Limits limits, int maxSources) { this(limits, maxSources, 16_000); }
        public Options {
            Objects.requireNonNull(limits);
            if (maxSources < 1 || maxSources > 256) throw new IllegalArgumentException("maxSources must be 1..256");
            if (maxPromptCharacters == 0) maxPromptCharacters = 16_000; // Historical frozen plans.
            if (maxPromptCharacters < 1 || maxPromptCharacters > 120_000) throw new IllegalArgumentException("Invalid relation prompt budget");
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

    public RelationSearchReport search(String original, Map<String, ? extends Number> inputScores, Options options) {
        return search(original, inputScores, options, new Budget(options));
    }

    /**
     * Complete further source cohorts in a non-resumable local request while its
     * original call/work budget permits. Durable sessions retain their explicit
     * continuation boundary; their caller owns the saved journal and next budget.
     */
    public RelationSearchReport searchWithSourceContinuation(String original,
            Map<String, ? extends Number> inputScores, Options options) {
        if (AnalysisCheckpointSession.active()) return search(original, inputScores, options);
        Objects.requireNonNull(inputScores); Objects.requireNonNull(options);
        long started = System.nanoTime();
        var budget = new Budget(options);
        var journal = new LocalRelationJournal();
        RelationSearchReport report;
        int assessed = 0;
        int maxCohorts = Math.max(1, (inputScores.size() + options.maxSources() - 1) / options.maxSources());
        try (var session = new AnalysisCheckpointSession(journal)) {
            report = search(original, inputScores, options, budget);
            for (int cohort = 1; cohort < maxCohorts; cohort++) {
                int nowAssessed = report.progress() == null ? 0 : report.progress().assessedSources();
                if (!budget.sourceLimitReached || !report.stopReason().isEmpty()
                        || budget.calls >= options.limits().maxCalls() || nowAssessed <= assessed) break;
                // Replaying accepted/clarification/failed answers preserves their
                // meaning. Only the newly admitted sources consume this cohort.
                journal.previous = report;
                assessed = nowAssessed;
                budget.nextSourceCohort();
                report = search(original, inputScores, options, budget);
            }
        }
        return new RelationSearchReport(report.schemaVersion(), report.originalSha256(), report.policy(),
                report.sources(), report.result(), report.totalCalls(), report.maxCalls(),
                (System.nanoTime() - started) / 1_000_000, report.warnings(), report.stopReason(),
                report.progress(), report.tasks());
    }

    private RelationSearchReport search(String original, Map<String, ? extends Number> inputScores,
                                         Options options, Budget budget) {
        if (original == null || original.isBlank()) throw new IllegalArgumentException("Missing original requirement");
        Objects.requireNonNull(inputScores); Objects.requireNonNull(options);
        Map<String, Double> scores = com.taxonomy.dto.AnalysisPercentages.copy(inputScores);
        long start = System.nanoTime();
        var previous = AnalysisCheckpointSession.previousRelations();
        if (previous != null && !previous.originalSha256().equals(sha256(original)))
            throw new IllegalArgumentException("Saved relation evidence belongs to another requirement");
        RelationWorkPlan plan = null;
        List<Node> nodes = List.of();
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
            nodes = sourceNodes(scores, warnings);
            plan = new RelationWorkPlan(nodes, roots, rules, options.limits().maxCalls());
            plan.restore(previous);
            budget.plan = plan;
            plan.calls(budget.calls);
            var protocol = new RelationSearchProtocol(budget::complete, provider, (step, ids) -> {}, options.maxPromptCharacters());
            assessSources(original, nodes, options, protocol, plan, sources, warnings);
            List<Intent> intents = intents(sources, roots, warnings);
            Limits l = options.limits();
            plan.expect(intents);
            var engine = new RelationSearchEngine(node -> {
                List<Node> children = catalogue.children(node);
                if (children.stream().anyMatch(child -> !child.root().equals(node.root()))) {
                    throw new IllegalStateException("CATALOGUE_ROOT_MISMATCH: child belongs to another taxonomy");
                }
                return children;
            }, protocol::evaluateRecovering, checkpoint, plan, true, options.maxPromptCharacters());
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
            var currentTasks = plan == null ? List.<com.taxonomy.dto.RelationSearchProgress.Task>of() : plan.tasks();
            Set<List<String>> completedTasks = new HashSet<>();
            Set<String> completedSources = new HashSet<>(), retainedLegacySources = new HashSet<>();
            currentTasks.forEach(t -> completedSources.add(t.sourceId()));
            currentTasks.forEach(t -> {
                if (t.state() == com.taxonomy.dto.RelationSearchProgress.State.COMPLETED)
                    completedTasks.add(List.of(t.sourceId(), t.targetRoot()));
                else completedSources.remove(t.sourceId());
            });
            result = new Result(List.copyOf(edges), retainedUnfinished(previous.result(), result, completedTasks,
                    completedSources, retainedLegacySources), List.copyOf(trace), result.calls(), result.visitedBatches(), result.durationMillis());
            if (plan != null) plan.retainUnresolved(previous.tasks(), retainedLegacySources);
        }
        sortSourcesByRank(sources, nodes);
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

    private static List<Unfinished> retainedUnfinished(Result previous, Result current, Set<List<String>> completedTasks,
            Set<String> completedSources, Set<String> retainedLegacySources) {
        Set<List<String>> technicalFailures = new HashSet<>(), revisited = new HashSet<>();
        current.unfinished().stream().filter(q -> q.contributionKey() != null
                && q.question().startsWith("TECHNICAL_ASSESSMENT_FAILURE:"))
                .forEach(q -> q.targets().forEach(target -> technicalFailures.add(List.of(q.contributionKey(), target))));
        current.trace().stream().filter(t -> t.contributionKey() != null && t.outcome() != Outcome.MATCH
                && !(t.outcome() == Outcome.UNRESOLVED && technicalFailures.contains(List.of(t.contributionKey(), t.targetId()))))
                .forEach(t -> revisited.add(List.of(t.contributionKey(), t.targetId())));
        var retained = new LinkedHashSet<Unfinished>();
        for (Unfinished question : previous.unfinished()) {
            if (completedSources.contains(question.sourceId()) || question.targetRoot() != null
                    && completedTasks.contains(List.of(question.sourceId(), question.targetRoot()))) continue;
            List<String> targets = question.targets().stream().filter(target -> question.contributionKey() == null
                    || !revisited.contains(List.of(question.contributionKey(), target))).toList();
            if (!targets.isEmpty()) {
                retained.add(new Unfinished(question.sourceId(), question.type(), question.direction(), targets,
                        question.reason(), question.question(), question.contributionKey(), question.targetRoot()));
                if (question.targetRoot() == null) retainedLegacySources.add(question.sourceId());
            }
        }
        retained.addAll(current.unfinished());
        return List.copyOf(retained);
    }

    /**
     * Extract source contributions once, from an immutable, complete source cohort.
     * The caller persists this scalar plan before dispatching target work. The
     * supplied catalogue must be bound to that cohort's exact frozen authority.
     * A missing root result must remain a prerequisite, not a synthetic zero score.
     */
    public RelationSearchDistribution.Plan prepare(String preparationId, String original,
            Map<String, ? extends Number> inputScores, List<String> sourceResultIds, Options options, boolean includeRelations) {
        if (original == null || original.isBlank()) throw new IllegalArgumentException("Missing original requirement");
        Objects.requireNonNull(inputScores); Objects.requireNonNull(options);
        Map<String, Double> scores = com.taxonomy.dto.AnalysisPercentages.copy(inputScores); Objects.requireNonNull(sourceResultIds);
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
            Set<String> admittedSources = new HashSet<>();
            var protocol = new RelationSearchProtocol((step, ids, prompt) -> completion.apply(prompt), provider, (step, ids) -> {
                if (charged[0] >= options.limits().maxCalls())
                    throw new AnalysisCheckpointSession.DeferredException("CALL_BUDGET");
                long additional = ids.stream().filter(id -> !admittedSources.contains(id)).count();
                if (admittedSources.size() + additional > options.maxSources())
                    throw new AnalysisCheckpointSession.DeferredException("SOURCE_LIMIT");
                charged[0]++; admittedSources.addAll(ids); charged[1] = admittedSources.size(); progress.calls(charged[0]);
            }, options.maxPromptCharacters());
            assessSources(original, nodes, options, protocol, progress, sources, warnings);
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
        sortSourcesByRank(sources, nodes);
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
        var progress = new RelationWorkPlan(List.of(intent.contribution().source()), List.of(target), rules, work.maxCalls());
        progress.assessed(plan.sources().get(item.sourceIndex()));
        progress.expect(List.of(intent));
        var protocol = new RelationSearchProtocol((step, ids, prompt) -> {
            if (step == Step.SOURCES) throw new IllegalStateException("Prepared source extraction cannot repeat");
            return completion.apply(prompt);
        }, provider, (step, ids) -> {
            if (calls[0] >= work.maxCalls()) throw new AnalysisCheckpointSession.DeferredException("CALL_BUDGET");
            if (calls[0] >= work.maxWorkItems()) throw new AnalysisCheckpointSession.DeferredException("WORK_LIMIT");
            calls[0]++; progress.calls(calls[0]);
        }, plan.options().maxPromptCharacters());
        Limits limits = plan.options().limits();
        var engine = new RelationSearchEngine(node -> {
            if (!node.root().equals(work.targetRoot().code())) throw new IllegalArgumentException("Foreign target node");
            List<Node> children = List.copyOf(catalogue.children(node));
            if (children.stream().anyMatch(child -> !child.root().equals(work.targetRoot().code())))
                throw new IllegalStateException("CATALOGUE_ROOT_MISMATCH: child belongs to another taxonomy");
            return children;
        }, protocol::evaluateRecovering, checkpoint, progress, true, plan.options().maxPromptCharacters());
        Result result = new Result(List.of(), List.of(), List.of(), 0, 0, 0);
        String stop = "";
        try {
            result = engine.search(original, List.of(intent), limits);
        } catch (RelationSearchEngine.InterruptedSearchException interrupted) {
            result = interrupted.partialResult(); stop = failureReason(interrupted.getCause());
        } catch (RuntimeException failure) { stop = failureReason(failure); }
        progress.finish(stop.isEmpty() && result.searchExhausted());
        return new RelationSearchDistribution.WorkResult(work, result, calls[0], stop);
    }

    /** A conservative branch bound, capped above any spendable operation budget. */
    private int queryBound(List<Node> candidates, int depth, Set<String> path, Limits limits,
                           int cap, Map<String, List<Node>> childrenCache) {
        ChildAssessmentContract.validateCandidates(candidates.stream().map(Node::id).toList());
        // Whole-description packing can need one navigation request per node,
        // plus its single corrective request. Grants must cover both even when
        // the usual count-based batch would have fit in one request.
        int total = (int) Math.min(cap, 2L * candidates.size());
        for (Node node : candidates) {
            if (total >= cap) return cap;
            checkpoint.run();
            int branch = node.container() ? 0 : 2; // Verification and at most one correction.
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
                               RelationWorkPlan plan, List<SourceAssessment> sources, List<String> warnings) {
        int processed = 0;
        List<List<Node>> batches = new ArrayList<>();
        // Stable source cohorts preserve the partial last group at a source-limit
        // boundary, including across replay. A group of three must not strand two
        // available source slots or force already accepted requests to change keys.
        for (int offset = 0; offset < nodes.size(); offset += options.maxSources()) {
            var cohort = nodes.subList(offset, Math.min(offset + options.maxSources(), nodes.size()));
            Map<String, List<Node>> taxonomies = new LinkedHashMap<>();
            for (Node node : cohort) taxonomies.computeIfAbsent(node.root(), unused -> new ArrayList<>()).add(node);
            for (List<Node> taxonomy : taxonomies.values()) {
                batches.addAll(RelationCandidateBatches.split(taxonomy, options.limits().batchSize(), options.maxPromptCharacters(),
                        offered -> RelationSearchProtocol.requestCharacters(RelationSearchProtocol.sourcePrompt(original, offered, Map.of()))));
            }
        }
        for (var batch : batches) {
            checkpoint.run();
            plan.sourceBatch(batch.stream().map(Node::id).toList());
            try {
                var result = protocol.contributionsRecovering(original, batch, assessment -> {
                    sources.add(assessment);
                    plan.assessed(assessment);
                    if (!assessment.question().isBlank()) warnings.add("SOURCE_UNRESOLVED "
                            + assessment.node().id() + ": " + assessment.question());
                });
                result.failures().forEach((id, error) -> warnings.add("INVALID_SOURCE_RESPONSE [" + id + "]: " + error));
                result.failures().values().stream().filter(error -> error.startsWith("CALL_BUDGET:") || error.startsWith("SOURCE_LIMIT:")
                        || error.startsWith("WORK_LIMIT:")).findFirst().ifPresent(warnings::add);
            } catch (AnalysisCheckpointSession.DeferredException deferred) {
                warnings.add(deferred.getMessage() + ": " + (nodes.size() - processed) + " sources remain unassessed.");
                break;
            } catch (RelationSearchEngine.InvalidResponseException invalid) {
                warnings.add("INVALID_SOURCE_RESPONSE " + batch.stream().map(Node::id).toList() + ": " + invalid.getMessage());
            }
            processed += batch.size();
        }
        // Packing combines interleaved taxonomies within the already ranked cohort.
        // Search intents and returned evidence must still follow the original priority.
        sortSourcesByRank(sources, nodes);
    }

    private static void sortSourcesByRank(List<SourceAssessment> sources, List<Node> nodes) {
        Map<String, Integer> rank = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) rank.put(nodes.get(i).id(), i);
        sources.sort(Comparator.comparingInt(source -> rank.getOrDefault(source.node().id(), Integer.MAX_VALUE)));
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
        private int calls, newWork;
        private boolean sourceLimitReached;
        private final Set<String> newSources = new HashSet<>();
        private RelationWorkPlan plan;
        Budget(Options options) { this.options = options; }
        void nextSourceCohort() { newSources.clear(); sourceLimitReached = false; }
        String complete(Step step, List<String> nodes, String prompt) {
            if (calls >= options.limits().maxCalls()) throw new AnalysisCheckpointSession.DeferredException("CALL_BUDGET");
            if (step == Step.SOURCES && newSources.size() + nodes.stream().filter(id -> !newSources.contains(id)).count() > options.maxSources()) {
                sourceLimitReached = true;
                throw new AnalysisCheckpointSession.DeferredException("SOURCE_LIMIT");
            }
            if (step != Step.SOURCES && newWork >= options.limits().maxWorkItems())
                throw new AnalysisCheckpointSession.DeferredException("WORK_LIMIT");
            if (step == Step.SOURCES) newSources.addAll(nodes);
            else newWork++;
            calls++; plan.calls(calls);
            return completion.apply(prompt);
        }
    }

    /** Request-owned replay only; the enclosing run still checks cancellation and resources. */
    private static final class LocalRelationJournal implements AnalysisCheckpointSession.Store {
        private final Map<String, AnalysisCheckpointSession.Checkpoint> answers = new HashMap<>();
        private RelationSearchReport previous;
        public AnalysisCheckpointSession.Checkpoint prepare(AnalysisCheckpointSession.Question question) {
            var saved = answers.get(question.key());
            return saved == null ? new AnalysisCheckpointSession.Checkpoint(question, "ATTEMPT", null) : saved;
        }
        public void finish(AnalysisCheckpointSession.Question question, String state, com.taxonomy.dto.LlmCallDetail detail) {
            // Deferred questions have no answer to retain; their identical input
            // can be admitted by the next bounded cohort without a remote replay.
            if ("READY".equals(state)) answers.remove(question.key());
            else answers.put(question.key(), new AnalysisCheckpointSession.Checkpoint(question, state, detail));
        }
        public void checkActive() { }
        public RelationSearchReport previousRelations() { return previous; }
    }

    private List<Node> sourceNodes(Map<String, Double> scores, List<String> warnings) {
        List<Map.Entry<String,Double>> positive = scores.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() > 0)
                .sorted(Map.Entry.<String,Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
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
        com.taxonomy.analysis.service.AnalysisExecutionFailures.rethrow(failure);
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
