package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.dag.json.AnalysisMessageCodec;
import com.taxonomy.analysis.dispatch.AnalysisDispatchService;
import com.taxonomy.analysis.dispatch.AnalysisDispatchStore;
import com.taxonomy.analysis.relations.RelationSearchDistribution;
import com.taxonomy.analysis.relations.RequirementRelationSearch;
import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.workspace.service.WorkspaceContext;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

import static com.taxonomy.dto.RelationSearchModel.*;
import static com.taxonomy.analysis.relations.RelationSearchDistribution.*;

/** Real durable cluster failure writes and the production scheduler; no broker or remote provider. */
public final class PartialClusterFailureChecks {
    private PartialClusterFailureChecks() { }

    public static void main(String[] args) throws Exception {
        int passed = 0;
        var failures = new ArrayList<String>();
        for (var method : Arrays.stream(PartialClusterFailureChecks.class.getDeclaredMethods())
                .filter(m -> m.getName().startsWith("test"))
                .sorted(Comparator.comparing(java.lang.reflect.Method::getName)).toList()) {
            try { method.invoke(null); passed++; System.out.println("PASS " + method.getName()); }
            catch (java.lang.reflect.InvocationTargetException failed) {
                failures.add(method.getName() + ": " + failed.getCause());
                failed.getCause().printStackTrace(System.err);
            }
        }
        System.out.println("Partial cluster failure persistence: " + passed + " passed, " + failures.size() + " failed");
        if (!failures.isEmpty()) throw new AssertionError(String.join("\n", failures));
    }

    public static void testPersistedCancellationCannotAuthorizeAnotherWave() { blocking("CANCELLED"); }
    public static void testPersistedTimeLimitCannotAuthorizeAnotherWave() { blocking("TIME_LIMIT"); }
    public static void testPersistedMemoryPressureCannotAuthorizeAnotherWave() { blocking("MEMORY_PRESSURE"); }
    public static void testPersistedUncertainDecisionCannotAuthorizeAnotherWave() { blocking("AWAITING_DECISION"); }
    public static void testPersistedProviderUnavailableCannotAuthorizeAnotherWave() { blocking("PROVIDER_UNAVAILABLE"); }
    public static void testPersistedUnknownFailureRemainsConservative() { blocking("BROKER_EXPIRED"); }

    public static void testPersistedOrdinaryRelationFailureAllowsIndependentWork() {
        try (var db = new Database()) {
            var failed = db.fail("RELATION_EVALUATION_FAILED");
            var ready = RelationSearchDistribution.ready(db.plan, List.of(failed, success(db.grants.get(1))));
            check(ready.size() == 1 && ready.getFirst().ordinal() == 2, "only independent unadmitted work may proceed");
            check(ready.getFirst().maxCalls() == 2, "failed grant's complete allowance must remain charged");
            check(failed.result().trace().isEmpty() && failed.result().edges().isEmpty(), "failure is not negative evidence");
        }
    }

    public static void testRepeatedFailureCannotOverwriteItsDurableEffect() {
        try (var db = new Database()) {
            var original = db.fail("CANCELLED");
            try { db.fail("RELATION_EVALUATION_FAILED"); throw new AssertionError("second terminal effect accepted"); }
            catch (IllegalStateException expected) { }
            check(db.saved().equals(original), "duplicate failure must leave the committed evidence unchanged");
        }
    }

    private static void blocking(String reason) {
        try (var db = new Database()) {
            var saved = db.fail(reason);
            check(saved.stopReason().equals(reason), "durable work must retain " + reason + ", not " + saved.stopReason());
            check(saved.result().unfinished().getFirst().reason().equals(reason), "unfinished report must keep the real cause");
            check(saved.calls() == Math.min(saved.work().maxCalls(), saved.work().maxWorkItems()),
                    "uncertain provider consumption must retain the complete admitted allowance");
            check(RelationSearchDistribution.ready(db.plan, List.of(saved)).isEmpty(),
                    "a stop must not authorize a missing same-wave grant");
            var completeWave = List.of(saved, success(db.grants.get(1)));
            check(RelationSearchDistribution.ready(db.plan, completeWave).isEmpty(),
                    "a stop must not authorize another wave when an in-flight sibling finishes");
            var report = RelationSearchDistribution.combine(db.plan, completeWave);
            check(report.stopReason().equals(reason), "aggregate stop must retain " + reason);
            check(report.result().trace().size() == 1 && report.result().edges().isEmpty(),
                    "only the successful sibling contributes a decision; no failure-derived edges");
        }
    }

    private static WorkResult success(Work grant) {
        var trace = new Trace("BP-1", "CONSUMES", Direction.OUTGOING, Phase.NAVIGATE,
                "IP", Outcome.REJECT, "not required");
        return new WorkResult(grant, new Result(List.of(), List.of(), List.of(trace), 1, 1, 0), 1, "");
    }

