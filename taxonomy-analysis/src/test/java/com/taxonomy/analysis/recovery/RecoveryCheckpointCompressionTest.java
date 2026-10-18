package com.taxonomy.analysis.recovery;

import com.taxonomy.dto.AnalysisPercentages;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.workspace.service.WorkspaceContext;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** Real SQL checks for the representation boundary; provider answers remain complete logical values. */
class RecoveryCheckpointCompressionTest {
    private static final int FIELD_LIMIT = 2_000_000;
    private static final long RUN_LIMIT = 24_000_000;
    private static final WorkspaceContext SCOPE = new WorkspaceContext("owner", "workspace", "draft", "repository");

    private record Stored(String nodes, String prompt, String detail, String state, int attempts, String hash,
                          long payloadCharacters, long version, String runState) { }

    private static final class Database implements AutoCloseable {
        final String id = UUID.randomUUID().toString();
        final String token = UUID.randomUUID().toString();
        final ObjectMapper mapper = new ObjectMapper();
        final List<String> sql = new ArrayList<>();
        final SessionFactory factory = new Configuration()
                .addAnnotatedClass(AnalysisContinuationRun.class).addAnnotatedClass(AnalysisQuestionCheckpoint.class)
                .setProperty("hibernate.connection.driver_class", "org.hsqldb.jdbc.JDBCDriver")
                .setProperty("hibernate.connection.url", "jdbc:hsqldb:mem:compressed-checkpoints-" + id)
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .setProperty("hibernate.search.enabled", "false")
                .setProperty("hibernate.generate_statistics", "true")
                .setStatementInspector(statement -> { sql.add(statement); return statement; })
                .buildSessionFactory();

        Database() {
            transaction(em -> {
                var run = new AnalysisContinuationRun(); run.id = id; run.username = SCOPE.username();
                run.workspaceId = SCOPE.workspaceId(); run.branchName = SCOPE.currentBranch(); run.repositoryId = SCOPE.repositoryId();
                run.inputHash = "0".repeat(64); run.requestJson = "{}"; run.state = "RUNNING"; run.claimToken = token;
                run.claimUntil = System.currentTimeMillis() + 120_000;
                run.resultJson = mapper.writeValueAsString(new AnalysisResult(Map.of(), List.of()));
                em.persist(run); return null;
            });
            resetSql();
        }
        AnalysisContinuationStore store(EntityManager em) { return new AnalysisContinuationStore(em, mapper); }
        AnalysisContinuationStore.Claim claim() { return new AnalysisContinuationStore.Claim(id, token, null); }
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
        Stored stored(AnalysisCheckpointSession.Question input) {
            return transaction(em -> {
                var run = em.find(AnalysisContinuationRun.class, id);
                var q = em.find(AnalysisQuestionCheckpoint.class, id + ":" + input.key());
                return new Stored(q.nodeCodes, q.prompt, q.detailJson, q.state, q.attempts, q.inputHash,
                        run.payloadCharacters, run.version, run.state);
            });
        }
        void legacy(AnalysisCheckpointSession.Question input, String state, String detail) {
            transaction(em -> {
                var run = em.find(AnalysisContinuationRun.class, id);
                var q = new AnalysisQuestionCheckpoint(); q.id = id + ":" + input.key(); q.run = run;
                q.questionKey = input.key(); q.inputHash = input.inputHash(); q.provider = input.provider();
                q.nodeCodes = mapper.writeValueAsString(input.nodes()); q.prompt = input.prompt(); q.detailJson = detail;
                q.state = state; q.attempts = 1; q.startedAt = System.currentTimeMillis();
                run.payloadCharacters += input.prompt().length() + (detail == null ? 0 : detail.length());
                em.persist(q); return null;
            });
        }
        AnalysisCheckpointSession.Store sessionStore() {
            return new AnalysisCheckpointSession.Store() {
                @Override public AnalysisCheckpointSession.Checkpoint prepare(AnalysisCheckpointSession.Question question) {
                    return transaction(em -> store(em).prepare(claim(), question));
                }
                @Override public void finish(AnalysisCheckpointSession.Question question, String state, LlmCallDetail detail) {
                    transaction(em -> { store(em).finish(claim(), question, state, detail); return null; });
                }
                @Override public void checkActive() {
                    assertEquals("RUNNING", transaction(em -> store(em).state(claim())));
                }
            };
        }
        void resetSql() { sql.clear(); factory.getStatistics().clear(); }
        @Override public void close() { factory.close(); }
    }

