package com.taxonomy.analysis.recovery;

import com.taxonomy.analysis.service.AnalysisStoppedException;
import com.taxonomy.dto.*;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** Executable checks using the real checkpoint adapter and real Hibernate/HSQL transactions. */
public final class PartialAnalysisResilienceChecks {
    private PartialAnalysisResilienceChecks() { }
    public static void main(String[] args) throws Exception {
        int passed = 0; List<String> failed = new ArrayList<>();
        for (var method : Arrays.stream(PartialAnalysisResilienceChecks.class.getDeclaredMethods())
                .filter(m -> m.getName().startsWith("test")).sorted(Comparator.comparing(java.lang.reflect.Method::getName)).toList()) {
            try { method.invoke(null); passed++; System.out.println("PASS " + method.getName()); }
            catch (java.lang.reflect.InvocationTargetException error) {
                failed.add(method.getName() + ": " + error.getCause());
                error.getCause().printStackTrace(System.err);
            }
        }
        System.out.println("Partial analysis: " + passed + " passed, " + failed.size() + " failed");
        if (!failed.isEmpty()) throw new AssertionError(String.join("\n", failed));
    }

    public static void testInvalidNodeAnswerDoesNotPauseOrPublishFallbackScores() {
        var journal = new Journal(); var calls = new AtomicInteger();
        try (var session = new AnalysisCheckpointSession(journal)) {
            var failure = AnalysisCheckpointSession.evaluate("CATEGORY", "TEST", List.of("BP-1"), "first", () -> {
                calls.incrementAndGet(); return answer(Map.of("BP-1", 0), "Invalid JSON", "broken");
            });
            check(failure.getScores().isEmpty(), "an invalid answer cannot publish fallback zeroes");
            var success = AnalysisCheckpointSession.evaluate("CATEGORY", "TEST", List.of("BP-2"), "second", () -> {
                calls.incrementAndGet(); return answer(Map.of("BP-2", 75), null, "accepted");
            });
            check(success.getScores().equals(Map.of("BP-2", 75)), "independent work must continue");
            check(journal.entries.values().stream().anyMatch(q -> q.state().equals("UNASSESSED")), "failure must remain inspectable and retryable");
            AnalysisCheckpointSession.evaluate("CATEGORY", "TEST", List.of("BP-1"), "first", () -> { throw new AssertionError("implicit retry"); });
            AnalysisCheckpointSession.evaluate("CATEGORY", "TEST", List.of("BP-2"), "second", () -> { throw new AssertionError("repeated success"); });
            check(calls.get() == 2, "exactly one physical request per admitted question");
        }
    }

    public static void testInvalidRelationAnswerDoesNotPauseIndependentRelations() {
        var journal = new Journal();
        try (var session = new AnalysisCheckpointSession(journal)) {
            var failed = AnalysisCheckpointSession.evaluateEvidence("NAVIGATE", "TEST", List.of("IP-1"), "relation-one",
                    () -> answer(Map.of(), "INVALID_RELATION_RESPONSE: bad JSON", "bad"));
            check(failed.getError().startsWith("INVALID_RELATION_RESPONSE"), "retain the actual failure");
            var valid = AnalysisCheckpointSession.evaluateEvidence("NAVIGATE", "TEST", List.of("IP-2"), "relation-two",
                    () -> answer(Map.of(), null, "valid raw"));
            check(valid.getRawResponse().equals("valid raw"), "next relation completes");
            var replay = AnalysisCheckpointSession.evaluateEvidence("NAVIGATE", "TEST", List.of("IP-1"), "relation-one",
                    () -> { throw new AssertionError("unselected relation must not be retried"); });
            check(replay.getRawResponse().equals("bad"), "failed raw evidence remains unchanged");
        }
    }

