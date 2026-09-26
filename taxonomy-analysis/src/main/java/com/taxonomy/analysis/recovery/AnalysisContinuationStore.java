package com.taxonomy.analysis.recovery;

import com.taxonomy.dto.*;
import com.taxonomy.workspace.service.WorkspaceContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** Short transactions around checkpoints. Provider I/O is never executed under a database lock. */
@Service
public class AnalysisContinuationStore {
    private static final int MAX_CALLS = 4096;
    private static final int MAX_QUESTION_CHARACTERS = 2_000_000;
    private static final long MAX_RUN_CHARACTERS = 24_000_000;
    // Must exceed the existing evaluator's 30 minute active-work limit. No automatic replay on expiry.
    private static final long CLAIM_MILLIS = 35 * 60 * 1000L;
    private final EntityManager em;
    private final ObjectMapper mapper;
    public AnalysisContinuationStore(EntityManager em, ObjectMapper mapper) { this.em = em; this.mapper = mapper; }
    public record Claim(String id, String token, AnalysisResult completedResult) { }
    public record Snapshot(AnalysisRecoveryView recovery, AnalysisResult result, AnalysisRequest request) { }

    @Transactional
    public Claim begin(AnalysisRequest request, WorkspaceContext scope, String signature) {
        requireScope(scope);
        String id = requireId(request.getContinuationId());
        var run = em.find(AnalysisContinuationRun.class, id, LockModeType.PESSIMISTIC_WRITE);
        String action = Objects.toString(request.getContinuationAction(), "START");
        if (run == null) {
            if (!"START".equals(action)) throw conflict("The continuation no longer exists");
            run = new AnalysisContinuationRun();
            run.id = id; run.username = scope.username(); run.workspaceId = scope.workspaceId();
            run.repositoryId = scope.repositoryId(); run.branchName = scope.currentBranch();
            run.inputHash = signature; run.requestJson = mapper.writeValueAsString(request);
            run.state = "NEW";
            em.persist(run);
        } else {
            authorize(run, scope);
            if (!run.inputHash.equals(signature)) throw conflict(
                    "Requirement, catalogue, prompt, provider or policy changed; the saved answers cannot be reused in this context");
            expire(run);
            if ("START".equals(action)) {
                if (run.resultJson != null && !"RUNNING".equals(run.state))
                    return new Claim(id, null, withRecovery(readResult(run), run));
                throw conflict("This operation already exists. Observe it instead of starting duplicate work");
            }
            if (!Set.of("PAUSED", "COMPLETED_WITH_GAPS", "STOPPED").contains(run.state))
                throw conflict("The operation is not awaiting a continuation decision");
            if (request.getContinuationVersion() == null || request.getContinuationVersion() != run.version)
                throw conflict("A newer operation revision exists; refresh before deciding");
            var question = question(run, request.getContinuationQuestion());
            if ("CONTINUE".equals(action) && "STOPPED".equals(run.state)
                    && openQuestions(run).stream().allMatch(AnalysisRecoveryView.OpenQuestion::skipped)) {
                // No unresolved provider attempt is outstanding. Keep previously skipped areas open.
            } else {
            if (question == null || !Set.of("FAILED", "SKIPPED").contains(question.state))
                throw conflict("The selected question is not open");
            if ("RETRY".equals(action)) question.state = "READY";
            else if ("SKIP".equals(action) && "FAILED".equals(question.state)) question.state = "SKIPPED";
            else throw conflict("Unsupported continuation decision");
            }
        }
        run.state = "RUNNING"; run.claimToken = UUID.randomUUID().toString();
        run.claimUntil = System.currentTimeMillis() + CLAIM_MILLIS; run.updatedAt = System.currentTimeMillis();
        em.flush();
        return new Claim(id, run.claimToken, null);
    }

