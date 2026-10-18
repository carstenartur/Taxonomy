package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.relations.RelationSearchDistribution;
import com.taxonomy.catalog.snapshot.RootCatalogueSnapshot;
import com.taxonomy.dto.*;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

/** Bounded worker observations; committed task effects remain the semantic result authority. */
final class ClusterAnalysisProgress {
    static final int MAX_JSON = 8 * 1024 * 1024;
    record Worker(String execution, int delivery, long revision, String phase, String node,
                  Map<String, Double> scores, RelationSearchProgress relations) {
        Worker {
            ClusterAnalysisDiagnostics.uuid(execution);
            if (delivery < 1 || revision < 1 || phase != null && !phase.matches("[A-Z_]{1,64}")
                    || node != null && node.length() > 512) throw new IllegalArgumentException("Invalid worker progress identity");
            scores = Map.copyOf(scores);
            for (var entry : scores.entrySet()) if (entry.getKey().isBlank() || !Double.isFinite(entry.getValue())
                    || entry.getValue() < 0 || entry.getValue() > 100) throw new IllegalArgumentException("Invalid worker coverage");
            validateRelations(relations);
        }
        Worker at(long sequence) { return new Worker(execution, delivery, sequence, phase, node, scores, relations); }
        Worker phase(String value, String current) { return new Worker(execution, delivery, revision, value,
                current == null ? node : ClusterAnalysisDiagnostics.clip(current, 512), scores, relations); }
        Worker scores(Map<String, Double> values) { return new Worker(execution, delivery, revision, phase, node, values, relations); }
        Worker relations(RelationSearchProgress value) { return new Worker(execution, delivery, revision, phase, node, scores, value); }
    }
    record View(AnalysisNodeProgress nodes, RelationSearchProgress relations, String phase, String node) {
        static final View EMPTY = new View(null, null, null, null);
    }

    static Worker read(String source, ObjectMapper json) {
        if (source == null) return null;
        if (source.length() > MAX_JSON) throw new IllegalArgumentException("Oversized worker progress");
        return json.readValue(source, Worker.class);
    }

    static RootCatalogueSnapshot frozen(String source, ObjectMapper json, AnalysisOperationContext context, String root) {
        return frozen(source, json, context, root, false);
    }
    static RootCatalogueSnapshot frozen(String source, ObjectMapper json, AnalysisOperationContext context, String root, boolean restored) {
        // Older admitted operations may predate frozen progress evidence. An unknown
        // catalogue has no denominator; task counts remain available for those runs.
        if (source == null || !json.readTree(source).has("schemaVersion")) return null;
        var frozen = json.readValue(source, RootCatalogueSnapshot.class);
        if (!root.equals(frozen.rootCode()) || !restored && !DurableClusterAnalysisExecution.source(context).equals(frozen.source()))
            throw new IllegalStateException("Progress catalogue differs from admitted authority");
        return frozen;
    }

    static View combine(AnalysisOperationContext context, ClusterAnalysisState state, List<ClusterAnalysisWork> work,
                        Map<String, RootCatalogueSnapshot> roots, String planJson, RelationSearchReport retained, ObjectMapper json) {
        var workers = new LinkedHashMap<ClusterAnalysisWork, Worker>();
        for (var task : work) {
            var progress = read(task.progressJson, json);
            if (progress != null) workers.put(task, progress);
        }
        var current = workers.entrySet().stream().filter(entry -> "RUNNING".equals(entry.getKey().state)
                        && entry.getValue().delivery() == entry.getKey().attempts)
                .max(Comparator.comparingLong(entry -> entry.getValue().revision())).map(Map.Entry::getValue).orElse(null);
        return new View(nodes(work, workers, roots, json), relations(state, work, workers, planJson, retained, json),
                state.terminal() || current == null ? null : current.phase(),
                state.terminal() || current == null ? null : current.node());
    }