    public static void testClarificationPreservesItsValidatedRawAnswerForReplay() {
        var journal = new Journal();
        try (var session = new AnalysisCheckpointSession(journal)) {
            var detail = AnalysisCheckpointSession.evaluateEvidence("VERIFY", "TEST", List.of("IP-1"), "clarify",
                    () -> answer(Map.of(), "RELATION_UNRESOLVED: Is read access sufficient?", "mixed validated decisions"));
            check(detail.getRawResponse().equals("mixed validated decisions"), "valid sibling decisions must remain available");
            check(journal.entries.values().iterator().next().state().equals("CLARIFICATION"), "clarification is not a parser failure");
            var replay = AnalysisCheckpointSession.evaluateEvidence("VERIFY", "TEST", List.of("IP-1"), "clarify",
                    () -> { throw new AssertionError("clarifications must not automatically call the provider again"); });
            check(replay.getRawResponse().equals(detail.getRawResponse()), "exact validated replay");
        }
    }

    public static void testUncertainAttemptStillRequiresAnExplicitDecision() {
        var journal = new Journal(); journal.forceState = "FAILED";
        try (var session = new AnalysisCheckpointSession(journal)) {
            try {
                AnalysisCheckpointSession.evaluate("CATEGORY", "TEST", List.of("BP-1"), "uncertain",
                        () -> { throw new AssertionError("uncertain attempt was sent again"); });
                throw new AssertionError("uncertain attempt must pause");
            } catch (AnalysisStoppedException expected) {
                check(expected.reason() == AnalysisStoppedException.Reason.AWAITING_DECISION, "keep uncertainty separate from invalid answers");
            }
        }
    }

    public static void testCancellationAndStoragePauseNeverReleaseMoreWork() {
        for (var reason : AnalysisStoppedException.Reason.values()) {
            var journal = new Journal();
            try (var session = new AnalysisCheckpointSession(journal)) {
                try {
                    AnalysisCheckpointSession.evaluateEvidence("VERIFY", "TEST", List.of("IP-1"), "stop",
                            () -> { throw new AnalysisStoppedException(reason); });
                    throw new AssertionError("cooperative stop swallowed");
                } catch (AnalysisStoppedException expected) { check(expected.reason() == reason, "stop type preserved"); }
            }
        }
        var journal = new Journal(); journal.rejectStorage = true;
        try (var session = new AnalysisCheckpointSession(journal)) {
            try {
                AnalysisCheckpointSession.evaluate("CATEGORY", "TEST", List.of("BP-1"), "storage",
                        () -> answer(Map.of(), "invalid", "broken"));
                throw new AssertionError("unpersisted result must not be released");
            } catch (AnalysisStoppedException expected) { check(expected.reason() == AnalysisStoppedException.Reason.AWAITING_DECISION, "storage pause"); }
        }
    }

    public static void testRealJournalCompletesWithGapsAndKeepsOnlyAcceptedScores() {
        try (var db = new Database()) {
            var request = request(); var claim = db.begin(request);
            try (var session = db.session(claim)) {
                AnalysisCheckpointSession.evaluate("CATEGORY", "TEST", List.of("BP-1"), "bad",
                        () -> answer(Map.of("BP-1", 0), "Invalid JSON", "original bad reply"));
                AnalysisCheckpointSession.evaluate("CATEGORY", "TEST", List.of("BP-2"), "good",
                        () -> answer(Map.of("BP-2", 70), null, "original good reply"));
            }
            var result = new AnalysisResult(Map.of("BP-2", 70), tree()); result.setStatus("PARTIAL");
            var completed = db.call(s -> s.complete(claim, result, tree()));
            check(completed.getRecovery().state().equals("COMPLETED_WITH_GAPS"), "finished incomplete analysis is not PAUSED");
            check(completed.getRecovery().openQuestions().size() == 1, "open failure must survive completion");
            check(!completed.getRawScores().containsKey("BP-1"), "unassessed cannot become zero");
            String key = completed.getRecovery().openQuestions().getFirst().key();
            check(db.call(s -> s.detail(request.getContinuationId(), key, scope())).getRawResponse().equals("original bad reply"), "raw response retained in database");
            request.setContinuationAction("RETRY"); request.setContinuationVersion(completed.getRecovery().version()); request.setContinuationQuestion(key);
            var resumed = db.begin(request);
            var calls = new AtomicInteger();
            try (var session = db.session(resumed)) {
                AnalysisCheckpointSession.evaluate("CATEGORY", "TEST", List.of("BP-1"), "bad", () -> {
                    calls.incrementAndGet(); return answer(Map.of("BP-1", 60), null, "fixed");
                });
                AnalysisCheckpointSession.evaluate("CATEGORY", "TEST", List.of("BP-2"), "good", () -> { throw new AssertionError("repeated successful request"); });
            }
            check(calls.get() == 1, "explicit retry repeats only selected failed question");
        }
    }