    @Transactional
    public AnalysisCheckpointSession.Checkpoint prepare(Claim claim, AnalysisCheckpointSession.Question request) {
        var run = ownedClaim(claim);
        var question = question(run, request.key());
        if (question != null) {
            if (!question.inputHash.equals(request.inputHash())) throw conflict("A saved question's input changed");
            if (!"READY".equals(question.state)) return checkpoint(question, request);
        } else {
            long count = em.createQuery("select count(q) from AnalysisQuestionCheckpoint q where q.run.id=:id", Long.class)
                    .setParameter("id", run.id).getSingleResult();
            if (count >= MAX_CALLS) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Continuation checkpoint limit reached; completed answers remain stored");
            question = new AnalysisQuestionCheckpoint();
            question.id = run.id + ":" + request.key(); question.run = run;
            question.questionKey = request.key(); question.inputHash = request.inputHash();
            question.provider = request.provider(); question.nodeCodes = mapper.writeValueAsString(request.nodes());
            question.state = "READY";
            em.persist(question);
        }
        question.attempts++; question.startedAt = System.currentTimeMillis(); question.state = "ATTEMPT";
        run.currentNode = String.join(", ", request.nodes());
        if (run.currentNode.length() > 320) run.currentNode = run.currentNode.substring(0, 317) + "...";
        run.updatedAt = System.currentTimeMillis();
        long retainedPrompt = question.prompt == null ? 0 : question.prompt.length();
        long nextPayload = run.payloadCharacters - retainedPrompt + request.prompt().length();
        if (request.prompt().length() > MAX_QUESTION_CHARACTERS || nextPayload > MAX_RUN_CHARACTERS) {
            question.state = "FAILED";
            question.error = "CHECKPOINT_INPUT_LIMIT: full prompt or total retained text exceeds the bounded checkpoint budget; "
                    + "it was not sent or silently shortened";
            run.state = "PAUSED";
        } else {
            question.prompt = request.prompt(); run.payloadCharacters = nextPayload;
        }
        em.flush();
        return checkpoint(question, request);
    }

    @Transactional
    public void finish(Claim claim, AnalysisCheckpointSession.Question request, String state, LlmCallDetail detail) {
        var run = em.find(AnalysisContinuationRun.class, claim.id(), LockModeType.PESSIMISTIC_WRITE);
        if (run == null || !Objects.equals(run.claimToken, claim.token())
                || !Set.of("RUNNING", "CANCELLED").contains(run.state)) throw conflict("Question claim was superseded");
        var question = question(run, request.key());
        if (question == null || !"ATTEMPT".equals(question.state)) throw conflict("Question no longer owns this attempt");
        String json = mapper.writeValueAsString(detail);
        long oldLength = question.detailJson == null ? 0 : question.detailJson.length();
        if (json.length() > MAX_QUESTION_CHARACTERS || run.payloadCharacters - oldLength + json.length() > MAX_RUN_CHARACTERS) {
            question.state = "FAILED"; question.error = "CHECKPOINT_STORAGE_LIMIT: response was not accepted; prior checkpoints remain intact";
            if (!"CANCELLED".equals(run.state)) run.state = "PAUSED";
            run.updatedAt = System.currentTimeMillis();
            // Do not pretend a result was durably cached. Signal the pause outside this transaction.
            em.flush(); return;
        }
        question.detailJson = json; question.state = state;
        question.error = bounded(detail.getError(), 2048);
        run.payloadCharacters += json.length() - oldLength;
        if ("FAILED".equals(state) && !"CANCELLED".equals(run.state)) run.state = "PAUSED";
        run.updatedAt = System.currentTimeMillis(); em.flush();
    }

    @Transactional(readOnly = true)
    public String state(Claim claim) {
        var run = em.find(AnalysisContinuationRun.class, claim.id());
        if (run == null || !Objects.equals(run.claimToken, claim.token())) return "CANCELLED";
        return run.state;
    }

