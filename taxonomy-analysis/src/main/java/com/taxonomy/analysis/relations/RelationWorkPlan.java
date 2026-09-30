package com.taxonomy.analysis.relations;

import com.taxonomy.analysis.service.AnalysisRunControl;
import com.taxonomy.dto.RelationSearchProgress;
import com.taxonomy.dto.RelationSearchProgress.*;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.service.RelationCompatibilityMatrix;
import java.util.*;
import static com.taxonomy.dto.RelationSearchModel.*;

/** One scalar plan for the entire run, including work outside the current execution budget. */
final class RelationWorkPlan implements RelationSearchEngine.Observer {
    private record Pair(String source, String root) { }
    private static final class Work {
        int expected, finished;
        State state = State.PENDING;
    }
    private final Map<Pair, Work> work = new LinkedHashMap<>();
    private final Set<String> assessedSources = new HashSet<>();
    private final Set<Intent> finished = new HashSet<>();
    private final Set<Edge> verified = new HashSet<>();
    private final int sources, maximum;
    private int calls;
    private Step step = Step.SOURCES;
    private Current current;

    RelationWorkPlan(List<Node> sources, List<Node> roots, RelationCompatibilityMatrix rules, int maximum) {
        this.sources = sources.size(); this.maximum = maximum;
        for (Node source : sources) for (Node root : roots) {
            boolean compatible = Arrays.stream(RelationType.values()).anyMatch(type ->
                    rules.allowedTargetRoots(source.root(), type).contains(root.root())
                    || rules.allowedTargetRoots(root.root(), type).contains(source.root()));
            if (compatible) work.putIfAbsent(new Pair(source.id(), root.root()), new Work());
        }
        publish();
    }
    void restore(com.taxonomy.dto.RelationSearchReport previous) {
        if (previous == null) return;
        previous.sources().forEach(source -> assessedSources.add(source.node().id()));
        verified.addAll(previous.result().edges());
        for (Task task : previous.tasks()) {
            Work value = work.get(new Pair(task.sourceId(), task.targetRoot()));
            if (value != null && task.state() == State.COMPLETED) value.state = State.COMPLETED;
        }
        publish();
    }
    void sourceBatch(List<String> ids) {
        step = Step.SOURCES;
        current = new Current(String.join(", ", ids), "", "", null, 0, ids);
        publish();
    }
    void assessed(SourceAssessment source) {
        assessedSources.add(source.node().id());
        if (source.contributions().isEmpty()) work.forEach((pair, value) -> {
            if (pair.source().equals(source.node().id())) value.state = source.question().isBlank()
                    ? State.COMPLETED : State.UNRESOLVED;
        });
        publish();
    }
    void expect(List<Intent> intents) {
        for (Intent intent : new LinkedHashSet<>(intents)) for (Node root : intent.roots()) {
            Work value = work.get(new Pair(intent.contribution().source().id(), root.root()));
            if (value != null) value.expected++;
        }
    }
    void calls(int count) { calls = count; publish(); }
    @Override public void started(Query query, int depth) {
        step = query.phase() == Phase.VERIFY ? Step.VERIFY : Step.NAVIGATE;
        current = new Current(query.contribution().source().id(), query.candidates().getFirst().root(),
                query.type(), query.direction(), depth, query.candidates().stream().map(Node::id).toList());
        Work value = work.get(new Pair(current.sourceId(), current.targetRoot()));
        if (value != null && value.state == State.PENDING) value.state = State.RUNNING;
        publish();
    }
    @Override public void finished(Intent intent, boolean complete) {
        if (!finished.add(intent)) return;
        for (Node root : intent.roots()) {
            Work value = work.get(new Pair(intent.contribution().source().id(), root.root()));
            if (value == null) continue;
            value.finished++;
            if (!complete && value.state != State.COMPLETED) value.state = State.UNRESOLVED;
            else if (value.state != State.UNRESOLVED && value.finished == value.expected) value.state = State.COMPLETED;
        }
        publish();
    }
    @Override public void verified(Edge edge) { verified.add(edge); publish(); }
    void finish(boolean exhausted) {
        step = exhausted ? Step.FINISHED : Step.PAUSED; current = null;
        work.values().stream().filter(w -> w.state == State.RUNNING).forEach(w -> w.state = State.PENDING);
        publish();
    }
    List<Task> tasks() {
        return work.entrySet().stream().map(e -> new Task(e.getKey().source(), e.getKey().root(), e.getValue().state)).toList();
    }
    RelationSearchProgress snapshot() {
        Map<String, int[]> parts = new LinkedHashMap<>();
        int complete = 0, unresolved = 0;
        for (var entry : work.entrySet()) {
            int[] part = parts.computeIfAbsent(entry.getKey().root(), unused -> new int[3]); part[0]++;
            if (entry.getValue().state == State.COMPLETED) { complete++; part[1]++; }
            if (entry.getValue().state == State.UNRESOLVED) { unresolved++; part[2]++; }
        }
        List<Taxonomy> taxonomies = parts.entrySet().stream().map(e -> new Taxonomy(e.getKey(),
                e.getValue()[0], e.getValue()[1], e.getValue()[2], e.getValue()[0] - e.getValue()[1] - e.getValue()[2])).toList();
        return new RelationSearchProgress(sources, assessedSources.size(), work.size(), complete, unresolved,
                work.size() - complete - unresolved, calls, maximum, verified.size(), step, current, taxonomies);
    }
    private void publish() { AnalysisRunControl.relations(snapshot()); }
}