    public static void testRealJournalUnknownPhysicalOutcomeStillPauses() {
        try (var db = new Database()) {
            var request = request(); var claim = db.begin(request);
            db.call(s -> s.prepare(claim, new AnalysisCheckpointSession.Question("a".repeat(64), "b".repeat(64), "TEST", List.of("BP-1"), "prompt")));
            var result = new AnalysisResult(Map.of(), tree()); result.setStatus("PARTIAL");
            var completed = db.call(s -> s.complete(claim, result, tree()));
            check(completed.getRecovery().state().equals("PAUSED"), "unknown physical outcome is not silently retried");
            check(completed.getRecovery().openQuestions().getFirst().outcomeUncertain(), "explain uncertain cost");
        }
    }

    public static void testRealDistributedJournalContinuesAfterInvalidRelationAnswer() {
        try (var db = new Database()) {
            var request = request(); var claim = db.begin(request);
            String operation = "distributed-" + UUID.randomUUID();
            db.entity(em -> {
                var row = em.find(AnalysisContinuationRun.class, claim.id());
                row.clusterOperationId = operation; row.claimUntil = Long.MAX_VALUE; return null;
            });
            var owner = new AnalysisContinuationStore.QuestionOwner(operation, "relation-task", 1, UUID.randomUUID().toString(), "RELATION_ANALYSIS:IP");
            try (var session = db.distributedSession(claim, owner)) {
                AnalysisCheckpointSession.evaluateEvidence("NAVIGATE", "TEST", List.of("IP-1"), "bad", () -> answer(Map.of(), "INVALID_RELATION_RESPONSE", "bad"));
                AnalysisCheckpointSession.evaluateEvidence("NAVIGATE", "TEST", List.of("IP-2"), "good", () -> answer(Map.of(), null, "good"));
            }
            check(db.call(s -> s.distributedState(claim, operation)).equals("RUNNING"), "one failure must not cancel sibling workers");
            var result = new AnalysisResult(Map.of(), tree()); result.setStatus("PARTIAL");
            var completed = db.call(s -> s.complete(claim, result, tree()));
            check(completed.getRecovery().state().equals("COMPLETED_WITH_GAPS"), "distributed partial completion");
        }
    }

