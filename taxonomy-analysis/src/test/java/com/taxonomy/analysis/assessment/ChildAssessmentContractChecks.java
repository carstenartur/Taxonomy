package com.taxonomy.analysis.assessment;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Dependency-free checks also invoked by the JUnit suite. */
public final class ChildAssessmentContractChecks {
    private static int checks;
    public static void main(String[] args) {
        run();
        System.out.println("ChildAssessmentContract: " + checks + " checks passed");
    }
    public static void run() {
        checks = 0;
        reject(() -> ChildAssessmentContract.decode(List.of("A", "B"), Map.of("A", 1), (id, v) -> v));
        reject(() -> ChildAssessmentContract.decode(List.of("A"), Map.of("A", 1, "X", 2), (id, v) -> v));
        reject(() -> ChildAssessmentContract.decode(List.of("A", "A"), Map.of("A", 1), (id, v) -> v));
        reject(() -> ChildAssessmentContract.decode(List.of(" "), Map.of(" ", 1), (id, v) -> v));
        reject(() -> ChildAssessmentContract.decode(Arrays.asList("A", null), Map.of("A", 1), (id, v) -> v));
        Map<String, Object> nullAnswer = new HashMap<>();
        nullAnswer.put("A", null);
        reject(() -> ChildAssessmentContract.decode(List.of("A"), nullAnswer, (id, v) -> v));
        reject(() -> ChildAssessmentContract.decode(List.of("A"), Map.of("A", 1), (id, v) -> null));
        AtomicInteger interpreted = new AtomicInteger();
        reject(() -> ChildAssessmentContract.decode(List.of("A", "B"), Map.of("A", 1), (id, v) -> interpreted.incrementAndGet()));
        require(interpreted.get() == 0, "Validate the whole ID set before interpreting any child");
        var scores = ChildAssessmentContract.decode(List.of("B", "A"), Map.of("A", 80, "B", 70), (id, v) -> (Integer) v);
        require(new ArrayList<>(scores.keySet()).equals(List.of("B", "A")), "Preserve offered candidate order");
        require(scores.get("A") == 80 && scores.get("B") == 70, "Never normalize independent scores");
        try { scores.put("X", 9); throw new AssertionError("Results must be immutable"); }
        catch (UnsupportedOperationException expected) { checks++; }
        var relations = ChildAssessmentContract.decode(List.of("A", "B"), Map.of("A", "DESCEND", "B", "UNRESOLVED"), (id, v) -> (String) v);
        require(relations.get("B").equals("UNRESOLVED"), "Non-score decisions must survive unchanged");
        require(ChildAssessmentContract.decode(List.of(), Map.of(), (id, v) -> v).isEmpty(), "Empty candidate sets are valid");
    }
    private static void reject(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("Expected invalid child assessment to be rejected");
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
}
