package com.taxonomy.analysis.recovery;

import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.LlmCallDetail;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** Real Hibernate/SQL boundary, with a large opaque saved result rather than an LLM or fake catalogue. */
public final class RecoveryCheckpointPersistenceProbe {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static final class Database implements AutoCloseable {
        final List<String> statements = new ArrayList<>();
        final String id = UUID.randomUUID().toString();
        final String token = UUID.randomUUID().toString();
        final ObjectMapper mapper = new ObjectMapper();
        final SessionFactory factory;
        final String savedResult = "{\"status\":\"IN_PROGRESS\",\"tree\":[],\"errorMessage\":\""
                + "retained evidence ".repeat(32_000) + "\"}";

        Database() {
            factory = new Configuration()
                    .addAnnotatedClass(AnalysisContinuationRun.class)
                    .addAnnotatedClass(AnalysisQuestionCheckpoint.class)
                    .setProperty("hibernate.connection.driver_class", "org.hsqldb.jdbc.JDBCDriver")
                    .setProperty("hibernate.connection.url", "jdbc:hsqldb:mem:recovery-" + id)
                    .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                    .setProperty("hibernate.search.enabled", "false")
                    .setProperty("hibernate.generate_statistics", "true")
                    .setStatementInspector(sql -> { statements.add(sql); return sql; })
                    .buildSessionFactory();
            transaction(em -> {
                var run = new AnalysisContinuationRun();
                run.id = id; run.username = "owner"; run.workspaceId = "workspace";
                run.repositoryId = "repository"; run.branchName = "draft";
                run.inputHash = "0".repeat(64); run.requestJson = "{}";
                run.resultJson = savedResult; run.state = "RUNNING"; run.claimToken = token;
                run.claimUntil = System.currentTimeMillis() + 60_000;
                em.persist(run);
                return null;
            });
            statements.clear(); factory.getStatistics().clear();
        }

        AnalysisContinuationStore.Claim claim() {
            return new AnalysisContinuationStore.Claim(id, token, null);
        }
        <T> T transaction(Function<EntityManager, T> action) {
            try (var em = factory.createEntityManager()) {
                em.getTransaction().begin();
                try {
                    T result = action.apply(em); em.getTransaction().commit(); return result;
                } catch (RuntimeException | Error failure) {
                    if (em.getTransaction().isActive()) em.getTransaction().rollback();
                    throw failure;
                }
            }
        }
        AnalysisContinuationStore store(EntityManager em) { return new AnalysisContinuationStore(em, mapper); }
        @Override public void close() { factory.close(); }
    }

    public static void stateDoesNotLoadSavedResult() {
        try (var db = new Database()) {
            for (int i = 0; i < 10; i++) {
                check("RUNNING".equals(db.transaction(em -> db.store(em).state(db.claim()))), "Claim stopped unexpectedly");
            }
            check(db.statements.stream().noneMatch(sql -> sql.contains("result_json") || sql.contains("request_json")),
                    "Frequent claim checks must not fetch megabytes of saved result: " + db.statements.getFirst());
            check(db.factory.getStatistics().getEntityStatistics(AnalysisContinuationRun.class.getName()).getLoadCount() == 0,
                    "Claim checks hydrated the entire continuation entity");
        }
    }