    private static AnalysisNodeProgress nodes(List<ClusterAnalysisWork> work, Map<ClusterAnalysisWork, Worker> workers,
                                              Map<String, RootCatalogueSnapshot> roots, ObjectMapper json) {
        var parts = new ArrayList<AnalysisNodeProgress.Taxonomy>();
        int total = 0, assessed = 0, excluded = 0;
        for (var task : work) {
            if (!AnalysisTaskType.SUBTAXONOMY_ANALYSIS.name().equals(task.taskType)) continue;
            var root = roots.get(task.root);
            if (root == null) return null;
            var scores = new HashMap<String, Double>();
            var live = workers.get(task); if (live != null) scores.putAll(live.scores());
            if (task.resultJson != null) scores.putAll(json.readValue(task.resultJson, AnalysisResult.class).getRawScores());
            var byCode = new HashMap<String, RootCatalogueSnapshot.Node>(); root.nodes().forEach(node -> byCode.put(node.code(), node));
            var pruning = new HashMap<String, Boolean>();
            var budgetParents = new HashSet<String>();
            root.nodes().stream().filter(node -> !"PRODUCT".equals(node.analysisRole()))
                    .map(RootCatalogueSnapshot.Node::parentCode).filter(Objects::nonNull).forEach(budgetParents::add);
            int direct = 0, pruned = 0, positive = 0;
            double allocated = 0;
            for (var node : root.nodes()) {
                if (scores.containsKey(node.code())) direct++;
                else if (excluded(node.parentCode(), byCode, scores, pruning)) pruned++;
                Double score = scores.get(node.code());
                if (score != null && score > 0) positive++;
                if (score != null && !"PRODUCT".equals(node.analysisRole()) && !budgetParents.contains(node.code()))
                    allocated += score;
            }
            var name = byCode.get(task.root);
            parts.add(new AnalysisNodeProgress.Taxonomy(task.root, name.nameEn(), name.nameDe(), root.nodes().size(),
                    direct, pruned, root.nodes().size() - direct - pruned, positive, allocated, scores.get(task.root)));
            total += root.nodes().size(); assessed += direct; excluded += pruned;
        }
        return parts.isEmpty() ? null : new AnalysisNodeProgress(total, assessed, excluded, total - assessed - excluded, parts);
    }
    private static boolean excluded(String code, Map<String, RootCatalogueSnapshot.Node> nodes, Map<String, Double> scores,
                                    Map<String, Boolean> memo) {
        if (code == null) return false;
        var known = memo.get(code); if (known != null) return known;
        // Frozen roots have already validated parent closure and absence of cycles.
        boolean value = Double.valueOf(0).equals(scores.get(code)) || excluded(nodes.get(code).parentCode(), nodes, scores, memo);
        memo.put(code, value); return value;
    }

    private static RelationSearchProgress relations(ClusterAnalysisState state, List<ClusterAnalysisWork> tasks,
            Map<ClusterAnalysisWork, Worker> workers, String planJson, RelationSearchReport retained, ObjectMapper json) {
        var live = workers.entrySet().stream().filter(entry -> AnalysisTaskType.RELATION_ANALYSIS.name().equals(entry.getKey().taskType)
                        && "RUNNING".equals(entry.getKey().state) && entry.getValue().delivery() == entry.getKey().attempts
                        && entry.getValue().relations() != null)
                .max(Comparator.comparingLong(entry -> entry.getValue().revision())).map(Map.Entry::getValue).orElse(null);
        if (planJson == null) {
            var preparation = workers.entrySet().stream().filter(entry -> AnalysisTaskType.RELATION_ANALYSIS.name().equals(entry.getKey().taskType)
                            && entry.getKey().inputJson == null && entry.getValue().relations() != null)
                    .max(Comparator.comparingLong(entry -> entry.getValue().revision())).map(entry -> entry.getValue().relations()).orElse(null);
            return preparation == null ? null : withActivity(preparation,
                    state.terminal() ? RelationSearchProgress.Step.PAUSED : preparation.step(), state.terminal() ? null : preparation.current(), preparation.calls());
        }
        var plan = json.readValue(planJson, RelationSearchDistribution.Plan.class);
        var results = tasks.stream().filter(task -> AnalysisTaskType.RELATION_ANALYSIS.name().equals(task.taskType)
                        && task.inputJson != null && task.resultJson != null)
                .map(task -> json.readValue(task.resultJson, RelationSearchDistribution.WorkResult.class)).toList();
        var report = RelationSearchDistribution.retainAcceptedRelations(RelationSearchDistribution.combine(plan, results), retained);
        var progress = report.progress(); int calls = progress.calls();
        for (var entry : workers.entrySet()) if (entry.getKey().inputJson != null && entry.getKey().resultJson == null
                && entry.getValue().relations() != null) calls += entry.getValue().relations().calls();
        calls = Math.min(calls, progress.maxCalls());
        var step = state.terminal() ? (state == ClusterAnalysisState.COMPLETED && report.isSearchExhausted()
                ? RelationSearchProgress.Step.FINISHED : RelationSearchProgress.Step.PAUSED)
                : report.isSearchExhausted() ? RelationSearchProgress.Step.FINISHED
                : live != null && live.relations().current() != null ? live.relations().step() : RelationSearchProgress.Step.NAVIGATE;
        return withActivity(progress, step, state.terminal() || live == null ? null : live.relations().current(), calls);
    }
    private static RelationSearchProgress withActivity(RelationSearchProgress progress, RelationSearchProgress.Step step,
                                                        RelationSearchProgress.Current current, int calls) {
        return new RelationSearchProgress(progress.totalSources(), progress.assessedSources(), progress.totalSearches(),
                progress.completedSearches(), progress.unresolvedSearches(), progress.pendingSearches(), calls, progress.maxCalls(),
                progress.verifiedRelations(), step, current, progress.taxonomies(), progress.sourceTaxonomies());
    }

