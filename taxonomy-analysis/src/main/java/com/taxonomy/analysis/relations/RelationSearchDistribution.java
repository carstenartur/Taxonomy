package com.taxonomy.analysis.relations;

import com.taxonomy.analysis.dag.TaxonomyShardRoot;
import com.taxonomy.dto.RelationSearchReport;
import com.taxonomy.relations.service.RelationCompatibilityMatrix;

import java.util.*;

import static com.taxonomy.dto.RelationSearchModel.*;

/**
 * JSON scalar contracts and deterministic admission for distributed downwalks.
 * Persist a Plan once, then publish only its identity and Work references. Source
 * assessments and original requirement text belong in the authoritative store.
 */
public final class RelationSearchDistribution {
    private RelationSearchDistribution() { }

    public record Plan(int schemaVersion, String preparationId, String originalSha256,
                       List<String> sourceResultIds, RequirementRelationSearch.Options options,
                       List<Node> sourceNodes, List<Node> targetRoots, List<SourceAssessment> sources,
                       List<Item> items, int sourceCalls, long durationMillis,
                       List<String> warnings, String stopReason, boolean included, RelationSearchReport inlineReport) {
        public Plan(int schemaVersion, String preparationId, String originalSha256,
                List<String> sourceResultIds, RequirementRelationSearch.Options options,
                List<Node> sourceNodes, List<Node> targetRoots, List<SourceAssessment> sources,
                List<Item> items, int sourceCalls, long durationMillis,
                List<String> warnings, String stopReason, boolean included) {
            this(schemaVersion, preparationId, originalSha256, sourceResultIds, options, sourceNodes, targetRoots,
                    sources, items, sourceCalls, durationMillis, warnings, stopReason, included, null);
        }
        public Plan {
            if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported relation preparation schema");
            required(preparationId); required(originalSha256); Objects.requireNonNull(options);
            sourceResultIds = List.copyOf(sourceResultIds);
            sourceResultIds.forEach(RelationSearchDistribution::required);
            if (new HashSet<>(sourceResultIds).size() != sourceResultIds.size())
                throw new IllegalArgumentException("Duplicate source result reference");
            sourceNodes = List.copyOf(sourceNodes); targetRoots = List.copyOf(targetRoots);
            sources = List.copyOf(sources); items = List.copyOf(items); warnings = List.copyOf(warnings);
            stopReason = stopReason == null ? "" : stopReason;
            if (sourceCalls < 0 || sourceCalls > options.limits().maxCalls() || durationMillis < 0)
                throw new IllegalArgumentException("Invalid preparation budget");
            if (sources.size() > (options.budget() == null ? options.maxSources() : sourceNodes.size()))
                throw new IllegalArgumentException("Source limit exceeded");
            if (!sources.isEmpty() && sourceResultIds.isEmpty())
                throw new IllegalArgumentException("Assessed sources require persisted source references");
            Set<TaxonomyShardRoot> roots = new HashSet<>();
            for (Node node : targetRoots) if (!roots.add(TaxonomyShardRoot.of(node.root())))
                throw new IllegalArgumentException("Duplicate target root");
            for (int n = 0; n < items.size(); n++) {
                Item item = items.get(n);
                if (item.ordinal() != n || item.sourceIndex() >= sources.size()
                        || item.contributionIndex() >= sources.get(item.sourceIndex()).contributions().size()
                        || !roots.contains(item.targetRoot()))
                    throw new IllegalArgumentException("Invalid prepared relation reference");
            }
            if (!included && (!sourceNodes.isEmpty() || !sources.isEmpty() || !items.isEmpty() || sourceCalls != 0))
                throw new IllegalArgumentException("Disabled relations cannot contain work");
            if (inlineReport != null && (!included || options.budget() == null
                    || !options.budget().automatic() && sourceCalls != options.limits().maxCalls()
                    || !items.isEmpty() || !inlineReport.originalSha256().equals(originalSha256)
                    || !inlineReport.sources().equals(sources) || !inlineReport.warnings().equals(warnings)
                    || !inlineReport.stopReason().equals(stopReason)
                    || inlineReport.maxCalls() != options.limits().maxCalls()
                    || inlineReport.totalCalls() < sourceCalls || inlineReport.totalCalls() > options.limits().maxCalls()
                    || inlineReport.budget() == null
                    || inlineReport.budget().callsPerSlice() != options.budget().callsPerSlice()
                    || inlineReport.budget().maxTotalCalls() != options.budget().maxTotalCalls()
                    || inlineReport.budget().automatic() != options.budget().automatic()
                    || inlineReport.budget().sourcesPerSlice() != options.budget().sourcesPerSlice()
                    || inlineReport.budget().workItemsPerSlice() != options.budget().workItemsPerSlice()
                    || inlineReport.budget().sourceCohortSize() != options.budget().sourceCohortSize()
                    || inlineReport.budget().newCalls() != inlineReport.totalCalls()
                    || inlineReport.budget().spentCalls() != (long) options.budget().callsBeforeExecution() + inlineReport.totalCalls()))
                throw new IllegalArgumentException("Invalid inline relation continuation");
            if (inlineReport != null) validateInlineEvidence(sourceNodes, targetRoots, sources, inlineReport);
        }
    }

