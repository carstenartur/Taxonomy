package com.taxonomy.analysis.relations;

import com.taxonomy.analysis.recovery.AnalysisCheckpointSession;
import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.dto.RelationSearchModel.Node;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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
}
