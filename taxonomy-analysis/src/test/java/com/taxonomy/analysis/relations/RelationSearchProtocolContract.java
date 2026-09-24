package com.taxonomy.analysis.relations;

import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static com.taxonomy.dto.RelationSearchModel.*;
import static com.taxonomy.analysis.relations.RelationSearchContract.*;

/** Tests real prompts and strict parsing; only the remote completion function is replaced. */
public final class RelationSearchProtocolContract {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String VALID = """
        {"selections":[{"nodeId":"app.reader","outcome":"EXPLICIT","contributions":[
        {"text":"read existing evidence","quote":"Readers may read evidence.","condition":"read-only role"}],
        "rationale":"The requirement requests reading, not editing.","question":""}]}
        """;
    public static void main(String[] args) throws Exception {
        int passed = 0; List<String> failed = new ArrayList<>();
        for (var method : RelationSearchProtocolContract.class.getDeclaredMethods()) {
            if (!method.getName().startsWith("test")) continue;
            try { method.invoke(null); passed++; } catch (ReflectiveOperationException ex) { failed.add(method.getName() + ": " + ex.getCause()); }
        }
        failed.forEach(System.err::println);
        System.out.println("Relation protocol contracts: " + passed + " passed, " + failed.size() + " failed");
        if (!failed.isEmpty()) throw new AssertionError(failed.size() + " failed");
    }
    public static void testExtractsExactScopedContribution() {
        var result = new RelationSearchProtocol(prompt -> VALID).contributions(ORIGINAL, List.of(SOURCE));
        check(result.size() == 1 && result.getFirst().contributions().equals(List.of(READ)), "original quote and read-only condition retained");
    }
    public static void testPromptCarriesOriginalAndCandidateDescriptions() {
        List<String> sent = new ArrayList<>();
        new RelationSearchProtocol(prompt -> { sent.add(prompt); return VALID; }).contributions(ORIGINAL, List.of(SOURCE));
        check(sent.size() == 1 && sent.getFirst().contains(ORIGINAL) && sent.getFirst().contains("app.reader"), "actual original and exact ID sent");
    }
    public static void testRejectsMissingCandidates() { rejects("{\"selections\":[]}"); }
    public static void testRejectsForeignCandidate() { rejects(VALID.replace("app.reader", "invented")); }
    public static void testRejectsInventedQuote() { rejects(VALID.replace("Readers may read evidence.", "Readers may delete evidence.")); }
    public static void testRejectsMissingCondition() { rejects(VALID.replace(",\"condition\":\"read-only role\"", "")); }
    public static void testRejectsUnknownFields() { rejects(VALID.replace("\"nodeId\"", "\"unexpected\":1,\"nodeId\"")); }
    public static void testRejectsTrailingJson() { rejects(VALID + "{}"); }
    public static void testRejectsDuplicateJsonKeys() { rejects(VALID.replace("\"nodeId\":\"app.reader\"", "\"nodeId\":\"invented\",\"nodeId\":\"app.reader\"")); }
    public static void testRejectsFreeTextWithoutSalvage() { rejects("Please use this: " + VALID); }
    public static void testRejectsMissingResponse() { rejects(null); }
    public static void testUnresolvedRequiresQuestion() { rejects(VALID.replace("EXPLICIT", "UNRESOLVED")); }
    public static void testNavigationResponseUsesTypedDecisions() {
        var q = new Query(ORIGINAL, READ, "CONSUMES", Direction.OUTGOING, Phase.NAVIGATE, List.of(FOLDER), null);
        Decision expected = new Decision(FOLDER.id(), Outcome.MATCH, "existing evidence", "Readers may read evidence.", Necessity.REQUIRED, "", "", "Only read access is requested", "");
        List<String> prompts = new ArrayList<>();
        String answer = JSON.writeValueAsString(Map.of("decisions", List.of(expected)));
        var decisions = new RelationSearchProtocol(p -> { prompts.add(p); return answer; }).evaluate(q);
        check(decisions.equals(List.of(expected)), "typed decisions");
        check(prompts.getFirst().contains("CONSUMES") && prompts.getFirst().contains("read-only role") && prompts.getFirst().contains(ORIGINAL), "full query context");
    }
    public static void testInvalidEnumsBecomeInvalidResponse() {
        var q = new Query(ORIGINAL, READ, "CONSUMES", Direction.OUTGOING, Phase.NAVIGATE, List.of(FOLDER), null);
        String answer = JSON.writeValueAsString(Map.of("decisions", List.of(new Decision(FOLDER.id(), Outcome.REJECT, "", "", null, "", "", "no", "")))).replace("REJECT", "YES");
        try { new RelationSearchProtocol(p -> answer).evaluate(q); throw new AssertionError("invalid enum accepted"); }
        catch (RelationSearchEngine.InvalidResponseException expected) { }
    }
    public static void testSourceAnswersKeepOfferedOrderNotProviderOrder() {
        var first = JSON.readTree(VALID).get("selections").get(0);
        var second = JSON.readTree(VALID.replace("app.reader", "app.editor")).get("selections").get(0);
        String reversed = JSON.writeValueAsString(Map.of("selections", List.of(second, first)));
        var result = new RelationSearchProtocol(p -> reversed).contributions(ORIGINAL,
                List.of(SOURCE, node("app.editor", "UA", false)));
        check(result.stream().map(a -> a.node().id()).toList().equals(List.of("app.reader", "app.editor")),
                "Provider answer order must not prioritize unrelated work under a fixed budget");
    }
    public static void testContributionOrderIsCanonicalWithoutLosingConditions() {
        var a = JSON.readTree(VALID.replace("read-only role", "A role")).get("selections").get(0).get("contributions").get(0);
        var b = JSON.readTree(VALID.replace("read-only role", "B role")).get("selections").get(0).get("contributions").get(0);
        String forward = sourceWithParts(List.of(a, b));
        String reverse = sourceWithParts(List.of(b, a));
        var one = new RelationSearchProtocol(p -> forward).contributions(ORIGINAL, List.of(SOURCE));
        var two = new RelationSearchProtocol(p -> reverse).contributions(ORIGINAL, List.of(SOURCE));
        check(one.equals(two) && one.getFirst().contributions().size() == 2,
                "Canonical ordering must preserve distinct role conditions");
    }
    public static void testDuplicateOfferedSourcesFailBeforeRemoteCall() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        boolean rejected = false;
        try {
            new RelationSearchProtocol(p -> { calls.incrementAndGet(); return VALID; })
                    .contributions(ORIGINAL, List.of(SOURCE, SOURCE));
        } catch (RuntimeException expected) { rejected = true; }
        check(rejected && calls.get() == 0, "Duplicate source request must fail before remote evaluation");
    }
    public static void testNavigationAnswersKeepOfferedOrder() {
        Decision first = new Decision(FOLDER.id(), Outcome.REJECT, "", "", null, "", "", "not needed", "");
        Decision second = new Decision(LEAF.id(), Outcome.REJECT, "", "", null, "", "", "not needed", "");
        String reverse = JSON.writeValueAsString(Map.of("decisions", List.of(second, first)));
        var actual = new RelationSearchProtocol(p -> reverse).evaluate(twoCandidates());
        check(actual.equals(List.of(first, second)), "Navigation output must retain offered candidate order");
    }
    public static void testProtocolRejectsMissingNavigationDecision() {
        rejectsNavigation(List.of(rejectedDecision(FOLDER.id())));
    }
    public static void testProtocolRejectsForeignNavigationDecision() {
        rejectsNavigation(List.of(rejectedDecision(FOLDER.id()), rejectedDecision("foreign")));
    }
    public static void testProtocolRejectsDuplicateNavigationDecision() {
        rejectsNavigation(List.of(rejectedDecision(FOLDER.id()), rejectedDecision(FOLDER.id())));
    }
    public static void testDuplicateNavigationCandidatesFailBeforeRemoteCall() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var query = new Query(ORIGINAL, READ, "CONSUMES", Direction.OUTGOING,
                Phase.NAVIGATE, List.of(FOLDER, FOLDER), null);
        boolean rejected = false;
        try {
            new RelationSearchProtocol(p -> { calls.incrementAndGet(); return "{\"decisions\":[]}"; }).evaluate(query);
        } catch (RuntimeException expected) { rejected = true; }
        check(rejected && calls.get() == 0, "Duplicate navigation request must not spend a model call");
    }
    private static String sourceWithParts(List<?> parts) {
        return JSON.writeValueAsString(Map.of("selections", List.of(Map.of(
                "nodeId", SOURCE.id(), "outcome", "EXPLICIT", "contributions", parts,
                "rationale", "Separate role conditions", "question", ""))));
    }
    private static Query twoCandidates() {
        return new Query(ORIGINAL, READ, "CONSUMES", Direction.OUTGOING,
                Phase.NAVIGATE, List.of(FOLDER, LEAF), null);
    }
    private static Decision rejectedDecision(String id) {
        return new Decision(id, Outcome.REJECT, "", "", null, "", "", "not required", "");
    }
    private static void rejectsNavigation(List<Decision> decisions) {
        String answer = JSON.writeValueAsString(Map.of("decisions", decisions));
        try {
            new RelationSearchProtocol(p -> answer).evaluate(twoCandidates());
            throw new AssertionError("Incomplete or foreign decision set accepted at protocol boundary");
        } catch (RelationSearchEngine.InvalidResponseException expected) { }
    }

    private static void rejects(String raw) {
        try { new RelationSearchProtocol(p -> raw).contributions(ORIGINAL, List.of(SOURCE)); throw new AssertionError("invalid response accepted"); }
        catch (RelationSearchEngine.InvalidResponseException expected) { }
    }
}
