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
                       List<String> warnings, String stopReason, boolean included) {
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
            if (sources.size() > options.maxSources()) throw new IllegalArgumentException("Source limit exceeded");
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
        }
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
        Schedule schedule = schedule(plan, persisted);
        var progress = new RelationWorkPlan(plan.sourceNodes(), plan.targetRoots(),
                new RelationCompatibilityMatrix(), plan.options().limits().maxCalls());
        plan.sources().forEach(progress::assessed);
        progress.expect(plan.items().stream().map(i -> intent(plan, i)).toList());
        Set<Edge> edges = new LinkedHashSet<>();
        List<Trace> trace = new ArrayList<>();
        List<Unfinished> unfinished = new ArrayList<>();
        int calls = plan.sourceCalls(), engineCalls = 0, visited = 0;
        long duration = plan.durationMillis();
        String stop = plan.stopReason();
        // The sequential engine records the first denied frontier query as an
        // attempted batch, then drains later intents. Preserve that diagnostic
        // and task state without dispatching a zero-allowance provider request.
        boolean deniedFrontier = Set.of("CALL_BUDGET", "WORK_LIMIT").contains(schedule.pendingReason())
                && schedule.results().values().stream().flatMap(r -> r.result().unfinished().stream())
                .noneMatch(u -> Set.of("CALL_BUDGET", "WORK_LIMIT").contains(u.reason()));
        for (Item item : plan.items()) {
            WorkResult completed = schedule.results().get(item.ordinal());
            Intent intent = intent(plan, item);
            if (completed == null) {
                unfinished.add(new Unfinished(intent.contribution().source().id(), item.type(), item.direction(),
                        intent.roots().stream().map(Node::id).toList(), schedule.pendingReason(), ""));
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
            if (stop.isEmpty() && !completed.stopReason().isEmpty()) stop = completed.stopReason();
        }
        progress.calls(calls);
        progress.finish(plan.warnings().isEmpty() && stop.isEmpty() && unfinished.isEmpty());
        return new RelationSearchReport(2, plan.originalSha256(),
                "relation-downwalk-v2/complete-plan/resumable-exchanges",
                plan.sources(), new Result(List.copyOf(edges), unfinished, trace, engineCalls, visited, duration),
                calls, plan.options().limits().maxCalls(), duration, plan.warnings(), stop,
                progress.snapshot(), progress.tasks());
    }

    static Intent intent(Plan plan, Item item) {
        Contribution contribution = plan.sources().get(item.sourceIndex()).contributions().get(item.contributionIndex());
        Node root = plan.targetRoots().stream().filter(n -> n.root().equals(item.targetRoot().code()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Missing prepared target root"));
        return new Intent(contribution, item.type(), item.direction(), List.of(root));
    }

    private record Schedule(List<Work> ready, Map<Integer, WorkResult> results, String pendingReason) { }

    private static Schedule schedule(Plan plan, List<WorkResult> persisted) {
        Objects.requireNonNull(plan); Objects.requireNonNull(persisted);
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
        int taskLimit = Math.min(plan.items().size(), plan.options().limits().maxWorkItems());
        int next = 0;
        String reason = plan.stopReason().isEmpty() ? "PENDING" : "INTERRUPTED";
        List<Work> ready = List.of();
        while (next < taskLimit && plan.stopReason().isEmpty()) {
            if (calls == 0 || workItems == 0) { reason = calls == 0 ? "CALL_BUDGET" : "WORK_LIMIT"; break; }
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
                stopped |= !result.stopReason().isEmpty();
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
