package com.taxonomy.analysis.relations;

import com.taxonomy.analysis.recovery.AnalysisCheckpointSession;
import com.taxonomy.dto.LlmCallDetail;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.taxonomy.dto.RelationSearchModel.*;
import static org.junit.jupiter.api.Assertions.*;

/** Offline protocol regressions; no Spring context and no provider requests. */
class RelationSearchResponseRobustnessTest {
    private static final String ORIGINAL = "arbeitszeiterfassung";
    private static final Node SOURCE = new Node("source", "BP", "Time recording",
            "Authored unit-test source, not a catalogue fixture", false);
    private static final String REJECT = """
            {"selections":[{"nodeId":"source","outcome":"REJECT","contributions":[],
            "rationale":"This source is not required.","question":""}]}
            """.strip();
    private static final String EXPLICIT = """
            {"selections":[{"nodeId":"source","outcome":"EXPLICIT",
            "contributions":[{"text":"Capture working time","quote":"arbeitszeiterfassung","condition":""}],
            "rationale":"The requested function is time recording.","question":""}]}
            """.strip();

    @Test
    void contradictoryNavigationRejectionIsCorrectedOnceWithoutLosingAValidSibling() {
        var area = new Node("BP", "BP", "Business processes", "", true);
        var peer = new Node("CP", "CP", "Capabilities", "", true);
        var contribution = new Contribution(new Node("CI-1151", "CI", "Training services", "", false),
                "Provide training", ORIGINAL, "");
        var query = new Query(ORIGINAL, contribution, "SUPPORTS", Direction.OUTGOING,
                Phase.NAVIGATE, List.of(area, peer), null);
        String validPeer = """
                {"targetId":"CP","outcome":"DESCEND","contribution":"","requirementQuote":"",
                "necessity":null,"condition":"","alternativeGroup":"","rationale":"Review scoped capabilities.","question":""}
                """;
        for (String rejection : List.of(
                """
                {"targetId":"BP","outcome":"REJECT","contribution":"","requirementQuote":"",
                "necessity":null,"condition":"","alternativeGroup":"","rationale":"The process is unclear.",
                "question":"Which training process is required?"}
                """,
                """
                {"targetId":"BP","outcome":"REJECT","contribution":"Training process","requirementQuote":"arbeitszeiterfassung",
                "necessity":"REQUIRED","condition":"During training","alternativeGroup":"","rationale":"Explicit process.","question":""}
                """)) {
            var prompts = new java.util.ArrayList<String>();
            var protocol = new RelationSearchProtocol(prompt -> {
                prompts.add(prompt);
                return "{\"decisions\":[" + rejection + (prompts.size() == 1 ? "," + validPeer : "") + "]}";
            });

            List<Decision> decisions = protocol.evaluateRecovering(query);

            assertEquals(List.of(Outcome.UNRESOLVED, Outcome.DESCEND), decisions.stream().map(Decision::outcome).toList());
            assertTrue(decisions.getFirst().question().startsWith("TECHNICAL_ASSESSMENT_FAILURE:"));
            assertEquals(2, prompts.size());
            assertTrue(prompts.getLast().contains("validationErrors"));
        }
    }

    @Test
    void compactVerificationRejectionWithAQuestionRemainsOpenAfterOneCorrection() {
        var target = new Node("target", "IP", "Timesheet", "", false);
        var contribution = new Contribution(SOURCE, "Record working time", ORIGINAL, "");
        var proposal = new Decision(target.id(), Outcome.MATCH, "Record the reported time", ORIGINAL,
                Necessity.REQUIRED, "", "", "Required output", "");
        var query = new Query(ORIGINAL, contribution, "PRODUCES", Direction.OUTGOING,
                Phase.VERIFY, List.of(target), proposal);
        var calls = new AtomicInteger();
        var protocol = new RelationSearchProtocol(prompt -> {
            calls.incrementAndGet();
            return """
                    {"decisions":[{"targetId":"target","outcome":"REJECT","rationale":"The output type is unclear.",
                    "question":"Which report is produced?"}]}
                    """;
        });

        var decision = protocol.evaluateRecovering(query).getFirst();

        assertEquals(Outcome.UNRESOLVED, decision.outcome());
        assertEquals(2, calls.get());
    }

