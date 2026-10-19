package com.taxonomy.analysis.recovery;

import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.dto.RelationSearchModel.Result;
import com.taxonomy.dto.RelationSearchReport;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** The admission ledger survives requests; checkpoint attempts are deliberately not a usage metric. */
class RelationCallLedgerTest {
    @Test void spendingSurvivesStoreRestartAndAnIncreasedAllowanceDoesNotResetIt() {
        try (var db = new Database(0)) {
            assertEquals(0, spent(db));
            debit(db, 2); debit(db, 2);
            assertEquals(2, spent(db));
            var exhausted = assertThrows(AnalysisCheckpointSession.DeferredException.class, () -> debit(db, 2));
            assertEquals("TOTAL_CALL_BUDGET", exhausted.getMessage());
            debit(db, 3);
            assertEquals(3, spent(db));
            assertThrows(AnalysisCheckpointSession.DeferredException.class, () -> debit(db, 0));
            assertEquals(3, spent(db));
        }
    }

    @Test void acceptedReplayAndDeferredQuestionsNeverSpendAnotherRelationCall() {
        try (var db = new Database(0)) {
            var providerCalls = new AtomicInteger();
            for (int restart = 0; restart < 2; restart++) {
                try (var session = new AnalysisCheckpointSession(db.session())) {
                    var detail = AnalysisCheckpointSession.evaluateEvidence("NAVIGATE", "MOCK", List.of("CP"),
                            "one immutable question", () -> {
                                beforeCurrent(2); providerCalls.incrementAndGet(); return accepted();
                            });
                    assertEquals("accepted evidence", detail.getRawResponse());
                    assertThrows(AnalysisCheckpointSession.DeferredException.class, () ->
                            AnalysisCheckpointSession.evaluateEvidence("VERIFY", "MOCK", List.of("IP"),
                                    "deferred question", () -> { throw new AnalysisCheckpointSession.DeferredException("CALL_BUDGET"); }));
                }
            }
            assertEquals(1, providerCalls.get()); assertEquals(1, spent(db));
            int attempts = db.tx(em -> em.createQuery("select sum(q.attempts) from AnalysisQuestionCheckpoint q", Long.class)
                    .getSingleResult().intValue());
            assertEquals(3, attempts, "No-call READY attempts must remain distinct from paid relation work");
        }
    }

    @Test void distributedRedeliveryRetainsItsGrantChargeButANewExecutionReusesItFreely() {
        try (var db = new Database(0)) {
            var admissions = new AtomicInteger(); var providerCalls = new AtomicInteger();
            for (String operation : List.of("original", "original", "continued")) {
                db.tx(em -> { em.find(AnalysisContinuationRun.class, db.id).clusterOperationId = operation; return null; });
                try (var session = new AnalysisCheckpointSession(db.distributedSession(operation))) {
                    var detail = evaluateWithAdmission(() -> {
                        beforeCurrent(2); providerCalls.incrementAndGet(); return accepted();
                    }, admissions::incrementAndGet);
                    assertEquals("accepted evidence", detail.getRawResponse());
                }
            }
            assertEquals(2, admissions.get(), "Same-operation grant replay remains charged; prior-operation evidence is free");
            assertEquals(1, providerCalls.get()); assertEquals(1, spent(db));
        }
    }

    @Test void aDistributedExecutionReplaysLocalLegacyEvidenceWithoutChargingItsNewGrant() {
        try (var db = new Database(0)) {
            var admissions = new AtomicInteger();
            try (var session = new AnalysisCheckpointSession(db.session())) {
                evaluateWithAdmission(RelationCallLedgerTest::accepted, admissions::incrementAndGet);
            }
            db.tx(em -> { em.find(AnalysisContinuationRun.class, db.id).clusterOperationId = "distributed"; return null; });
            try (var session = new AnalysisCheckpointSession(db.distributedSession("distributed"))) {
                evaluateWithAdmission(() -> { throw new AssertionError("Prior accepted answer was repeated"); }, admissions::incrementAndGet);
            }
            assertEquals(1, admissions.get());
        }
    }