    /** Inline preparation must retain the same contribution and route authority as target grants. */
    private static void validateInlineEvidence(List<Node> sourceNodes, List<Node> targetRoots,
            List<SourceAssessment> sources, RelationSearchReport report) {
        Set<Node> frozenSources = new HashSet<>(sourceNodes);
        Set<String> sourceIds = new HashSet<>(), roots = new HashSet<>();
        sourceNodes.forEach(node -> sourceIds.add(node.id()));
        targetRoots.forEach(node -> roots.add(node.root()));
        var contributions = new HashMap<String, List<Contribution>>();
        for (SourceAssessment source : sources) {
            if (!frozenSources.contains(source.node()) || source.node().container()
                    || contributions.putIfAbsent(source.node().id(), source.contributions()) != null
                    || source.contributions().stream().anyMatch(value -> !source.node().equals(value.source())))
                throw new IllegalArgumentException("Foreign inline source assessment");
        }
        Set<Contribution> accepted = new HashSet<>();
        contributions.values().forEach(accepted::addAll);
        var rules = new RelationCompatibilityMatrix();
        for (Edge edge : report.result().edges()) {
            if (!accepted.contains(edge.contribution()) || edge.target() == null || edge.target().container()
                    || !roots.contains(edge.target().root()) || edge.evidence() == null
                    || edge.evidence().outcome() != Outcome.VERIFIED
                    || !edge.target().id().equals(edge.evidence().targetId())
                    || !inlineRoute(contributions, roots, rules, edge.contribution().source().id(),
                            edge.type(), edge.direction(), null, edge.target().root()))
                throw new IllegalArgumentException("Foreign inline relation evidence");
        }
        for (Trace trace : report.result().trace()) {
            if (trace.phase() == null || trace.outcome() == null || trace.targetId() == null || trace.targetId().isBlank()
                    || !inlineRoute(contributions, roots, rules, trace.sourceId(), trace.type(), trace.direction(),
                            trace.contributionKey(), null))
                throw new IllegalArgumentException("Foreign inline relation trace");
        }
        for (Unfinished unfinished : report.result().unfinished()) {
            if (unfinished.targets().isEmpty() || unfinished.targets().stream().anyMatch(id -> id == null || id.isBlank())
                    || !inlineRoute(contributions, roots, rules, unfinished.sourceId(), unfinished.type(), unfinished.direction(),
                            unfinished.contributionKey(), unfinished.targetRoot()))
                throw new IllegalArgumentException("Foreign inline unfinished relation");
        }
        var result = report.result();
        if (result.calls() < 0 || result.visitedBatches() < 0 || result.visitedBatches() > result.calls()
                || result.durationMillis() < 0 || report.durationMillis() < 0)
            throw new IllegalArgumentException("Invalid inline relation diagnostics");
        var progress = report.progress();
        if (progress != null && (progress.calls() != report.totalCalls() || progress.maxCalls() != report.maxCalls()
                || !Objects.equals(progress.budget(), report.budget())
                || progress.totalSources() != sourceNodes.size() || progress.assessedSources() != sources.size()
                || progress.taxonomies().stream().anyMatch(taxonomy -> !roots.contains(taxonomy.root()))))
            throw new IllegalArgumentException("Invalid inline relation progress");
        if (report.tasks().stream().anyMatch(task -> !sourceIds.contains(task.sourceId()) || !roots.contains(task.targetRoot())))
            throw new IllegalArgumentException("Foreign inline relation task");
    }