    @Test
    void acceptsOneWholeJsonCodeFenceWithoutChangingItsContents() {
        var plain = decode(EXPLICIT);
        assertEquals(plain, decode("```json\n" + EXPLICIT + "\n```"));
        assertEquals(plain, decode(" \r\n```JSON\r\n" + EXPLICIT + "\r\n```\r\n "));
        assertEquals(plain, decode("```\n" + EXPLICIT + "\n```"));
    }

    @Test
    void keepsBackticksInsideAJsonStringUnchanged() {
        String response = REJECT.replace("This source is not required.", "Literal ``` is data.");
        assertEquals("Literal ``` is data.", decode("```json\n" + response + "\n```").getFirst().rationale());
    }

    @Test
    void doesNotExtractAConvenientObjectFromProseOrMultipleAnswers() {
        for (String response : List.of("Explanation\n" + REJECT, REJECT + "\nExplanation",
                "```json\n" + REJECT + "\n```\nExplanation",
                "```json\n" + REJECT + "\n```\n```json\n" + REJECT + "\n```",
                REJECT + "\n" + REJECT)) {
            assertThrows(RelationSearchEngine.InvalidResponseException.class, () -> decode(response));
        }
    }

    @Test
    void rejectsTruncatedAndForeignFormatFences() {
        for (String response : List.of("```json\n" + REJECT, "```javascript\n" + REJECT + "\n```",
                "```json\n" + REJECT.substring(0, REJECT.length() - 1) + "\n```")) {
            assertThrows(RelationSearchEngine.InvalidResponseException.class, () -> decode(response));
        }
    }

    @Test
    void rejectsDuplicateKeysInsideAnOtherwiseValidFence() {
        String duplicate = REJECT.replace("\"nodeId\":\"source\"", "\"nodeId\":\"source\",\"nodeId\":\"source\"");
        assertThrows(RelationSearchEngine.InvalidResponseException.class,
                () -> decode("```json\n" + duplicate + "\n```"));
    }

    @Test
    void stillRequiresExactlyTheOfferedIds() {
        for (String response : List.of(REJECT.replace("\"source\"", "\"unknown\""),
                "{\"selections\":[]}",
                REJECT.replace("}]}", "}," + REJECT.substring(REJECT.indexOf("[{" ) + 1)))) {
            assertThrows(RelationSearchEngine.InvalidResponseException.class,
                    () -> decode("```json\n" + response + "\n```"));
        }
    }

    @Test
    void stillRejectsAnInventedOrTranslatedOriginalQuote() {
        String response = EXPLICIT.replace("\"quote\":\"arbeitszeiterfassung\"", "\"quote\":\"time recording\"");
        var failure = assertThrows(RelationSearchEngine.InvalidResponseException.class,
                () -> decode("```json\n" + response + "\n```"));
        assertTrue(failure.getMessage().contains("quote"));
    }

    @Test
    void distinguishesMissingEmptyAndOversizedResponses() {
        var missing = assertThrows(RelationSearchEngine.InvalidResponseException.class, () -> decode(null));
        var empty = assertThrows(RelationSearchEngine.InvalidResponseException.class, () -> decode("  \n"));
        var oversized = assertThrows(RelationSearchEngine.InvalidResponseException.class, () -> decode("x".repeat(131_073)));
        assertTrue(missing.getMessage().startsWith("Missing JSON response"));
        assertTrue(empty.getMessage().startsWith("Empty JSON response"));
        assertTrue(oversized.getMessage().startsWith("Oversized JSON response"));
        assertTrue(oversized.getMessage().contains("131073"));
    }