    public static void checkpointDoesNotRewriteSavedResult() {
        try (var db = new Database()) {
            String provider = "P".repeat(128);
            var question = new AnalysisCheckpointSession.Question("1".repeat(64), "2".repeat(64), provider, List.of("BP"), "prompt");
            db.transaction(em -> db.store(em).prepare(db.claim(), question));
            var answer = new LlmCallDetail(); answer.setScores(Map.of("BP", 70)); answer.setProvider(provider);
            db.transaction(em -> { db.store(em).finish(db.claim(), question, "SUCCESS", answer); return null; });
            var updates = db.statements.stream().filter(sql -> sql.startsWith("update analysis_continuation ")).toList();
            check(updates.size() == 2, "Both the attempt and successful answer must be persisted");
            check(updates.stream().noneMatch(sql -> sql.contains("result_json") || sql.contains("request_json")),
                    "A question checkpoint rewrites the immutable saved snapshot: " + updates);
            check(updates.stream().allMatch(sql -> sql.contains("row_version")), "Optimistic revision was lost");
            db.transaction(em -> {
                var run = em.find(AnalysisContinuationRun.class, db.id);
                var checkpoint = em.find(AnalysisQuestionCheckpoint.class, db.id + ":" + question.key());
                check(provider.equals(checkpoint.provider), "Open provider identity was truncated at persistence boundary");
                check(db.savedResult.equals(run.resultJson), "Question update changed the saved catalogue/result");
                check("SUCCESS".equals(checkpoint.state) && run.version == 2, "Checkpoint or revision not committed");
                check(run.payloadCharacters == question.prompt().length() + checkpoint.detailJson.length(), "Evidence budget not updated");
                return null;
            });
            var finalResult = new AnalysisResult(Map.of(), List.of()); finalResult.setStatus("SUCCESS");
            db.transaction(em -> db.store(em).complete(db.claim(), finalResult, List.of()));
            String durable = db.transaction(em -> em.find(AnalysisContinuationRun.class, db.id).resultJson);
            check(!durable.equals(db.savedResult) && durable.contains("\"status\":\"SUCCESS\""),
                    "Explicit result completion must still update the snapshot");
        }
    }

    public static void checkpointDoesNotLoadSavedResult() {
        try (var db = new Database()) {
            var question = new AnalysisCheckpointSession.Question("1".repeat(64), "2".repeat(64), "MOCK", List.of("BP"), "prompt");
            db.transaction(em -> db.store(em).prepare(db.claim(), question));
            var answer = new LlmCallDetail(); answer.setScores(Map.of("BP", 70)); answer.setProvider("MOCK");
            db.transaction(em -> { db.store(em).finish(db.claim(), question, "SUCCESS", answer); return null; });
            var cached = db.transaction(em -> db.store(em).prepare(db.claim(), question));
            check("SUCCESS".equals(cached.state()), "The successful answer was not reused");
            check(db.statements.stream().noneMatch(sql -> sql.contains("result_json") || sql.contains("request_json")),
                    "Question bookkeeping must never hydrate its frozen result: " + db.statements);
            check(db.factory.getStatistics().getEntityStatistics(AnalysisContinuationRun.class.getName()).getLoadCount() == 0,
                    "Question prepare/finish hydrated the entire continuation entity");
            check(db.statements.stream().filter(sql -> sql.startsWith("select") && sql.contains("analysis_continuation"))
                    .allMatch(sql -> sql.contains("for update")), "Question bookkeeping lost the run row lock");
        }
    }

    public static void checkpointClaimIsStillEnforced() {
        try (var db = new Database()) {
            var question = new AnalysisCheckpointSession.Question("1".repeat(64), "2".repeat(64), "MOCK", List.of("BP"), "prompt");
            expectConflict(() -> db.transaction(em -> db.store(em).prepare(
                    new AnalysisContinuationStore.Claim(db.id, "superseded", null), question)));
            expectConflict(() -> db.transaction(em -> db.store(em).prepare(
                    new AnalysisContinuationStore.Claim(UUID.randomUUID().toString(), db.token, null), question)));
            db.transaction(em -> { em.find(AnalysisContinuationRun.class, db.id).state = "CANCELLED"; return null; });
            expectConflict(() -> db.transaction(em -> db.store(em).prepare(db.claim(), question)));
            var late = new LlmCallDetail(); late.setScores(Map.of("BP", 99));
            expectConflict(() -> db.transaction(em -> { db.store(em).finish(db.claim(), question, "SUCCESS", late); return null; }));
            db.transaction(em -> { var run = em.find(AnalysisContinuationRun.class, db.id);
                run.state = "RUNNING"; run.claimUntil = System.currentTimeMillis() - 1; return null; });
            expectConflict(() -> db.transaction(em -> db.store(em).prepare(db.claim(), question)));
            db.transaction(em -> {
                check(em.find(AnalysisQuestionCheckpoint.class, db.id + ":" + question.key()) == null,
                        "Rejected execution created question evidence");
                check(db.savedResult.equals(em.find(AnalysisContinuationRun.class, db.id).resultJson),
                        "Rejected execution changed the frozen result");
                return null;
            });
        }
    }