    public static void testRealBulkRetryIsAtomicAndReusesOtherAnswers() {
        try (var db = new Database()) {
            var request = request(); var claim = db.begin(request);
            try (var session = db.session(claim)) {
                for (String code : List.of("BP-1", "BP-2")) AnalysisCheckpointSession.evaluate("CATEGORY", "TEST", List.of(code), code,
                        () -> answer(Map.of(), "invalid JSON", "original " + code));
                AnalysisCheckpointSession.evaluateEvidence("VERIFY", "TEST", List.of("IP-1"), "accepted relation",
                        () -> answer(Map.of(), null, "accepted evidence"));
            }
            var result = new AnalysisResult(Map.of(), tree()); result.setStatus("PARTIAL");
            var complete = db.call(store -> store.complete(claim, result, tree()));
            var keys = complete.getRecovery().openQuestions().stream().map(AnalysisRecoveryView.OpenQuestion::key).toList();
            request.setContinuationAction("RETRY"); request.setContinuationVersion(complete.getRecovery().version() - 1);
            request.setContinuationQuestions(keys);
            expectConflict(() -> db.begin(request));
            request.setContinuationVersion(complete.getRecovery().version());
            request.setContinuationQuestions(List.of(keys.getFirst(), "f".repeat(64)));
            expectConflict(() -> db.begin(request));
            var before = db.call(store -> store.read(request.getContinuationId(), scope()));
            check(before.recovery().version() == complete.getRecovery().version(), "rejected batch cannot change the revision");
            check(before.recovery().openQuestions().size() == 2, "validate all choices before mutating any checkpoint");
            request.setContinuationQuestions(keys); var retry = db.begin(request); var calls = new AtomicInteger();
            try (var session = db.session(retry)) {
                for (String code : List.of("BP-1", "BP-2")) AnalysisCheckpointSession.evaluate("CATEGORY", "TEST", List.of(code), code,
                        () -> { calls.incrementAndGet(); return answer(Map.of(code, 50), null, "fixed " + code); });
                AnalysisCheckpointSession.evaluateEvidence("VERIFY", "TEST", List.of("IP-1"), "accepted relation",
                        () -> { throw new AssertionError("a successful relation was reissued"); });
            }
            check(calls.get() == 2, "only the explicitly selected requests are reissued");
            var done = new AnalysisResult(Map.of("BP-1", 50, "BP-2", 50), tree()); done.setStatus("SUCCESS");
            var completed = db.call(store -> store.complete(retry, done, tree()));
            check(completed.getRecovery().state().equals("COMPLETED") && completed.getArchitectureView().getIncludedElements().size() == 2,
                    "selected rework completes and regenerates the architecture");
            expectConflict(() -> db.call(store -> store.complete(claim, result, tree())));
        }
    }

    public static void testCancellationProjectsEarlierCommittedScoresAndFencesLateDelivery() {
        try (var db = new Database()) {
            var request = request(); var claim = db.begin(request);
            try (var session = db.session(claim)) {
                AnalysisCheckpointSession.evaluate("CATEGORY", "TEST", List.of("BP-1"), "accepted",
                        () -> answer(Map.of("BP-1", 70), null, "accepted"));
            }
            var cancelled = db.call(store -> store.cancel(request.getContinuationId(), scope()));
            check(cancelled.recovery().state().equals("CANCELLED"), "cancellation remains authoritative");
            check(cancelled.result().getArchitectureView().getIncludedElements().size() == 1, "cancellation retains a useful accepted view");
            var late = new AnalysisResult(Map.of("BP-1", 1, "BP-2", 99), tree()); late.setStatus("SUCCESS");
            var settled = db.call(store -> store.complete(claim, late, tree()));
            check(settled.getRawScores().equals(Map.of("BP-1", 70)), "late result cannot replace the cancelled snapshot");
        }
    }

