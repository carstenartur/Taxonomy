package com.taxonomy;

import com.sun.management.ThreadMXBean;
import com.taxonomy.analysis.recovery.AnalysisCheckpointSession;
import com.taxonomy.analysis.recovery.AnalysisContinuationStore;
import com.taxonomy.analysis.relations.RequirementRelationSearch;
import com.taxonomy.relations.service.RelationCompatibilityMatrix;
import com.taxonomy.dto.RelationSearchModel.Decision;
import com.taxonomy.dto.RelationSearchModel.Node;
import com.taxonomy.analysis.service.LlmTransportMeter;
import com.taxonomy.catalog.provenance.CatalogueSourceBytes;
import com.taxonomy.dto.AnalysisModelBinding;
import com.taxonomy.dto.AnalysisRequest;
import com.taxonomy.dto.RelationSearchModel.Phase;
import com.taxonomy.dto.RelationSearchModel.Query;
import com.taxonomy.portfolio.dto.PortfolioDtos.SnapshotSummary;
import com.taxonomy.portfolio.service.PortfolioAnalysisWorkQueue.WorkItem;
import com.taxonomy.workspace.service.WorkspaceContext;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;

import static net.bytebuddy.matcher.ElementMatchers.*;

/** Observes only the named workload boundaries. Loaded with JaCoCo still active in the child JVM. */
public final class AutomaticCatalogueWorkloadProbe implements AutoCloseable {
    private static final String ANALYSIS = "com.taxonomy.analysis.";
    private static final String RELATIONS = ANALYSIS + "relations.";
    private static final String RECOVERY = ANALYSIS + "recovery.";
    private static final String CATALOGUE = "com.taxonomy.catalog.";
    private static final String PORTFOLIO = "com.taxonomy.portfolio.service.";
    private static final ThreadMXBean ALLOCATION = ManagementFactory.getPlatformMXBean(ThreadMXBean.class);
    private static final ThreadLocal<Interval> CURRENT = new ThreadLocal<>();
    private static AutomaticCatalogueWorkloadProbe active;

    private final Map<String, Metrics> phases = new ConcurrentHashMap<>();
    private final Set<String> targets = new LinkedHashSet<>();
    private final Set<String> transformed = ConcurrentHashMap.newKeySet();
    private final Queue<String> errors = new ConcurrentLinkedQueue<>();
    private final Queue<WorkItem> winners = new ConcurrentLinkedQueue<>();
    private final Queue<BeginEvidence> begins = new ConcurrentLinkedQueue<>();
    private final Queue<PersistEvidence> persisted = new ConcurrentLinkedQueue<>();
    private final ResettableClassFileTransformer transformer;
    private volatile Map<String, String> sourceKinds = Map.of();
    private volatile SearchInput searchInput;
    private final Queue<Sibling> siblings = new ConcurrentLinkedQueue<>();

    static AutomaticCatalogueWorkloadProbe install() throws ReflectiveOperationException {
        if (active != null) throw new IllegalStateException("Only one workload probe belongs in a child");
        return active = new AutomaticCatalogueWorkloadProbe();
    }