    @ParameterizedTest @CsvSource({"RELATION_ANALYSIS:all,RELATION_ANALYSIS:CP", "RELATION_ANALYSIS:CP,RELATION_ANALYSIS:all"})
    void inlineAndTargetScopesReuseTheSameExactAnswerAcrossExecutions(String first, String next) {
        try (var db = new Database(0)) {
            var admissions = new AtomicInteger(); var calls = new AtomicInteger();
            for (int execution = 0; execution < 2; execution++) {
                String operation = "operation-" + execution;
                db.tx(em -> { em.find(AnalysisContinuationRun.class, db.id).clusterOperationId = operation; return null; });
                try (var session = new AnalysisCheckpointSession(db.distributedSession(operation, "task-" + execution,
                        execution == 0 ? first : next))) {
                    evaluateWithAdmission(() -> { beforeCurrent(2); calls.incrementAndGet(); return accepted(); }, admissions::incrementAndGet);
                }
            }
            assertEquals(1, calls.get()); assertEquals(1, admissions.get()); assertEquals(1, spent(db));
            assertEquals(1L, (Long) db.tx(em -> em.createQuery("select count(q) from AnalysisQuestionCheckpoint q", Long.class).getSingleResult()));
        }
    }

    @Test void aDifferentTaskCannotReplayTheOriginalTasksGrantWithinTheSameOperation() {
        try (var db = new Database(0)) {
            db.tx(em -> { em.find(AnalysisContinuationRun.class, db.id).clusterOperationId = "operation"; return null; });
            var admissions = new AtomicInteger();
            for (String task : List.of("original-task", "later-task")) {
                try (var session = new AnalysisCheckpointSession(db.distributedSession("operation", task, "RELATION_ANALYSIS:CP"))) {
                    evaluateWithAdmission(RelationCallLedgerTest::accepted, admissions::incrementAndGet);
                }
            }
            assertEquals(1, admissions.get());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"READY", "FAILED", "ATTEMPT"})
    void inlineStateRemainsAuthoritativeWhenTheNextExecutionUsesATargetWorker(String state) {
        try (var db = new Database(0)) {
            db.tx(em -> { em.find(AnalysisContinuationRun.class, db.id).clusterOperationId = "original"; return null; });
            try (var session = new AnalysisCheckpointSession(db.distributedSession("original", "inline", "RELATION_ANALYSIS:all"))) {
                evaluateWithAdmission(() -> { beforeCurrent(2); return accepted(); }, () -> { });
            }
            db.tx(em -> {
                em.createQuery("update AnalysisQuestionCheckpoint q set q.state=:state").setParameter("state", state).executeUpdate();
                em.find(AnalysisContinuationRun.class, db.id).clusterOperationId = "continued"; return null;
            });
            try (var session = new AnalysisCheckpointSession(db.distributedSession("continued", "target", "RELATION_ANALYSIS:CP"))) {
                if (state.equals("READY")) evaluateWithAdmission(() -> { beforeCurrent(2); return accepted(); }, () -> { });
                else assertThrows(com.taxonomy.analysis.service.AnalysisStoppedException.class,
                        () -> evaluateWithAdmission(RelationCallLedgerTest::accepted, () -> { }));
            }
            assertEquals(1L, (Long) db.tx(em -> em.createQuery("select count(q) from AnalysisQuestionCheckpoint q", Long.class).getSingleResult()));
            assertEquals(state.equals("READY") ? "SUCCESS" : "FAILED",
                    db.tx(em -> em.createQuery("select q.state from AnalysisQuestionCheckpoint q", String.class).getSingleResult()));
            assertEquals(state.equals("READY") ? 2 : 1, (Integer) db.tx(em -> em.find(AnalysisContinuationRun.class, db.id).relationCallsSpent));
        }
    }

    @Test void sourceCohortSurvivesAStoreRestartBeforeAnyReportWasSaved() {
        try (var db = new Database(0)) {
            assertEquals(32, freezeCohort(db, 32));
            db.statements.clear(); db.factory.getStatistics().clear();
            assertEquals(32, freezeCohort(db, 128));
            assertEquals(0, db.factory.getStatistics().getEntityStatistics(AnalysisContinuationRun.class.getName()).getLoadCount());
            assertTrue(db.statements.stream().noneMatch(sql -> sql.startsWith("select")
                    && (sql.contains("result_json") || sql.contains("request_json") || sql.contains("analysis_question_checkpoint"))));
            assertThrows(IllegalArgumentException.class, () -> freezeCohort(db, 0));
            assertThrows(IllegalArgumentException.class, () -> freezeCohort(db, 257));
            db.tx(em -> { em.find(AnalysisContinuationRun.class, db.id).state = "CANCELLED"; return null; });
            assertThrows(ResponseStatusException.class, () -> freezeCohort(db, 128));
        }
    }

