import com.sun.management.ThreadMXBean;
import com.taxonomy.analysis.dag.TaxonomyShardRoot;
import com.taxonomy.analysis.relations.RelationSearchDistribution;
import com.taxonomy.analysis.relations.RelationSearchDistribution.Item;
import com.taxonomy.analysis.relations.RelationSearchDistribution.Plan;
import com.taxonomy.analysis.relations.RelationSearchDistribution.WorkResult;
import com.taxonomy.analysis.relations.RequirementRelationSearch;
import com.taxonomy.dsl.model.CatalogueDefinition;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static com.taxonomy.dto.RelationSearchModel.*;

/** Standalone, opt-in probe. Start with the analysis test runtime classpath. */
class RelationValidationProbe {
    private static final int SOURCES = 48;
    private static final int PERSISTED = 72;
    private static final int TRIALS = 7;
    private static final int ITERATIONS = 1_000;
    private static final Direction DIRECTION = Direction.OUTGOING;

    public static void main(String[] args) {
        var plan = plan();
        var history = history(plan);
        var report = RelationSearchDistribution.combine(plan, history);
        RelationSearchDistribution.validateResults(plan, history);
        if (report.result().trace().size() != 216 || report.result().edges().size() != 72
                || report.result().unfinished().size() != 24 || report.totalCalls() != 221) {
            throw new IllegalStateException("Expected 216 observations, 72 verified edges, 24 pending and 221 calls");
        }

        var allocation = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!allocation.isThreadAllocatedMemorySupported()) {
            throw new IllegalStateException("Thread allocation counters unavailable");
        }
        allocation.setThreadAllocatedMemoryEnabled(true);
        Runnable combine = () -> RelationSearchDistribution.combine(plan, history);
        Runnable validate = () -> RelationSearchDistribution.validateResults(plan, history);
        for (int i = 0; i < 3; i++) {
            run(combine);
            run(validate);
        }
        System.out.printf("java=%s, heap=%d, sources=%d, items=%d, persisted=%d, trace=%d, edges=%d, pending=%d%n",
                System.getProperty("java.runtime.version"), Runtime.getRuntime().maxMemory(), SOURCES,
                plan.items().size(), history.size(), report.result().trace().size(),
                report.result().edges().size(), report.result().unfinished().size());
        System.out.printf("%d warmup calls and %d measured trials of %d calls per path%n",
                3 * ITERATIONS, TRIALS, ITERATIONS);
        var full = new Sample[TRIALS];
        var validation = new Sample[TRIALS];
        for (int i = 0; i < TRIALS; i++) {
            if (i % 2 == 0) {
                full[i] = sample(allocation, combine);
                validation[i] = sample(allocation, validate);
            } else {
                validation[i] = sample(allocation, validate);
                full[i] = sample(allocation, combine);
            }
            System.out.printf(Locale.ROOT, "trial=%d combine=%.1f ns/op %.1f bytes/op validate=%.1f ns/op %.1f bytes/op%n",
                    i + 1, full[i].nanos(), full[i].bytes(), validation[i].nanos(), validation[i].bytes());
        }
        printMedian("combine", full);
        printMedian("validate", validation);
    }

    private static Plan plan() {
        var root = new Node("IP", "IP", "Information products", "Target catalogue root", true);
        var shard = TaxonomyShardRoot.of(root.root());
        var sourceNodes = new ArrayList<Node>();
        var sources = new ArrayList<SourceAssessment>();
        var items = new ArrayList<Item>();
        for (int i = 0; i < SOURCES; i++) {
            var source = new Node("source-" + i, "BP", "Process " + i, "Source catalogue process", false);
            sourceNodes.add(source);
            var contribution = new Contribution(source, "Records and reviews evidence", "Review evidence.", "");
            sources.add(new SourceAssessment(source, List.of(contribution), "", ""));
            for (String type : List.of("CONSUMES", "PRODUCES")) {
                items.add(new Item(items.size(), i, 0, type, DIRECTION, shard, 6));
            }
        }
        var budget = new RequirementRelationSearch.BudgetPolicy(256, 600, 0, true, 128, 600, 128);
        var options = new RequirementRelationSearch.Options(new Limits(600, 8, 10, 600), 128, 16_000, budget);
        return new Plan(2, "representative-run:relations", "a".repeat(64), List.of("source-result"), options,
                sourceNodes, List.of(root), sources, items, 5, 0, List.of(), "", true, null,
                CatalogueDefinition.standard().spec());
    }

    private static List<WorkResult> history(Plan plan) {
        var wave = RelationSearchDistribution.ready(plan, List.of());
        var history = new ArrayList<WorkResult>();
        for (var work : wave.subList(0, PERSISTED).reversed()) {
            var item = plan.items().get(work.ordinal());
            var contribution = plan.sources().get(item.sourceIndex()).contributions().getFirst();
            var sourceId = contribution.source().id();
            var target = new Node("target-" + work.ordinal(), "IP", "Evidence record", "Verified target", false);
            var decision = new Decision(target.id(), Outcome.VERIFIED, contribution.text(), contribution.quote(),
                    Necessity.REQUIRED, "", "", "Confirmed evidence", "");
            var edge = new Edge(contribution, target, item.type(), DIRECTION, decision);
            var trace = List.of(
                    new Trace(sourceId, item.type(), DIRECTION, Phase.NAVIGATE, "IP", Outcome.DESCEND, "Look for evidence"),
                    new Trace(sourceId, item.type(), DIRECTION, Phase.NAVIGATE, target.id(), Outcome.MATCH, "Evidence record"),
                    new Trace(sourceId, item.type(), DIRECTION, Phase.VERIFY, target.id(), Outcome.VERIFIED, "Confirmed evidence"));
            history.add(new WorkResult(work, new Result(List.of(edge), List.of(), trace, 3, 3, 0), 3, ""));
        }
        return List.copyOf(history);
    }

    private static void run(Runnable call) {
        for (int i = 0; i < ITERATIONS; i++) call.run();
    }

    private static Sample sample(ThreadMXBean allocation, Runnable call) {
        long bytes = allocation.getCurrentThreadAllocatedBytes();
        long nanos = System.nanoTime();
        run(call);
        return new Sample((System.nanoTime() - nanos) / (double) ITERATIONS,
                (allocation.getCurrentThreadAllocatedBytes() - bytes) / (double) ITERATIONS);
    }

    private static void printMedian(String name, Sample[] samples) {
        var nanos = Arrays.stream(samples).mapToDouble(Sample::nanos).sorted().toArray();
        var bytes = Arrays.stream(samples).mapToDouble(Sample::bytes).sorted().toArray();
        System.out.printf(Locale.ROOT, "%s median %.1f ns/op %.1f bytes/op%n",
                name, nanos[samples.length / 2], bytes[samples.length / 2]);
    }

    private record Sample(double nanos, double bytes) { }
}