    @Transactional
    public AnalysisResult complete(Claim claim, AnalysisResult result, List<TaxonomyNodeDto> tree) {
        var run = em.find(AnalysisContinuationRun.class, claim.id(), LockModeType.PESSIMISTIC_WRITE);
        if (run == null || !Objects.equals(run.claimToken, claim.token())) throw conflict("Operation claim was superseded");
        for (var question : questions(run)) if ("ATTEMPT".equals(question.state)) {
            question.state = "FAILED";
            question.error = "OUTCOME_UNCERTAIN: interrupted call; the provider may have processed it";
        }
        var open = openQuestions(run);
        if ("CANCELLED".equals(run.state)) result.setStatus("CANCELLED");
        else if (open.stream().anyMatch(q -> !q.skipped())) run.state = "PAUSED";
        else if ("ERROR".equals(result.getStatus()) || cooperativeInterruption(result.getErrorMessage())) {
            // Runtime guards use these typed-reason prefixes at the existing result boundary.
            // A prior skipped question must not disguise unfinished independent work as complete.
            run.state = "STOPPED";
        } else if (open.stream().anyMatch(AnalysisRecoveryView.OpenQuestion::skipped)) {
            run.state = "COMPLETED_WITH_GAPS"; result.setStatus("PARTIAL");
        } else run.state = "SUCCESS".equals(result.getStatus()) ? "COMPLETED" : "STOPPED";
        Map<String, String> missing = new LinkedHashMap<>();
        for (var question : open) for (String node : question.nodes()) missing.put(node,
                (question.skipped() ? "LEFT_OPEN:" : "FAILED:") + question.key());
        // This is evidence metadata, never a new official catalogue node or an invented score.
        if (result.getTree() == null || result.getTree().isEmpty()) result.setTree(tree);
        result.setAnalysisCoverage(AnalysisCoverage.derive(tree, result.getRawScores(), result.getScores(), missing));
        if (result.getArchitectureView() != null) {
            var view = result.getArchitectureView(); view.setAnalysisCoverage(result.getAnalysisCoverage());
            if (!open.isEmpty()) {
                var notes = new ArrayList<>(view.getNotes() == null ? List.<String>of() : view.getNotes());
                notes.add("PARTIAL ANALYSIS / TEILANALYSE: " + open.size()
                        + " unassessed question(s); missing assessments are not negative findings. "
                        + String.join("; ", missing.keySet()));
                view.setNotes(notes);
            }
        }
        run.claimUntil = 0; run.updatedAt = System.currentTimeMillis(); em.flush();
        result.setRecovery(view(run));
        // Tree is retained: restoring against a later catalogue must not relabel old evidence.
        String json = mapper.writeValueAsString(result);
        if (json.length() > 12_000_000) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Analysis result exceeds the durable result limit; individual answers remain stored");
        run.resultJson = json; em.flush();
        result.setRecovery(view(run));
        return result;
    }

