package com.taxonomy.analysis.recovery;

import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.workspace.service.WorkspaceContext;
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
    /** Independent transactions exercise the same retry and completion boundary as a live continuation. */
    public static final class Journal implements AnalysisCheckpointSession.Store, AutoCloseable {
        private final Database db = new Database();
        private AnalysisContinuationStore.Claim claim = db.claim();
        private AnalysisContinuationStore.QuestionOwner owner;

        public Journal() { }
        public Journal(String namespace) {
            db.transaction(em -> { em.find(AnalysisContinuationRun.class, db.id).clusterOperationId = "operation"; return null; });
            owner = new AnalysisContinuationStore.QuestionOwner("operation", "task", 1, UUID.randomUUID().toString(), namespace);
        }
        public void useLocalScope() { owner = null; }

        public AnalysisCheckpointSession.Checkpoint prepare(AnalysisCheckpointSession.Question question) {
            return db.transaction(em -> owner == null ? db.store(em).prepare(claim, question)
                    : db.store(em).prepareDistributed(claim, question, owner));
        }
        public void finish(AnalysisCheckpointSession.Question question, String state, LlmCallDetail detail) {
            db.transaction(em -> {
                if (owner == null) db.store(em).finish(claim, question, state, detail);
                else db.store(em).finishDistributed(claim, question, state, detail, owner);
                return null;
            });
        }
        public void checkActive() {
            if (!"RUNNING".equals(db.transaction(em -> db.store(em).state(claim))))
                throw new com.taxonomy.analysis.service.AnalysisStoppedException(
                        com.taxonomy.analysis.service.AnalysisStoppedException.Reason.AWAITING_DECISION);
        }
        public void supersedeEvidence(String kind, String provider, java.util.function.Predicate<AnalysisCheckpointSession.Question> covered) {
            db.transaction(em -> { db.store(em).supersedeEvidence(claim, kind, provider, covered, owner); return null; });
        }
        public void supersedeEvidence(String kind, String provider, java.util.Set<String> singletonCodes,
                java.util.function.Predicate<AnalysisCheckpointSession.Question> covered) {
            db.transaction(em -> { db.store(em).supersedeEvidence(claim, kind, provider, singletonCodes, covered, owner); return null; });
        }
        public Map<String, AnalysisCheckpointSession.Checkpoint> checkpoints() {
            return db.transaction(em -> {
                var result = new java.util.LinkedHashMap<String, AnalysisCheckpointSession.Checkpoint>();
                for (var saved : em.createQuery("select q from AnalysisQuestionCheckpoint q where q.run.id=:id", AnalysisQuestionCheckpoint.class)
                        .setParameter("id", db.id).getResultList()) {
                    var layout = db.payloads.parseLayout(saved.nodeCodes);
                    var question = new AnalysisCheckpointSession.Question(saved.questionKey, saved.inputHash, saved.provider,
                            layout.nodes(), db.payloads.decodePrompt(layout, saved.prompt));
                    result.put(saved.questionKey, new AnalysisCheckpointSession.Checkpoint(question, saved.state,
                            saved.detailJson == null ? null : db.mapper.readValue(
                                    db.payloads.decodeDetail(layout, saved.detailJson, question.prompt()), LlmCallDetail.class)));
                }
                return result;
            });
        }
        public String complete() {
            var result = new AnalysisResult(Map.of(), List.of()); result.setStatus("SUCCESS");
            db.transaction(em -> db.store(em).complete(claim, result, List.of()));
            return db.transaction(em -> em.find(AnalysisContinuationRun.class, db.id).state);
        }
        public void retryOpen() {
            decide("RETRY", List.of("UNASSESSED", "CLARIFICATION", "FAILED"));
        }
        public void skipCorrections() {
            decide("SKIP", List.of("UNASSESSED"));
        }
        private void decide(String action, List<String> states) {
            claim = db.transaction(em -> {
                var run = em.find(AnalysisContinuationRun.class, db.id);
                var request = new com.taxonomy.dto.AnalysisRequest();
                request.setContinuationId(db.id); request.setContinuationAction(action);
                request.setContinuationVersion(run.version);
                request.setContinuationQuestions(em.createQuery("select q.questionKey from AnalysisQuestionCheckpoint q where q.run.id=:id and q.state in :states", String.class)
                        .setParameter("id", db.id).setParameter("states", states).getResultList());
                return db.store(em).begin(request, new com.taxonomy.workspace.service.WorkspaceContext(
                        "owner", "workspace", "draft", "repository"), "0".repeat(64), List.of());
            });
        }
        @Override public void close() { db.close(); }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static final class Database implements AutoCloseable {
        final List<String> statements = new ArrayList<>();
        final String id = UUID.randomUUID().toString();
        final String token = UUID.randomUUID().toString();
        final ObjectMapper mapper = new ObjectMapper();
        final AnalysisCheckpointPayloadCodec payloads = new AnalysisCheckpointPayloadCodec(mapper);
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
        void resetObservations() { statements.clear(); factory.getStatistics().clear(); }
        void question(String key, String state, int attempts, long startedAt, String error) {
            transaction(em -> {
                var question = new AnalysisQuestionCheckpoint();
                question.id = id + ":" + key; question.run = em.getReference(AnalysisContinuationRun.class, id);
                question.questionKey = key; question.inputHash = "2".repeat(64); question.provider = "MOCK";
                question.nodeCodes = mapper.writeValueAsString(List.of("BP-1017", "BP-1529"));
                question.prompt = "original source and candidate context ".repeat(8_000);
                question.detailJson = mapper.writeValueAsString(Map.of("rawResponse", "durable answer ".repeat(16_000)));
                question.state = state; question.attempts = attempts; question.startedAt = startedAt; question.error = error;
                em.persist(question); return null;
            });
        }
        @Override public void close() { factory.close(); }
    }

    private static WorkspaceContext scope() { return new WorkspaceContext("owner", "workspace", "draft", "repository"); }

    private static void noQuestionPayloadReads(Database db, String boundary) {
        check(db.statements.stream().noneMatch(sql -> sql.startsWith("select")
                        && sql.contains("from analysis_question_checkpoint ")
                        && (sql.contains("prompt_text") || sql.contains("detail_json"))),
                boundary + " read opaque prompts or responses: " + db.statements);
        check(db.factory.getStatistics().getEntityStatistics(AnalysisQuestionCheckpoint.class.getName()).getLoadCount() == 0,
                boundary + " hydrated question entities");
    }

    private static void noQuestionPayloadWrites(Database db, String boundary) {
        check(db.statements.stream().noneMatch(sql -> sql.startsWith("update analysis_question_checkpoint ")
                        && (sql.contains("prompt_text") || sql.contains("detail_json") || sql.contains("node_codes"))),
                boundary + " rewrote unchanged prompt or response evidence: " + db.statements);
    }

    public static void originalRequestUsesOnlyItsPayloadAndScope() {
        try (var db = new Database()) {
            var request = new com.taxonomy.dto.AnalysisRequest("Full original requirement äöü\n".repeat(12_000));
            request.setProvider("MOCK"); request.setContinuationId(db.id); request.setContinuationAuthority("frozen-authority");
            request.setAnalysisReasonLength(com.taxonomy.dto.AnalysisReasonLength.DETAILED);
            String json = db.mapper.writeValueAsString(request);
            db.transaction(em -> {
                var run = em.find(AnalysisContinuationRun.class, db.id);
                run.requestJson = json; run.clusterSourceJson = "unrelated frozen catalogue ".repeat(24_000); return null;
            });
            db.resetObservations();
            var restored = db.transaction(em -> db.store(em).originalRequest(db.id, scope()).orElseThrow());
            check(json.equals(db.mapper.writeValueAsString(restored)), "The complete frozen input changed at its scalar read boundary");
            check(db.statements.size() == 1 && db.statements.getFirst().contains("request_json")
                            && db.statements.getFirst().contains("username") && db.statements.getFirst().contains("workspace_id")
                            && db.statements.getFirst().contains("branch_name") && db.statements.getFirst().contains("repository_id")
                            && !db.statements.getFirst().contains("result_json") && !db.statements.getFirst().contains("cluster_source_json"),
                    "Original-input admission fetched unrelated saved results: " + db.statements);
            check(db.factory.getStatistics().getEntityLoadCount() == 0, "Original-input admission hydrated the complete run");
            for (var foreign : List.of(new WorkspaceContext("foreign", "workspace", "draft", "repository"),
                    new WorkspaceContext("owner", "foreign", "draft", "repository"),
                    new WorkspaceContext("owner", "workspace", "foreign", "repository"),
                    new WorkspaceContext("owner", "workspace", "draft", "foreign"))) {
                expectStatus(404, () -> db.transaction(em -> db.store(em).originalRequest(db.id, foreign)));
            }
            check(db.transaction(em -> db.store(em).originalRequest(UUID.randomUUID().toString(), scope())).isEmpty(),
                    "A missing original input must remain absent");
            expectStatus(403, () -> db.transaction(em -> db.store(em).originalRequest(db.id, null)));
            expectStatus(400, () -> db.transaction(em -> db.store(em).originalRequest("invalid", scope())));
            expectStatus(400, () -> db.transaction(em -> db.store(em).originalRequest("01234567-89AB-4CDE-8FAB-0123456789AB", scope())));
        }
    }

    public static void continueUsesQuestionStates() {
        try (var db = new Database()) {
            db.question("accepted", "SUCCESS", 1, 1, null);
            db.question("left-open", "SKIPPED", 2, 2, "deliberately open");
            db.question("next", "READY", 0, 3, null);
            long version = db.transaction(em -> {
                var run = em.find(AnalysisContinuationRun.class, db.id); run.state = "STOPPED"; em.flush(); return run.version;
            });
            var request = new com.taxonomy.dto.AnalysisRequest(); request.setContinuationId(db.id);
            request.setContinuationAction("CONTINUE"); request.setContinuationVersion(version);
            db.resetObservations();
            var claim = db.transaction(em -> db.store(em).begin(request, scope(), "0".repeat(64), List.of()));
            check(claim.token() != null && !db.token.equals(claim.token()), "Explicit continuation must claim new work");
            noQuestionPayloadReads(db, "Continuation state check");
            check(db.statements.stream().anyMatch(sql -> sql.startsWith("select")
                            && sql.contains("from analysis_continuation ") && sql.contains("for update")),
                    "Continuation lost its run lock");
            db.transaction(em -> {
                check("SKIPPED".equals(em.find(AnalysisQuestionCheckpoint.class, db.id + ":left-open").state),
                        "Ordinary continuation reopened a deliberately skipped assessment");
                var run = em.find(AnalysisContinuationRun.class, db.id); run.state = "STOPPED";
                em.find(AnalysisQuestionCheckpoint.class, db.id + ":next").state = "FAILED";
                em.flush(); request.setContinuationVersion(run.version); return null;
            });
            expectConflict(() -> db.transaction(em -> db.store(em).begin(request, scope(), "0".repeat(64), List.of())));
        }
    }

    public static void localCompletionUsesQuestionMetadata() {
        try (var db = new Database()) {
            db.question("accepted", "SUCCESS", 1, 1, null);
            db.question("left-open", "SKIPPED", 2, 2, "deliberately open");
            db.question("next", "READY", 0, 3, null);
            var result = new AnalysisResult(Map.of("BP-1017", .125), List.of()); result.setStatus("SUCCESS");
            result.setReasons(Map.of("BP-1017", "Original local assessment"));
            db.resetObservations();
            var completed = db.transaction(em -> db.store(em).complete(db.claim(), result, List.of()));
            check("STOPPED".equals(completed.getRecovery().state()) && "PARTIAL".equals(completed.getStatus()),
                    "Pending independent work or a skipped assessment became successful completion");
            check(completed.getRecovery().completedCalls() == 1 && completed.getRecovery().attemptedCalls() == 3
                            && completed.getRecovery().openQuestions().size() == 1
                            && completed.getRecovery().openQuestions().getFirst().skipped(),
                    "Question state or attempt accounting changed during local completion");
            check(completed.getRawScores().equals(Map.of("BP-1017", .125))
                            && "Original local assessment".equals(completed.getReasons().get("BP-1017")),
                    "Local completion replaced its node evidence with journal details");
            noQuestionPayloadReads(db, "Local completion");
            noQuestionPayloadWrites(db, "Local completion");
        }
    }

    public static void completionMarksUncertainWithoutPayloadReads() {
        try (var db = new Database()) {
            db.question("pending", "ATTEMPT", 3, 1, null);
            String[] original = db.transaction(em -> {
                var q = em.find(AnalysisQuestionCheckpoint.class, db.id + ":pending"); return new String[]{q.prompt, q.detailJson};
            });
            var result = new AnalysisResult(Map.of(), List.of()); result.setStatus("SUCCESS");
            var completed = db.transaction(em -> {
                em.find(AnalysisContinuationRun.class, db.id); // the production cancellation path owns its run before its questions
                em.find(AnalysisQuestionCheckpoint.class, db.id + ":pending"); // already managed questions must not remain stale
                db.resetObservations();
                var value = db.store(em).complete(db.claim(), result, List.of());
                noQuestionPayloadReads(db, "Uncertain completion");
                noQuestionPayloadWrites(db, "Uncertain completion");
                check("FAILED".equals(em.find(AnalysisQuestionCheckpoint.class, db.id + ":pending").state),
                        "The persistence context hid an uncertain outcome after completion");
                return value;
            });
            check("PAUSED".equals(completed.getRecovery().state()) && "PARTIAL".equals(completed.getStatus()),
                    "An interrupted provider attempt must pause for an explicit decision");
            var open = completed.getRecovery().openQuestions().getFirst();
            check(open.outcomeUncertain() && open.attempts() == 3 && !open.skipped(),
                    "Uncertain outcome or original attempt count was lost");
            db.transaction(em -> {
                var q = em.find(AnalysisQuestionCheckpoint.class, db.id + ":pending");
                check("FAILED".equals(q.state) && q.error.startsWith("OUTCOME_UNCERTAIN:") && q.attempts == 3
                                && original[0].equals(q.prompt) && original[1].equals(q.detailJson),
                        "Uncertain completion rewrote the original provider evidence");
                return null;
            });
        }
    }

    public static void clusterCompletionReadsRequiredDetailsOnly() {
        try (var db = new Database()) {
            db.question("a-accepted", "SUCCESS", 1, 1, null);
            db.question("b-accepted", "SUCCESS", 1, 2, null);
            db.question("Rrelation", "SUCCESS", 1, 3, null);
            db.question("failed", "FAILED", 1, 4, "invalid evidence");
            db.transaction(em -> {
                em.find(AnalysisContinuationRun.class, db.id).clusterOperationId = "operation";
                var first = new LlmCallDetail(); first.setScores(Map.of("BP-1017", .125, "BP-1529", 32.5));
                first.setReasons(Map.of("BP-1017", "First accepted fractional assessment", "BP-1529", "Older reason"));
                first.setRawResponse("Accepted raw evidence ".repeat(8_000));
                em.find(AnalysisQuestionCheckpoint.class, db.id + ":a-accepted").detailJson = db.mapper.writeValueAsString(first);
                var second = new LlmCallDetail(); second.setScores(Map.of("BP-1017", 90, "BP-1529", 80, "CP-1000", 12.5));
                second.setReasons(Map.of("BP-1017", "Later reason", "CP-1000", "Independent accepted assessment"));
                em.find(AnalysisQuestionCheckpoint.class, db.id + ":b-accepted").detailJson = db.mapper.writeValueAsString(second);
                // These are intentionally not category score payloads; filtering must happen before deserialization.
                em.find(AnalysisQuestionCheckpoint.class, db.id + ":Rrelation").detailJson = "opaque relation response";
                em.find(AnalysisQuestionCheckpoint.class, db.id + ":failed").detailJson = "invalid provider JSON";
                return null;
            });
            var result = new AnalysisResult(Map.of("BP-1529", 65.5), List.of()); result.setStatus("SUCCESS");
            result.setReasons(Map.of("BP-1529", "Current accepted assessment"));
            db.resetObservations();
            var completed = db.transaction(em -> db.store(em).complete(db.claim(), result, List.of()));
            check(completed.getRawScores().equals(Map.of("BP-1017", .125, "BP-1529", 65.5, "CP-1000", 12.5)),
                    "Cluster reconstruction changed fractional scores, ordering or current-result precedence");
            check("First accepted fractional assessment".equals(completed.getReasons().get("BP-1017"))
                            && "Current accepted assessment".equals(completed.getReasons().get("BP-1529"))
                            && "Independent accepted assessment".equals(completed.getReasons().get("CP-1000")),
                    "Cluster reconstruction changed the retained explanation precedence");
            check("PAUSED".equals(completed.getRecovery().state()) && completed.getRecovery().openQuestions().size() == 1,
                    "A failed cluster assessment was silently completed");
            var payloadReads = db.statements.stream().filter(sql -> sql.startsWith("select")
                    && sql.contains("from analysis_question_checkpoint ") && (sql.contains("prompt_text") || sql.contains("detail_json"))).toList();
            check(payloadReads.size() == 1 && payloadReads.getFirst().contains("node_codes")
                            && payloadReads.getFirst().contains("prompt_text") && payloadReads.getFirst().contains("detail_json")
                            && !payloadReads.getFirst().contains("attempt_owner") && !payloadReads.getFirst().contains("error_text")
                            && payloadReads.getFirst().contains("state='SUCCESS'")
                            && payloadReads.getFirst().contains("question_key not like 'R%'")
                            && payloadReads.getFirst().contains("order by"),
                    "Cluster completion fetched unrelated question payloads: " + payloadReads);
            check(db.factory.getStatistics().getEntityStatistics(AnalysisQuestionCheckpoint.class.getName()).getLoadCount() == 0,
                    "Cluster score reconstruction hydrated complete question entities");
            noQuestionPayloadWrites(db, "Cluster completion");
        }
    }

    private static AnalysisCheckpointSession.Question saveCorrectiveQuestion(Database db, String node, String provider,
            AnalysisContinuationStore.QuestionOwner owner) {
        var input = AnalysisCheckpointSession.evidenceQuestion("SOURCES", provider, List.of(node),
                node + " full source context ".repeat(8_000));
        var question = db.transaction(em -> owner == null ? db.store(em).prepare(db.claim(), input)
                : db.store(em).prepareDistributed(db.claim(), input, owner)).question();
        var detail = new LlmCallDetail(); detail.setRawResponse("Original unassessed reply ".repeat(8_000)); detail.setError("invalid source quote");
        db.transaction(em -> {
            if (owner == null) db.store(em).finish(db.claim(), question, "UNASSESSED", detail);
            else db.store(em).finishDistributed(db.claim(), question, "UNASSESSED", detail, owner);
            return null;
        });
        return question;
    }

    public static void supersessionUsesQuestionInputsOnly() { supersessionUsesQuestionInputsOnly(false); }

    public static void supersessionUsesQuestionInputsOnly(boolean filtered) {
        try (var db = new Database()) {
            var covered = saveCorrectiveQuestion(db, "UA-covered", "MOCK", null);
            var open = saveCorrectiveQuestion(db, "UA-open", "MOCK", null);
            var foreign = saveCorrectiveQuestion(db, "UA-covered", "FOREIGN", null);
            db.question("RwrongHash", "UNASSESSED", 1, 1, "retain wrong-hash evidence");
            db.question("category", "FAILED", 1, 2, "retain category failure");
            db.transaction(em -> {
                em.find(AnalysisQuestionCheckpoint.class, db.id + ":RwrongHash").nodeCodes = db.mapper.writeValueAsString(List.of("UA-covered"));
                return null;
            });
            Object[] original = db.transaction(em -> {
                var run = em.find(AnalysisContinuationRun.class, db.id);
                var q = em.find(AnalysisQuestionCheckpoint.class, db.id + ":" + covered.key());
                return new Object[]{q.prompt, q.detailJson, q.error, run.version, run.payloadCharacters};
            });
            db.transaction(em -> {
                em.find(AnalysisQuestionCheckpoint.class, db.id + ":" + covered.key());
                db.resetObservations();
                if (filtered) db.store(em).supersedeEvidence(db.claim(), "SOURCES", "MOCK", java.util.Set.of("UA-covered"), q -> {
                    check(q.equals(covered), "Metadata filtering changed the canonical full question identity");
                    return true;
                }, null);
                else db.store(em).supersedeEvidence(db.claim(), "SOURCES", "MOCK", q -> q.nodes().contains("UA-covered"), null);
                if (filtered) check(promptSelects(db) == 2,
                        "Only the matching node and its deliberately wrong-hash question may load prompts: " + db.statements);
                check(db.statements.stream().noneMatch(sql -> sql.startsWith("select") && sql.contains("detail_json")),
                        "Supersession read retained response bodies: " + db.statements);
                check(db.factory.getStatistics().getEntityLoadCount() == 0, "Supersession hydrated saved entities");
                noQuestionPayloadWrites(db, "Supersession");
                check(db.statements.stream().anyMatch(sql -> sql.startsWith("select")
                                && sql.contains("from analysis_continuation ") && sql.contains("for update")),
                        "Supersession lost the execution-owner row lock");
                check("SUPERSEDED".equals(em.find(AnalysisQuestionCheckpoint.class, db.id + ":" + covered.key()).state),
                        "A previously managed question hid its supersession");
                return null;
            });
            db.transaction(em -> {
                var run = em.find(AnalysisContinuationRun.class, db.id);
                var q = em.find(AnalysisQuestionCheckpoint.class, db.id + ":" + covered.key());
                check("SUPERSEDED".equals(q.state) && q.attempts == 1 && original[0].equals(q.prompt)
                                && original[1].equals(q.detailJson) && original[2].equals(q.error),
                        "Supersession changed the original failed evidence or attempt count");
                check(run.version == (long) original[3] + 1 && run.payloadCharacters == (long) original[4],
                        "Supersession lost revision fencing or changed the retained-text budget");
                for (String key : List.of(open.key(), foreign.key(), "RwrongHash"))
                    check("UNASSESSED".equals(em.find(AnalysisQuestionCheckpoint.class, db.id + ":" + key).state),
                            "Supersession crossed its evidence, input hash or provider scope: " + key);
                check("FAILED".equals(em.find(AnalysisQuestionCheckpoint.class, db.id + ":category").state),
                        "A relation correction changed category evidence");
                return null;
            });
        }
    }

    private static long promptSelects(Database db) {
        return db.statements.stream().filter(sql -> sql.startsWith("select")
                && sql.contains("from analysis_question_checkpoint ")
                && sql.substring(0, sql.indexOf(" from ")).contains("prompt_text")).count();
    }

    public static void unrelatedSupersessionUsesMetadataOnly() {
        try (var db = new Database()) {
            var open = saveCorrectiveQuestion(db, "UA-open", "MOCK", null);
            saveCorrectiveQuestion(db, "UA-covered", "FOREIGN", null);
            db.question("Rlegacy", "READY", 0, 0, null);
            db.question("Rbatch", "SKIPPED", 1, 1, "keep the complete batch open");
            db.question("category", "FAILED", 1, 1, "keep classification separate");
            db.transaction(em -> {
                em.find(AnalysisQuestionCheckpoint.class, db.id + ":Rbatch").nodeCodes =
                        db.mapper.writeValueAsString(List.of("UA-covered", "UA-open"));
                em.find(AnalysisQuestionCheckpoint.class, db.id + ":category").nodeCodes =
                        db.mapper.writeValueAsString(List.of("UA-covered"));
                return null;
            });
            long[] original = db.transaction(em -> {
                var run = em.find(AnalysisContinuationRun.class, db.id);
                return new long[]{run.version, run.payloadCharacters};
            });
            var inspected = new ArrayList<AnalysisCheckpointSession.Question>();
            db.resetObservations();
            db.transaction(em -> {
                db.store(em).supersedeEvidence(db.claim(), "SOURCES", "MOCK", java.util.Set.of("UA-covered"), q -> {
                    inspected.add(q); return false;
                }, null);
                return null;
            });
            check(promptSelects(db) == 0, "Unrelated open questions loaded their prompt LOBs: " + db.statements);
            check(db.statements.stream().noneMatch(sql -> sql.startsWith("select") && sql.contains("detail_json")),
                    "Metadata filtering loaded response evidence");
            check(inspected.isEmpty(), "An unrelated question reached the full prompt/hash predicate");
            check(db.factory.getStatistics().getEntityLoadCount() == 0, "Metadata filtering hydrated checkpoint entities");
            noQuestionPayloadWrites(db, "Singleton preselection");
            check(db.statements.stream().anyMatch(sql -> sql.startsWith("select")
                            && sql.contains("from analysis_continuation ") && sql.contains("for update")),
                    "Metadata preselection bypassed the current execution lock");
            db.transaction(em -> {
                var run = em.find(AnalysisContinuationRun.class, db.id);
                check(run.version == original[0] && run.payloadCharacters == original[1],
                        "An unrelated scan changed revision or payload accounting");
                check("UNASSESSED".equals(em.find(AnalysisQuestionCheckpoint.class, db.id + ":" + open.key()).state),
                        "An unrelated question was retired");
                check("SKIPPED".equals(em.find(AnalysisQuestionCheckpoint.class, db.id + ":Rbatch").state),
                        "A multi-node batch was treated as a singleton correction");
                return null;
            });
        }
    }

    public static void filteredSupersessionDecodesOnlyRelevantPrompts(boolean relevant) {
        try (var db = new Database()) {
            var question = saveCorrectiveQuestion(db, "UA-open", "MOCK", null);
            db.transaction(em -> {
                em.find(AnalysisQuestionCheckpoint.class, db.id + ":" + question.key()).prompt = "invalid encoded prompt";
                return null;
            });
            db.resetObservations();
            Runnable scan = () -> db.transaction(em -> {
                db.store(em).supersedeEvidence(db.claim(), "SOURCES", "MOCK",
                        java.util.Set.of(relevant ? "UA-open" : "UA-covered"), q -> false, null);
                return null;
            });
            if (relevant) {
                try { scan.run(); throw new AssertionError("A relevant corrupt prompt was trusted"); }
                catch (IllegalArgumentException expected) { }
                check(promptSelects(db) == 1, "A relevant prompt must pass through the real payload decoder");
            } else {
                scan.run();
                check(promptSelects(db) == 0, "An unrelated encoded prompt was loaded despite its node metadata");
            }
            noQuestionPayloadWrites(db, "Filtered corrupt-prompt read");
            db.transaction(em -> {
                check("UNASSESSED".equals(em.find(AnalysisQuestionCheckpoint.class, db.id + ":" + question.key()).state),
                        "A corrupt or unrelated prompt was silently superseded"); return null;
            });
        }
    }

    public static void filteredSupersessionRejectsCorruptNodeMetadata() {
        try (var db = new Database()) {
            var question = saveCorrectiveQuestion(db, "UA-open", "MOCK", null);
            for (String invalid : List.of("not JSON", "{}", "[42]",
                    "{\"format\":\"analysis-question-payload-v999\",\"nodes\":[\"UA-open\"]}")) {
                db.transaction(em -> {
                    em.find(AnalysisQuestionCheckpoint.class, db.id + ":" + question.key()).nodeCodes = invalid;
                    return null;
                });
                db.resetObservations();
                try {
                    db.transaction(em -> {
                        db.store(em).supersedeEvidence(db.claim(), "SOURCES", "MOCK", java.util.Set.of("UA-covered"), q -> false, null);
                        return null;
                    });
                    throw new AssertionError("Corrupt node metadata was treated as a safe exclusion");
                } catch (IllegalArgumentException expected) { }
                check(promptSelects(db) == 0, "Corrupt metadata must fail before loading any prompt: " + db.statements);
                noQuestionPayloadWrites(db, "Corrupt-node preselection");
            }
        }
    }

    public static void compatibleSupersessionUsesQuestionInputsOnly() {
        try (var db = new Database()) {
            db.transaction(em -> { em.find(AnalysisContinuationRun.class, db.id).clusterOperationId = "operation"; return null; });
            var owner = new AnalysisContinuationStore.QuestionOwner("operation", "task", 1,
                    UUID.randomUUID().toString(), "RELATION_ANALYSIS:UA");
            var question = saveCorrectiveQuestion(db, "UA-covered", "MOCK", owner);
            db.resetObservations();
            db.transaction(em -> { db.store(em).supersedeEvidence(db.claim(), "SOURCES", "MOCK", q -> true, null); return null; });
            check(db.statements.stream().noneMatch(sql -> sql.startsWith("select") && sql.contains("detail_json")),
                    "Compatible distributed supersession read retained response bodies: " + db.statements);
            check(db.factory.getStatistics().getEntityLoadCount() == 0, "Compatible supersession hydrated saved entities");
            noQuestionPayloadWrites(db, "Compatible supersession");
            db.transaction(em -> {
                check("SUPERSEDED".equals(em.find(AnalysisQuestionCheckpoint.class, db.id + ":" + question.key()).state),
                        "The unique compatible distributed question was not superseded"); return null;
            });
        }
    }

    public static void supersessionBookkeepingRemainsAtomic() {
        try (var db = new Database()) {
            var question = saveCorrectiveQuestion(db, "UA-covered", "MOCK", null);
            long version = db.transaction(em -> em.find(AnalysisContinuationRun.class, db.id).version);
            try {
                db.transaction(em -> {
                    var intercepted = (EntityManager) java.lang.reflect.Proxy.newProxyInstance(EntityManager.class.getClassLoader(),
                            new Class<?>[]{EntityManager.class}, (proxy, method, args) -> {
                                if ("createQuery".equals(method.getName()) && args != null && args[0] instanceof String sql
                                        && sql.startsWith("update AnalysisContinuationRun"))
                                    throw new IllegalStateException("injected supersession bookkeeping failure");
                                try { return method.invoke(em, args); }
                                catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                            });
                    db.store(intercepted).supersedeEvidence(db.claim(), "SOURCES", "MOCK", q -> true, null); return null;
                });
                throw new AssertionError("Injected supersession failure was ignored");
            } catch (IllegalStateException expected) {
                check(expected.getMessage().equals("injected supersession bookkeeping failure"), "Unexpected supersession failure");
            }
            db.transaction(em -> {
                var run = em.find(AnalysisContinuationRun.class, db.id);
                var q = em.find(AnalysisQuestionCheckpoint.class, db.id + ":" + question.key());
                check("UNASSESSED".equals(q.state) && q.attempts == 1
                                && run.version == version,
                        "Failed supersession published a question state without its owner revision");
                return null;
            });
        }
    }

    private static AnalysisResult completeWithFallback(AnalysisContinuationStore store,
            AnalysisContinuationStore.Claim claim, AnalysisResult result,
            java.util.function.Supplier<List<com.taxonomy.dto.TaxonomyNodeDto>> fallback) {
        return store.completeWithFallback(claim, result, fallback);
    }

    public static void completionUsesStoredCatalogue() {
        try (var db = new Database()) {
            for (boolean empty : List.of(false, true)) {
                var node = new com.taxonomy.dto.TaxonomyNodeDto(); node.setCode("BP-1000"); node.setNameEn("Frozen root");
                var tree = empty ? List.<com.taxonomy.dto.TaxonomyNodeDto>of() : List.of(node);
                var seed = new AnalysisResult(Map.of(), tree); seed.setStatus("IN_PROGRESS");
                db.transaction(em -> {
                    var run = em.find(AnalysisContinuationRun.class, db.id);
                    run.resultJson = db.mapper.writeValueAsString(seed); run.state = "RUNNING";
                    return null;
                });
                var result = new AnalysisResult(Map.of(), List.of()); result.setStatus("SUCCESS");
                var completed = db.transaction(em -> completeWithFallback(db.store(em), db.claim(), result,
                        () -> { throw new AssertionError("An admitted catalogue must not be read again"); }));
                check(db.mapper.writeValueAsString(completed.getTree()).equals(db.mapper.writeValueAsString(tree)),
                        "The complete stored catalogue, including an empty one, must remain authoritative");
            }
        }
    }

    public static void completionUsesMissingCatalogueFallback() {
        try (var db = new Database()) {
            for (String missing : java.util.Arrays.asList(null, "{\"status\":\"IN_PROGRESS\"}")) {
                db.transaction(em -> {
                    var run = em.find(AnalysisContinuationRun.class, db.id);
                    run.resultJson = missing; run.state = "RUNNING"; return null;
                });
                var node = new com.taxonomy.dto.TaxonomyNodeDto(); node.setCode("BP-1000"); node.setNameEn("Legacy fallback");
                var calls = new java.util.concurrent.atomic.AtomicInteger();
                var result = new AnalysisResult(Map.of(), List.of()); result.setStatus("SUCCESS");
                var completed = db.transaction(em -> completeWithFallback(db.store(em), db.claim(), result,
                        () -> { calls.incrementAndGet(); return List.of(node); }));
                check(calls.get() == 1 && "Legacy fallback".equals(completed.getTree().getFirst().getNameEn()),
                        "An absent legacy seed needs its fallback exactly once");
                check(completed.getAnalysisCoverage().nodes().containsKey("BP-1000"),
                        "The supplied legacy catalogue was lost before coverage derivation");
            }
        }
    }

    public static void completionRejectsClaimBeforeCatalogueFallback() {
        try (var db = new Database()) {
            db.transaction(em -> { em.find(AnalysisContinuationRun.class, db.id).resultJson = null; return null; });
            expectConflict(() -> db.transaction(em -> completeWithFallback(db.store(em),
                    new AnalysisContinuationStore.Claim(db.id, "foreign-claim", null), new AnalysisResult(Map.of(), List.of()),
                    () -> { throw new AssertionError("A superseded claim must not reach the catalogue fallback"); })));
        }
    }

    public static void blockingBudgetCheckDoesNotLoadSavedPayloads() {
        try (var db = new Database()) {
            db.question("failed", "FAILED", 1, 1, "invalid reply");
            db.resetObservations();
            check(db.transaction(em -> db.store(em).hasBlockingBudgetQuestions(db.id, scope())), "Failed evidence must block continuation");
            check(db.statements.stream().noneMatch(sql -> sql.contains("result_json") || sql.contains("request_json")
                            || sql.contains("cluster_source_json") || sql.contains("prompt_text") || sql.contains("detail_json")
                            || sql.contains("node_codes")),
                    "Budget eligibility fetched payloads instead of authority and states: " + db.statements);
            check(db.factory.getStatistics().getEntityLoadCount() == 0, "Budget eligibility hydrated saved entities");
            var ownerReads = db.statements.stream().filter(sql -> sql.startsWith("select")
                    && sql.contains("from analysis_continuation ")).toList();
            check(ownerReads.size() == 1 && ownerReads.getFirst().contains("for update"),
                    "Budget eligibility must retain its run row lock: " + ownerReads);
            check(db.statements.stream().noneMatch(sql -> sql.startsWith("update") || sql.startsWith("insert")),
                    "Observing eligibility changed the journal");
        }
    }

    public static void blockingBudgetCheckPreservesScopeAndStates() {
        try (var db = new Database()) {
            check(!db.transaction(em -> db.store(em).hasBlockingBudgetQuestions(db.id, scope())), "An empty journal has no blocking question");
            db.question("question", "READY", 1, 1, null);
            var allowed = List.of("SUCCESS", "CLARIFICATION", "DECOMPOSED", "READY", "SUPERSEDED");
            var states = new ArrayList<>(allowed);
            states.addAll(List.of("FAILED", "SKIPPED", "UNASSESSED", "ATTEMPT", "UNKNOWN_STATE"));
            for (String state : states) {
                db.transaction(em -> { em.find(AnalysisQuestionCheckpoint.class, db.id + ":question").state = state; return null; });
                check(db.transaction(em -> db.store(em).hasBlockingBudgetQuestions(db.id, scope())) == !allowed.contains(state),
                        "Changed blocking semantics for " + state);
            }
            for (var foreign : List.of(new WorkspaceContext("foreign", "workspace", "draft", "repository"),
                    new WorkspaceContext("owner", "foreign", "draft", "repository"),
                    new WorkspaceContext("owner", "workspace", "foreign", "repository"),
                    new WorkspaceContext("owner", "workspace", "draft", "foreign"))) {
                expectStatus(404, () -> db.transaction(em -> db.store(em).hasBlockingBudgetQuestions(db.id, foreign)));
            }
            expectStatus(404, () -> db.transaction(em -> db.store(em).hasBlockingBudgetQuestions(UUID.randomUUID().toString(), scope())));
            expectStatus(400, () -> db.transaction(em -> db.store(em).hasBlockingBudgetQuestions("invalid", scope())));
            expectStatus(400, () -> db.transaction(em -> db.store(em).hasBlockingBudgetQuestions("01234567-89AB-4CDE-8FAB-0123456789AB", scope())));
            expectStatus(403, () -> db.transaction(em -> db.store(em).hasBlockingBudgetQuestions(db.id, null)));
            for (var incomplete : List.of(new WorkspaceContext("", "workspace", "draft", "repository"),
                    new WorkspaceContext("owner", " ", "draft", "repository"),
                    new WorkspaceContext("owner", "workspace", "", "repository"))) {
                expectStatus(403, () -> db.transaction(em -> db.store(em).hasBlockingBudgetQuestions(db.id, incomplete)));
            }
            db.transaction(em -> {
                var run = em.find(AnalysisContinuationRun.class, db.id);
                check(run.version == 0 && db.savedResult.equals(run.resultJson) && db.token.equals(run.claimToken),
                        "Eligibility observation changed a revision, result or execution claim");
                return null;
            });
        }
    }

    public static void recoveryViewReadsQuestionMetadataOnce() {
        try (var db = new Database()) {
            db.question("success", "SUCCESS", 2, 3, null);
            db.question("b-failed", "FAILED", 3, 1, "OUTCOME_UNCERTAIN: original failure");
            db.question("a-skipped", "SKIPPED", 1, 1, "left open deliberately");
            db.question("c-unassessed", "UNASSESSED", 0, 2, "invalid scope");
            db.question("d-clarification", "CLARIFICATION", 1, 2, null);
            db.question("ready", "READY", 2, 4, null);
            db.question("decomposed", "DECOMPOSED", 3, 4, null);
            db.question("superseded", "SUPERSEDED", 1, 4, null);
            db.question("attempt", "ATTEMPT", 1, 4, null);
            db.transaction(em -> {
                var run = em.find(AnalysisContinuationRun.class, db.id); run.currentNode = "BP-1017";
                run.requestJson = db.mapper.writeValueAsString(new com.taxonomy.dto.AnalysisRequest("Original requirement"));
                return null;
            });
            db.resetObservations();
            var snapshot = db.transaction(em -> db.store(em).read(db.id, scope()));
            var view = snapshot.recovery();
            check(view.equals(snapshot.result().getRecovery()), "Snapshot and embedded result disagree about recovery");
            check(view.completedCalls() == 1 && view.attemptedCalls() == 14 && "RUNNING".equals(view.state())
                            && "BP-1017".equals(view.currentNode()), "Recovery metadata changed");
            var open = view.openQuestions();
            check(open.stream().map(com.taxonomy.dto.AnalysisRecoveryView.OpenQuestion::key).toList()
                            .equals(List.of("a-skipped", "b-failed", "c-unassessed", "d-clarification")),
                    "Open states or deterministic start/key order changed: " + open);
            check(open.getFirst().skipped() && !open.getFirst().outcomeUncertain()
                            && "left open deliberately".equals(open.getFirst().error())
                            && !open.get(1).skipped() && open.get(1).outcomeUncertain() && open.get(1).attempts() == 3
                            && "OUTCOME_UNCERTAIN: original failure".equals(open.get(1).error())
                            && open.get(2).attempts() == 0 && open.get(3).error() == null,
                    "Question decisions, attempts or uncertainty were lost");
            check(open.stream().allMatch(question -> question.nodes().equals(List.of("BP-1017", "BP-1529"))),
                    "Question scope changed");
            check("Original requirement".equals(snapshot.request().getBusinessText())
                            && "retained evidence ".repeat(32_000).equals(snapshot.result().getErrorMessage()),
                    "Explicit read must still restore the complete original request and result");
            var questionReads = db.statements.stream().filter(sql -> sql.startsWith("select")
                    && sql.contains("from analysis_question_checkpoint ")).toList();
            check(questionReads.size() == 1, "A snapshot must read its question metadata once: " + questionReads);
            check(questionReads.stream().noneMatch(sql -> sql.contains("prompt_text") || sql.contains("detail_json")),
                    "Recovery metadata fetched opaque prompt/answer payloads: " + questionReads);
            check(db.factory.getStatistics().getEntityStatistics(AnalysisQuestionCheckpoint.class.getName()).getLoadCount() == 0,
                    "Recovery metadata hydrated full question entities");
            check(db.statements.stream().anyMatch(sql -> sql.contains("result_json") && sql.contains("request_json")),
                    "Full snapshot read unexpectedly omitted its durable result/request");
            var detail = db.transaction(em -> db.store(em).detail(db.id, "b-failed", scope()));
            check("original source and candidate context ".repeat(8_000).equals(detail.getPrompt())
                            && "durable answer ".repeat(16_000).equals(detail.getRawResponse()),
                    "Metadata observation changed the separately accessible full question evidence");
        }
    }

    public static void recoveryReadStillExpiresAnUncertainAttempt() {
        try (var db = new Database()) {
            db.question("attempt", "ATTEMPT", 2, 1, null);
            db.question("success", "SUCCESS", 1, 2, null);
            db.transaction(em -> { em.find(AnalysisContinuationRun.class, db.id).claimUntil = System.currentTimeMillis() - 1; return null; });
            var snapshot = db.transaction(em -> db.store(em).read(db.id, scope()));
            var view = snapshot.recovery();
            check("PAUSED".equals(view.state()) && view.completedCalls() == 1 && view.attemptedCalls() == 3,
                    "Expired provider work must pause without inventing an attempt");
            check(view.openQuestions().size() == 1 && "attempt".equals(view.openQuestions().getFirst().key())
                            && view.openQuestions().getFirst().outcomeUncertain(), "Uncertain in-flight evidence disappeared");
            check(view.equals(snapshot.result().getRecovery()), "Expired snapshot has stale embedded recovery");
            db.transaction(em -> {
                var run = em.find(AnalysisContinuationRun.class, db.id);
                var uncertain = em.find(AnalysisQuestionCheckpoint.class, db.id + ":attempt");
                check(run.claimToken == null && "PAUSED".equals(run.state) && db.savedResult.equals(run.resultJson),
                        "Expiry changed the saved analysis or retained a provider claim");
                check("FAILED".equals(uncertain.state) && uncertain.attempts == 2
                                && "SUCCESS".equals(em.find(AnalysisQuestionCheckpoint.class, db.id + ":success").state),
                        "Expiry changed accepted evidence or question attempts");
                return null;
            });
        }
    }

    private static void expectStatus(int status, Runnable operation) {
        try { operation.run(); throw new AssertionError("Invalid scope/id was admitted"); }
        catch (org.springframework.web.server.ResponseStatusException expected) {
            check(expected.getStatusCode().value() == status, "Expected " + status + " but received " + expected.getStatusCode());
        }
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
                check(run.payloadCharacters == checkpoint.prompt.length() + checkpoint.detailJson.length(), "Stored evidence budget not updated");
                check(question.prompt().equals(db.payloads.decodePrompt(db.payloads.parseLayout(checkpoint.nodeCodes), checkpoint.prompt)),
                        "The original checkpoint input was changed by its persisted representation");
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
    public static void fractionalCheckpointSurvivesIndependentSessions() {
        try (var db = new Database()) {
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            AnalysisCheckpointSession.Store durable = new AnalysisCheckpointSession.Store() {
                @Override public AnalysisCheckpointSession.Checkpoint prepare(AnalysisCheckpointSession.Question question) {
                    return db.transaction(em -> db.store(em).prepare(db.claim(), question));
                }
                @Override public void finish(AnalysisCheckpointSession.Question question, String state, LlmCallDetail detail) {
                    db.transaction(em -> { db.store(em).finish(db.claim(), question, state, detail); return null; });
                }
                @Override public void checkActive() {
                    check("RUNNING".equals(db.transaction(em -> db.store(em).state(db.claim()))), "claim lost");
                }
            };
            for (int iteration = 0; iteration < 2; iteration++) {
                try (var session = new AnalysisCheckpointSession(durable)) {
                    var accepted = AnalysisCheckpointSession.evaluate("CATEGORY", "MOCK", List.of("BP"), "decimal prompt", () -> {
                        calls.incrementAndGet();
                        return db.mapper.readValue("{\"scores\":{\"BP\":0.125},\"rawResponse\":\"verbatim reply\"}", LlmCallDetail.class);
                    });
                    check(accepted.getScores().get("BP") == .125, "durable fractional score changed");
                    check("verbatim reply".equals(accepted.getRawResponse()), "raw reply changed");
                    var failed = AnalysisCheckpointSession.evaluate("CATEGORY", "MOCK", List.of("CP"), "failed prompt", () -> {
                        calls.incrementAndGet();
                        var detail = new LlmCallDetail(); detail.setScores(Map.of("CP", 0));
                        detail.setRawResponse("broken JSON"); detail.setError("INVALID_JSON"); return detail;
                    });
                    check(failed.getScores().isEmpty(), "technical failure became zero evidence");
                    check("broken JSON".equals(failed.getRawResponse()), "failed evidence was lost");
                }
            }
            check(calls.get() == 2, "a second session repeated already durable requests");
            db.transaction(em -> {
                check(db.savedResult.equals(em.find(AnalysisContinuationRun.class, db.id).resultJson),
                        "checkpoint replay replaced the frozen snapshot");
                return null;
            });
        }
    }

    public static void supersessionIsNamespaceBound() { supersessionIsNamespaceBound(false); }

    public static void supersessionIsNamespaceBound(boolean filtered) {
        try (var db = new Database()) {
            db.transaction(em -> { em.find(AnalysisContinuationRun.class, db.id).clusterOperationId = "operation"; return null; });
            var first = new AnalysisContinuationStore.QuestionOwner("operation", "first-task", 1,
                    UUID.randomUUID().toString(), "RELATION_ANALYSIS:UA");
            var second = new AnalysisContinuationStore.QuestionOwner("operation", "second-task", 1,
                    UUID.randomUUID().toString(), "RELATION_ANALYSIS:IP");
            var input = AnalysisCheckpointSession.evidenceQuestion("SOURCES", "MOCK", List.of("UA-first"), "corrective prompt");
            var detail = new LlmCallDetail(); detail.setRawResponse("failed raw response"); detail.setError("invalid quote");
            var questions = new ArrayList<AnalysisCheckpointSession.Question>();
            for (var owner : List.of(first, second)) {
                var question = db.transaction(em -> db.store(em).prepareDistributed(db.claim(), input, owner)).question();
                questions.add(question);
                db.transaction(em -> { db.store(em).finishDistributed(db.claim(), question, "UNASSESSED", detail, owner); return null; });
            }
            db.transaction(em -> {
                if (filtered) db.store(em).supersedeEvidence(db.claim(), "SOURCES", "MOCK", java.util.Set.of("UA-first"), question -> true, first);
                else db.store(em).supersedeEvidence(db.claim(), "SOURCES", "MOCK", question -> true, first);
                return null;
            });
            expectConflict(() -> db.transaction(em -> {
                if (filtered) db.store(em).supersedeEvidence(db.claim(), "SOURCES", "MOCK", java.util.Set.of("UA-first"), question -> true, null);
                else db.store(em).supersedeEvidence(db.claim(), "SOURCES", "MOCK", question -> true, null);
                return null;
            })); // Local compatibility must not choose between two identical distributed scopes.
            db.transaction(em -> {
                var covered = em.find(AnalysisQuestionCheckpoint.class, db.id + ":" + questions.getFirst().key());
                var independent = em.find(AnalysisQuestionCheckpoint.class, db.id + ":" + questions.getLast().key());
                check("SUPERSEDED".equals(covered.state), "Current worker's obsolete correction remained open");
                check("UNASSESSED".equals(independent.state), "Another worker namespace was changed");
                check(covered.attempts == 1 && independent.attempts == 1, "Retirement invented an assessment attempt");
                var layout = db.payloads.parseLayout(covered.nodeCodes);
                String prompt = db.payloads.decodePrompt(layout, covered.prompt);
                check("failed raw response".equals(db.mapper.readValue(
                                db.payloads.decodeDetail(layout, covered.detailJson, prompt), LlmCallDetail.class).getRawResponse()),
                        "Retirement rewrote failed raw evidence");
                return null;
            });
        }
    }

    public static void supersessionIsClaimBound() { supersessionIsClaimBound(false); }

    public static void supersessionIsClaimBound(boolean filtered) {
        try (var db = new Database()) {
            var question = AnalysisCheckpointSession.evidenceQuestion("SOURCES", "MOCK", List.of("UA-first"), "corrective prompt");
            db.transaction(em -> db.store(em).prepare(db.claim(), question));
            var detail = new LlmCallDetail(); detail.setRawResponse("failed raw response"); detail.setError("invalid quote");
            db.transaction(em -> { db.store(em).finish(db.claim(), question, "UNASSESSED", detail); return null; });
            var foreign = new AnalysisContinuationStore.Claim(db.id, UUID.randomUUID().toString(), null);
            java.util.function.Consumer<AnalysisContinuationStore.Claim> scan = claim -> db.transaction(em -> {
                if (filtered) db.store(em).supersedeEvidence(claim, "SOURCES", "MOCK", java.util.Set.of(), q -> true, null);
                else db.store(em).supersedeEvidence(claim, "SOURCES", "MOCK", q -> true, null);
                return null;
            });
            expectConflict(() -> scan.accept(foreign));
            db.transaction(em -> { em.find(AnalysisContinuationRun.class, db.id).state = "CANCELLED"; return null; });
            expectConflict(() -> scan.accept(db.claim()));
            db.transaction(em -> {
                var run = em.find(AnalysisContinuationRun.class, db.id); run.state = "RUNNING"; run.claimUntil = System.currentTimeMillis() - 1; return null;
            });
            expectConflict(() -> scan.accept(db.claim()));
            db.transaction(em -> {
                check("UNASSESSED".equals(em.find(AnalysisQuestionCheckpoint.class, db.id + ":" + question.key()).state),
                        "A stale or cancelled claim retired the correction");
                return null;
            });
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
            case "budget-metadata" -> blockingBudgetCheckDoesNotLoadSavedPayloads();
            case "budget-scope" -> blockingBudgetCheckPreservesScopeAndStates();
            case "view-metadata" -> recoveryViewReadsQuestionMetadataOnce();
            case "view-expiry" -> recoveryReadStillExpiresAnUncertainAttempt();
            case "fractional" -> fractionalCheckpointSurvivesIndependentSessions();
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
