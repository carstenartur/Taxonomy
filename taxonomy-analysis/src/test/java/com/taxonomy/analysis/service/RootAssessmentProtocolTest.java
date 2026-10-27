package com.taxonomy.analysis.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** Actual root protocol: ambiguity is evidence to retain, never a fabricated exclusion. */
class RootAssessmentProtocolTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final LlmResponseParser parser = new LlmResponseParser(json);
    private final TaxonomyNode root = root();

    private static TaxonomyNode root() {
        var node = new TaxonomyNode();
        node.setCode("CO"); node.setTaxonomyRoot("CO"); node.setNameEn("Communications Services");
        return node;
    }

    @Test
    void unresolvedReplyRetainsItsReasonAndQuestionWithoutManufacturingZero() {
        var parsed = assertDoesNotThrow(() -> parser.parseScoreParseResult("""
                {"CO":{"status":"UNRESOLVED","score":null,
                "reason":"The requirement does not settle online or isolated operation.",
                "question":"Must time entries be exchanged between devices?"}}
                """, List.of(root), 100, ScoreAssessmentKind.ROOT_RELEVANCE));
        assertTrue(parsed.scores().isEmpty());
        assertEquals("The requirement does not settle online or isolated operation.", parsed.reasons().get("CO"));
        var evidence = json.valueToTree(parsed).path("rootAssessments").path("CO");
        assertEquals("UNRESOLVED", evidence.path("status").asString());
        assertTrue(evidence.path("score").isNull());
        assertEquals("Must time entries be exchanged between devices?", evidence.path("question").asString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"status\":\"UNRESOLVED\",\"score\":0,\"reason\":\"Open\",\"question\":\"Online?\"}",
            "{\"status\":\"RELEVANT\",\"score\":0,\"reason\":\"Needed\",\"question\":\"\"}",
            "{\"status\":\"IRRELEVANT\",\"score\":20,\"reason\":\"Excluded\",\"question\":\"\"}",
            "{\"status\":\"MAYBE\",\"score\":20,\"reason\":\"Open\",\"question\":\"Online?\"}",
            "{\"status\":\"RELEVANT\",\"score\":20,\"reason\":\"\",\"question\":\"\"}",
            "{\"status\":\"RELEVANT\",\"score\":20,\"reason\":\"Needed\",\"question\":\"\",\"confidence\":100}",
            "{\"status\":\"UNRESOLVED\",\"score\":null,\"reason\":\"Open\",\"question\":\"\"}",
            "{\"status\":\"RELEVANT\",\"score\":20,\"reason\":\"Needed\",\"question\":\"Online?\"}",
            "{\"status\":\"RELEVANT\",\"score\":\"20\",\"reason\":\"Needed\",\"question\":\"\"}",
            "{\"status\":\"RELEVANT\",\"score\":100.00000000000000000001,\"reason\":\"Needed\",\"question\":\"\"}",
            "{\"status\":\"RELEVANT\",\"score\":null,\"reason\":\"Needed\",\"question\":\"\"}",
            "{\"status\":\"IRRELEVANT\",\"score\":null,\"reason\":\"Excluded\",\"question\":\"\"}",
            "{\"status\":\"UNRESOLVED\",\"score\":null,\"reason\":\"Open\",\"question\":null}"
    })
    void contradictoryOrUnsupportedTypedAnswersCannotBecomeAcceptedScores(String answer) {
        assertThrows(IllegalArgumentException.class, () -> parser.parseScoreParseResult(
                "{\"CO\":" + answer + "}", List.of(root), 100, ScoreAssessmentKind.ROOT_RELEVANCE));
    }

    @Test
    void positiveReusedServiceAndGenuineExclusionKeepTheirIndependentWeights() throws Exception {
        var positive = parser.parseScoreParseResult("""
                {"CO":{"status":"RELEVANT","score":0.125,
                "reason":"Online terminals require the existing connectivity service.","question":""}}
                """, List.of(root), 100, ScoreAssessmentKind.ROOT_RELEVANCE);
        assertEquals(0.125, positive.scores().get("CO"));
        assertEquals("RELEVANT", json.valueToTree(positive).at("/rootAssessments/CO/status").asString());
        var excluded = parser.parseScoreParseResult("""
                {"CO":{"status":"IRRELEVANT","score":0,
                "reason":"The required device is isolated, with no communication or later synchronization.","question":""}}
                """, List.of(root), 100, ScoreAssessmentKind.ROOT_RELEVANCE);
        assertEquals(0d, excluded.scores().get("CO"));
        assertEquals("IRRELEVANT", json.valueToTree(excluded).at("/rootAssessments/CO/status").asString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.125", "20", "100"})
    void legacyRepliesRetainExactValuesWithoutInventingTypedEvidence(String number) throws Exception {
        for (String value : List.of(number, "{\"score\":" + number + ",\"reason\":\"Existing evidence\"}")) {
            var result = parser.parseScoreParseResult("{\"CO\":" + value + "}", List.of(root), 100,
                    ScoreAssessmentKind.ROOT_RELEVANCE);
            assertEquals(Double.valueOf(number), result.scores().get("CO"));
            assertTrue(result.rootAssessments().isEmpty(), "A legacy number cannot supply a missing semantic decision");
        }
    }

    @Test
    void rootQuestionsCannotBeSilentlyInterpretedAsChildBudgetAllocations() {
        var child = new TaxonomyNode(); child.setCode("CO-1000"); child.setTaxonomyRoot("CO"); child.setParentCode("CO");
        assertThrows(IllegalArgumentException.class, () -> parser.parseScoreParseResult("""
                {"CO-1000":{"status":"UNRESOLVED","score":null,"reason":"Open","question":"Online?"}}
                """, List.of(child), 20, ScoreAssessmentKind.CHILD_BUDGET));
    }
}