    private static boolean inlineRoute(Map<String, List<Contribution>> contributions, Set<String> roots,
            RelationCompatibilityMatrix rules, String sourceId, String type, Direction direction,
            String contributionKey, String targetRoot) {
        if (type == null || direction == null || targetRoot != null && !roots.contains(targetRoot)) return false;
        var relation = com.taxonomy.model.RelationType.valueOf(type);
        for (Contribution contribution : contributions.getOrDefault(sourceId, List.of())) {
            // A missing key in legacy retained reports stays unspecified; a
            // supplied key must identify this exact contribution and route.
            if (contributionKey != null && !contributionKey.equals(RelationSearchEngine.contributionKey(contribution, type, direction))) continue;
            for (String root : roots) {
                if (targetRoot != null && !targetRoot.equals(root)) continue;
                String origin = direction == Direction.OUTGOING ? contribution.source().root() : root;
                String destination = direction == Direction.OUTGOING ? root : contribution.source().root();
                if (rules.allowedTargetRoots(origin, relation).contains(destination)) return true;
            }
        }
        return false;
    }

    /** One ordered intent, referring to the immutable assessment rather than copying it. */
    public record Item(int ordinal, int sourceIndex, int contributionIndex, String type,
                       Direction direction, TaxonomyShardRoot targetRoot, int maxQueries) {
        public Item {
            if (ordinal < 0 || sourceIndex < 0 || contributionIndex < 0 || maxQueries < 1)
                throw new IllegalArgumentException("Invalid relation item");
            required(type); com.taxonomy.model.RelationType.valueOf(type);
            Objects.requireNonNull(direction); Objects.requireNonNull(targetRoot);
        }
    }

    /** A durable grant: both allowances are part of the identity checked at aggregation. */
    public record Work(String preparationId, int ordinal, TaxonomyShardRoot targetRoot,
                       int maxCalls, int maxWorkItems) {
        public Work {
            required(preparationId); Objects.requireNonNull(targetRoot);
            if (ordinal < 0 || maxCalls < 1 || maxWorkItems < 1)
                throw new IllegalArgumentException("Invalid relation work grant");
        }
    }

    /** Operation budget charges retain already spent exchanges during checkpoint replay. */
    public record WorkResult(Work work, Result result, int calls, String stopReason) {
        public WorkResult {
            Objects.requireNonNull(work); Objects.requireNonNull(result);
            stopReason = stopReason == null ? "" : stopReason;
            if (calls < 0 || calls > work.maxCalls() || calls > work.maxWorkItems())
                throw new IllegalArgumentException("Relation grant exceeded");
        }
    }

    /**
     * A terminal worker failure settles the EXISTING grant with no semantic finding.
     * Consumption is unknown, so reserve its entire allowance rather than inventing
     * a zero-cost failure. Aggregation still verifies the deterministic grant identity.
     */
    public static WorkResult failed(Plan plan, Work grant) {
        return failed(plan, grant, "RELATION_EVALUATION_FAILED");
    }

    /** Preserve actual stop codes: only ordinary evaluation failures permit independent work. */
    public static WorkResult failed(Plan plan, Work grant, String reason) {
        if (reason == null || !reason.matches("[A-Z_]{1,64}"))
            throw new IllegalArgumentException("Invalid relation failure code");
        if (!grant.preparationId().equals(plan.preparationId()) || grant.ordinal() >= plan.items().size())
            throw new IllegalArgumentException("Foreign failed relation grant");
        Item item = plan.items().get(grant.ordinal());
        if (!item.targetRoot().equals(grant.targetRoot()) || grant.maxCalls() > item.maxQueries()
                || grant.maxWorkItems() > item.maxQueries()) throw new IllegalArgumentException("Invalid failed relation grant");
        Intent intent = intent(plan, item);
        var unfinished = new Unfinished(intent.contribution().source().id(), item.type(), item.direction(),
                intent.roots().stream().map(Node::id).toList(), reason, "",
                RelationSearchEngine.contributionKey(intent.contribution(), item.type(), item.direction()), item.targetRoot().code());
        return new WorkResult(grant, new Result(List.of(), List.of(unfinished), List.of(), 0, 0, 0),
                Math.min(grant.maxCalls(), grant.maxWorkItems()), reason);
    }

