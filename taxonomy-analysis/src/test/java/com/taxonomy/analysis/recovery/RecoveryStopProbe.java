package com.taxonomy.analysis.recovery;

import com.taxonomy.dto.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/** A runtime guard must not turn earlier skipped questions into a completed traversal. */
public final class RecoveryStopProbe {
    public static void verify() {
        interruptedEvidenceSurvivesExchange();
        var mapper = new ObjectMapper();
        var run = new AnalysisContinuationRun(); run.id = "run"; run.state = "RUNNING"; run.claimToken = "claim";
        var skipped = new AnalysisQuestionCheckpoint(); skipped.run = run; skipped.questionKey = "q";
        skipped.state = "SKIPPED"; skipped.nodeCodes = "[\"IP\"]"; skipped.error = "request too large";
        var query = Proxy.newProxyInstance(TypedQuery.class.getClassLoader(), new Class<?>[]{TypedQuery.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "setParameter" -> proxy;
                    case "getResultList" -> List.of(skipped);
                    default -> throw new AssertionError("Unexpected query operation: " + method.getName());
                });
        var em = (EntityManager) Proxy.newProxyInstance(EntityManager.class.getClassLoader(), new Class<?>[]{EntityManager.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "find" -> run;
                    case "createQuery" -> query;
                    case "flush" -> null;
                    default -> throw new AssertionError("Unexpected entity operation: " + method.getName());
                });
        var store = new AnalysisContinuationStore(em, mapper);
        for (String reason : List.of("MEMORY_PRESSURE", "TIME_LIMIT")) {
            run.state = "RUNNING";
            var result = new AnalysisResult(); result.setStatus("PARTIAL"); result.setErrorMessage(reason + ": analysis stopped");
            var stopped = store.complete(new AnalysisContinuationStore.Claim("run", "claim", null), result, List.of());
            if (!"STOPPED".equals(stopped.getRecovery().state()))
                throw new AssertionError(reason + " was disguised as completed work: " + stopped.getRecovery().state());
        }
        run.state = "RUNNING";
        var result = new AnalysisResult(); result.setStatus("PARTIAL"); result.setErrorMessage("UNASSESSED: skipped input");
        var complete = store.complete(new AnalysisContinuationStore.Claim("run", "claim", null), result, List.of());
        if (!"COMPLETED_WITH_GAPS".equals(complete.getRecovery().state())) throw new AssertionError("Finished skipped branch must remain partial");
    }
    /** Completed local evidence is retained, while even not-yet-started roots remain visibly open. */
    private static void interruptedEvidenceSurvivesExchange() {
        var mapper = new ObjectMapper();
        var run = new AnalysisContinuationRun(); run.id = "run"; run.claimToken = "claim";
        var query = Proxy.newProxyInstance(TypedQuery.class.getClassLoader(), new Class<?>[]{TypedQuery.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "setParameter" -> proxy;
                    case "getResultList" -> List.of();
                    default -> throw new AssertionError("Unexpected query operation: " + method.getName());
                });
        var em = (EntityManager) Proxy.newProxyInstance(EntityManager.class.getClassLoader(), new Class<?>[]{EntityManager.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "find" -> run;
                    case "createQuery" -> query;
                    case "flush" -> null;
                    default -> throw new AssertionError("Unexpected entity operation: " + method.getName());
                });
        // Official root identities; no synthetic products or catalogue mutations.
        var bp = new TaxonomyNodeDto(); bp.setCode("BP");
        var cp = new TaxonomyNodeDto(); cp.setCode("CP");
        var tree = List.of(bp, cp);
        var store = new AnalysisContinuationStore(em, mapper);
        for (String state : List.of("RUNNING", "CANCELLED")) {
            run.state = state;
            var result = new AnalysisResult(); result.setStatus("PARTIAL");
            result.setErrorMessage("TIME_LIMIT: analysis stopped");
            result.setRawScores(Map.of("BP", 100)); result.setScores(Map.of("BP", 100));
            var view = new RequirementArchitectureView(); result.setArchitectureView(view);
            var completed = store.complete(new AnalysisContinuationStore.Claim("run", "claim", null), result, tree);
            var coverage = completed.getAnalysisCoverage();
            if (!coverage.hasOpenEvaluations())
                throw new AssertionError("An interrupted, unassessed root must remain open without a failed provider question");
            var known = coverage.nodes().get("BP"); var unknown = coverage.nodes().get("CP");
            if (known.state() != AnalysisCoverage.State.RELEVANT || known.score() != 100
                    || unknown.state() != AnalysisCoverage.State.UNKNOWN || unknown.score() != null
                    || unknown.effectiveRelevance() != null || completed.getScores().containsKey("CP"))
                throw new AssertionError("Interrupted evidence was turned into a zero or a known assessment was lost");
            if (view.getAnalysisCoverage() != coverage) throw new AssertionError("Architecture lost its uncertainty metadata");
            var saved = new SavedAnalysis(); saved.setVersion(3); saved.setScores(completed.getScores());
            saved.setRawScores(completed.getRawScores()); saved.setAnalysisCoverage(coverage); saved.setAnalysisStatus("SUCCESS");
            var restored = mapper.readValue(mapper.writeValueAsString(saved), SavedAnalysis.class);
            restored.validateCoverageEvidence();
            if (!restored.getAnalysisCoverage().hasOpenEvaluations() || !"PARTIAL".equals(restored.getAnalysisStatus()))
                throw new AssertionError("JSON import incorrectly authorized a global completeness claim");
        }
    }
    public static void main(String[] args) { verify(); System.out.println("Recovery guard-stop contracts passed"); }
}