    private AutomaticCatalogueWorkloadProbe() throws ReflectiveOperationException {
        var setup = new AgentBuilder.Default(new ByteBuddy().ignore(none())).disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .ignore(nameStartsWith("net.bytebuddy.").or(isSynthetic()))
                .with(new AgentBuilder.Listener.Adapter() {
                    @Override public void onTransformation(TypeDescription type, ClassLoader loader, JavaModule module,
                            boolean loaded, DynamicType dynamicType) { transformed.add(type.getName()); }

                    @Override public void onError(String name, ClassLoader loader, JavaModule module, boolean loaded, Throwable error) {
                        errors.add(name + ": " + error);
                        error.printStackTrace(System.err);
                    }
                });
        setup = hook(setup, PORTFOLIO + "ProjectRequirementAnalysisService", method("executePendingItems", 5).and(isPrivate()), Worker.class);
        setup = hook(setup, PORTFOLIO + "PortfolioAnalysisWorkQueue", method("pending", 3), Won.class);
        setup = hook(setup, PORTFOLIO + "PortfolioAnalysisClaimPersistenceService", method("persistSnapshot", 14), Persist.class);
        setup = hook(setup, RECOVERY + "AnalysisContinuationStore", method("begin", 4), Begin.class);
        setup = hook(setup, RELATIONS + "RequirementRelationSearch", method("search", 4).and(isPrivate()), Search.class);
        setup = hook(setup, RELATIONS + "RequirementRelationSearch", method("searchWithSourceContinuation", 4).and(isPrivate()), Count.class);
        setup = hook(setup, RELATIONS + "RequirementRelationSearch$Budget", method("nextSourceCohort", 0), Count.class);
        setup = hook(setup, RELATIONS + "RelationWorkPlan", method("snapshot", 0), Progress.class);
        setup = hook(setup, RELATIONS + "RelationWorkPlan", nameStartsWith("lambda$snapshot$")
                .and(takesArguments(3)).and(takesArgument(1, String.class)), SourceVisit.class);
        setup = hook(setup, RELATIONS + "RelationWorkPlan", nameStartsWith("lambda$snapshot$")
                .and(takesArguments(1)).and(takesArgument(0, named(RELATIONS + "RelationWorkPlan$Work"))),
                TaskVisit.class);
        setup = hook(setup, RELATIONS + "RelationWorkPlan", method("publish", 0), Publish.class);
        setup = hook(setup, RELATIONS + "RelationWorkPlan", method("started", 2), Dispatch.class);
        setup = hook(setup, RELATIONS + "RelationSearchEngine$Run", method("validate", 2), Validated.class);
        setup = hook(setup, RELATIONS + "RelationSearchEngine$Run", method("scheduleFollowups", 0), Schedule.class);
        setup = hook(setup, RELATIONS + "RelationSearchEngine$Run", method("admit", 1), Admitted.class);
        setup = hook(setup, RELATIONS + "RelationSearchEngine$Run", method("descend", 2), Descend.class);
        setup = hook(setup, RELATIONS + "RelationSearchEngine$Work", isConstructor().and(takesArguments(3)), ChildBatch.class);
        setup = hook(setup, RELATIONS + "RelationSearchProtocol", method("evaluateRecovering", 1), Primary.class);
        setup = hook(setup, RELATIONS + "RelationSearchProtocol", method("requestCharacters", 1), PromptSize.class);
        setup = hook(setup, RELATIONS + "RequirementRelationSearch$Budget", method("complete", 3), Numeric.class);
        setup = hook(setup, CATALOGUE + "snapshot.CatalogueSnapshotService", method("captureRevision", 3), Capture.class);
        setup = hook(setup, CATALOGUE + "snapshot.CatalogueSnapshotService", namedOneOf("retainedHierarchy", "bind"), Count.class);
        setup = hook(setup, CATALOGUE + "snapshot.RootCatalogueSnapshot", isConstructor().and(takesArguments(8)), Count.class);
        setup = hook(setup, CATALOGUE + "snapshot.FrozenCatalogueView", isConstructor().and(takesArguments(3)), Count.class);
        setup = hook(setup, CATALOGUE + "service.TaxonomyService", method("readWorkbookNodes", 1)
                .or(method("getFullTree", 0)), Count.class);
        setup = hook(setup, RECOVERY + "AnalysisEvidenceFingerprint", method("digestJson", 4).and(isStatic()), Digest.class);
        setup = hook(setup, CATALOGUE + "provenance.CatalogueSourceJournal", method("retainedInput", 1), Verified.class);
        setup = hook(setup, RECOVERY + "AnalysisContinuationStore", method("prepareQuestion", 4).and(isPrivate()), Prepare.class);
        setup = hook(setup, RECOVERY + "AnalysisContinuationStore", method("question", 2).and(takesArgument(0, String.class)), ExactQuestion.class);
        setup = hook(setup, RECOVERY + "AnalysisContinuationStore", method("compatibleQuestion", 2).or(method("compatibleQuestionKey", 2)), Compatible.class);
        setup = hook(setup, RECOVERY + "AnalysisCheckpointPayloadCodec", namedOneOf("decodePrompt", "decodeDetail"), Binding.class);
        setup = hook(setup, "com.zaxxer.hikari.HikariDataSource", method("getConnection", 0), Jdbc.class);
        // An advice signature can load another target while the agent's transformation lock is held.
        // Preload the exact targets without initializing them, then retransform the complete set.
        for (String target : targets) Class.forName(target, false, getClass().getClassLoader());
        transformer = setup.installOn(ByteBuddyAgent.getInstrumentation());
        if (!transformed.containsAll(targets) || !errors.isEmpty()) {
            var missing = new TreeSet<>(targets);
            missing.removeAll(transformed);
            throw new IllegalStateException("Missing workload transformations: " + missing + "; errors: " + errors);
        }
    }