    /**
     * Return missing members of the current deterministic wave. Do not release
     * unused reservations until every member has a persisted result. This makes
     * reverse delivery equivalent and prevents completion races inflating quotas.
     * Fully funded independent branches may overlap. A constrained first branch
     * receives the remaining allowance and retains the sequential budget prefix.
     */
    public static List<Work> ready(Plan plan, List<WorkResult> persisted) {
        return schedule(plan, persisted).ready();
    }

    /** Aggregate in original intent order, irrespective of broker completion order. */
    public static RelationSearchReport combine(Plan plan, List<WorkResult> persisted) {
        return combine(plan, persisted, null);
    }

    /** Saved progress and questions remain visible before the new grants replay their evidence. */
    public static RelationSearchReport combine(Plan plan, List<WorkResult> persisted, RelationSearchReport previous) {
        Schedule schedule = schedule(plan, persisted);
        if (plan.inlineReport() != null) return retainAcceptedRelations(plan.inlineReport(), previous);
        if (previous != null && !plan.originalSha256().equals(previous.originalSha256()))
            throw new IllegalArgumentException("Saved relation evidence belongs to another requirement");
        var progress = new RelationWorkPlan(plan.sourceNodes(), plan.targetRoots(),
                new RelationCompatibilityMatrix(), plan.options().limits().maxCalls(), false);
        progress.restore(previous);
        plan.sources().forEach(progress::assessed);
        progress.expect(plan.items().stream().map(i -> intent(plan, i)).toList());
        Set<List<String>> previouslyCompleted = new HashSet<>();
        if (previous != null) previous.tasks().stream()
                .filter(task -> task.state() == com.taxonomy.dto.RelationSearchProgress.State.COMPLETED)
                .forEach(task -> previouslyCompleted.add(List.of(task.sourceId(), task.targetRoot())));
        Set<Edge> edges = new LinkedHashSet<>();
        List<Trace> trace = new ArrayList<>();
        List<Unfinished> unfinished = new ArrayList<>();
        int calls = plan.sourceCalls(), engineCalls = 0, visited = 0;
        long duration = plan.durationMillis();
        String stop = plan.stopReason();
        // The sequential engine records the first denied frontier query as an
        // attempted batch, then drains later intents. Preserve that diagnostic
        // and task state without dispatching a zero-allowance provider request.
        boolean deniedFrontier = Set.of("CALL_BUDGET", "TOTAL_CALL_BUDGET", "WORK_LIMIT").contains(schedule.pendingReason())
                && schedule.results().values().stream().flatMap(r -> r.result().unfinished().stream())
                .noneMatch(u -> Set.of("CALL_BUDGET", "TOTAL_CALL_BUDGET", "WORK_LIMIT").contains(u.reason()));
        for (Item item : plan.items()) {
            WorkResult completed = schedule.results().get(item.ordinal());
            Intent intent = intent(plan, item);
            if (completed == null) {
                if (previouslyCompleted.contains(List.of(intent.contribution().source().id(), item.targetRoot().code()))) continue;
                unfinished.add(new Unfinished(intent.contribution().source().id(), item.type(), item.direction(),
                        intent.roots().stream().map(Node::id).toList(), schedule.pendingReason(), "",
                        RelationSearchEngine.contributionKey(intent.contribution(), item.type(), item.direction()), item.targetRoot().code()));
                if (deniedFrontier) {
                    engineCalls++; progress.finished(intent, false); deniedFrontier = false;
                }
                continue;
            }
            Result result = completed.result();
            edges.addAll(result.edges()); trace.addAll(result.trace()); unfinished.addAll(result.unfinished());
            calls += completed.calls(); engineCalls += result.calls(); visited += result.visitedBatches();
            duration += result.durationMillis();
            progress.finished(intent, completed.stopReason().isEmpty() && result.searchExhausted());
            result.edges().forEach(progress::verified);
            // An earlier non-blocking assessment failure must never hide a later execution stop.
            if (!completed.stopReason().isEmpty() && (stop.isEmpty()
                    || !blocksFurtherWork(stop) && blocksFurtherWork(completed.stopReason()))) stop = completed.stopReason();
        }
        progress.calls(calls);
        var sources = new LinkedHashMap<String, SourceAssessment>();
        if (previous != null) {
            previous.sources().forEach(source -> sources.put(source.node().id(), source));
            Set<List<String>> completedTasks = new HashSet<>();
            Set<String> completedSources = new HashSet<>(), retainedLegacySources = new HashSet<>();
            progress.tasks().forEach(task -> completedSources.add(task.sourceId()));
            progress.tasks().forEach(task -> {
                if (task.state() == com.taxonomy.dto.RelationSearchProgress.State.COMPLETED)
                    completedTasks.add(List.of(task.sourceId(), task.targetRoot()));
                else completedSources.remove(task.sourceId());
            });
            unfinished = RequirementRelationSearch.retainedUnfinished(previous.result(),
                    new Result(List.copyOf(edges), unfinished, trace, engineCalls, visited, duration),
                    completedTasks, completedSources, retainedLegacySources);
            progress.retainUnresolved(previous.tasks(), retainedLegacySources);
            var accepted = new LinkedHashSet<>(previous.result().edges()); accepted.addAll(edges); edges = accepted;
            var observations = new LinkedHashSet<>(previous.result().trace()); observations.addAll(trace); trace = List.copyOf(observations);
        }
        plan.sources().forEach(source -> sources.put(source.node().id(), source));
        progress.finish(plan.warnings().isEmpty() && stop.isEmpty() && unfinished.isEmpty());
        var report = new RelationSearchReport(2, plan.originalSha256(),
                "relation-downwalk-v2/complete-plan/resumable-exchanges",
                List.copyOf(sources.values()), new Result(List.copyOf(edges), unfinished, trace, engineCalls, visited, duration),
                calls, plan.options().limits().maxCalls(), duration, plan.warnings(), stop,
                progress.snapshot(), progress.tasks());
        return plan.options().budget() == null ? report : report.withBudget(
                plan.options().budget().usage(calls, plan.sources().size(), Math.max(0, calls - plan.sourceCalls())));
    }