    public static void bookkeepingFailureRollsBackQuestion() {
        try (var db = new Database()) {
            var question = new AnalysisCheckpointSession.Question("1".repeat(64), "2".repeat(64), "MOCK", List.of("BP"), "prompt");
            try {
                db.transaction(em -> {
                    EntityManager failing = (EntityManager) java.lang.reflect.Proxy.newProxyInstance(
                            EntityManager.class.getClassLoader(), new Class<?>[]{EntityManager.class}, (proxy, method, args) -> {
                                if (method.getName().equals("createQuery") && args[0] instanceof String query
                                        && query.startsWith("update AnalysisContinuationRun"))
                                    throw new IllegalStateException("Injected bookkeeping failure after question flush");
                                try { return method.invoke(em, args); }
                                catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                            });
                    return db.store(failing).prepare(db.claim(), question);
                });
                throw new AssertionError("Expected persistence failure");
            } catch (IllegalStateException expected) {
                check(expected.getMessage().startsWith("Injected bookkeeping failure"), "Unexpected persistence error");
            }
            db.transaction(em -> {
                check(em.find(AnalysisQuestionCheckpoint.class, db.id + ":" + question.key()) == null,
                        "Question escaped the rolled-back bookkeeping transaction");
                var run = em.find(AnalysisContinuationRun.class, db.id);
                check(run.version == 0 && run.payloadCharacters == 0 && db.savedResult.equals(run.resultJson),
                        "Failed bookkeeping published a partial update");
                return null;
            });
            check("ATTEMPT".equals(db.transaction(em -> db.store(em).prepare(db.claim(), question)).state()),
                    "A failed persistence transaction prevented the next valid attempt");
        }
    }
    private static void expectConflict(Runnable operation) {
        try { operation.run(); throw new AssertionError("An invalid claim was admitted"); }
        catch (org.springframework.web.server.ResponseStatusException expected) {
            check(expected.getStatusCode().value() == 409, "Expected a claim conflict");
        }
    }

    public static void stateIsFreshAndClaimBound() {
        try (var db = new Database(); var observer = db.factory.createEntityManager()) {
            observer.getTransaction().begin();
            observer.find(AnalysisContinuationRun.class, db.id); // a stale first-level cache must not own cancellation
            var store = db.store(observer);
            check("RUNNING".equals(store.state(db.claim())), "Claim missing");
            db.transaction(em -> { em.find(AnalysisContinuationRun.class, db.id).state = "CANCELLED"; return null; });
            check("CANCELLED".equals(store.state(db.claim())), "Cancellation hidden by previously loaded entity");
            observer.getTransaction().commit();
            check("CANCELLED".equals(db.transaction(em -> db.store(em).state(
                    new AnalysisContinuationStore.Claim(db.id, "wrong-claim", null)))), "Superseded claim was authorized");
            check("CANCELLED".equals(db.transaction(em -> db.store(em).state(
                    new AnalysisContinuationStore.Claim(UUID.randomUUID().toString(), db.token, null)))), "Absent run was authorized");
        }
    }

    public static void main(String[] args) {
        switch (args[0]) {
            case "read" -> stateDoesNotLoadSavedResult();
            case "checkpoint-read" -> checkpointDoesNotLoadSavedResult();
            case "checkpoint-authority" -> checkpointClaimIsStillEnforced();
            case "rollback" -> bookkeepingFailureRollsBackQuestion();
            case "write" -> checkpointDoesNotRewriteSavedResult();
            case "authority" -> stateIsFreshAndClaimBound();
            default -> throw new IllegalArgumentException("read, write or authority required");
        }
        System.out.println("RECOVERY_CHECKPOINT_PERSISTENCE_OK: " + args[0]);
    }
}