    private static ElementMatcher.Junction<MethodDescription> method(String name, int arguments) {
        return named(name).and(takesArguments(arguments));
    }

    private AgentBuilder hook(AgentBuilder builder, String type, ElementMatcher<? super MethodDescription> method, Class<?> advice) {
        targets.add(type);
        return builder.type(named(type)).transform((target, description, loader, module, domain) ->
                target.visit(Advice.to(advice).on(method)));
    }

    @Override public void close() {
        try {
            if (!transformer.reset(ByteBuddyAgent.getInstrumentation(), AgentBuilder.RedefinitionStrategy.RETRANSFORMATION))
                throw new IllegalStateException("Could not restore test instrumentation");
        } finally { active = null; }
    }

    static AutomaticCatalogueWorkloadProbe current() { return Objects.requireNonNull(active); }
    static Metrics metrics() { var scope = CURRENT.get(); return scope == null ? null : scope.metrics; }
    static String sourceKind(String hash) { return active.sourceKinds.getOrDefault(hash, "unknown"); }
    void sources(Map<String, String> sourceKinds) { this.sourceKinds = Map.copyOf(sourceKinds); }
    List<WorkItem> winners() { return List.copyOf(winners); }
    List<BeginEvidence> begins() { return List.copyOf(begins); }
    List<PersistEvidence> persisted() { return List.copyOf(persisted); }
    SearchInput searchInput() { return searchInput; }
    List<Sibling> siblings() { return List.copyOf(siblings); }
    RequirementRelationSearch.Options options() { return searchInput.options(); }
    long count(String phase, String key) { return phases.getOrDefault(phase, new Metrics()).get(key); }

    Map<String, Object> report() {
        Map<String, Object> result = new TreeMap<>();
        phases.forEach((name, metrics) -> result.put(name, metrics.snapshot()));
        result.put("transformed", new TreeSet<>(transformed));
        result.put("transformationErrors", List.copyOf(errors));
        result.put("allocatedBytesScope", "Current JVM worker, startup and scoped server request threads");
        result.put("allocationAvailable", ALLOCATION != null && ALLOCATION.isThreadAllocatedMemoryEnabled());
        result.put("durableBeginWitness", begins());
        result.put("snapshotWrites", persisted());
        return result;
    }

    List<String> errors() { return List.copyOf(errors); }

    public static void add(String counter, long count) {
        Metrics metrics = metrics();
        if (metrics != null) metrics.add(counter, count);
    }

    public static long allocation() {
        return ALLOCATION != null && ALLOCATION.isThreadAllocatedMemoryEnabled()
                ? ALLOCATION.getCurrentThreadAllocatedBytes() : -1L;
    }

    public static Interval enter(String phase) {
        var next = new Interval(CURRENT.get(), active.phases.computeIfAbsent(phase, key -> new Metrics()));
        CURRENT.set(next);
        return next;
    }