    /** Carry accepted findings across attempts without crediting the current plan for prior work or calls. */
    public static RelationSearchReport retainAcceptedRelations(RelationSearchReport current, RelationSearchReport retained) {
        if (current == null) return retained;
        if (retained == null) return current;
        if (!current.originalSha256().equals(retained.originalSha256()))
            throw new IllegalArgumentException("Saved relation evidence belongs to another requirement");
        var edges = new LinkedHashSet<Edge>();
        retained.result().edges().stream().filter(edge -> edge.evidence().outcome() == Outcome.VERIFIED).forEach(edges::add);
        edges.addAll(current.result().edges());
        var result = current.result();
        var progress = current.progress();
        if (progress != null) progress = new com.taxonomy.dto.RelationSearchProgress(progress.totalSources(), progress.assessedSources(),
                progress.totalSearches(), progress.completedSearches(), progress.unresolvedSearches(), progress.pendingSearches(),
                progress.calls(), progress.maxCalls(), (int) edges.stream().filter(edge -> edge.evidence().outcome() == Outcome.VERIFIED).count(),
                progress.step(), progress.current(), progress.taxonomies(), progress.sourceTaxonomies());
        return new RelationSearchReport(current.schemaVersion(), current.originalSha256(), current.policy(), current.sources(),
                new Result(List.copyOf(edges), result.unfinished(), result.trace(), result.calls(), result.visitedBatches(), result.durationMillis()),
                current.totalCalls(), current.maxCalls(), current.durationMillis(), current.warnings(), current.stopReason(), progress,
                current.tasks(), current.budget()).withBudget(current.budget());
    }

