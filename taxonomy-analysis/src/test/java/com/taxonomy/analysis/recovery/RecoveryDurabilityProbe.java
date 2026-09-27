package com.taxonomy.analysis.recovery;

import com.taxonomy.analysis.controller.AnalysisApiController;
import com.taxonomy.analysis.controller.AnalysisSseEventMapper;
import com.taxonomy.analysis.service.*;
import com.taxonomy.analysis.usecase.*;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.*;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.lang.reflect.Proxy;
import java.util.*;

/** Executes the production controller/store with only persistence and provider boundaries replaced. */
public final class RecoveryDurabilityProbe {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final WorkspaceContext SCOPE = new WorkspaceContext("alice", "alice-ws", "draft");
    private static final String ID = "0dfb0635-516c-4a5d-95cd-fa07124b5ab9";
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static TaxonomyNodeDto node(String code) { var n = new TaxonomyNodeDto(); n.setCode(code); return n; }

    static final class Journal {
        AnalysisContinuationRun run = new AnalysisContinuationRun();
        boolean present = true;
        final List<AnalysisQuestionCheckpoint> questions = new ArrayList<>();
        final EntityManager em;
        Journal() {
            run.id = ID; run.username = SCOPE.username(); run.workspaceId = SCOPE.workspaceId();
            run.repositoryId = SCOPE.repositoryId(); run.branchName = SCOPE.currentBranch();
            run.claimToken = "claim"; run.state = "RUNNING"; run.requestJson = "{}";
            run.claimUntil = System.currentTimeMillis() + 60_000;
            var seed = new AnalysisResult(Map.of(), List.of(node("BP"), node("CP"), node("IP")));
            seed.setStatus("IN_PROGRESS"); run.resultJson = MAPPER.writeValueAsString(seed);
            var query = Proxy.newProxyInstance(TypedQuery.class.getClassLoader(), new Class<?>[]{TypedQuery.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "setParameter" -> proxy;
                        case "getResultList" -> questions;
                        case "getSingleResult" -> (long) questions.size();
                        default -> throw new AssertionError(method.getName());
                    });
            var ownerQuery = Proxy.newProxyInstance(TypedQuery.class.getClassLoader(), new Class<?>[]{TypedQuery.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "setParameter", "setLockMode" -> proxy;
                        case "getResultList" -> present ? Collections.singletonList(new Object[]{run.version,
                                run.payloadCharacters, run.currentNode, run.state, run.claimToken, run.claimUntil}) : List.of();
                        default -> throw new AssertionError(method.getName());
                    });
            em = (EntityManager) Proxy.newProxyInstance(EntityManager.class.getClassLoader(), new Class<?>[]{EntityManager.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "find" -> args[0] == AnalysisContinuationRun.class ? (present ? run : null)
                                : questions.stream().filter(q -> q.id.equals(args[1])).findFirst().orElse(null);
                        case "createQuery" -> args.length > 1 && args[1] == Object[].class ? ownerQuery : query;
                        case "flush" -> null;
                        case "persist" -> { run = (AnalysisContinuationRun) args[0]; present = true; yield null; }
                        default -> throw new AssertionError(method.getName());
                    });
        }
        void question(String key, String state, String code, int score) {
            var q = new AnalysisQuestionCheckpoint(); q.id = ID + ":" + key; q.run = run; q.questionKey = key;
            q.state = state; q.nodeCodes = MAPPER.writeValueAsString(List.of(code)); q.provider = "MOCK"; q.attempts = 1;
            if (!"ATTEMPT".equals(state)) {
                var detail = new LlmCallDetail(); detail.setScores(Map.of(code, score)); detail.setReasons(Map.of(code, "saved reason"));
                detail.setProvider("MOCK");
                if ("FAILED".equals(state)) detail.setError("invalid JSON with fallback zero");
                q.detailJson = MAPPER.writeValueAsString(detail);
            }
            questions.add(q);
        }
        AnalysisContinuationStore store() { return new AnalysisContinuationStore(em, MAPPER); }
        AnalysisContinuationStore.Claim claim() { return new AnalysisContinuationStore.Claim(ID, "claim", null); }
    }

