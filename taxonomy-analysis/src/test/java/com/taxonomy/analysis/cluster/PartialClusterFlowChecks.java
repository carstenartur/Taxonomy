package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.dag.json.AnalysisMessageCodec;
import com.taxonomy.analysis.dispatch.AnalysisDispatchService;
import com.taxonomy.analysis.dispatch.AnalysisDispatchStore;
import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.dto.*;
import com.taxonomy.workspace.service.WorkspaceContext;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;

/** Real completion/transaction boundary for requests without an optional continuation journal. */
public final class PartialClusterFlowChecks {
    private PartialClusterFlowChecks() { }

    public static void main(String[] args) throws Exception {
        int passed = 0;
        var failures = new ArrayList<String>();
        for (var method : Arrays.stream(PartialClusterFlowChecks.class.getDeclaredMethods())
                .filter(m -> m.getName().startsWith("test"))
                .sorted(Comparator.comparing(java.lang.reflect.Method::getName)).toList()) {
            try { method.invoke(null); passed++; System.out.println("PASS " + method.getName()); }
            catch (java.lang.reflect.InvocationTargetException failed) {
                failures.add(method.getName() + ": " + failed.getCause());
                failed.getCause().printStackTrace(System.err);
            }
        }
        System.out.println("Partial cluster completion: " + passed + " passed, " + failures.size() + " failed");
        if (!failures.isEmpty()) throw new AssertionError(String.join("\n", failures));
    }

    public static void testTimeStopKeepsAnArchitectureWithoutAContinuation() { stopped("TIME_LIMIT"); }
    public static void testMemoryStopKeepsAnArchitectureWithoutAContinuation() { stopped("MEMORY_PRESSURE"); }
    public static void testUncertainCallKeepsEvidenceButDoesNotAdmitWork() { stopped("AWAITING_DECISION"); }

    private static void stopped(String reason) {
        try (var db = new Database(true)) {
            db.persist("PARTIAL");
            check(db.store.accept(db.messages.completed(db.task, AnalysisTaskOutcome.STOPPED, 100, 1, reason)), "first completion rejected");
            var snapshot = db.store.snapshot(db.context);
            check(snapshot.state() == ClusterAnalysisState.PARTIAL, "stop must remain terminal partial");
            check(snapshot.result().getErrorMessage().startsWith(reason + ":"), "actual stop reason lost");
            architecture(snapshot.result());
            check(db.published.isEmpty(), "stopped operation admitted more work");
        }
    }

    public static void testExplicitCancellationRetainsAcceptedScoresAndView() {
        try (var db = new Database(true)) {
            db.persist("SUCCESS");
            check(db.store.cancel(db.context), "cancellation was not accepted");
            var snapshot = db.store.snapshot(db.context);
            check(snapshot.state() == ClusterAnalysisState.CANCELLED, "cancellation disappeared");
            architecture(snapshot.result());
            check(db.published.isEmpty(), "cancelled operation admitted work");
        }
    }

    public static void testUnrequestedViewRemainsAbsent() {
        try (var db = new Database(false)) {
            db.persist("PARTIAL");
            db.store.accept(db.messages.completed(db.task, AnalysisTaskOutcome.STOPPED, 100, 1, "TIME_LIMIT"));
            check(db.store.snapshot(db.context).result().getArchitectureView() == null, "unrequested view created");
        }
    }

    public static void testDuplicateCompletionCannotChangeItsDurableResult() {
        try (var db = new Database(true)) {
            db.persist("PARTIAL");
            var completion = db.messages.completed(db.task, AnalysisTaskOutcome.STOPPED, 100, 1, "TIME_LIMIT");
            check(db.store.accept(completion), "first completion rejected");
            var first = db.store.snapshot(db.context);
            check(!db.store.accept(completion), "duplicate completion accepted");
            var second = db.store.snapshot(db.context);
            check(first.revision() == second.revision(), "duplicate changed event revision");
            check(db.json.writeValueAsString(first.result()).equals(db.json.writeValueAsString(second.result())), "duplicate changed saved result");
            check(db.published.isEmpty(), "duplicate dispatched tasks");
        }
    }

    public static void testFinalizationWithoutDetailedViewStillPublishesAnHonestPartialArchitecture() {
        try (var db = new Database(true)) {
            db.persist("SUCCESS");
            db.store.accept(db.messages.completed(db.task, AnalysisTaskOutcome.COMPLETED, 100, 1, null));
            var snapshot = db.store.snapshot(db.context);
            check(snapshot.state() == ClusterAnalysisState.FINALIZING, "normal presentation stage was bypassed");
            var result = snapshot.result(); result.setStatus("PARTIAL");
            result.setErrorMessage("ARCHITECTURE_VIEW_INCOMPLETE: presentation unavailable");
            db.store.finalizeResult(db.context, result);
            var complete = db.store.snapshot(db.context);
            check(complete.state() == ClusterAnalysisState.PARTIAL, "failed presentation claimed completeness");
            architecture(complete.result());
        }
    }

