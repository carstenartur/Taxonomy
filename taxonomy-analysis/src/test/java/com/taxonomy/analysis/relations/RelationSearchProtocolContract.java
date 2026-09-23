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
    private static void rejects(String raw) {
        try { new RelationSearchProtocol(p -> raw).contributions(ORIGINAL, List.of(SOURCE)); throw new AssertionError("invalid response accepted"); }
        catch (RelationSearchEngine.InvalidResponseException expected) { }
    }
}