    static void validateRelations(RelationSearchProgress p) {
        if (p == null) return;
        if (p.step() == null || p.totalSources() < 0 || p.assessedSources() < 0 || p.assessedSources() > p.totalSources()
                || p.totalSources() > 1_000_000 || p.totalSearches() > 8_000_000 || p.maxCalls() > 10_000 || p.verifiedRelations() > 1_000_000
                || p.totalSearches() < 0 || p.completedSearches() < 0 || p.unresolvedSearches() < 0 || p.pendingSearches() < 0
                || (long) p.completedSearches() + p.unresolvedSearches() + p.pendingSearches() != p.totalSearches()
                || p.calls() < 0 || p.maxCalls() < 0 || p.calls() > p.maxCalls() || p.verifiedRelations() < 0
                || p.taxonomies().size() > 8 || p.sourceTaxonomies().size() > 8)
            throw new IllegalArgumentException("Invalid relation progress counts");
        int sources = 0; var sourceRoots = new HashSet<String>();
        for (var source : p.sourceTaxonomies()) {
            if (!TaxonomyShardRoot.of(source.root()).defaultCatalogueRoot() || !sourceRoots.add(source.root())
                    || source.total() < 0 || source.completed() < 0 || source.unresolved() < 0
                    || (long) source.completed() + source.unresolved() > source.total())
                throw new IllegalArgumentException("Invalid relation source progress");
            sources += source.total();
        }
        if (!p.sourceTaxonomies().isEmpty() && sources != p.totalSources())
            throw new IllegalArgumentException("Relation source totals differ from plan");
        int total = 0, completed = 0, unresolved = 0, pending = 0; var roots = new HashSet<String>();
        for (var root : p.taxonomies()) {
            if (!TaxonomyShardRoot.of(root.root()).defaultCatalogueRoot() || !roots.add(root.root()) || root.total() < 0
                    || root.completed() < 0 || root.unresolved() < 0 || root.pending() < 0
                    || (long) root.completed() + root.unresolved() + root.pending() != root.total())
                throw new IllegalArgumentException("Invalid target progress");
            total += root.total(); completed += root.completed(); unresolved += root.unresolved(); pending += root.pending();
        }
        if (total != p.totalSearches() || completed != p.completedSearches() || unresolved != p.unresolvedSearches() || pending != p.pendingSearches())
            throw new IllegalArgumentException("Conflicting target progress");
        var current = p.current();
        if (current != null && (current.sourceId() == null || current.sourceId().length() > 32_000
                || current.targetRoot() == null || current.type() == null || current.type().length() > 64
                || current.depth() < 0 || current.candidates().size() > 256
                || current.candidates().stream().anyMatch(id -> id == null || id.length() > 512)))
            throw new IllegalArgumentException("Invalid current relation search");
    }
}