    public static void testActualProtocolRetainsVerifiedSiblingOfAnUnresolvedDecision() {
        var journal = new Journal(); var calls = new AtomicInteger(); var json = new ObjectMapper();
        var source = new RelationSearchModel.Node("BP-1", "BP", "Process", "", false);
        var contribution = new RelationSearchModel.Contribution(source, "Record status", "Record status", "");
        var first = new RelationSearchModel.Node("IP-1", "IP", "Status", "", false);
        var second = new RelationSearchModel.Node("IP-2", "IP", "History", "", false);
        var intent = new RelationSearchModel.Intent(contribution, "CONSUMES", RelationSearchModel.Direction.OUTGOING, List.of(first, second));
        var protocol = new com.taxonomy.analysis.relations.RelationSearchProtocol((step, ids, prompt) -> {
            calls.incrementAndGet();
            var outcome = step == RelationSearchProgress.Step.VERIFY
                    ? RelationSearchModel.Outcome.VERIFIED : RelationSearchModel.Outcome.MATCH;
            var decisions = new ArrayList<RelationSearchModel.Decision>();
            decisions.add(new RelationSearchModel.Decision("IP-1", outcome, "Status record", "Record status",
                    RelationSearchModel.Necessity.REQUIRED, "", "", "Needed to read status", ""));
            if (ids.size() > 1) decisions.add(new RelationSearchModel.Decision("IP-2", RelationSearchModel.Outcome.UNRESOLVED,
                    "", "", null, "", "", "Retention unspecified", "Which retention period applies?"));
            return json.writeValueAsString(Map.of("decisions", decisions));
        }, "TEST");
        var engine = new com.taxonomy.analysis.relations.RelationSearchEngine(node -> List.of(), protocol::evaluate, AnalysisCheckpointSession::checkpoint);
        try (var session = new AnalysisCheckpointSession(journal)) {
            var result = engine.search("Record status", List.of(intent), new RelationSearchModel.Limits(10, 8, 10, 64));
            check(result.edges().size() == 1 && result.unfinished().size() == 1, "valid sibling must reach verification while the actual question stays open");
            check(result.unfinished().getFirst().question().equals("Which retention period applies?"), "actual question is retained");
            var replay = engine.search("Record status", List.of(intent), new RelationSearchModel.Limits(10, 8, 10, 64));
            check(replay.edges().equals(result.edges()) && calls.get() == 2, "replay preserves valid edge without model calls");
        }
    }