    public static void cancelledEvidence() {
        var journal = new Journal(); journal.question("good", "SUCCESS", "BP", 70);
        journal.question("bad", "FAILED", "CP", 0); journal.question("pending", "ATTEMPT", "IP", 0);
        var cancelled = journal.store().cancel(ID, SCOPE);
        check(cancelled.result() != null && Integer.valueOf(70).equals(cancelled.result().getRawScores().get("BP")),
                "Cancellation published before successful checkpoint evidence was materialized");
        check(!cancelled.result().getRawScores().containsKey("CP") && !cancelled.result().getRawScores().containsKey("IP"),
                "Failed or in-flight questions became completed zero scores");
        check(cancelled.result().getAnalysisCoverage().hasOpenEvaluations(), "Cancellation lost unknown coverage");
        check(cancelled.result().getAnalysisCoverage().nodes().get("BP").state() == AnalysisCoverage.State.RELEVANT,
                "Cancellation erased independently assessed evidence");
        String durable = journal.run.resultJson;
        var restarted = journal.store().read(ID, SCOPE);
        check("CANCELLED".equals(restarted.result().getStatus()) && restarted.result().getRawScores().equals(Map.of("BP",70)),
                "Restored store depends on a late worker to produce the cancellation result");
        journal.store().cancel(ID, SCOPE);
        check(durable.equals(journal.run.resultJson), "Repeated cancellation rewrites the accepted result");
    }
    public static void lateCompletion() {
        var journal = new Journal(); journal.question("good", "SUCCESS", "BP", 70);
        journal.question("pending", "ATTEMPT", "IP", 0);
        journal.store().cancel(ID, SCOPE);
        String accepted = journal.run.resultJson;
        var late = new LlmCallDetail(); late.setScores(Map.of("IP", 90));
        try {
            journal.store().finish(journal.claim(), new AnalysisCheckpointSession.Question("pending", "", "MOCK", List.of("IP"), ""), "SUCCESS", late);
            throw new AssertionError("Late provider response changed a terminal cancellation");
        } catch (ResponseStatusException expected) { check(expected.getStatusCode().value() == 409, "Expected conflict"); }
        var incomplete = new AnalysisResult(Map.of("IP",90), List.of(node("IP"))); incomplete.setStatus("SUCCESS");
        var completed = journal.store().complete(journal.claim(), incomplete, List.of(node("IP")));
        check(completed.getRawScores().equals(Map.of("BP",70)), "Late complete overwrote durable cancellation evidence");
        check(accepted.equals(journal.run.resultJson), "Late complete rewrote frozen cancellation snapshot");
    }
    public static void frozenAdmission() {
        var journal = new Journal(); journal.present = false;
        var root = node("BP");
        var request = new AnalysisRequest(); request.setBusinessText("Hospital"); request.setContinuationId(ID);
        journal.store().begin(request, SCOPE, "original-signature", List.of(root, node("CP")));
        root.setCode("CHANGED_AFTER_ADMISSION");
        var seed = MAPPER.readValue(journal.run.resultJson, AnalysisResult.class);
        check(seed.getTree().getFirst().getCode().equals("BP"), "Admission did not freeze its original catalogue");
        journal.question("root", "SUCCESS", "BP", 80);
        var stopped = journal.store().cancel(ID, SCOPE);
        check(stopped.result().getAnalysisCoverage().nodes().containsKey("BP"), "Cancellation used a later catalogue");
        check(!stopped.result().getAnalysisCoverage().nodes().containsKey("CHANGED_AFTER_ADMISSION"), "Original identity was relabelled");
    }
    public static void legacyCancellation() {
        for (String state : List.of("RUNNING", "CANCELLED")) {
            var journal = new Journal(); journal.run.state = state; journal.run.resultJson = null;
            journal.run.inputHash = "original-signature"; journal.question("good", "SUCCESS", "BP",70);
            try {
                journal.store().cancel(ID, SCOPE, List.of(node("BP")), "changed-signature");
                throw new AssertionError("Legacy cancellation trusted a changed catalogue");
            } catch (ResponseStatusException expected) { }
            var result = journal.store().cancel(ID, SCOPE, List.of(node("BP"),node("CP")), "original-signature").result();
            check(result != null && result.getRawScores().equals(Map.of("BP",70)), "Legacy cancelled row lost checkpoint evidence");
        }
    }
    public static void invalidProvider() {
        var llm = new LlmService(null, null, MAPPER, null, null, null, null);
        try { llm.recoveryPolicyFingerprint("not-a-provider"); throw new AssertionError("Invalid provider accepted"); }
        catch (UnknownAnalysisProviderException expected) { check("not-a-provider".equals(expected.getProvider()), "Input not retained"); }
        catch (IllegalArgumentException wrong) { throw new AssertionError("Invalid resumable provider escapes the documented HTTP 400 mapping", wrong); }
    }
    public static void publication(String persistedStatus, boolean failPersistence) throws Exception {
        var registry = new AnalysisProgressRegistry(new StandardEnvironment());
        var catalogue = new TaxonomyService(null, null, null) {
            @Override public boolean isInitialized() { return true; }
            @Override public List<TaxonomyNodeDto> getFullTree() { return List.of(node("BP")); }
        };
        var store = new AnalysisContinuationStore(null, MAPPER) {
            @Override public AnalysisResult complete(Claim claim, AnalysisResult result, List<TaxonomyNodeDto> tree) {
                var progress = registry.recent("alice", SCOPE, null, null).getFirst();
                check(progress.finishedAt() == null, "Terminal live progress was published before the durable commit");
                if (failPersistence) throw new IllegalStateException("simulated durable write failure");
                var saved = new AnalysisResult(Map.of("BP",70), tree); saved.setStatus(persistedStatus); return saved;
            }
            @Override public void interrupted(Claim claim) { }
        };
        var continuation = new AnalysisContinuationService(store, catalogue, null, null, MAPPER) {
            @Override public Execution begin(AnalysisRequest request, String username, WorkspaceContext scope, int maxNodes) {
                return new Execution(new AnalysisContinuationStore.Claim(ID, "claim", null));
            }
        };
        var useCase = new AnalyzeRequirementUseCase(null, null, null, null, null, null, null) {
            @Override public AnalyzeRequirementResult analyze(AnalyzeRequirementCommand command) {
                var result = new AnalysisResult(); result.setStatus("SUCCESS"); return new AnalyzeRequirementResult(result);
            }
        };
        var repositories = new RepositoryStateService(null, null, null) {
            @Override public void ensureWorkspaceState(String username) { }
        };
        var resolver = new WorkspaceResolver(null) {
            @Override public String resolveCurrentUsername() { return "alice"; }
            @Override public WorkspaceContext resolveCurrentContext() { return SCOPE; }
        };
        var controller = new AnalysisApiController(catalogue, null, MAPPER, useCase, null, null, null,
                new AnalysisSseEventMapper(), repositories, resolver, null);
        for (var entry : Map.of("analysisProgressRegistry", registry, "continuationService", continuation).entrySet()) {
            var field = AnalysisApiController.class.getDeclaredField(entry.getKey()); field.setAccessible(true); field.set(controller, entry.getValue());
        }
        var request = new AnalysisRequest(); request.setBusinessText("Hospital requirement"); request.setResumable(true);
        try {
            var response = controller.analyze(request).getBody();
            check(!failPersistence, "Failed persistence returned a successful response");
            check(persistedStatus.equals(response.getStatus()), "Progress reconciliation mutated the already persisted result status");
            var progress = registry.recent("alice", SCOPE, null, null).getFirst();
            check(persistedStatus.equals(progress.status()), "Live progress did not use the committed status: " + progress.status());
        } catch (IllegalStateException expected) {
            check(failPersistence && "simulated durable write failure".equals(expected.getMessage()), "Unexpected error");
            check(!"COMPLETED".equals(registry.recent("alice", SCOPE, null, null).getFirst().status()), "Failed persistence reported success");
        }
    }
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "cancel" -> cancelledEvidence();
            case "late" -> lateCompletion();
            case "provider" -> invalidProvider();
            case "seed" -> frozenAdmission();
            case "legacy" -> legacyCancellation();
            case "publication" -> publication("PARTIAL", false);
            case "cancel-publication" -> publication("CANCELLED", false);
            case "persist-failure" -> publication("PARTIAL", true);
            default -> throw new IllegalArgumentException(args[0]);
        }
        System.out.println("PASS " + args[0]);
    }
}