    @Test
    void retainsTheUnchangedFencedReplyAndReusesTheValidatedCheckpoint() {
        String raw = "```json\n" + EXPLICIT + "\n```";
        Map<String, AnalysisCheckpointSession.Checkpoint> saved = new HashMap<>();
        var store = new AnalysisCheckpointSession.Store() {
            @Override public AnalysisCheckpointSession.Checkpoint prepare(AnalysisCheckpointSession.Question question) {
                return saved.getOrDefault(question.key(), new AnalysisCheckpointSession.Checkpoint(question, "ATTEMPT", null));
            }
            @Override public void finish(AnalysisCheckpointSession.Question question, String state, LlmCallDetail detail) {
                saved.put(question.key(), new AnalysisCheckpointSession.Checkpoint(question, state, detail));
            }
            @Override public void checkActive() { }
        };
        var calls = new AtomicInteger();
        try (var session = new AnalysisCheckpointSession(store)) {
            var protocol = new RelationSearchProtocol(prompt -> { calls.incrementAndGet(); return raw; });
            var first = protocol.contributions(ORIGINAL, List.of(SOURCE));
            assertEquals(first, protocol.contributions(ORIGINAL, List.of(SOURCE)));
        }
        assertEquals(1, calls.get());
        assertEquals(1, saved.size());
        var checkpoint = saved.values().iterator().next();
        assertEquals("SUCCESS", checkpoint.state());
        assertEquals(raw, checkpoint.detail().getRawResponse());
    }

    private static List<com.taxonomy.dto.RelationSearchModel.SourceAssessment> decode(String raw) {
        return new RelationSearchProtocol(prompt -> raw).contributions(ORIGINAL, List.of(SOURCE));
    }

    @Test
    void photographedTimeTrackingClarificationRetainsSiblingsAndAllowsAnotherSourceWithoutRetry() {
        // Transcribed response from the reported failure; catalogue text is irrelevant to decoding.
        String raw = """
                {"selections":[{"nodeId":"CP-1000","outcome":"REJECT","contributions":[],
                "rationale":"The requirement for time tracking (arbeitszeiterfassung) is not a high-level military capability described in this node.","question":""},
                {"nodeId":"CP-1023","outcome":"REJECT","contributions":[],
                "rationale":"The requirement for time tracking (arbeitszeiterfassung) is not a core CIS capability such as secure communication or network protocols.","question":""},
                {"nodeId":"CP-1122","outcome":"UNRESOLVED","contributions":[],
                "rationale":"While time tracking is a digital function/application, it is not explicitly listed in the IT Services Capabilities description. It is unclear if this capability's scope for 'mission-critical software applications' or 'digital functions' extends to administrative HR functions like time tracking.",
                "question":"Does the scope of IT Services Capabilities (CP-1122) include administrative digital functions such as time tracking (arbeitszeiterfassung)?"}]}
                """;
        var nodes = List.of(new Node("CP-1000", "CP", "Capabilities", "", false),
                new Node("CP-1023", "CP", "Communication and Information System Capabilities", "", false),
                new Node("CP-1122", "CP", "IT Services Capabilities", "", false));
        Map<String, AnalysisCheckpointSession.Checkpoint> saved = new HashMap<>();
        var store = new AnalysisCheckpointSession.Store() {
            public AnalysisCheckpointSession.Checkpoint prepare(AnalysisCheckpointSession.Question question) {
                return saved.getOrDefault(question.key(), new AnalysisCheckpointSession.Checkpoint(question, "ATTEMPT", null));
            }
            public void finish(AnalysisCheckpointSession.Question question, String state, LlmCallDetail detail) {
                saved.put(question.key(), new AnalysisCheckpointSession.Checkpoint(question, state, detail));
            }
            public void checkActive() { }
        };
        var calls = new AtomicInteger();
        try (var session = new AnalysisCheckpointSession(store)) {
            var protocol = new RelationSearchProtocol(prompt -> calls.incrementAndGet() == 1 ? raw : EXPLICIT);
            var first = protocol.contributionsRecovering(ORIGINAL, nodes);
            assertTrue(first.failures().isEmpty());
            assertEquals(3, first.accepted().size());
            assertTrue(first.accepted().get(0).question().isEmpty());
            assertTrue(first.accepted().get(1).question().isEmpty());
            assertTrue(first.accepted().get(2).question().contains("administrative digital functions"));
            var checkpoint = saved.values().iterator().next();
            assertEquals("CLARIFICATION", checkpoint.state());
            assertEquals(raw, checkpoint.detail().getRawResponse());
            assertEquals(1, protocol.contributionsRecovering(ORIGINAL, List.of(SOURCE))
                    .accepted().getFirst().contributions().size());
            assertEquals(first, protocol.contributionsRecovering(ORIGINAL, nodes));
            assertEquals(2, calls.get(), "The semantic question must replay without another provider request");
        }
    }
}