    private static void architecture(AnalysisResult result) {
        check(result.getRawScores().equals(Map.of("BP", 100d, "BP-1", 70d)), "accepted scores changed");
        var view = result.getArchitectureView();
        check(view != null, "accepted evidence has no architecture without continuation journal");
        check(view.getIncludedElements().size() == 1 && view.getIncludedElements().getFirst().getNodeCode().equals("BP-1"), "missing or invented concrete elements");
        check(view.getIncludedRelationships().isEmpty(), "missing relation work became invented edges");
        check(view.getAnalysisStatus().equals("PARTIAL"), "view claims a complete analysis");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static final class Database implements AutoCloseable {
        final ObjectMapper json = new ObjectMapper();
        final SessionFactory factory = new Configuration().addAnnotatedClass(ClusterAnalysisRun.class)
                .addAnnotatedClass(ClusterAnalysisWork.class).addAnnotatedClass(ClusterAnalysisEvent.class)
                .setProperty("hibernate.connection.driver_class", "org.hsqldb.jdbc.JDBCDriver")
                .setProperty("hibernate.connection.url", "jdbc:hsqldb:mem:" + UUID.randomUUID())
                .setProperty("hibernate.connection.username", "sa").setProperty("hibernate.connection.password", "")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop").setProperty("hibernate.show_sql", "false")
                .setProperty("hibernate.search.enabled", "false").buildSessionFactory();
        final EntityManager em = SharedEntityManagerCreator.createSharedEntityManager(factory);
        final JpaTransactionManager transactions = new JpaTransactionManager(factory);
        final TransactionTemplate tx = new TransactionTemplate(transactions);
        final List<AnalysisTaskMessage> published = new ArrayList<>();
        final ClusterAnalysisStore store = new ClusterAnalysisStore(em, transactions, json,
                new AnalysisDispatchService(new AnalysisDispatchStore(em, transactions), published::add, 1, 1));
        final AnalysisOperationContext context;
        final AnalysisMessageFactory messages;
        final SubtaxonomyAnalysisTask task;

        Database(boolean view) {
            String id = "partial-flow-" + UUID.randomUUID();
            context = new AnalysisOperationContext(id, new AnalysisSourceAuthority("repo-a", "work-a", "draft", "source-a"),
                    RequirementReference.adHoc("Record status"), id);
            messages = new AnalysisMessageFactory(context, Clock.systemUTC());
            task = (SubtaxonomyAnalysisTask) messages.task(AnalysisTaskGraph.plan(id, List.of(TaxonomyShardRoot.of("BP")), false).tasks().getFirst());
            var command = new AnalyzeRequirementCommand("Record status", view, 50, null, "tester",
                    new WorkspaceContext("tester", "work-a", "draft", "repo-a"), null,
                    new AnalysisScope(Set.of("BP"), AnalysisMode.TAXONOMIES_ONLY));
            tx.executeWithoutResult(status -> {
                var run = new ClusterAnalysisRun();
                run.id = id; run.username = "tester"; run.scopeKey = ClusterAnalysisStore.scopeKey(command.workspaceContext());
                run.contextJson = json.writeValueAsString(context); run.commandJson = json.writeValueAsString(command);
                run.state = ClusterAnalysisState.RUNNING; run.totalRoots = 1;
                run.createdAt = System.currentTimeMillis(); run.updatedAt = run.createdAt; em.persist(run);
                var work = new ClusterAnalysisWork(); work.id = RequirementReference.sha256(task.taskId().value());
                work.operationId = id; work.taskId = task.taskId().value(); work.taskType = task.taskType().name(); work.root = "BP";
                work.messageJson = new String(new AnalysisMessageCodec().encode(task), StandardCharsets.UTF_8); work.state = "QUEUED";
                em.persist(work);
            });
            check(store.start(task).executable(), "fixture task was not admitted");
        }
        void persist(String status) {
            var root = new TaxonomyNodeDto(); root.setCode("BP"); root.setTaxonomyRoot("BP"); root.setNameEn("Processes");
            var child = new TaxonomyNodeDto(); child.setCode("BP-1"); child.setTaxonomyRoot("BP"); child.setParentCode("BP");
            child.setLevel(1); child.setNameEn("Status records"); root.setChildren(new ArrayList<>(List.of(child)));
            // Legacy accepted evidence is a supported aggregation input; it must not acquire invented coverage.
            var result = new AnalysisResult(Map.of("BP", 100d, "BP-1", 70d), List.of(root)); result.setStatus(status);
            tx.executeWithoutResult(ignored -> store.persistResult(task, result));
        }
        public void close() { factory.close(); }
    }
}
