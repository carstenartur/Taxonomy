package com.taxonomy.analysis.recovery;

import com.taxonomy.dto.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.lang.reflect.Proxy;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

/** A runtime guard must not turn earlier skipped questions into a completed traversal. */
public final class RecoveryStopProbe {
    public static void verify() {
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
    public static void main(String[] args) { verify(); System.out.println("Recovery guard-stop contracts passed"); }
}