    private static AnalysisCheckpointSession.Question category(String prompt) {
        return new AnalysisCheckpointSession.Question(AnalysisCheckpointSession.digest(AnalysisPercentages.POLICY, "CATEGORY", "BP"),
                AnalysisCheckpointSession.digest(AnalysisPercentages.POLICY, "MOCK", prompt), "MOCK", List.of("BP"), prompt);
    }

    private static LlmCallDetail detail(String prompt) {
        var detail = new LlmCallDetail(); detail.setProvider("MOCK"); detail.setPrompt(prompt);
        detail.setScores(Map.of("BP", .125)); detail.setReasons(Map.of("BP", "Original assessment with fractional relevance"));
        detail.setRawResponse("Original raw reply: \"quoted\" \\ line\nÄöü 😀\uD800 end");
        return detail;
    }

    @Test void compressedEvidenceRestartsWithTheExactPromptReplyAndFractionalAssessment() {
        try (var db = new Database()) {
            String prompt = "Full original prompt with its catalogue, requirement and context.\n".repeat(5_000);
            var input = category(prompt); var expected = detail(prompt); var calls = new AtomicInteger();
            for (int restart = 0; restart < 2; restart++) {
                try (var session = new AnalysisCheckpointSession(db.sessionStore())) {
                    var value = AnalysisCheckpointSession.evaluate("CATEGORY", "MOCK", List.of("BP"), prompt,
                            () -> { calls.incrementAndGet(); return expected; });
                    assertEquals(db.mapper.writeValueAsString(expected), db.mapper.writeValueAsString(value));
                }
            }
            assertEquals(1, calls.get(), "Persisted accepted answers must not cause another provider request");
            var saved = db.stored(input);
            assertTrue(saved.nodes().startsWith("{"), "Compact encoding needs explicit server-owned layout metadata");
            assertTrue(saved.prompt().length() + saved.detail().length() < prompt.length(),
                    "The duplicate prompt and response representation still consumes the logical text budget");
            assertEquals(saved.prompt().length() + saved.detail().length(), saved.payloadCharacters());
            assertEquals(input.inputHash(), saved.hash()); assertEquals(1, saved.attempts());
            var full = db.transaction(em -> db.store(em).detail(db.id, input.key(), SCOPE));
            assertEquals(db.mapper.writeValueAsString(expected), db.mapper.writeValueAsString(full));
        }
    }

    @Test void legacyArraysRemainLiteralAndAcceptedReplayNeverRewritesThem() {
        try (var db = new Database()) {
            String prompt = "{\"encoding\":\"gzip+base64\",\"data\":\"literal provider input, not an envelope\"}";
            var input = category(prompt); String json = db.mapper.writeValueAsString(detail(prompt));
            db.legacy(input, "SUCCESS", json); var before = db.stored(input); db.resetSql();
            try (var session = new AnalysisCheckpointSession(db.sessionStore())) {
                var restored = AnalysisCheckpointSession.evaluate("CATEGORY", "MOCK", List.of("BP"), prompt,
                        () -> { throw new AssertionError("Legacy accepted evidence was repeated"); });
                assertEquals(json, db.mapper.writeValueAsString(restored));
            }
            assertTrue(db.sql.stream().noneMatch(sql -> sql.startsWith("update") || sql.startsWith("insert")));
            assertEquals(before, db.stored(input));
        }
    }

