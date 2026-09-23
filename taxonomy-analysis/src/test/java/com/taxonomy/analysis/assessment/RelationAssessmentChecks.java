package com.taxonomy.analysis.assessment;

import java.util.*;

/** Executable semantic checks; no remote model or Spring context. */
public final class RelationAssessmentChecks {
    private static int checks;
    private static final String ORIGINAL = "Authorized staff may read existing evidence. Notification is required; the channel is undecided.";
    public static void main(String[] args) {
        run();
        System.out.println("RelationAssessment: " + checks + " checks passed");
    }
    public static void run() {
        checks = 0;
        var context = context(RelationAssessment.Phase.NAVIGATE);
        var parent = new RelationAssessment.Candidate("IP-1", "Evidence", "Existing evidence", "IP", true);
        var leaf = new RelationAssessment.Candidate("IP-2", "Evidence record", "An existing record", "IP", false);
        var accepted = RelationAssessment.decode(context, leaf, answer("ACCEPT"));
        require(accepted.decision() == RelationAssessment.Decision.ACCEPT, "A concrete candidate may be proposed");
        require(accepted.evidence().equals(List.of("read existing evidence")), "Keep exact requirement evidence");
        reject(() -> RelationAssessment.decode(context, leaf, answer("DESCEND")));
        reject(() -> RelationAssessment.decode(context(RelationAssessment.Phase.VERIFY), parent, answer("DESCEND")));
        require(RelationAssessment.decode(context, parent, answer("DESCEND")).decision() == RelationAssessment.Decision.DESCEND,
                "Navigation need not assert a relationship to a parent");
        reject(() -> RelationAssessment.decode(context, leaf, with("decision", "MAYBE")));
        reject(() -> RelationAssessment.decode(context, leaf, with("reason", " ")));
        reject(() -> RelationAssessment.decode(context, leaf, with("contribution", "")));
        reject(() -> RelationAssessment.decode(context, leaf, with("evidence", List.of())));
        reject(() -> RelationAssessment.decode(context, leaf, with("evidence", List.of("edit all documents"))));
        reject(() -> RelationAssessment.decode(context, leaf, with("evidence", List.of(17))));
        reject(() -> RelationAssessment.decode(context, leaf, with("score", 99)));
        reject(() -> RelationAssessment.decode(context, leaf, with("decision", "UNRESOLVED")));
        var unresolved = with("decision", "UNRESOLVED");
        unresolved.put("question", "Which notification channel should be used?");
        require(RelationAssessment.decode(context, parent, unresolved).question().startsWith("Which"), "Keep open decisions");
        reject(() -> RelationAssessment.decode(context, leaf, with("combination", "ALTERNATIVE")));
        var alternative = with("combination", "ALTERNATIVE");
        alternative.put("alternativeGroup", "notification-channel");
        require(RelationAssessment.decode(context, parent, alternative).combination() == RelationAssessment.Combination.ALTERNATIVE,
                "Do not collapse alternatives into jointly required children");
        require(RelationAssessment.decode(context, parent, with("combination", "OPTIONAL")).combination() == RelationAssessment.Combination.OPTIONAL,
                "Keep optional contributions explicit");
        reject(() -> new RelationAssessment.Context("rev-1", ORIGINAL, "BP-1", "BP", "read only",
                List.of("invented source evidence"), "CONSUMES", RelationAssessment.Direction.OUTGOING, RelationAssessment.Phase.NAVIGATE));
        require(context.originalText().equals(ORIGINAL), "Do not rewrite the original requirement");
        try { accepted.evidence().add("edit"); throw new AssertionError("Evidence must be immutable"); }
        catch (UnsupportedOperationException expected) { checks++; }
    }
    static RelationAssessment.Context context(RelationAssessment.Phase phase) {
        return new RelationAssessment.Context("rev-1", ORIGINAL, "BP-1", "BP", "Read existing evidence, not edit it",
                List.of("read existing evidence"), "CONSUMES", RelationAssessment.Direction.OUTGOING, phase);
    }
    static Map<String, Object> answer(String decision) {
        var result = new LinkedHashMap<String, Object>();
        result.put("decision", decision);
        result.put("contribution", "Provide access to existing evidence");
        result.put("evidence", List.of("read existing evidence"));
        result.put("reason", "The requested read operation needs the existing evidence");
        result.put("question", "");
        result.put("combination", "REQUIRED");
        result.put("alternativeGroup", "");
        return result;
    }
    static Map<String, Object> with(String key, Object value) {
        var result = answer("ACCEPT"); result.put(key, value); return result;
    }
    private static void reject(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("Expected invalid relation assessment to be rejected");
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message); checks++;
    }
}