    private static int freezeCohort(Database db, int proposed) {
        var method = assertDoesNotThrow(() -> AnalysisContinuationStore.class.getMethod("freezeRelationSourceCohort",
                AnalysisContinuationStore.Claim.class, int.class), "The original prompt cohort must survive a missing first report");
        return db.tx(em -> {
            try { return (Integer) method.invoke(db.store(em), db.claim(), proposed); }
            catch (java.lang.reflect.InvocationTargetException failure) {
                if (failure.getCause() instanceof RuntimeException cause) throw cause;
                throw new AssertionError(failure.getCause());
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        });
    }

    @Test void inlineReuseRejectsAmbiguousTargetAnswersWithoutMergingTheirNamespaces() {
        try (var db = new Database(0)) {
            db.tx(em -> { em.find(AnalysisContinuationRun.class, db.id).clusterOperationId = "operation"; return null; });
            for (String root : List.of("CP", "IP")) {
                try (var session = new AnalysisCheckpointSession(db.distributedSession("operation", root, "RELATION_ANALYSIS:" + root))) {
                    evaluateWithAdmission(() -> { beforeCurrent(3); return accepted(); }, () -> { });
                }
            }
            assertEquals(2, spent(db), "Different target scopes keep independent question authority");
            try (var session = new AnalysisCheckpointSession(db.distributedSession("operation", "inline", "RELATION_ANALYSIS:all"))) {
                var conflict = assertThrows(ResponseStatusException.class, () -> evaluateWithAdmission(
                        RelationCallLedgerTest::accepted, () -> { }));
                assertEquals(409, conflict.getStatusCode().value());
            }
            assertEquals(2, spent(db));
        }
    }

    @ParameterizedTest @CsvSource({"RELATION_ANALYSIS:all,RELATION_ANALYSIS:CP", "RELATION_ANALYSIS:CP,RELATION_ANALYSIS:all"})
    void aCoveredInlineCorrectionCanBeSupersededThroughItsExactTargetScope(String first, String next) {
        try (var db = new Database(0)) {
            db.tx(em -> { em.find(AnalysisContinuationRun.class, db.id).clusterOperationId = "operation"; return null; });
            try (var session = new AnalysisCheckpointSession(db.distributedSession("operation", "old-task", first))) {
                evaluateWithAdmission(() -> { var invalid = accepted(); invalid.setError("invalid quote"); return invalid; }, () -> { });
            }
            try (var session = new AnalysisCheckpointSession(db.distributedSession("operation", "new-task", next))) {
                AnalysisCheckpointSession.supersedeEvidence("NAVIGATE", "MOCK", question -> true);
            }
            assertEquals("SUPERSEDED", db.tx(em -> em.createQuery("select q.state from AnalysisQuestionCheckpoint q", String.class).getSingleResult()));
        }
    }

    @Test void aDeferredGrantAdmissionLeavesThePersistedQuestionReady() {
        try (var db = new Database(0); var session = new AnalysisCheckpointSession(db.session())) {
            var stopped = assertThrows(AnalysisCheckpointSession.DeferredException.class, () ->
                    evaluateWithAdmission(() -> { throw new AssertionError("A denied grant made a provider request"); },
                            () -> { throw new AnalysisCheckpointSession.DeferredException("CALL_BUDGET"); }));
            assertEquals("CALL_BUDGET", stopped.getMessage());
            assertEquals("READY", db.tx(em -> em.createQuery("select q.state from AnalysisQuestionCheckpoint q", String.class).getSingleResult()));
            assertEquals(0, spent(db));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void packedLegacyResultSeedsItsNullableLedgerWithoutLosingEvidence(boolean cumulativeBudget) throws Exception {
        try (var db = new Database(7)) {
            var previous = result(7);
            previous.setReasons(Map.of("CP", "Original evidence — 水位 🌊. ".repeat(6000)));
            if (cumulativeBudget) previous.setRelationSearchReport(previous.getRelationSearchReport().withBudget(
                    new com.taxonomy.dto.RelationSearchBudget(24, 3096, 7, 19, false, 1, 32, 512, 32)));
            String raw = db.json.writeValueAsString(previous);
            assertTrue(raw.length() > 65_536);
            String packed = com.taxonomy.exchange.backup.PortableJsonPayloads.pack(raw, 24_000_000);
            assertNotEquals(raw, packed, "The regression must exercise the actual packed storage frame");
            db.tx(em -> {
                var run = em.find(AnalysisContinuationRun.class, db.id);
                run.resultJson = packed; run.relationCallsSpent = null; return null;
            });
            int expected = cumulativeBudget ? 19 : 7;
            assertEquals(expected, assertDoesNotThrow(() -> spent(db),
                    "A restored packed result must initialize the same durable call ledger as plain JSON"));
            assertEquals(packed, db.tx(em -> em.find(AnalysisContinuationRun.class, db.id).resultJson));
            db.statements.clear(); db.factory.getStatistics().clear();
            assertEquals(expected, spent(db)); debit(db, expected + 1); assertEquals(expected + 1, spent(db));
            assertTrue(db.statements.stream().noneMatch(sql -> sql.startsWith("select") && sql.contains("result_json")),
                    "An initialized ledger must not reread the complete result on subsequent admissions");
        }
    }

    @Test void legacyReportSeedsTheCounterOnceWithoutLoadingLargePayloadsOnEveryCall() {
        try (var db = new Database(7)) {
            assertEquals(7, spent(db));
            db.tx(em -> { em.find(AnalysisContinuationRun.class, db.id).resultJson = db.json.writeValueAsString(result(1)); return null; });
            db.statements.clear(); db.factory.getStatistics().clear();
            assertEquals(7, spent(db)); debit(db, 8); assertEquals(8, spent(db));
            assertEquals(0, db.factory.getStatistics().getEntityStatistics(AnalysisContinuationRun.class.getName()).getLoadCount());
            assertTrue(db.statements.stream().noneMatch(sql -> sql.startsWith("select")
                    && (sql.contains("result_json") || sql.contains("request_json") || sql.contains("analysis_question_checkpoint"))),
                    "An initialized ledger must read only small claimed metadata: " + db.statements);
        }
    }

    @Test void aSavedCumulativeReportTakesPrecedenceOverItsLastExecutionCalls() {
        try (var db = new Database(2)) {
            db.tx(em -> {
                var previous = result(2);
                previous.setRelationSearchReport(previous.getRelationSearchReport().withBudget(
                        new com.taxonomy.dto.RelationSearchBudget(2, 20, 2, 9, true, 1, 32, 512, 32)));
                em.find(AnalysisContinuationRun.class, db.id).resultJson = db.json.writeValueAsString(previous);
                return null;
            });
            assertEquals(9, spent(db), "The old execution's replay/grant count cannot reset cumulative spending");
            debit(db, 10); assertEquals(10, spent(db));
        }
    }

    @Test void completedReportsUseActualNewCallsEvenWhenReplayedGrantsAreLarger() {
        assertTerminalBudget(false);
    }

    @Test void cancellationRetainsActualCumulativeCallsInBothSavedEvidenceViews() {
        assertTerminalBudget(true);
    }

    private void assertTerminalBudget(boolean cancelled) {
        try (var db = new Database(2)) {
            assertEquals(2, spent(db)); debit(db, 3);
            var offered = result(5); offered.setStatus("SUCCESS");
            offered.setRelationSearchReport(offered.getRelationSearchReport().withBudget(
                    new com.taxonomy.dto.RelationSearchBudget(2, 20, 5, 7, true, 3, 32, 512, 32)));
            var architecture = new com.taxonomy.dto.RequirementArchitectureView();
            architecture.setRelationSearchReport(offered.getRelationSearchReport()); offered.setArchitectureView(architecture);
            AnalysisResult completed;
            if (cancelled) {
                db.tx(em -> { em.find(AnalysisContinuationRun.class, db.id).resultJson = db.json.writeValueAsString(offered); return null; });
                completed = db.tx(em -> db.store(em).cancel(db.id,
                        new com.taxonomy.workspace.service.WorkspaceContext("owner", "workspace", "draft", "repository"),
                        offered.getRelationSearchReport()).result());
            } else completed = db.tx(em -> db.store(em).complete(db.claim(), offered, List.of()));
            assertEquals(3, completed.getRelationSearchReport().budget().spentCalls());
            assertEquals(1, completed.getRelationSearchReport().budget().newCalls());
            assertEquals(completed.getRelationSearchReport(), completed.getArchitectureView().getRelationSearchReport());
            var persisted = db.tx(em -> db.json.readValue(em.find(AnalysisContinuationRun.class, db.id).resultJson, AnalysisResult.class));
            assertEquals(completed.getRelationSearchReport().budget(), persisted.getRelationSearchReport().budget());
            assertEquals(cancelled ? "CANCELLED" : "COMPLETED", completed.getRecovery().state());
        }
    }

    @Test void staleCancelledAndExpiredClaimsCannotReadOrSpendTheLedger() {
        for (String state : List.of("SUPERSEDED", "CANCELLED", "EXPIRED")) {
            try (var db = new Database(0)) {
                assertEquals(0, spent(db));
                db.tx(em -> {
                    var run = em.find(AnalysisContinuationRun.class, db.id);
                    if (state.equals("SUPERSEDED")) run.claimToken = UUID.randomUUID().toString();
                    else if (state.equals("EXPIRED")) run.claimUntil = System.currentTimeMillis() - 1;
                    else run.state = state;
                    return null;
                });
                assertThrows(ResponseStatusException.class, () -> debit(db, 1), state);
                assertThrows(ResponseStatusException.class, () -> spent(db), state);
            }
        }
    }

    @Test void concurrentWorkersCannotSpendTheSameLastCredit() throws Exception {
        try (var db = new Database(0); var workers = Executors.newFixedThreadPool(2)) {
            assertEquals(0, spent(db));
            var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
            var accepted = new AtomicInteger(); var rejected = new AtomicInteger();
            var jobs = java.util.stream.IntStream.range(0, 2).mapToObj(ignored -> workers.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                try { debit(db, 1); accepted.incrementAndGet(); }
                catch (AnalysisCheckpointSession.DeferredException exhausted) { rejected.incrementAndGet(); }
                return null;
            })).toList();
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            for (var job : jobs) job.get(20, TimeUnit.SECONDS);
            assertEquals(1, accepted.get()); assertEquals(1, rejected.get()); assertEquals(1, spent(db));
        }
    }

    @Test void scoringQuestionsDoNotConsumeTheWholeRelationJournalCapacity() {
        try (var db = new Database(0)) {
            db.tx(em -> {
                var run = em.getReference(AnalysisContinuationRun.class, db.id);
                for (int i = 0; i < 4096; i++) {
                    var question = new AnalysisQuestionCheckpoint();
                    question.questionKey = "%064x".formatted(i); question.id = db.id + ":" + question.questionKey;
                    question.run = run; question.inputHash = "a".repeat(64); question.provider = "MOCK";
                    question.nodeCodes = "[\"CP\"]"; question.state = "READY"; question.attempts = 1;
                    em.persist(question);
                }
                return null;
            });
            var question = AnalysisCheckpointSession.evidenceQuestion("VERIFY", "MOCK", List.of("IP"), "new relation question");
            var checkpoint = assertDoesNotThrow(() -> db.tx(em -> db.store(em).prepare(db.claim(), question)));
            assertEquals("ATTEMPT", checkpoint.state());
        }
    }

    private static int spent(Database db) { return db.tx(em -> db.store(em).relationCallsSpent(db.claim())); }
    private static void debit(Database db, int maximum) { db.tx(em -> { db.store(em).beforeRelationCall(db.claim(), maximum); return null; }); }
    private static void beforeCurrent(int maximum) { AnalysisCheckpointSession.beforeRelationCall(maximum); }
    private static LlmCallDetail evaluateWithAdmission(java.util.function.Supplier<LlmCallDetail> completion, Runnable admission) {
        return AnalysisCheckpointSession.evaluateEvidence(
                "NAVIGATE", "MOCK", List.of("CP"), "immutable distributed question", completion, admission);
    }

    private static LlmCallDetail accepted() {
        var result = new LlmCallDetail(); result.setProvider("MOCK"); result.setScores(Map.of());
        result.setRawResponse("accepted evidence"); return result;
    }
    static RelationSearchReport report(int calls) {
        return new RelationSearchReport(2, com.taxonomy.analysis.dag.RequirementReference.sha256("requirement"), "policy", List.of(), new Result(List.of(), List.of(), List.of(), 0, 0, 0),
                calls, Math.max(calls, 1), 0, List.of(), "");
    }
    private static AnalysisResult result(int calls) {
        var result = new AnalysisResult(Map.of(), List.of()); result.setRelationSearchReport(report(calls)); return result;
    }

    private static final class Database implements AutoCloseable {
        final String id = UUID.randomUUID().toString(), token = UUID.randomUUID().toString();
        final ObjectMapper json = new ObjectMapper();
        final List<String> statements = new CopyOnWriteArrayList<>();
        final SessionFactory factory = new Configuration().addAnnotatedClass(AnalysisContinuationRun.class)
                .addAnnotatedClass(AnalysisQuestionCheckpoint.class)
                .setProperty("hibernate.connection.driver_class", "org.hsqldb.jdbc.JDBCDriver")
                .setProperty("hibernate.connection.url", "jdbc:hsqldb:mem:relation-ledger-" + id + ";hsqldb.tx=mvcc")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop").setProperty("hibernate.search.enabled", "false")
                .setProperty("hibernate.generate_statistics", "true")
                .setStatementInspector(sql -> { statements.add(sql); return sql; }).buildSessionFactory();
        Database(int previousCalls) {
            tx(em -> {
                var run = new AnalysisContinuationRun(); run.id = id; run.username = "owner"; run.workspaceId = "workspace";
                run.repositoryId = "repository"; run.branchName = "draft"; run.inputHash = "a".repeat(64);
                run.requestJson = json.writeValueAsString(new com.taxonomy.dto.AnalysisRequest("requirement"));
                run.resultJson = json.writeValueAsString(result(previousCalls));
                run.state = "RUNNING"; run.claimToken = token; run.claimUntil = System.currentTimeMillis() + 120_000;
                em.persist(run); return null;
            });
        }
        AnalysisContinuationStore.Claim claim() { return new AnalysisContinuationStore.Claim(id, token, null); }
        AnalysisContinuationStore store(EntityManager em) { return new AnalysisContinuationStore(em, json); }
        AnalysisCheckpointSession.Store session() {
            return new AnalysisCheckpointSession.Store() {
                public AnalysisCheckpointSession.Checkpoint prepare(AnalysisCheckpointSession.Question q) { return tx(em -> store(em).prepare(claim(), q)); }
                public void finish(AnalysisCheckpointSession.Question q, String state, LlmCallDetail detail) {
                    tx(em -> { store(em).finish(claim(), q, state, detail); return null; });
                }
                public void checkActive() { assertEquals("RUNNING", tx(em -> store(em).state(claim()))); }
                public int relationCallsSpent() { return spent(Database.this); }
                public void beforeRelationCall(int maximum) { debit(Database.this, maximum); }
            };
        }
        AnalysisCheckpointSession.Store distributedSession(String operation) {
            return distributedSession(operation, "relation-task", "RELATION_ANALYSIS:CP");
        }
        AnalysisCheckpointSession.Store distributedSession(String operation, String task, String namespace) {
            var owner = new AnalysisContinuationStore.QuestionOwner(operation, task, 1, UUID.randomUUID().toString(), namespace);
            return new AnalysisCheckpointSession.Store() {
                public AnalysisCheckpointSession.Checkpoint prepare(AnalysisCheckpointSession.Question question) {
                    return tx(em -> store(em).prepareDistributed(claim(), question, owner));
                }
                public void finish(AnalysisCheckpointSession.Question question, String state, LlmCallDetail detail) {
                    tx(em -> { store(em).finishDistributed(claim(), question, state, detail, owner); return null; });
                }
                public void checkActive() { assertEquals("RUNNING", tx(em -> store(em).state(claim()))); }
                public int relationCallsSpent() { return spent(Database.this); }
                public void beforeRelationCall(int maximum) { debit(Database.this, maximum); }
                public void supersedeEvidence(String kind, String provider, java.util.function.Predicate<AnalysisCheckpointSession.Question> covered) {
                    tx(em -> { store(em).supersedeEvidence(claim(), kind, provider, covered, owner); return null; });
                }
            };
        }
        <T> T tx(Function<EntityManager, T> action) {
            try (var em = factory.createEntityManager()) {
                em.getTransaction().begin();
                try { T result = action.apply(em); em.getTransaction().commit(); return result; }
                catch (RuntimeException | Error failure) {
                    if (em.getTransaction().isActive()) em.getTransaction().rollback(); throw failure;
                }
            }
        }
        public void close() { factory.close(); }
    }
}