    private static Plan plan() {
        var node = new Node("BP-1", "BP", "Process", "", false);
        var contribution = new Contribution(node, "Record status", "Record status", "");
        var root = new Node("IP", "IP", "Information", "", true);
        var items = java.util.stream.IntStream.range(0, 3).mapToObj(i ->
                new Item(i, 0, 0, "CONSUMES", Direction.OUTGOING, TaxonomyShardRoot.of("IP"), 2)).toList();
        return new Plan(1, "preparation", RequirementReference.sha256("Record status"), List.of("source-result"),
                new RequirementRelationSearch.Options(new Limits(6, 8, 10, 512), 32), List.of(node), List.of(root),
                List.of(new SourceAssessment(node, List.of(contribution), "required", "")), items, 1, 0, List.of(), "", true);
    }

    private static final class Database implements AutoCloseable {
        final ObjectMapper json = new ObjectMapper();
        final Plan plan = plan();
        final List<Work> grants = RelationSearchDistribution.ready(plan, List.of());
        final SessionFactory factory = new Configuration().addAnnotatedClass(ClusterAnalysisRun.class)
                .addAnnotatedClass(ClusterAnalysisWork.class)
                .setProperty("hibernate.connection.driver_class", "org.hsqldb.jdbc.JDBCDriver")
                .setProperty("hibernate.connection.url", "jdbc:hsqldb:mem:" + UUID.randomUUID())
                .setProperty("hibernate.connection.username", "sa").setProperty("hibernate.connection.password", "")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop").setProperty("hibernate.show_sql", "false")
                .setProperty("hibernate.search.enabled", "false").buildSessionFactory();
        final EntityManager em = SharedEntityManagerCreator.createSharedEntityManager(factory);
        final JpaTransactionManager transactions = new JpaTransactionManager(factory);
        final TransactionTemplate tx = new TransactionTemplate(transactions);
        final ClusterAnalysisStore store = new ClusterAnalysisStore(em, transactions, json,
                new AnalysisDispatchService(new AnalysisDispatchStore(em, transactions),
                        task -> { throw new AssertionError("failure persistence must not directly publish tasks"); }, 1, 1));
        final RelationAnalysisTask task;

        Database() {
            String id = "partial-" + UUID.randomUUID();
            var context = new AnalysisOperationContext(id, new AnalysisSourceAuthority("repo-a", "work-a", "draft", "source-a"),
                    RequirementReference.adHoc("Record status"), id);
            var root = TaxonomyShardRoot.of("IP");
            var taskId = AnalysisTaskId.relationWork(id, root, 0);
            task = new RelationAnalysisTask(new AnalysisEnvelope(2, AnalysisMessageType.RELATION_ANALYSIS_TASK, id,
                    taskId, AnalysisTaskType.RELATION_ANALYSIS, context.authority(), context.requirement(),
                    List.of(root), 1, null, id, Instant.now(), null), List.of(root), List.of());
            var command = new AnalyzeRequirementCommand("Record status", true, 50, null, "tester",
                    new WorkspaceContext("tester", "work-a", "draft", "repo-a"));
            tx.executeWithoutResult(status -> {
                var run = new ClusterAnalysisRun();
                run.id = id; run.username = "tester"; run.scopeKey = ClusterAnalysisStore.scopeKey(command.workspaceContext());
                run.contextJson = json.writeValueAsString(context); run.commandJson = json.writeValueAsString(command);
                run.state = ClusterAnalysisState.RELATIONS; run.relationPlanJson = json.writeValueAsString(plan);
                run.totalRoots = 1; run.completedRoots = 1; run.createdAt = System.currentTimeMillis(); run.updatedAt = run.createdAt;
                em.persist(run);
                var work = new ClusterAnalysisWork(); work.id = RequirementReference.sha256(taskId.value());
                work.operationId = id; work.taskId = taskId.value(); work.taskType = AnalysisTaskType.RELATION_ANALYSIS.name();
                work.root = "IP"; work.ordinal = 2;
                work.messageJson = new String(new AnalysisMessageCodec().encode(task), StandardCharsets.UTF_8);
                work.inputJson = json.writeValueAsString(grants.getFirst()); work.state = "RUNNING"; work.attempts = 1;
                em.persist(work);
            });
        }

        WorkResult fail(String reason) {
            tx.executeWithoutResult(status -> store.persistRelationFailure(task, reason));
            return saved();
        }
        WorkResult saved() {
            return tx.execute(status -> json.readValue(em.find(ClusterAnalysisWork.class,
                    RequirementReference.sha256(task.taskId().value())).resultJson, WorkResult.class));
        }
        @Override public void close() { em.close(); factory.close(); }
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