    static Intent intent(Plan plan, Item item) {
        Contribution contribution = plan.sources().get(item.sourceIndex()).contributions().get(item.contributionIndex());
        Node root = plan.targetRoots().stream().filter(n -> n.root().equals(item.targetRoot().code()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Missing prepared target root"));
        return new Intent(contribution, item.type(), item.direction(), List.of(root));
    }

    private static boolean blocksFurtherWork(String reason) {
        return !reason.isEmpty() && !reason.equals("RELATION_EVALUATION_FAILED");
    }

    private record Schedule(List<Work> ready, Map<Integer, WorkResult> results, String pendingReason) { }

    private static Schedule schedule(Plan plan, List<WorkResult> persisted) {
        Objects.requireNonNull(plan); Objects.requireNonNull(persisted);
        if (plan.inlineReport() != null) {
            if (!persisted.isEmpty()) throw new IllegalArgumentException("Inline relations cannot also contain worker results");
            return new Schedule(List.of(), Map.of(), "");
        }
        Map<Integer, WorkResult> byOrdinal = new TreeMap<>();
        for (WorkResult result : persisted) {
            Work work = result.work();
            if (!work.preparationId().equals(plan.preparationId()) || work.ordinal() >= plan.items().size())
                throw new IllegalArgumentException("Foreign relation result");
            WorkResult previous = byOrdinal.putIfAbsent(work.ordinal(), result);
            if (previous != null && !previous.equals(result)) throw new IllegalArgumentException("Conflicting relation result");
        }
        Map<Integer, WorkResult> checked = new TreeMap<>();
        int calls = plan.options().limits().maxCalls() - plan.sourceCalls();
        int workItems = plan.options().limits().maxWorkItems();
        // Ordinals identify all intents, including free accepted replay. The
        // configured work allowance is debited below by actual grant charges.
        int taskLimit = Math.min(plan.items().size(), 100_000);
        int next = 0;
        String reason = plan.stopReason().isEmpty() ? "PENDING" : "INTERRUPTED";
        List<Work> ready = List.of();
        while (next < taskLimit && plan.stopReason().isEmpty()) {
            if (calls == 0 || workItems == 0) { reason = calls == 0 ? RequirementRelationSearch.callBudgetReason(plan.options()) : "WORK_LIMIT"; break; }
            int reserved = 0;
            List<Work> wave = new ArrayList<>();
            while (next < taskLimit) {
                Item item = plan.items().get(next);
                int callQuota = Math.min(item.maxQueries(), calls - reserved);
                int workQuota = Math.min(item.maxQueries(), workItems - reserved);
                int quota = Math.min(callQuota, workQuota);
                if (quota < 1 || (!wave.isEmpty() && quota < item.maxQueries())) break;
                wave.add(new Work(plan.preparationId(), next++, item.targetRoot(), callQuota, workQuota));
                reserved += quota;
                if (quota < item.maxQueries()) break;
            }
            List<Work> missing = new ArrayList<>();
            boolean stopped = false;
            for (Work grant : wave) {
                WorkResult result = byOrdinal.get(grant.ordinal());
                if (result == null) { missing.add(grant); continue; }
                if (!grant.equals(result.work())) throw new IllegalArgumentException("Result does not match its deterministic grant");
                validateEvidence(plan, result);
                checked.put(grant.ordinal(), result);
                // A settled branch failure consumes its allowance, not the right of
                // independently admitted branches to finish. Unknown stop kinds remain conservative.
                stopped |= blocksFurtherWork(result.stopReason());
            }
            if (stopped) { reason = "INTERRUPTED"; break; }
            if (!missing.isEmpty()) { ready = List.copyOf(missing); break; }
            for (Work grant : wave) {
                int spent = byOrdinal.get(grant.ordinal()).calls(); calls -= spent; workItems -= spent;
            }
        }
        if (ready.isEmpty() && next == taskLimit && taskLimit < plan.items().size() && reason.equals("PENDING"))
            reason = "WORK_LIMIT";
        if (checked.size() != byOrdinal.size()) throw new IllegalArgumentException("Result precedes its admitted work wave");
        return new Schedule(ready, Map.copyOf(checked), reason);
    }

    private static void validateEvidence(Plan plan, WorkResult result) {
        Item item = plan.items().get(result.work().ordinal());
        Intent intent = intent(plan, item);
        for (Edge edge : result.result().edges()) {
            if (!edge.contribution().equals(intent.contribution()) || !edge.type().equals(item.type())
                    || edge.direction() != item.direction() || !edge.target().root().equals(item.targetRoot().code())
                    || edge.evidence().outcome() != Outcome.VERIFIED)
                throw new IllegalArgumentException("Foreign relation evidence");
        }
        for (Trace trace : result.result().trace()) {
            if (!trace.sourceId().equals(intent.contribution().source().id()) || !trace.type().equals(item.type())
                    || trace.direction() != item.direction()) throw new IllegalArgumentException("Foreign relation trace");
        }
        for (Unfinished unfinished : result.result().unfinished()) {
            if (!unfinished.sourceId().equals(intent.contribution().source().id()) || !unfinished.type().equals(item.type())
                    || unfinished.direction() != item.direction()) throw new IllegalArgumentException("Foreign unfinished relation");
        }
    }

    private static void required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing relation preparation identity");
    }
}