    @Test void anExplicitLegacyRetryTranscodesBothPayloadsAndTheCounterTogether() {
        try (var db = new Database()) {
            String prompt = "Immutable original requirement and catalogue. ".repeat(6_000);
            var input = category(prompt); var oldDetail = detail(prompt); oldDetail.setError("old invalid JSON");
            String originalJson = db.mapper.writeValueAsString(oldDetail);
            db.legacy(input, "READY", originalJson); var before = db.stored(input);
            var retry = db.transaction(em -> db.store(em).prepare(db.claim(), input));
            assertEquals("ATTEMPT", retry.state()); assertEquals(originalJson, db.mapper.writeValueAsString(retry.detail()));
            var saved = db.stored(input);
            assertTrue(saved.nodes().startsWith("{"), "Explicit retry left the large legacy fields uncompressed");
            assertNotEquals(before.prompt(), saved.prompt()); assertNotEquals(before.detail(), saved.detail());
            assertEquals(saved.prompt().length() + saved.detail().length(), saved.payloadCharacters());
            assertEquals(before.version() + 1, saved.version()); assertEquals(2, saved.attempts());
            assertEquals(originalJson, db.mapper.writeValueAsString(db.transaction(em -> db.store(em).detail(db.id, input.key(), SCOPE))));
            db.transaction(em -> { db.store(em).finish(db.claim(), input, "SUCCESS", detail(prompt)); return null; });
            assertEquals(db.mapper.writeValueAsString(detail(prompt)), db.mapper.writeValueAsString(
                    db.transaction(em -> db.store(em).prepare(db.claim(), input)).detail()));
        }
    }

    @Test void failedOwnerBookkeepingRollsBackLegacyTranscodingCompletely() {
        try (var db = new Database()) {
            String prompt = "Original durable context ".repeat(5_000); var input = category(prompt);
            db.legacy(input, "READY", db.mapper.writeValueAsString(detail(prompt))); var before = db.stored(input);
            var failure = assertThrows(IllegalStateException.class, () -> db.transaction(em -> {
                var intercepted = (EntityManager) Proxy.newProxyInstance(EntityManager.class.getClassLoader(),
                        new Class<?>[]{EntityManager.class}, (proxy, method, args) -> {
                            if (method.getName().equals("createQuery") && args[0] instanceof String query
                                    && query.startsWith("update AnalysisContinuationRun")) throw new IllegalStateException("injected owner failure");
                            try { return method.invoke(em, args); }
                            catch (InvocationTargetException cause) { throw cause.getCause(); }
                        });
                return db.store(intercepted).prepare(db.claim(), input);
            }));
            assertEquals("injected owner failure", failure.getMessage()); assertEquals(before, db.stored(input));
        }
    }

    @Test void compressionCannotBypassEitherCompleteLogicalFieldLimit() {
        try (var db = new Database()) {
            var oversized = category("p".repeat(FIELD_LIMIT + 1));
            var refused = db.transaction(em -> db.store(em).prepare(db.claim(), oversized));
            assertEquals("FAILED", refused.state());
            var saved = db.stored(oversized); assertNull(saved.prompt()); assertNull(saved.detail());
            assertEquals(0, saved.payloadCharacters()); assertEquals("PAUSED", saved.runState());
        }
        try (var db = new Database()) {
            String prompt = "p".repeat(500_000); var input = category(prompt);
            db.transaction(em -> db.store(em).prepare(db.claim(), input));
            var tooLarge = detail(prompt); tooLarge.setRawResponse("r".repeat(1_600_000));
            assertTrue(db.mapper.writeValueAsString(tooLarge).length() > FIELD_LIMIT);
            var before = db.stored(input);
            db.transaction(em -> { db.store(em).finish(db.claim(), input, "SUCCESS", tooLarge); return null; });
            var after = db.stored(input);
            assertEquals("FAILED", after.state()); assertEquals("PAUSED", after.runState()); assertNull(after.detail());
            assertEquals(before.payloadCharacters(), after.payloadCharacters()); assertEquals(before.prompt(), after.prompt());
        }
    }

    @Test void moreThan24MillionLogicalCharactersFitOnlyWhenTheirActualStoredTextFits() {
        try (var db = new Database()) {
            long logical = 0;
            for (int index = 0; index < 24; index++) {
                String prompt = "Question " + index + ": " + "Complete catalogue and original requirement. ".repeat(14_000);
                var input = AnalysisCheckpointSession.evidenceQuestion("NAVIGATE", "MOCK", List.of("BP-" + index), prompt);
                var answer = detail(prompt); logical += prompt.length() + db.mapper.writeValueAsString(answer).length();
                var admitted = db.transaction(em -> db.store(em).prepare(db.claim(), input));
                assertEquals("ATTEMPT", admitted.state(), "Normal stored-text capacity stopped at question " + index);
                db.transaction(em -> { db.store(em).finish(db.claim(), input, "SUCCESS", answer); return null; });
                assertEquals("SUCCESS", db.stored(input).state(), "Complete logical answer was not durably accepted at question " + index);
            }
            assertTrue(logical > RUN_LIMIT, "The regression must cross the old plain-text storage boundary");
            db.transaction(em -> {
                var run = em.find(AnalysisContinuationRun.class, db.id);
                long actual = em.createQuery("select q.prompt, q.detailJson from AnalysisQuestionCheckpoint q where q.run.id=:id", Object[].class)
                        .setParameter("id", db.id).getResultList().stream()
                        .mapToLong(row -> ((String) row[0]).length() + ((String) row[1]).length()).sum();
                assertEquals(actual, run.payloadCharacters); assertTrue(actual < RUN_LIMIT); return null;
            });
        }
    }