    public static Interval worker() {
        var next = enter("worker");
        next.transport = LlmTransportMeter.open(next::observeTransport);
        return next;
    }

    public static final class Interval implements AutoCloseable {
        private final Interval previous;
        private Metrics metrics;
        private long start = System.nanoTime();
        private long allocated = allocation();
        private LlmTransportMeter.Scope transport;
        private int cohort, captureCount, capture;
        private Preparation preparation;
        private Query parent;
        private Node descendedFrom;
        private List<Decision> validated;
        private boolean scheduling;
        private final List<Query> admitted = new ArrayList<>();
        private final Map<Query, String> splitParents = new IdentityHashMap<>();
        private final Map<Query, QuestionUse> waiting = new IdentityHashMap<>();
        private QuestionUse primary;

        Interval(Interval previous, Metrics metrics) {
            this.previous = previous;
            this.metrics = metrics;
            metrics.add("intervals", 1);
        }

        private void observeTransport(LlmTransportMeter.Observation value) {
            metrics.add("provider." + value.source().name(), 1);
            if (TransactionSynchronizationManager.isActualTransactionActive())
                metrics.add("provider.inDatabaseTransaction", 1);
            metrics.add("provider." + value.outcome().name(), 1);
            metrics.add("provider.meterElapsedMillis", value.durationMillis());
            if (value.retryIndex() > 0) metrics.add("provider.retries", 1);
            if (value.usage().totalTokens() == null) metrics.add("provider.usageUnavailable", 1);
            else metrics.add("provider.reportedTotalTokens", value.usage().totalTokens());
        }

        private void finishInterval() {
            metrics.add("wallNanos", System.nanoTime() - start);
            long nowAllocated = allocation();
            if (allocated >= 0 && nowAllocated >= allocated)
                metrics.add("allocatedBytes", nowAllocated - allocated);
        }

        private void persisted(PersistEvidence evidence) {
            active.persisted.add(evidence);
            finishInterval();
            metrics = active.phases.computeIfAbsent("workerAfterPersist", unused -> new Metrics());
            metrics.add("intervals", 1);
            start = System.nanoTime();
            allocated = allocation();
        }

        @Override public void close() {
            try {
                finishInterval();
                if (transport != null) transport.close();
            } finally {
                if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
            }
        }
    }

    static final class Metrics {
        private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();
        void add(String key, long amount) { counters.computeIfAbsent(key, unused -> new LongAdder()).add(amount); }
        long get(String key) { var tally = counters.get(key); return tally == null ? 0 : tally.sum(); }
        Map<String, Long> snapshot() {
            Map<String, Long> copy = new TreeMap<>();
            counters.forEach((key, tally) -> copy.put(key, tally.sum()));
            return copy;
        }
    }

    record BeginEvidence(String id, boolean hasToken, boolean replay, String inputHash,
                         WorkspaceContext owner, AnalysisModelBinding model, String authority) { }
    record PersistEvidence(String snapshotId, String jobId, long requirementVersionId, int attempt,
                            String username, WorkspaceContext workspace, String recoveryId) { }

    public static void won(List<WorkItem> items) {
        if (CURRENT.get() == null) return;
        active.winners.addAll(items);
        add("winningItems", items.size());
    }

    public static void began(AnalysisRequest request, WorkspaceContext owner, String hash, AnalysisContinuationStore.Claim claim) {
        if (CURRENT.get() == null || claim == null) return;
        active.begins.add(new BeginEvidence(claim.id(), claim.token() != null && !claim.token().isBlank(),
                claim.completedResult() != null, hash, owner, request.getModelBinding(), request.getContinuationAuthority()));
        add("durableBegins", 1);
    }

