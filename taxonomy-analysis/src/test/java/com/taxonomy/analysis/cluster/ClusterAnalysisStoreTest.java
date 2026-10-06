package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.dispatch.*;
import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.dto.*;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class ClusterAnalysisStoreTest {
    private static final TaxonomyShardRoot CP = TaxonomyShardRoot.of("CP"), IP = TaxonomyShardRoot.of("IP");

    @Test void acceptsOnlyExactInputsAndDispatchesAfterCommit() {
        try (var db = new Database()) {
            var context = context("admission");
            db.admit(context);
            assertEquals(2, db.sent.size());
            assertEquals(2, db.store.snapshot(context).totalRoots());
            assertEquals(0, db.store.snapshot(context).completedRoots());
            assertEquals("QUEUED", db.store.snapshot(context).state().name());
            assertEquals("{\"root\":\"CP\"}", db.store.shard(context, CP));
            assertThrows(IllegalStateException.class, () -> db.store.shard(context, TaxonomyShardRoot.of("UA")));
            var foreign = new AnalysisOperationContext(context.operationId(),
                    new AnalysisSourceAuthority("other", "workspace", "draft", "source"), context.requirement(), context.correlationId());
            assertThrows(IllegalStateException.class, () -> db.store.snapshot(foreign));
            assertThrows(IllegalStateException.class, () -> db.store.admit(context, command("changed"), null,
                    Map.of(CP, "{}", IP, "{}")));
        }
    }

    @Test void rejectsEveryAdmissionIdentityMismatchWithoutDurableOrPublishedEffects() {
        try (var db = new Database()) {
            var context = context("rejected-admission");
            var workspace = command("requirement").workspaceContext();
            var progress = new ArrayList<AnalysisProgressEvent>();
            db.store.eventPublisher(progress::add);
            record InvalidInput(String name, String text, String username, WorkspaceContext workspace) { }
            var invalidInputs = List.of(
                    new InvalidInput("missing text", null, "alice", workspace),
                    new InvalidInput("changed text", "changed", "alice", workspace),
                    new InvalidInput("missing owner", "requirement", null, workspace),
                    new InvalidInput("blank owner", "requirement", " ", workspace),
                    new InvalidInput("different owner", "requirement", "bob", workspace),
                    new InvalidInput("different workspace owner", "requirement", "alice",
                            new WorkspaceContext("bob", "workspace", "draft", "repo")),
                    new InvalidInput("different repository", "requirement", "alice",
                            new WorkspaceContext("alice", "workspace", "draft", "other")),
                    new InvalidInput("different workspace", "requirement", "alice",
                            new WorkspaceContext("alice", "other", "draft", "repo")),
                    new InvalidInput("central scope substitution", "requirement", "alice",
                            new WorkspaceContext("alice", null, "draft", "repo")),
                    new InvalidInput("different branch", "requirement", "alice",
                            new WorkspaceContext("alice", "workspace", "other", "repo")),
                    new InvalidInput("missing branch", "requirement", "alice",
                            new WorkspaceContext("alice", "workspace", null, "repo")));
            for (var input : invalidInputs) {
                var command = new AnalyzeRequirementCommand(input.text(), false, 20, "MOCK", input.username(),
                        input.workspace(), null, new AnalysisScope(Set.of("CP", "IP"), AnalysisMode.TAXONOMIES_ONLY));
                assertThrows(IllegalStateException.class,
                        () -> db.store.admit(context, command, null, Map.of(CP, "{}", IP, "{}")), input.name());
            }
            assertTrue(db.sent.isEmpty());
            assertTrue(progress.isEmpty());
            for (String entity : List.of("ClusterAnalysisRun", "ClusterAnalysisWork", "ClusterAnalysisInput",
                    "ClusterAnalysisEvent", "AnalysisDispatchIntent")) {
                assertEquals(0L, db.em.createQuery("select count(e) from " + entity + " e", Long.class).getSingleResult(),
                        "Rejected admission must not write " + entity);
            }
        }
    }

    @Test void exactAdmissionReplayPreservesEvidenceAndRejectsChangedSourceIdentity() {
        try (var db = new Database()) {
            var context = context("replayed-admission");
            var progress = new ArrayList<AnalysisProgressEvent>();
            db.store.eventPublisher(progress::add);
            db.admit(context);
            var before = db.store.snapshot(context);
            var events = db.store.events(context, 0, 100);

            db.admit(context);
            var changedSource = new AnalysisOperationContext(context.operationId(),
                    new AnalysisSourceAuthority("repo", "workspace", "draft", "other-source"),
                    context.requirement(), context.correlationId());
            assertThrows(IllegalStateException.class, () -> db.admit(changedSource));

            assertEquals(before, db.store.snapshot(context));
            assertEquals(events, db.store.events(context, 0, 100));
            assertEquals(2, db.sent.size());
            assertEquals(1, progress.size());
        }
    }

    @Test void reverseAndDuplicateCompletionsAggregateExactlyOnce() {
        try (var db = new Database()) {
            var context = context("reverse"); db.admit(context);
            var cp = db.task(CP); var ip = db.task(IP);
            assertEquals("requirement", db.store.start(cp).command().businessText());
            db.store.start(ip);
            assertEquals(2, db.store.snapshot(context).runningTasks());
            var cpCompletion = db.complete(cp, result("CP", 50));
            var ipCompletion = db.complete(ip, result("IP", 70));
            assertEquals(0, db.store.snapshot(context).completedRoots(), "Only the coordinator settles progress");
            assertTrue(db.store.accept(ipCompletion));
            long revision = db.store.snapshot(context).revision();
            assertFalse(db.store.accept(ipCompletion));
            assertEquals(revision, db.store.snapshot(context).revision());
            assertEquals(1, db.store.snapshot(context).completedRoots());
            assertTrue(db.store.accept(cpCompletion));
            var snapshot = db.store.snapshot(context);
            assertEquals(ClusterAnalysisState.COMPLETED, snapshot.state());
            assertEquals(Map.of("CP", 50, "IP", 70), snapshot.result().getRawScores());
            assertEquals(List.of("CP", "IP"), snapshot.result().getTree().stream().map(TaxonomyNodeDto::getCode).toList());
            assertEquals("SUCCESS", snapshot.result().getStatus());
            assertEquals(snapshot.revision(), db.store.events(context, 0, 100).getLast().sequence());
        }
    }

    @Test void cancellationWinsOverLateProviderResultAndSurvivesNewStore() {
        try (var db = new Database()) {
            var context = context("cancel"); db.admit(context);
            var task = db.task(CP); db.store.start(task);
            assertTrue(db.store.cancel(context));
            long revision = db.store.snapshot(context).revision();
            assertFalse(db.store.cancel(context));
            var completion = db.complete(task, result("CP", 99));
            assertFalse(db.store.accept(completion));
            assertEquals(ClusterAnalysisState.CANCELLED, db.store.snapshot(context).state());
            assertEquals(revision, db.store.snapshot(context).revision());
            assertTrue(db.store.snapshot(context).result().getRawScores().isEmpty());
            assertFalse(db.store.start(db.task(IP)).executable());
            var restarted = new ClusterAnalysisStore(db.em, db.transactions, new ObjectMapper(), db.dispatch);
            assertEquals(ClusterAnalysisState.CANCELLED, restarted.snapshot(context).state());
        }
    }

    @Test void unknownOrForeignOrUnpersistedCompletionNeverCounts() {
        try (var db = new Database()) {
            var context = context("forged"); db.admit(context);
            var task = db.task(CP);
            var messages = new AnalysisMessageFactory(context, Clock.systemUTC());
            var completion = messages.completed(task, AnalysisTaskOutcome.COMPLETED, 50, 1, null);
            assertThrows(IllegalStateException.class, () -> db.store.accept(completion));
            assertEquals(0, db.store.snapshot(context).completedRoots());
            var foreign = new AnalysisOperationContext(context.operationId(),
                    new AnalysisSourceAuthority("repo", "workspace", "another-branch", "source"), context.requirement(), context.correlationId());
            var other = (SubtaxonomyAnalysisTask) new AnalysisMessageFactory(foreign, Clock.systemUTC())
                    .task(AnalysisTaskGraph.plan(context.operationId(), List.of(CP), false).tasks().getFirst());
            assertThrows(IllegalStateException.class, () -> db.store.start(other));
        }
    }

    @Test void missingAndFailedRootsRemainExplicitRatherThanZeros() {
        try (var db = new Database()) {
            var context = context("partial"); db.admit(context);
            var partial = result("CP", 80); partial.setStatus("PARTIAL"); partial.setErrorMessage("provider unavailable");
            db.store.accept(db.complete(db.task(CP), partial));
            assertFalse(db.store.snapshot(context).state().terminal());
            var failed = new AnalysisResult(Map.of(), List.of()); failed.setStatus("ERROR");
            failed.setErrorMessage("evaluation failed");
            db.store.accept(db.complete(db.task(IP), failed));
            var result = db.store.snapshot(context).result();
            assertEquals(ClusterAnalysisState.PARTIAL, db.store.snapshot(context).state());
            assertEquals(Map.of("CP", 80), result.getRawScores());
            assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("IP")));
            assertFalse(result.getRawScores().containsKey("IP"));
        }
    }

    @Test void aggregationPreservesIndependentAssessmentAndDescendantCoverageAcrossRoots() {
        try (var db = new Database()) {
            var context = context("coverage-aggregation"); db.admit(context);
            var cp = result("CP", 80);
            var assessed = new AnalysisCoverage.NodeAssessment(AnalysisCoverage.State.RELEVANT, 80, 80,
                    AnalysisCoverage.Descendants.PARTIAL, null);
            var unresolved = new AnalysisCoverage.NodeAssessment(AnalysisCoverage.State.UNKNOWN, null, null,
                    AnalysisCoverage.Descendants.UNASSESSED, "INTERRUPTED:TIMEOUT");
            cp.setAnalysisCoverage(new AnalysisCoverage(Map.of("CP", assessed, "fixture-child", unresolved), 1, 1, 1));
            cp.setStatus("PARTIAL");
            cp.setErrorMessage("TIMEOUT");
            var ip = result("IP", 0);
            var excluded = new AnalysisCoverage.NodeAssessment(AnalysisCoverage.State.NOT_RELEVANT, 0, 0,
                    AnalysisCoverage.Descendants.COMPLETE, null);
            ip.setAnalysisCoverage(new AnalysisCoverage(Map.of("IP", excluded), 1, 0, 0));

            db.store.accept(db.complete(db.task(IP), ip));
            db.store.accept(db.complete(db.task(CP), cp));

            var coverage = db.store.snapshot(context).result().getAnalysisCoverage();
            assertNotNull(coverage, "Cluster aggregation must retain the workers' assessment evidence");
            assertEquals(Map.of("CP", assessed, "fixture-child", unresolved, "IP", excluded), coverage.nodes());
            assertEquals(2, coverage.assessedNodes());
            assertEquals(1, coverage.unknownNodes());
            assertEquals(1, coverage.failedOrBlockedNodes());
            assertTrue(coverage.hasOpenEvaluations());
            var restarted = new ClusterAnalysisStore(db.em, db.transactions, new ObjectMapper(), db.dispatch);
            assertEquals(coverage, restarted.snapshot(context).result().getAnalysisCoverage());
        }
    }

    @Test void cancellationRetainsCoverageOfCommittedRootEvidence() {
        try (var db = new Database()) {
            var context = context("coverage-cancellation"); db.admitFrozen(context);
            var cp = result("CP", 75);
            var coverage = AnalysisCoverage.derive(cp.getTree(), cp.getRawScores(), cp.getScores(), Map.of());
            cp.setAnalysisCoverage(coverage);
            db.store.accept(db.complete(db.task(CP), cp));
            db.store.cancel(context);

            var combined = db.store.snapshot(context).result().getAnalysisCoverage();
            assertEquals(coverage.nodes().get("CP"), combined.nodes().get("CP"));
            assertNotNull(combined.nodes().get("IP"), "The unexecuted admitted root must remain visible");
            assertNotNull(combined.nodes().get("fixture-ip-child"), "Frozen descendants must remain visible");
            assertEquals(AnalysisCoverage.State.UNKNOWN, combined.nodes().get("IP").state());
            assertEquals(AnalysisCoverage.State.UNKNOWN, combined.nodes().get("fixture-ip-child").state());
            assertNull(combined.nodes().get("IP").score());
            assertTrue(combined.hasOpenEvaluations());
            assertEquals(2, combined.unknownNodes());
            assertFalse(db.store.snapshot(context).result().getRawScores().containsKey("IP"));
        }
    }

    @Test void failedRootWithoutEvidenceRemainsOpenBesideCompleteCoverage() {
        try (var db = new Database()) {
            var context = context("coverage-failure"); db.admitFrozen(context);
            var cp = result("CP", 75);
            cp.setAnalysisCoverage(AnalysisCoverage.derive(cp.getTree(), cp.getRawScores(), cp.getScores(), Map.of()));
            db.store.accept(db.complete(db.task(CP), cp));
            var failed = new AnalysisResult(Map.of(), List.of()); failed.setStatus("ERROR");
            db.store.accept(db.complete(db.task(IP), failed));
            var combined = db.store.snapshot(context).result();
            assertEquals("PARTIAL", combined.getStatus());
            assertTrue(combined.getAnalysisCoverage().hasOpenEvaluations());
            assertEquals(AnalysisCoverage.State.UNKNOWN, combined.getAnalysisCoverage().nodes().get("IP").state());
            assertFalse(combined.getRawScores().containsKey("IP"));
            assertExportable(combined);
        }
    }

    @Test void mixedLegacyAndCurrentResultsRemainExportableWithoutInventingCoverage() {
        try (var db = new Database()) {
            var context = context("coverage-mixed"); db.admit(context);
            var cp = result("CP", 75);
            var nodes = new LinkedHashMap<>(AnalysisCoverage.derive(cp.getTree(), cp.getRawScores(), cp.getScores(), Map.of()).nodes());
            nodes.put("fixture-open", new AnalysisCoverage.NodeAssessment(AnalysisCoverage.State.UNKNOWN, null, null,
                    AnalysisCoverage.Descendants.UNASSESSED, "LEFT_OPEN:fixture"));
            cp.setAnalysisCoverage(new AnalysisCoverage(nodes, 1, 1, 1));
            cp.setStatus("PARTIAL");
            db.store.accept(db.complete(db.task(CP), cp));
            db.store.accept(db.complete(db.task(IP), result("IP", 30)));
            var combined = db.store.snapshot(context).result();
            assertExportable(combined);
            assertNull(combined.getAnalysisCoverage(), "Legacy evidence cannot be promoted to complete version 3 coverage");
            assertEquals("PARTIAL", combined.getStatus());
            assertEquals(Map.of("CP", 75, "IP", 30), combined.getRawScores());
        }
    }

    @Test void allLegacyResultsRetainAbsentCoverage() {
        try (var db = new Database()) {
            var context = context("coverage-legacy"); db.admit(context);
            db.store.accept(db.complete(db.task(CP), result("CP", 75)));
            db.store.accept(db.complete(db.task(IP), result("IP", 30)));
            var combined = db.store.snapshot(context).result();
            assertNull(combined.getAnalysisCoverage());
            assertExportable(combined);
        }
    }

    @Test void duplicateUnknownIdentitiesAcrossRootCoverageAreRejected() {
        try (var db = new Database()) {
            var context = context("coverage-duplicate"); db.admit(context);
            var unknown = new AnalysisCoverage.NodeAssessment(AnalysisCoverage.State.UNKNOWN, null, null,
                    AnalysisCoverage.Descendants.UNASSESSED, "LEFT_OPEN:fixture");
            var cp = result("CP", 75); var ip = result("IP", 30);
            for (var result : List.of(cp, ip)) {
                var nodes = new LinkedHashMap<>(AnalysisCoverage.derive(result.getTree(), result.getRawScores(), result.getScores(), Map.of()).nodes());
                nodes.put("fixture-duplicate", unknown);
                result.setAnalysisCoverage(new AnalysisCoverage(nodes, 1, 1, 1));
            }
            db.store.accept(db.complete(db.task(CP), cp));
            var completion = db.complete(db.task(IP), ip);
            assertThrows(IllegalStateException.class, () -> db.store.accept(completion));
            assertFalse(db.store.snapshot(context).state().terminal());
        }
    }

    private static void assertExportable(AnalysisResult result) {
        var saved = new SavedAnalysis();
        saved.setVersion(result.getAnalysisCoverage() == null ? 2 : 3);
        saved.setScores(result.getScores()); saved.setRawScores(result.getRawScores());
        saved.setAnalysisCoverage(result.getAnalysisCoverage()); saved.setAnalysisStatus(result.getStatus());
        assertDoesNotThrow(saved::validateCoverageEvidence);
    }

    @Test void concurrentDeliveriesAndResultRollbackPreserveOneDurableEffect() throws Exception {
        try (var db = new Database(); var pool = Executors.newFixedThreadPool(2)) {
            var context = context("duplicate"); db.admit(context);
            var task = db.task(CP); db.store.start(task);
            var completion = new AnalysisMessageFactory(context, Clock.systemUTC())
                    .completed(task, AnalysisTaskOutcome.COMPLETED, 40, 1, null);
            var result = result("CP", 40);
            assertThrows(IllegalStateException.class, () -> db.completions.commit(new PreparedAnalysisCompletion<>(completion, () -> {
                db.store.persistResult(task, result); throw new IllegalStateException("crash");
            })));
            assertThrows(IllegalStateException.class, () -> db.store.accept(completion));
            var start = new CountDownLatch(1);
            var prepared = new PreparedAnalysisCompletion<>(completion, () -> db.store.persistResult(task, result));
            Callable<AnalysisCompletionMessage> call = () -> { start.await(); return db.completions.commit(prepared); };
            var first = pool.submit(call); var second = pool.submit(call); start.countDown();
            assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertTrue(db.store.accept(completion)); assertFalse(db.store.accept(completion));
            assertEquals(1, db.store.snapshot(context).completedRoots());
        }
    }

    static AnalysisOperationContext context(String id) {
        return new AnalysisOperationContext(id, new AnalysisSourceAuthority("repo", "workspace", "draft", "source"),
                RequirementReference.adHoc("requirement"), id);
    }
    static AnalyzeRequirementCommand command(String text) {
        return new AnalyzeRequirementCommand(text, false, 20, "MOCK", "alice",
                new WorkspaceContext("alice", "workspace", "draft", "repo"), null,
                new AnalysisScope(Set.of("CP", "IP"), AnalysisMode.TAXONOMIES_ONLY));
    }
    static AnalysisResult result(String code, int score) {
        var node = new TaxonomyNodeDto(); node.setCode(code); node.setTaxonomyRoot(code);
        var result = new AnalysisResult(Map.of(code, score), List.of(node));
        result.setStatus("SUCCESS"); result.setProvider("MOCK"); return result;
    }

    static final class Database implements AutoCloseable {
        final LocalContainerEntityManagerFactoryBean bean = new LocalContainerEntityManagerFactoryBean();
        final jakarta.persistence.EntityManager em;
        final JpaTransactionManager transactions;
        final List<AnalysisTaskMessage> sent = new CopyOnWriteArrayList<>();
        final AnalysisDispatchService dispatch;
        final ClusterAnalysisStore store;
        final JpaAnalysisTaskCompletionStore completions;
        Database() {
            this(sql -> sql);
        }
        Database(org.hibernate.resource.jdbc.spi.StatementInspector inspector) {
            bean.setDataSource(new DriverManagerDataSource("jdbc:hsqldb:mem:cluster-" + UUID.randomUUID() + ";hsqldb.tx=mvcc", "sa", ""));
            bean.setManagedTypes(PersistenceManagedTypes.of(ClusterAnalysisRun.class.getName(), ClusterAnalysisWork.class.getName(),
                    ClusterAnalysisInput.class.getName(), ClusterAnalysisEvent.class.getName(),
                    AnalysisDispatchIntent.class.getName(), AnalysisTaskCompletionRecord.class.getName()));
            bean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            bean.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop", "hibernate.search.enabled", "false",
                    "hibernate.session_factory.statement_inspector", inspector));
            bean.afterPropertiesSet();
            em = SharedEntityManagerCreator.createSharedEntityManager(bean.getObject());
            transactions = new JpaTransactionManager(bean.getObject());
            dispatch = new AnalysisDispatchService(new AnalysisDispatchStore(em, transactions), task -> {
                var committed = new TransactionTemplate(transactions);
                committed.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                assertNotNull(committed.execute(status -> em.find(ClusterAnalysisRun.class, task.envelope().operationId())),
                        "Publication must observe committed operation authority");
                sent.add(task);
            }, 20, 100);
            store = new ClusterAnalysisStore(em, transactions, new ObjectMapper(), dispatch);
            completions = new JpaAnalysisTaskCompletionStore(em, transactions);
        }
        void admit(AnalysisOperationContext context) {
            store.admit(context, command("requirement"), null, Map.of(CP, "{\"root\":\"CP\"}", IP, "{\"root\":\"IP\"}"));
        }
        void admitFrozen(AnalysisOperationContext context) {
            var source = DurableClusterAnalysisExecutionTest.source(context);
            var cp = DurableClusterAnalysisExecutionTest.root(source, "CP");
            var ip = DurableClusterAnalysisExecutionTest.root(source, "IP");
            var child = new com.taxonomy.catalog.model.TaxonomyNode();
            child.setCode("fixture-ip-child"); child.setTaxonomyRoot("IP"); child.setParentCode("IP");
            child.setLevel(1); child.setNameEn("Unexecuted fixture child");
            var nodes = new ArrayList<>(ip.nodes());
            nodes.add(com.taxonomy.catalog.snapshot.RootCatalogueSnapshot.Node.capture(child, ip.nodes().getFirst().metadata(), false));
            ip = new com.taxonomy.catalog.snapshot.RootCatalogueSnapshot(ip.schemaVersion(), source, "IP", nodes,
                    ip.overlayMetadata(), ip.catalogueProvenance());
            var mapper = new ObjectMapper();
            store.admit(context, command("requirement"), null,
                    Map.of(CP, mapper.writeValueAsString(cp), IP, mapper.writeValueAsString(ip)));
        }
        SubtaxonomyAnalysisTask task(TaxonomyShardRoot root) {
            return sent.stream().filter(t -> t.routingRoot().equals(root)).map(SubtaxonomyAnalysisTask.class::cast).findFirst().orElseThrow();
        }
        AnalysisCompletionMessage complete(SubtaxonomyAnalysisTask task, AnalysisResult result) {
            var e = task.envelope();
            var factory = new AnalysisMessageFactory(new AnalysisOperationContext(e.operationId(), e.authority(), e.requirement(), e.correlationId()), Clock.systemUTC());
            var outcome = "SUCCESS".equals(result.getStatus()) ? AnalysisTaskOutcome.COMPLETED : AnalysisTaskOutcome.PARTIAL;
            var completion = factory.completed(task, outcome, result.getRawScores().get(task.root().code()), result.getRawScores().size(), null);
            return completions.commit(new PreparedAnalysisCompletion<>(completion, () -> store.persistResult(task, result)));
        }
        @Override public void close() { bean.destroy(); }
    }
}
