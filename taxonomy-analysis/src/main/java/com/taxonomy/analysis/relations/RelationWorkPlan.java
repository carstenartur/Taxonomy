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
    private static final class Counts {
        int total, completed, unresolved;
    }
    private static final class Work {
        final Counts counts;
        int expected, finished;
        State state = State.PENDING;
        Work(Counts counts) { this.counts = counts; }
    }
    private final Map<Pair, Work> work = new LinkedHashMap<>();
    private final Map<String, List<Work>> workBySource = new HashMap<>();
    private final Map<String, Counts> countsByRoot = new LinkedHashMap<>();
    private final Set<String> assessedSources = new HashSet<>();
    private final Set<Intent> finished = new HashSet<>();
    private final Set<Edge> verified = new HashSet<>();
    private final int sources, maximum;
    private int calls, completed, unresolved;
    private Step step = Step.SOURCES;
    private Current current;

    RelationWorkPlan(List<Node> sources, List<Node> roots, RelationCompatibilityMatrix rules, int maximum) {
        this.sources = sources.size(); this.maximum = maximum;
        for (Node source : sources) for (Node root : roots) {
            boolean compatible = Arrays.stream(RelationType.values()).anyMatch(type ->
                    rules.allowedTargetRoots(source.root(), type).contains(root.root())
                    || rules.allowedTargetRoots(root.root(), type).contains(source.root()));
            Pair pair = new Pair(source.id(), root.root());
            if (compatible && !work.containsKey(pair)) {
                Counts counts = countsByRoot.computeIfAbsent(root.root(), unused -> new Counts());
                Work value = new Work(counts);
                work.put(pair, value);
                workBySource.computeIfAbsent(source.id(), unused -> new ArrayList<>()).add(value);
                counts.total++;
            }
        }
        publish();
    }
    void restore(com.taxonomy.dto.RelationSearchReport previous) {
        if (previous == null) return;
        previous.sources().forEach(source -> assessedSources.add(source.node().id()));
        verified.addAll(previous.result().edges());
        for (Task task : previous.tasks()) {
            Work value = work.get(new Pair(task.sourceId(), task.targetRoot()));
            if (value != null && task.state() == State.COMPLETED) state(value, State.COMPLETED);
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
        if (source.contributions().isEmpty()) {
            for (Work value : workBySource.getOrDefault(source.node().id(), List.of())) {
                state(value, source.question().isBlank() ? State.COMPLETED : State.UNRESOLVED);
            }
        }
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
        if (value != null && value.state == State.PENDING) state(value, State.RUNNING);
        publish();
    }
    @Override public void finished(Intent intent, boolean complete) {
        if (!finished.add(intent)) return;
        for (Node root : intent.roots()) {
            Work value = work.get(new Pair(intent.contribution().source().id(), root.root()));
            if (value == null) continue;
            value.finished++;
            if (!complete && value.state != State.COMPLETED) state(value, State.UNRESOLVED);
            else if (value.state != State.UNRESOLVED && value.finished == value.expected) state(value, State.COMPLETED);
        }
        publish();
    }
    @Override public void verified(Edge edge) { verified.add(edge); publish(); }
    void finish(boolean exhausted) {
        step = exhausted ? Step.FINISHED : Step.PAUSED; current = null;
        work.values().stream().filter(w -> w.state == State.RUNNING).forEach(w -> state(w, State.PENDING));
        publish();
    }
    List<Task> tasks() {
        return work.entrySet().stream().map(e -> new Task(e.getKey().source(), e.getKey().root(), e.getValue().state)).toList();
    }
    RelationSearchProgress snapshot() {
        List<Taxonomy> taxonomies = countsByRoot.entrySet().stream().map(e -> {
            Counts counts = e.getValue();
            return new Taxonomy(e.getKey(), counts.total, counts.completed, counts.unresolved,
                    counts.total - counts.completed - counts.unresolved);
        }).toList();
        return new RelationSearchProgress(sources, assessedSources.size(), work.size(), completed, unresolved,
                work.size() - completed - unresolved, calls, maximum, verified.size(), step, current, taxonomies);
    }
    private void state(Work value, State next) {
        if (value.state == next) return;
        if (value.state == State.COMPLETED) { completed--; value.counts.completed--; }
        if (value.state == State.UNRESOLVED) { unresolved--; value.counts.unresolved--; }
        value.state = next;
        if (next == State.COMPLETED) { completed++; value.counts.completed++; }
        if (next == State.UNRESOLVED) { unresolved++; value.counts.unresolved++; }
    }
    private void publish() { AnalysisRunControl.relations(snapshot()); }
}