    public static void persisted(WorkItem item, String username, WorkspaceContext workspace,
                                 com.taxonomy.dto.AnalysisResult result, SnapshotSummary summary) {
        var scope = CURRENT.get();
        if (scope == null || summary == null) return;
        var evidence = new PersistEvidence(summary.id(), item.jobId(), item.requirementVersionId(), item.attempt(),
                username, workspace, result.getRecovery() == null ? null : result.getRecovery().id());
        if (!TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("Snapshot persistence must be observed through its commit");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) {
                    add("snapshotCommitted", 1);
                    scope.persisted(evidence);
                } else add("snapshotRolledBack", 1);
            }
        });
    }

    record SearchInput(RequirementRelationSearch.Options options, Map<String, ? extends Number> scores,
                       RelationCompatibilityMatrix rules, String provider) { }
    public static void search(RequirementRelationSearch.Options execution, Map<String, ? extends Number> scores,
                              RelationCompatibilityMatrix rules, String provider) {
        var scope = CURRENT.get();
        if (scope == null) return;
        if (active.searchInput == null) active.searchInput = new SearchInput(execution, Map.copyOf(scores), rules, provider);
        scope.cohort++;
        add("privateSearchPasses", 1);
    }

    public static void dispatched(Query query, int depth) {
        if (depth == 0 && query.phase() == Phase.NAVIGATE) add("localDepthZeroDispatch", 1);
    }

    public static void verified(CatalogueSourceBytes bytes) {
        if (metrics() == null || bytes == null) return;
        add("verifiedInputs", 1);
        String name = "verifiedBytes." + sourceKind(bytes.sha256());
        add(name, bytes.length());
        captureMetric(name, bytes.length());
    }

    public static void beginCapture(String method) {
        var scope = CURRENT.get();
        if (scope == null) return;
        add(method, 1);
        scope.capture = ++scope.captureCount;
    }

    public static void endCapture() {
        var scope = CURRENT.get();
        if (scope != null) scope.capture = 0;
    }

    static void captureMetric(String key, long amount) {
        var scope = CURRENT.get();
        if (scope != null && scope.capture > 0) add("capture." + scope.capture + "." + key, amount);
    }

    public static class Capture {
        @Advice.OnMethodEnter static void enter(@Advice.Origin("#t.#m") String method) { beginCapture(method); }
        @Advice.OnMethodExit(onThrowable = Throwable.class) static void exit() { endCapture(); }
    }

    private static final Set<String> RETAINED = Set.of("SUCCESS", "CLARIFICATION", "DECOMPOSED");

    public static void preparing(AnalysisCheckpointSession.Question question) {
        var scope = CURRENT.get();
        if (scope != null) scope.preparation = new Preparation(
                AnalysisCheckpointSession.isRelationQuestion(question.key()), System.nanoTime());
    }

    public static void prepared(AnalysisCheckpointSession.Checkpoint checkpoint, int maximumQuestions) {
        var scope = CURRENT.get();
        if (scope == null) return;
        var question = scope.preparation;
        scope.preparation = null;
        if (question == null) return;
        add("prepareQuestion.nanos", System.nanoTime() - question.started);
        add("prepareQuestion.calls", 1);
        if (scope.primary != null && scope.primary.admission == null) {
            scope.primary.admission = primaryAdmission(question, checkpoint);
            scope.primary.priorQuestionCount = question.capacityCount;
            scope.primary.maximumQuestions = maximumQuestions;
        }
        if (checkpoint == null) add("prepareQuestion.threw", 1);
        else {
            add("prepareQuestion." + checkpoint.state(), 1);
            if (question.relation && scope.cohort > 0 && RETAINED.contains(checkpoint.state())) {
                String prefix = "cohort." + scope.cohort + ".acceptedPrefix.";
                add(prefix + "prepares", 1);
                add(prefix + "payloadBindings", question.bindings);
                add(prefix + "jdbcBinds", question.jdbcBinds);
            }
        }
    }

    private static PrimaryAdmission primaryAdmission(Preparation question, AnalysisCheckpointSession.Checkpoint checkpoint) {
        if (checkpoint == null) return PrimaryAdmission.UNAVAILABLE;
        if (RETAINED.contains(checkpoint.state())) return PrimaryAdmission.REPLAY;
        if (question.compatibilityAbsent && "ATTEMPT".equals(checkpoint.state())) return PrimaryAdmission.FRESH;
        return PrimaryAdmission.OTHER;
    }

    private static final class Preparation {
        private final boolean relation;
        private final long started;
        private int bindings, jdbcBinds, compatibleRows, capacityCount = -1;
        private boolean compatibilityExecuted, compatibilityAbsent;
        Preparation(boolean relation, long started) { this.relation = relation; this.started = started; }
    }

    static boolean preparing() { return CURRENT.get() != null && CURRENT.get().preparation != null; }
    static void jdbcBind() { if (preparing()) CURRENT.get().preparation.jdbcBinds++; }
    static void compatibilityExecuted() { if (preparing()) CURRENT.get().preparation.compatibilityExecuted = true; }
    static void compatibilityRow() { if (preparing()) CURRENT.get().preparation.compatibleRows++; }
    static void capacityCount(int count) { if (preparing()) CURRENT.get().preparation.capacityCount = count; }
    public static void payloadBinding() { if (preparing()) CURRENT.get().preparation.bindings++; }

    public static class Worker {
        @Advice.OnMethodEnter static Interval enter() { return worker(); }
        @Advice.OnMethodExit(onThrowable = Throwable.class) static void exit(@Advice.Enter Interval interval) { interval.close(); }
    }
    public static class Won {
        @Advice.OnMethodExit static void exit(@Advice.Return List<WorkItem> result) { won(result); }
    }
    public static class Begin {
        @Advice.OnMethodExit static void exit(@Advice.Argument(0) AnalysisRequest request,
                @Advice.Argument(1) WorkspaceContext owner, @Advice.Argument(2) String hash,
                @Advice.Return AnalysisContinuationStore.Claim result) { began(request, owner, hash, result); }
    }
    public static class Persist {
        @Advice.OnMethodExit static void exit(@Advice.Argument(0) WorkItem item, @Advice.Argument(11) String username,
                @Advice.Argument(12) WorkspaceContext workspace, @Advice.Argument(3) com.taxonomy.dto.AnalysisResult result,
                @Advice.Return SnapshotSummary summary) { persisted(item, username, workspace, result, summary); }
    }
    public static class Search {
        @Advice.OnMethodEnter static void enter(@Advice.Argument(2) RequirementRelationSearch.Options options,
                @Advice.Argument(1) Map<String, ? extends Number> scores, @Advice.FieldValue("rules") RelationCompatibilityMatrix rules,
                @Advice.FieldValue("provider") String provider) { search(options, scores, rules, provider); }
    }
    public static class Count {
        @Advice.OnMethodEnter static void enter(@Advice.Origin("#t.#m") String method) { add(method, 1); }
    }
    public static class Digest {
        @Advice.OnMethodEnter static void enter(@Advice.Argument(1) String[] prefix) {
            if (prefix.length == 0) add("digest.treeOnly", 1);
            else if (prefix[0].equals("resumable-scoring-v1")) add("digest.requestSignature", 1);
            else add("digest.other", 1);
        }
    }
    public static class Verified {
        @Advice.OnMethodExit static void exit(@Advice.Return CatalogueSourceBytes result) { verified(result); }
    }
    public static class Prepare {
        @Advice.OnMethodEnter static void enter(@Advice.Argument(1) AnalysisCheckpointSession.Question question) { preparing(question); }
        @Advice.OnMethodExit(onThrowable = Throwable.class) static void exit(
                @Advice.Return AnalysisCheckpointSession.Checkpoint result,
                @Advice.FieldValue("MAX_QUESTIONS") int limit) { prepared(result, limit); }
    }

    public static void questionResult(String kind, Object result, Throwable failure) {
        if (!preparing()) return;
        if (failure != null) add(kind + ".threw", 1);
        else add(kind + (result == null ? ".absent" : ".hit"), 1);
    }

    public static void compatibilityResult(String kind, Object result, Throwable failure) {
        if (!preparing()) return;
        var preparation = CURRENT.get().preparation;
        if (result == null && failure == null) {
            if (!preparation.compatibilityExecuted) add(kind + ".unobservedRows", 1);
            else {
                preparation.compatibilityAbsent = preparation.compatibleRows == 0;
                add(kind + (preparation.compatibilityAbsent ? ".absent" : ".scopeMismatch"), 1);
            }
        } else if (failure != null && preparation.compatibleRows >= 2) add(kind + ".ambiguous", 1);
        else questionResult(kind, result, failure);
    }

    public static class ExactQuestion {
        @Advice.OnMethodExit(onThrowable = Throwable.class) static void exit(@Advice.Return Object result,
                @Advice.Thrown Throwable failure) { questionResult("exactKey", result, failure); }
    }
    public static class Compatible {
        @Advice.OnMethodExit(onThrowable = Throwable.class) static void exit(@Advice.Origin("#m") String method,
                @Advice.Return Object result, @Advice.Thrown Throwable failure) {
            compatibilityResult(method, result, failure);
        }
    }
    public static class Binding {
        @Advice.OnMethodExit static void exit() { payloadBinding(); }
    }
    public static class Progress {
        @Advice.OnMethodEnter static long enter() { return System.nanoTime(); }
        @Advice.OnMethodExit static void exit(@Advice.Enter long start,
                @Advice.FieldValue("sourceRoots") Map<?, ?> sourceRoots,
                @Advice.FieldValue("work") Map<?, ?> tasks) {
            add("progress.snapshot", 1);
            add("progress.snapshotNanos", System.nanoTime() - start);
            add("progress.sourcePopulation", sourceRoots.size());
            add("progress.taskPopulation", tasks.size());
        }
    }
    public static class SourceVisit {
        @Advice.OnMethodEnter static void enter() { add("progress.sourceVisits", 1); }
    }
    public static class TaskVisit {
        @Advice.OnMethodEnter static void enter() { add("progress.taskPredicateVisits", 1); }
    }
    public static class Publish {
        @Advice.OnMethodEnter static long enter() { return System.nanoTime(); }
        @Advice.OnMethodExit static void exit(@Advice.Enter long start, @Advice.FieldValue("publish") boolean enabled) {
            if (!enabled) return;
            add("progress.publish", 1);
            add("progress.publishNanos", System.nanoTime() - start);
        }
    }
    public static class Dispatch {
        @Advice.OnMethodEnter static void enter(@Advice.Argument(0) Query query, @Advice.Argument(1) int depth) {
            dispatched(query, depth);
        }
    }
    record Sibling(Query origin, Query first, Query second, QuestionUse one, QuestionUse two, boolean sameSplit) { }
    enum PrimaryAdmission { FRESH, REPLAY, OTHER, UNAVAILABLE }
    static final class QuestionUse {
        PrimaryAdmission admission;
        int rendered, maximumQuestions;
        int priorQuestionCount = -1, callsLeft = -1, workLeft = -1, cumulativeLeft = -1;
    }

    public static void validated(Query parent, List<Decision> decisions) {
        var scope = CURRENT.get();
        if (scope != null) {
            scope.parent = parent;
            scope.validated = decisions;
        }
    }

    public static void childBatch(Query query) {
        var scope = CURRENT.get();
        if (scope != null && scope.descendedFrom != null && query.phase() == Phase.NAVIGATE)
            scope.splitParents.put(query, scope.descendedFrom.id());
    }

    public static void descendFrom(Node node) {
        var scope = CURRENT.get();
        if (scope != null) scope.descendedFrom = node;
    }

    public static void beginSchedule() {
        var scope = CURRENT.get();
        if (scope != null) scope.scheduling = scope.parent != null && scope.parent.phase() == Phase.NAVIGATE;
    }

    public static void admitted(Query query) {
        var scope = CURRENT.get();
        if (scope != null && scope.scheduling) scope.admitted.add(query);
    }

    public static void finishSchedule() {
        var scope = CURRENT.get();
        if (scope == null) return;
        List<Query> ordered = scope.admitted;
        for (int index = 1; index < ordered.size(); index++) {
            Query one = ordered.get(index - 1), two = ordered.get(index);
            if (one.phase() != two.phase()) continue;
            if (one.phase() == Phase.VERIFY && (!scope.validated.contains(one.proposal()) || !scope.validated.contains(two.proposal())))
                continue;
            if (one.phase() == Phase.NAVIGATE && (one.candidates().stream().anyMatch(Node::container)
                    || two.candidates().stream().anyMatch(Node::container))) continue;
            var useOne = scope.waiting.computeIfAbsent(one, unused -> new QuestionUse());
            var useTwo = scope.waiting.computeIfAbsent(two, unused -> new QuestionUse());
            String split = scope.splitParents.get(one);
            active.siblings.add(new Sibling(scope.parent, one, two, useOne, useTwo,
                    split != null && split.equals(scope.splitParents.get(two))));
        }
        ordered.clear();
        scope.splitParents.clear();
        scope.validated = null;
        scope.parent = null;
        scope.scheduling = false;
    }

    public static void primary(Query query) {
        var scope = CURRENT.get();
        if (scope != null) scope.primary = query == null ? null : scope.waiting.remove(query);
    }

    public static void promptSize(int characters) {
        var scope = CURRENT.get();
        if (scope != null && scope.primary != null && scope.primary.rendered == 0)
            scope.primary.rendered = characters;
    }

    public static void numeric(RequirementRelationSearch.Options options, int calls, int work) {
        var scope = CURRENT.get();
        if (scope == null || scope.primary == null || scope.primary.callsLeft >= 0) return;
        scope.primary.callsLeft = options.limits().maxCalls() - calls;
        scope.primary.workLeft = options.limits().maxWorkItems() - work;
        scope.primary.cumulativeLeft = options.budget().maxTotalCalls() - options.budget().callsBeforeExecution() - calls;
    }

    public static class Validated {
        @Advice.OnMethodExit static void exit(@Advice.Argument(0) Query query,
                @Advice.Return List<Decision> reply) { validated(query, reply); }
    }
    public static class Schedule {
        @Advice.OnMethodEnter static void enter() { beginSchedule(); }
        @Advice.OnMethodExit(onThrowable = Throwable.class) static void exit() { finishSchedule(); }
    }
    public static class Admitted {
        @Advice.OnMethodExit static void exit(@Advice.Argument(0) Query query, @Advice.Return boolean accepted) {
            if (accepted) admitted(query);
        }
    }
    public static class Descend {
        @Advice.OnMethodEnter static void enter(@Advice.Argument(1) Node node) { descendFrom(node); }
        @Advice.OnMethodExit(onThrowable = Throwable.class) static void exit() { descendFrom(null); }
    }
    public static class ChildBatch {
        @Advice.OnMethodEnter static void enter(@Advice.Argument(0) Query query) { childBatch(query); }
    }
    public static class Primary {
        @Advice.OnMethodEnter static void enter(@Advice.Argument(0) Query query) { primary(query); }
        @Advice.OnMethodExit(onThrowable = Throwable.class) static void exit() { primary(null); }
    }
    public static class PromptSize {
        @Advice.OnMethodExit static void exit(@Advice.Return int characters) { promptSize(characters); }
    }
    public static class Numeric {
        @Advice.OnMethodEnter static void enter(@Advice.FieldValue("options") RequirementRelationSearch.Options options,
                @Advice.FieldValue("calls") int calls, @Advice.FieldValue("newWork") int work) { numeric(options, calls, work); }
    }

    public static class Jdbc {
        @Advice.OnMethodExit static void exit(@Advice.Return(readOnly = false) Connection connection) {
            connection = AutomaticCatalogueWorkloadJdbc.connection(connection);
        }
    }
}