    @Transactional
    public Snapshot read(String id, WorkspaceContext scope) {
        var run = scoped(id, scope); expire(run); em.flush();
        return new Snapshot(view(run), readResult(run), mapper.readValue(run.requestJson, AnalysisRequest.class));
    }
    @Transactional
    public Snapshot cancel(String id, WorkspaceContext scope) {
        var run = scoped(id, scope);
        if (!"COMPLETED".equals(run.state) && !"CANCELLED".equals(run.state)) {
            run.state = "CANCELLED"; run.updatedAt = System.currentTimeMillis();
        }
        em.flush();
        var result = readResult(run);
        if (result != null && "CANCELLED".equals(run.state)) {
            result.setStatus("CANCELLED"); result.setRecovery(view(run));
            run.resultJson = mapper.writeValueAsString(result); em.flush();
        }
        return new Snapshot(view(run), result, mapper.readValue(run.requestJson, AnalysisRequest.class));
    }
    @Transactional
    public LlmCallDetail detail(String id, String key, WorkspaceContext scope) {
        var run = scoped(id, scope);
        var question = question(run, key);
        if (question == null) throw notFound();
        var detail = question.detailJson == null ? new LlmCallDetail()
                : mapper.readValue(question.detailJson, LlmCallDetail.class);
        detail.setPrompt(question.prompt);
        if (detail.getError() == null) detail.setError(question.error);
        return detail;
    }
    @Transactional
    public void interrupted(Claim claim) {
        var run = em.find(AnalysisContinuationRun.class, claim.id(), LockModeType.PESSIMISTIC_WRITE);
        if (run != null && Objects.equals(run.claimToken, claim.token()) && "RUNNING".equals(run.state)) {
            run.state = "STOPPED"; run.claimUntil = 0; run.updatedAt = System.currentTimeMillis();
            markUncertain(run);
        }
    }
    private AnalysisContinuationRun scoped(String id, WorkspaceContext scope) {
        requireScope(scope);
        var run = em.find(AnalysisContinuationRun.class, requireId(id), LockModeType.PESSIMISTIC_WRITE);
        if (run == null) throw notFound(); authorize(run, scope); return run;
    }
    private AnalysisContinuationRun ownedClaim(Claim claim) {
        var run = em.find(AnalysisContinuationRun.class, claim.id(), LockModeType.PESSIMISTIC_WRITE);
        if (run == null || !Objects.equals(run.claimToken, claim.token()) || !"RUNNING".equals(run.state)
                || System.currentTimeMillis() > run.claimUntil) throw conflict("Operation no longer owns its execution claim");
        return run;
    }
    private AnalysisQuestionCheckpoint question(AnalysisContinuationRun run, String key) {
        return key == null ? null : em.find(AnalysisQuestionCheckpoint.class, run.id + ":" + key);
    }
    private AnalysisCheckpointSession.Checkpoint checkpoint(AnalysisQuestionCheckpoint q, AnalysisCheckpointSession.Question input) {
        return new AnalysisCheckpointSession.Checkpoint(input, q.state,
                q.detailJson == null ? null : mapper.readValue(q.detailJson, LlmCallDetail.class));
    }
    private List<AnalysisQuestionCheckpoint> questions(AnalysisContinuationRun run) {
        return em.createQuery("select q from AnalysisQuestionCheckpoint q where q.run.id=:id order by q.startedAt, q.questionKey", AnalysisQuestionCheckpoint.class)
                .setParameter("id", run.id).getResultList();
    }
    private List<AnalysisRecoveryView.OpenQuestion> openQuestions(AnalysisContinuationRun run) {
        return questions(run).stream().filter(q -> Set.of("FAILED", "SKIPPED").contains(q.state))
                .map(q -> new AnalysisRecoveryView.OpenQuestion(q.questionKey,
                        List.of(mapper.readValue(q.nodeCodes, String[].class)), q.error, q.attempts,
                        "SKIPPED".equals(q.state), q.error != null && q.error.startsWith("OUTCOME_UNCERTAIN"))).toList();
    }
    private AnalysisRecoveryView view(AnalysisContinuationRun run) {
        var all = questions(run);
        int complete = (int) all.stream().filter(q -> "SUCCESS".equals(q.state)).count();
        return new AnalysisRecoveryView(run.id, run.version, run.state, complete,
                all.stream().mapToInt(q -> q.attempts).sum(), run.currentNode, openQuestions(run));
    }
    private AnalysisResult readResult(AnalysisContinuationRun run) {
        return run.resultJson == null ? null : withRecovery(mapper.readValue(run.resultJson, AnalysisResult.class), run);
    }
    private AnalysisResult withRecovery(AnalysisResult result, AnalysisContinuationRun run) {
        result.setRecovery(view(run)); return result;
    }
    private static boolean cooperativeInterruption(String message) {
        return message != null && (message.startsWith("MEMORY_PRESSURE:")
                || message.startsWith("TIME_LIMIT:") || message.startsWith("CANCELLED:"));
    }
    private void expire(AnalysisContinuationRun run) {
        if ("RUNNING".equals(run.state) && run.claimUntil < System.currentTimeMillis()) {
            run.claimToken = null; run.state = "STOPPED"; markUncertain(run);
        }
    }
    private void markUncertain(AnalysisContinuationRun run) {
        for (var q : questions(run)) if ("ATTEMPT".equals(q.state)) {
            q.state = "FAILED"; q.error = "OUTCOME_UNCERTAIN: interrupted attempt; the provider may have processed it. Explicit retry or leave-open is required";
            run.state = "PAUSED";
        }
    }
    private static void requireScope(WorkspaceContext scope) {
        if (scope == null || scope.username() == null || scope.username().isBlank()
                || scope.workspaceId() == null || scope.workspaceId().isBlank()
                || scope.currentBranch() == null || scope.currentBranch().isBlank())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "An owned isolated workspace is required");
    }
    private static void authorize(AnalysisContinuationRun run, WorkspaceContext scope) {
        if (!run.username.equals(scope.username()) || !run.workspaceId.equals(scope.workspaceId())
                || !run.branchName.equals(scope.currentBranch()) || !run.repositoryId.equals(scope.repositoryId())) throw notFound();
    }
    private static String requireId(String id) {
        try { if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException(); return id; }
        catch (RuntimeException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A canonical continuation UUID is required"); }
    }
    private static String bounded(String text, int limit) { return text == null ? null : text.substring(0, Math.min(text.length(), limit)); }
    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis continuation not found"); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
}