    public static void testPersistenceAndAuthorizationFailuresAreNeverSoftModelErrors() {
        for (RuntimeException failure : List.of(new org.springframework.dao.DataAccessResourceFailureException("offline"),
                new jakarta.persistence.PersistenceException("failed commit"), new SecurityException("revoked"),
                new org.springframework.security.access.AccessDeniedException("permission revoked"),
                new org.springframework.security.authentication.BadCredentialsException("session revoked"),
                new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "superseded"))) {
            var journal = new Journal();
            try (var session = new AnalysisCheckpointSession(journal)) {
                try {
                    AnalysisCheckpointSession.evaluate("CATEGORY", "TEST", List.of("BP-1"), "authority",
                            () -> { throw failure; });
                    throw new AssertionError("execution failure swallowed");
                } catch (RuntimeException actual) { check(actual == failure, "retain original execution failure"); }
            }
            check(journal.entries.isEmpty(), "failed execution is not persisted as an accepted model outcome");
        }
    }

    public static void testRelationRuntimeStopRemainsContinuableEvenWithoutAnOpenQuestion() {
        try (var db = new Database()) {
            var request = request(); var claim = db.begin(request);
            var result = new AnalysisResult(Map.of("BP-1",70),tree()); result.setStatus("PARTIAL");
            result.setErrorMessage("RELATION_SEARCH_PARTIAL: interrupted search");
            result.setRelationSearchReport(com.taxonomy.analysis.relations.RequirementRelationSearch.unassessed(
                    "a requirement",24,"TIME_LIMIT: no further calls admitted"));
            var completed = db.call(store -> store.complete(claim,result,tree()));
            check(completed.getRecovery().state().equals("STOPPED"), "a runtime stop in the relation report remains continuable");
            check(completed.getArchitectureView().getIncludedElements().size()==1, "stopping still produces the accepted partial view");
            request.setContinuationAction("CONTINUE"); request.setContinuationVersion(completed.getRecovery().version());
            var continued = db.begin(request);
            check(db.call(store -> store.state(continued)).equals("RUNNING"), "continuation needs no invented failed question");
        }
    }
    public static void testRestoredOpenAssessmentsStayInertAndNeverPublishFailedScores() {
        try (var db = new Database()) {
            var sourceId = new com.taxonomy.backup.SourceRecordId("analysis.continuation","source-run");
            var target = new WorkspaceContext("restored-owner","restored-work","staged","restored-repo");
            String id = UUID.randomUUID().toString();
            var request = request(); request.setContinuationAction("RETRY"); request.setContinuationQuestions(List.of("a".repeat(64)));
            var result = new AnalysisResult(Map.of(),tree()); result.setStatus("PARTIAL");
            var source = new com.taxonomy.analysis.backup.AnalysisBackupContributor.ContinuationRecord(sourceId,
                    "source-owner","source-repo","source-work","draft","f".repeat(64),db.json.writeValueAsString(request),
                    db.json.writeValueAsString(result),"COMPLETED_WITH_GAPS",
                    com.taxonomy.analysis.backup.AnalysisBackupContributor.ResumePolicy.REVIEW_REQUIRED,1,null);
            var rows = new ArrayList<com.taxonomy.analysis.backup.AnalysisBackupContributor.QuestionRecord>();
            for (String state : List.of("UNASSESSED","CLARIFICATION")) {
                String key = state.equals("UNASSESSED") ? "a".repeat(64) : "R"+"b".repeat(63);
                var detail = answer(state.equals("UNASSESSED") ? Map.of("BP-1",0) : Map.of(),
                        state.equals("UNASSESSED") ? "Invalid JSON" : "RELATION_UNRESOLVED: Which retention period?", "raw " + state);
                rows.add(new com.taxonomy.analysis.backup.AnalysisBackupContributor.QuestionRecord(
                        new com.taxonomy.backup.SourceRecordId("analysis.question",key),sourceId,key,"f".repeat(64),"TEST",
                        "[\"BP-1\"]",db.json.writeValueAsString(detail),"original prompt",state,1,1,detail.getError()));
            }
            var shared = org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(db.factory);
            var transactions = new org.springframework.orm.jpa.JpaTransactionManager(db.factory);
            new AnalysisContinuationBackupRestorer(shared,transactions,db.json).restore(source,rows,id,target);
            var restored = db.call(store -> store.read(id,target));
            check(restored.result().getRawScores().isEmpty(), "raw error zeroes are not restored as assessments");
            check(restored.recovery().openQuestions().size()==2, "both new open states survive the archive boundary");
            check(restored.request().getContinuationQuestions().isEmpty() && restored.request().getContinuationAction()==null,
                    "archive data must never authorize a retry");
            db.entity(em -> {
                var run = em.find(AnalysisContinuationRun.class,id);
                check(run.claimToken==null && run.claimUntil==0 && run.clusterOperationId==null, "no active execution or broker grant is restored");
                return null;
            });
            check(db.call(store -> store.detail(id,"a".repeat(64),target)).getRawResponse().equals("raw UNASSESSED"), "exact failed evidence retained");
        }
    }
    private static void expectConflict(Runnable action) {
        try { action.run(); throw new AssertionError("stale or invalid decision accepted"); }
        catch (ResponseStatusException expected) { check(expected.getStatusCode().value() == 409, "expected scoped conflict"); }
    }

    static LlmCallDetail answer(Map<String, Integer> scores, String error, String raw) {
        var detail = new LlmCallDetail(); detail.setScores(scores); detail.setReasons(Map.of());
        detail.setError(error); detail.setRawResponse(raw); detail.setProvider("TEST"); return detail;
    }
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    static AnalysisRequest request() {
        var request = new AnalysisRequest("a requirement"); request.setContinuationId(UUID.randomUUID().toString()); request.setProvider("TEST");
        request.setResumable(true); request.setIncludeArchitectureView(true); request.setMaxArchitectureNodes(20); return request;
    }
    static WorkspaceContext scope() { return new WorkspaceContext("tester", "work", "draft", "repo"); }
    static List<TaxonomyNodeDto> tree() {
        var root = new TaxonomyNodeDto(); root.setCode("BP"); root.setTaxonomyRoot("BP"); root.setNameEn("Processes");
        for (String code : List.of("BP-1", "BP-2")) {
            var node = new TaxonomyNodeDto(); node.setCode(code); node.setTaxonomyRoot("BP"); node.setParentCode("BP"); node.setNameEn(code); node.setLevel(1); root.getChildren().add(node);
        }
        return List.of(root);
    }
    static final class Journal implements AnalysisCheckpointSession.Store {
        final Map<String, AnalysisCheckpointSession.Checkpoint> entries = new LinkedHashMap<>();
        String forceState; boolean paused; boolean rejectStorage;
        public AnalysisCheckpointSession.Checkpoint prepare(AnalysisCheckpointSession.Question q) {
            return forceState != null ? new AnalysisCheckpointSession.Checkpoint(q, forceState, null)
                    : entries.getOrDefault(q.key(), new AnalysisCheckpointSession.Checkpoint(q, "ATTEMPT", null));
        }
        public void finish(AnalysisCheckpointSession.Question q, String state, LlmCallDetail detail) {
            entries.put(q.key(), new AnalysisCheckpointSession.Checkpoint(q, state, detail));
            paused = rejectStorage || state.equals("FAILED");
        }
        public void checkActive() { if (paused) throw new AnalysisStoppedException(AnalysisStoppedException.Reason.AWAITING_DECISION); }
    }
    static final class Database implements AutoCloseable {
        final ObjectMapper json = new ObjectMapper();
        final SessionFactory factory = new Configuration().addAnnotatedClass(AnalysisContinuationRun.class)
                .addAnnotatedClass(AnalysisQuestionCheckpoint.class)
                .setProperty("hibernate.connection.driver_class", "org.hsqldb.jdbc.JDBCDriver")
                .setProperty("hibernate.connection.url", "jdbc:hsqldb:mem:" + UUID.randomUUID())
                .setProperty("hibernate.connection.username", "sa").setProperty("hibernate.connection.password", "")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop").setProperty("hibernate.show_sql", "false")
                .setProperty("hibernate.search.enabled", "false").buildSessionFactory();
        <T> T entity(Function<jakarta.persistence.EntityManager,T> action) {
            try (var em = factory.createEntityManager()) {
                var tx = em.getTransaction(); tx.begin();
                try { T value = action.apply(em); tx.commit(); return value; }
                catch (RuntimeException | Error failure) { if (tx.isActive()) tx.rollback(); throw failure; }
            }
        }
        <T> T call(Function<AnalysisContinuationStore,T> action) { return entity(em -> action.apply(new AnalysisContinuationStore(em,json))); }
        AnalysisContinuationStore.Claim begin(AnalysisRequest request) { return call(s -> s.begin(request, scope(), "f".repeat(64), tree())); }
        AnalysisCheckpointSession session(AnalysisContinuationStore.Claim claim) { return new AnalysisCheckpointSession(new AnalysisCheckpointSession.Store() {
            public AnalysisCheckpointSession.Checkpoint prepare(AnalysisCheckpointSession.Question q) { return call(s -> s.prepare(claim,q)); }
            public void finish(AnalysisCheckpointSession.Question q, String state, LlmCallDetail detail) { call(s -> { s.finish(claim,q,state,detail); return null; }); }
            public void checkActive() { String state = call(s -> s.state(claim)); if (!state.equals("RUNNING")) throw new AnalysisStoppedException(state.equals("PAUSED") ? AnalysisStoppedException.Reason.AWAITING_DECISION : AnalysisStoppedException.Reason.CANCELLED); }
        }); }
        AnalysisCheckpointSession distributedSession(AnalysisContinuationStore.Claim claim, AnalysisContinuationStore.QuestionOwner owner) { return new AnalysisCheckpointSession(new AnalysisCheckpointSession.Store() {
            public AnalysisCheckpointSession.Checkpoint prepare(AnalysisCheckpointSession.Question q) { return call(s -> s.prepareDistributed(claim,q,owner)); }
            public void finish(AnalysisCheckpointSession.Question q, String state, LlmCallDetail detail) { call(s -> { s.finishDistributed(claim,q,state,detail,owner); return null; }); }
            public void checkActive() { if (!call(s -> s.distributedState(claim,owner.operationId())).equals("RUNNING")) throw new AnalysisStoppedException(AnalysisStoppedException.Reason.AWAITING_DECISION); }
        }); }
        public void close() { factory.close(); }
    }
}