    @Test void theActualStoredTextQuotaStillRejectsAReplyWithoutDiscardingPriorEvidence() {
        try (var db = new Database()) {
            for (int index = 0; index < 12; index++) {
                String prompt = ("Legacy " + index + " ").repeat(250_000).substring(0, 1_990_000);
                var input = AnalysisCheckpointSession.evidenceQuestion("NAVIGATE", "MOCK", List.of("BP-" + index), prompt);
                db.legacy(input, "SUCCESS", "{}");
            }
            var input = category("A final short prompt");
            assertEquals("ATTEMPT", db.transaction(em -> db.store(em).prepare(db.claim(), input)).state());
            var before = db.stored(input); assertTrue(before.payloadCharacters() < RUN_LIMIT);
            var answer = detail(input.prompt());
            var noise = new char[350_000]; var random = new Random(42);
            for (int i = 0; i < noise.length; i++) noise[i] = (char) ('!' + random.nextInt(90));
            answer.setRawResponse(new String(noise));
            assertTrue(db.mapper.writeValueAsString(answer).length() < FIELD_LIMIT);
            db.transaction(em -> { db.store(em).finish(db.claim(), input, "SUCCESS", answer); return null; });
            var after = db.stored(input);
            assertEquals("FAILED", after.state()); assertEquals("PAUSED", after.runState());
            assertEquals(before.payloadCharacters(), after.payloadCharacters()); assertEquals(before.prompt(), after.prompt());
            assertNull(after.detail());
        }
    }

    @Test void unknownEncodingIsVisibleAndNeverReinterpretedAsAnAcceptedPlainReply() {
        try (var db = new Database()) {
            var input = category("Original input"); db.legacy(input, "SUCCESS", db.mapper.writeValueAsString(detail(input.prompt())));
            db.transaction(em -> {
                em.find(AnalysisQuestionCheckpoint.class, db.id + ":" + input.key()).nodeCodes = "{\"nodes\":[\"BP\"],\"encoding\":\"unknown\",\"version\":99}";
                return null;
            });
            var before = db.stored(input);
            assertThrows(IllegalArgumentException.class, () -> db.transaction(em -> db.store(em).prepare(db.claim(), input)));
            assertEquals(before, db.stored(input));
        }
    }

    @Test void metadataViewsDecodeOnlyNodeIdentityAndLeaveOpaqueResponsesUnread() {
        try (var db = new Database()) {
            var input = category("Full prompt ".repeat(6_000));
            db.transaction(em -> db.store(em).prepare(db.claim(), input));
            var answer = detail(input.prompt()); answer.setError("unassessed evidence");
            db.transaction(em -> { db.store(em).finish(db.claim(), input, "UNASSESSED", answer); return null; });
            db.transaction(em -> {
                em.find(AnalysisQuestionCheckpoint.class, db.id + ":" + input.key()).detailJson = "corrupt opaque response";
                return null;
            });
            db.resetSql();
            var observed = db.transaction(em -> db.store(em).read(db.id, SCOPE));
            assertEquals(List.of("BP"), observed.recovery().openQuestions().getFirst().nodes());
            assertEquals("unassessed evidence", observed.recovery().openQuestions().getFirst().error());
            assertTrue(db.sql.stream().noneMatch(sql -> sql.startsWith("select")
                    && sql.contains("from analysis_question_checkpoint ")
                    && (sql.contains("prompt_text") || sql.contains("detail_json"))), db.sql.toString());
            assertEquals(0, db.factory.getStatistics().getEntityStatistics(AnalysisQuestionCheckpoint.class.getName()).getLoadCount());
            assertThrows(IllegalArgumentException.class,
                    () -> db.transaction(em -> db.store(em).detail(db.id, input.key(), SCOPE)));
        }
    }

    @Test void clusterCompletionReconstructsItsAcceptedCompressedCategoryEvidence() {
        try (var db = new Database()) {
            db.transaction(em -> { em.find(AnalysisContinuationRun.class, db.id).clusterOperationId = "operation"; return null; });
            var owner = new AnalysisContinuationStore.QuestionOwner("operation", "task", 1,
                    UUID.randomUUID().toString(), "CATEGORY:BP");
            var input = category("Frozen distributed source ".repeat(5_000)); var answer = detail(input.prompt());
            var scoped = db.transaction(em -> db.store(em).prepareDistributed(db.claim(), input, owner)).question();
            db.transaction(em -> { db.store(em).finishDistributed(db.claim(), scoped, "SUCCESS", answer, owner); return null; });
            var saved = db.stored(scoped); assertTrue(saved.nodes().startsWith("{"));
            var result = new AnalysisResult(Map.of(), List.of()); result.setStatus("SUCCESS"); db.resetSql();
            var completed = db.transaction(em -> db.store(em).complete(db.claim(), result, List.of()));
            assertEquals(Map.of("BP", .125), completed.getRawScores()); assertEquals(answer.getReasons(), completed.getReasons());
            var payloadReads = db.sql.stream().filter(sql -> sql.startsWith("select")
                    && sql.contains("from analysis_question_checkpoint ") && sql.contains("detail_json")).toList();
            assertEquals(1, payloadReads.size()); assertTrue(payloadReads.getFirst().contains("node_codes"));
            assertTrue(payloadReads.getFirst().contains("prompt_text"));
            assertTrue(payloadReads.getFirst().contains("state='SUCCESS'"));
            assertTrue(payloadReads.getFirst().contains("question_key not like 'R%'"));
            assertEquals(0, db.factory.getStatistics().getEntityStatistics(AnalysisQuestionCheckpoint.class.getName()).getLoadCount());
            var after = db.stored(scoped);
            assertEquals(saved.prompt(), after.prompt()); assertEquals(saved.detail(), after.detail());
            assertEquals(saved.nodes(), after.nodes()); assertEquals(saved.payloadCharacters(), after.payloadCharacters());
        }
    }

    @Test void cancellationRetainsCompressedAnswersAndUncertainAttemptsWithoutTranscoding() {
        try (var db = new Database()) {
            var accepted = category("Original accepted prompt ".repeat(5_000)); var answer = detail(accepted.prompt());
            db.transaction(em -> db.store(em).prepare(db.claim(), accepted));
            db.transaction(em -> { db.store(em).finish(db.claim(), accepted, "SUCCESS", answer); return null; });
            var pending = new AnalysisCheckpointSession.Question("2".repeat(64), "3".repeat(64), "MOCK",
                    List.of("CP"), "Full pending prompt ".repeat(5_000));
            db.transaction(em -> db.store(em).prepare(db.claim(), pending));
            var beforeAccepted = db.stored(accepted); var beforePending = db.stored(pending);
            var cancelled = db.transaction(em -> db.store(em).cancel(db.id, SCOPE));
            assertEquals("CANCELLED", cancelled.result().getStatus());
            assertEquals(Map.of("BP", .125), cancelled.result().getRawScores());
            assertEquals(answer.getReasons(), cancelled.result().getReasons());
            assertTrue(cancelled.recovery().openQuestions().getFirst().outcomeUncertain());
            assertEquals(List.of("CP"), cancelled.recovery().openQuestions().getFirst().nodes());
            var afterAccepted = db.stored(accepted); var afterPending = db.stored(pending);
            assertEquals(beforeAccepted.prompt(), afterAccepted.prompt()); assertEquals(beforeAccepted.detail(), afterAccepted.detail());
            assertEquals(beforeAccepted.nodes(), afterAccepted.nodes()); assertEquals(beforePending.prompt(), afterPending.prompt());
            assertEquals(beforePending.nodes(), afterPending.nodes()); assertEquals("FAILED", afterPending.state());
            assertEquals(beforePending.payloadCharacters(), afterPending.payloadCharacters());
        }
    }
}
