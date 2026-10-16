package com.taxonomy.analysis.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.dto.TaxonomyDiscrepancy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LlmResponseParser}.
 * <p>No Spring context needed — the class under test is stateless and only depends
 * on a Jackson {@link ObjectMapper}.
 */
class LlmResponseParserTest {

    private LlmResponseParser parser;

    @BeforeEach
    void setUp() {
        parser = new LlmResponseParser(new ObjectMapper());
    }

    @ParameterizedTest
    @ValueSource(strings={"0.125", "0.5", "0.999999999999999999", "1.000000000000000001", "99.5"})
    void acceptsFractionalCategoryRelevanceWithoutIntegerCoercion(String number) throws Exception {
        var parser = new LlmResponseParser(new ObjectMapper());
        var node = new TaxonomyNode(); node.setCode("A"); node.setTaxonomyRoot("A");
        double parent = new java.math.BigDecimal(number).doubleValue();
        var result = parser.parseScoreParseResult("{\"A\":{\"score\":" + number
                + ",\"reason\":\"Authored fractional fixture\"}}", List.of(node), parent);
        assertEquals(parent, result.scores().get("A"));
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static TaxonomyNode node(String code, String parentCode, String root) {
        TaxonomyNode n = new TaxonomyNode();
        n.setCode(code);
        n.setParentCode(parentCode);
        n.setTaxonomyRoot(root);
        return n;
    }

    private static TaxonomyNode node(String code) {
        return node(code, null, null);
    }

    // ── extractGeminiText ─────────────────────────────────────────────────────

    @Nested
    class ExtractGeminiText {

        @Test
        void validResponse_returnsText() {
            String json = """
                    {
                      "candidates": [{
                        "content": {
                          "parts": [{"text": "Hello from Gemini"}]
                        }
                      }]
                    }""";
            assertEquals("Hello from Gemini", parser.extractGeminiText(json));
        }

        @Test
        void emptyCandidates_returnsNull() {
            String json = """
                    {"candidates": []}""";
            assertNull(parser.extractGeminiText(json));
        }

        @Test
        void nullCandidates_returnsNull() {
            String json = """
                    {"other": "field"}""";
            assertNull(parser.extractGeminiText(json));
        }

        @Test
        void emptyParts_returnsNull() {
            String json = """
                    {
                      "candidates": [{
                        "content": {"parts": []}
                      }]
                    }""";
            assertNull(parser.extractGeminiText(json));
        }

        @Test
        void malformedJson_returnsNull() {
            assertNull(parser.extractGeminiText("not json at all"));
        }
    }

    // ── extractOpenAiText ─────────────────────────────────────────────────────

    @Nested
    class ExtractOpenAiText {

        @Test
        void validResponse_returnsContent() {
            String json = """
                    {
                      "choices": [{
                        "message": {"content": "Hello from OpenAI"}
                      }]
                    }""";
            assertEquals("Hello from OpenAI", parser.extractOpenAiText(json));
        }

        @Test
        void emptyChoices_returnsNull() {
            String json = """
                    {"choices": []}""";
            assertNull(parser.extractOpenAiText(json));
        }

        @Test
        void nullChoices_returnsNull() {
            String json = """
                    {"data": "something"}""";
            assertNull(parser.extractOpenAiText(json));
        }

        @Test
        void malformedJson_returnsNull() {
            assertNull(parser.extractOpenAiText("{broken"));
        }
    }

    // ── extractJson ───────────────────────────────────────────────────────────

    @Nested
    class ExtractJson {

        @Test
        void withMarkdownFences_stripsAndReturnsJson() {
            String input = "```json\n{\"A\": 10}\n```";
            assertEquals("{\"A\": 10}", parser.extractJson(input));
        }

        @Test
        void withoutFences_returnsJsonObject() {
            String input = "Some preamble {\"B\": 20} trailing text";
            assertEquals("{\"B\": 20}", parser.extractJson(input));
        }

        @Test
        void nestedJsonObject_returnsOutermost() {
            String input = "{\"A\": {\"score\": 5}}";
            assertEquals("{\"A\": {\"score\": 5}}", parser.extractJson(input));
        }

        @Test
        void noJsonFound_returnsStrippedInput() {
            String input = "no json here";
            assertEquals("no json here", parser.extractJson(input));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "```json\n{\"x\":1}\n```",
                "```\n{\"x\":1}\n```"
        })
        void variousFenceFormats_extractSuccessfully(String input) {
            String result = parser.extractJson(input);
            assertTrue(result.contains("\"x\""));
        }
    }

    // ── zeroScores ────────────────────────────────────────────────────────────

    @Nested
    class ZeroScores {

        @Test
        void allNodesGetZero() {
            List<TaxonomyNode> nodes = List.of(node("BP"), node("CP"), node("CR"));
            Map<String, Double> zeros = parser.zeroScores(nodes);

            assertEquals(3, zeros.size());
            assertEquals(0d, zeros.get("BP"));
            assertEquals(0d, zeros.get("CP"));
            assertEquals(0d, zeros.get("CR"));
        }

        @Test
        void emptyList_returnsEmptyMap() {
            assertTrue(parser.zeroScores(List.of()).isEmpty());
        }
    }

    // ── deriveParentCode ──────────────────────────────────────────────────────

    @Nested
    class DeriveParentCode {

        @Test
        void withParentCode_returnsParentCode() {
            List<TaxonomyNode> nodes = List.of(node("CP-1023", "CP", "CP"));
            assertEquals("CP", parser.deriveParentCode(nodes));
        }

        @Test
        void noParentCode_fallsBackToTaxonomyRoot() {
            List<TaxonomyNode> nodes = List.of(node("BP", null, "BP"));
            assertEquals("BP", parser.deriveParentCode(nodes));
        }

        @Test
        void noParentCodeNoRoot_returnsUnknown() {
            List<TaxonomyNode> nodes = List.of(node("X"));
            assertEquals("unknown", parser.deriveParentCode(nodes));
        }

        @Test
        void emptyList_returnsUnknown() {
            assertEquals("unknown", parser.deriveParentCode(List.of()));
        }

        @Test
        void blankParentCode_fallsBackToRoot() {
            List<TaxonomyNode> nodes = List.of(node("CP-1023", "  ", "CP"));
            assertEquals("CP", parser.deriveParentCode(nodes));
        }
    }

    // ── normalizeToParent ─────────────────────────────────────────────────────

    @Nested
    class NormalizeToParent {

        @Test
        void basicNormalization_sumsToTarget() {
            Map<String, Double> scores = new LinkedHashMap<>();
            scores.put("A", 60d);
            scores.put("B", 30d);
            scores.put("C", 10d);

            Map<String, Double> result = parser.normalizeToParent(scores, 50);
            double sum = result.values().stream().mapToDouble(Double::doubleValue).sum();
            assertEquals(50d, sum);
        }

        @Test
        void zeroTotal_returnsOriginalScores() {
            Map<String, Double> scores = Map.of("A", 0d, "B", 0d);
            Map<String, Double> result = parser.normalizeToParent(scores, 100);
            assertEquals(0d, result.get("A"));
            assertEquals(0d, result.get("B"));
        }

        @Test
        void exactSumMatchingTarget_preservesValues() {
            Map<String, Double> scores = new LinkedHashMap<>();
            scores.put("A", 30d);
            scores.put("B", 20d);

            Map<String, Double> result = parser.normalizeToParent(scores, 50);
            assertEquals(30d, result.get("A"));
            assertEquals(20d, result.get("B"));
        }

        @Test
        void equalWeightsKeepFractionalSharesInsteadOfAwardingWholeRemainders() {
            // No child receives an artificial extra percentage point from integer apportionment.
            Map<String, Double> scores = new LinkedHashMap<>();
            scores.put("A", 1d);
            scores.put("B", 1d);
            scores.put("C", 1d);

            Map<String, Double> result = parser.normalizeToParent(scores, 100);
            double sum = result.values().stream().mapToDouble(Double::doubleValue).sum();
            assertEquals(100d, sum);
            for (double value : result.values()) {
                assertEquals(100d / 3, value, 2 * Math.ulp(100d / 3));
            }
        }

        @Test
        void singleEntry_getsFullTarget() {
            Map<String, Double> scores = Map.of("ONLY", 42d);
            Map<String, Double> result = parser.normalizeToParent(scores, 80);
            assertEquals(80d, result.get("ONLY"));
        }
    }

    // ── normalizeToHundred ────────────────────────────────────────────────────

    @Nested
    class NormalizeToHundred {

        @Test
        void normalizesToExactlyOneHundred() {
            Map<String, Double> scores = new LinkedHashMap<>();
            scores.put("X", 30d);
            scores.put("Y", 70d);

            Map<String, Double> result = parser.normalizeToHundred(scores);
            assertEquals(100d, result.values().stream().mapToDouble(Double::doubleValue).sum());
            assertEquals(30d, result.get("X"));
            assertEquals(70d, result.get("Y"));
        }

        @Test
        void unevenDistribution_sumsToHundred() {
            Map<String, Double> scores = new LinkedHashMap<>();
            scores.put("A", 10d);
            scores.put("B", 20d);
            scores.put("C", 30d);

            Map<String, Double> result = parser.normalizeToHundred(scores);
            assertEquals(100d, result.values().stream().mapToDouble(Double::doubleValue).sum());
        }
    }

    // ── parseScoreParseResult ─────────────────────────────────────────────────

    @Nested
    class ParseScoreParseResult {

        @Test
        void oneChildStillReceivesTheParentBudget() throws Exception {
            var child = node("BP-1", "BP", "BP");
            var result = parser.parseScoreParseResult("{\"BP-1\":20}", List.of(child), 60);
            assertEquals(60d, result.scores().get("BP-1"));
        }

        @Test
        void oldFormat_integerValues() throws Exception {
            String text = "{\"BP\": 60, \"CP\": 40}";
            List<TaxonomyNode> nodes = List.of(node("BP"), node("CP"));

            LlmService.ScoreParseResult result = parser.parseScoreParseResult(text, nodes, 100);

            assertEquals(60d, result.scores().get("BP"));
            assertEquals(40d, result.scores().get("CP"));
            assertTrue(result.reasons().isEmpty());
            assertNull(result.discrepancy());
        }

        @Test
        void newFormat_objectWithScoreAndReason() throws Exception {
            String text = """
                    {
                      "BP": {"score": 70, "reason": "Relevant"},
                      "CP": {"score": 30, "reason": "Partially relevant"}
                    }""";
            List<TaxonomyNode> nodes = List.of(node("BP"), node("CP"));

            LlmService.ScoreParseResult result = parser.parseScoreParseResult(text, nodes, 100);

            assertEquals(70d, result.scores().get("BP"));
            assertEquals(30d, result.scores().get("CP"));
            assertEquals("Relevant", result.reasons().get("BP"));
            assertEquals("Partially relevant", result.reasons().get("CP"));
            assertNull(result.discrepancy());
        }

        @Test
        void missingNodes_areAnIncompleteAssessment() {
            String text = "{\"BP\": 100}";
            List<TaxonomyNode> nodes = List.of(node("BP"), node("CP"), node("CR"));

            assertThrows(IllegalArgumentException.class, () -> parser.parseScoreParseResult(text, nodes, 100));
        }

        @Test
        void sumExceedsParent_createsDiscrepancy() throws Exception {
            String text = "{\"BP\": 80, \"CP\": 60}";
            List<TaxonomyNode> nodes = List.of(
                    node("BP", "ROOT", "ROOT"),
                    node("CP", "ROOT", "ROOT"));

            LlmService.ScoreParseResult result = parser.parseScoreParseResult(text, nodes, 100);

            assertNotNull(result.discrepancy());
            TaxonomyDiscrepancy d = result.discrepancy();
            assertEquals("ROOT", d.parentCode());
            assertEquals(100d, d.expectedParentScore());
            assertEquals(140d, d.actualChildSum());
        }

        @Test
        void sumLessThanParent_normalizesUpward() throws Exception {
            String text = "{\"A\": 10, \"B\": 10}";
            List<TaxonomyNode> nodes = List.of(node("A"), node("B"));

            LlmService.ScoreParseResult result = parser.parseScoreParseResult(text, nodes, 100);

            double sum = result.scores().values().stream().mapToDouble(Double::doubleValue).sum();
            assertEquals(100d, sum);
            assertNull(result.discrepancy());
        }

        @Test
        void exactMatch_noNormalization() throws Exception {
            String text = "{\"A\": 75, \"B\": 25}";
            List<TaxonomyNode> nodes = List.of(node("A"), node("B"));

            LlmService.ScoreParseResult result = parser.parseScoreParseResult(text, nodes, 100);

            assertEquals(75d, result.scores().get("A"));
            assertEquals(25d, result.scores().get("B"));
            assertNull(result.discrepancy());
        }

        @Test
        void markdownWrappedJson_parsed() throws Exception {
            String text = "```json\n{\"X\": 50, \"Y\": 50}\n```";
            List<TaxonomyNode> nodes = List.of(node("X"), node("Y"));

            LlmService.ScoreParseResult result = parser.parseScoreParseResult(text, nodes, 100);

            assertEquals(50d, result.scores().get("X"));
            assertEquals(50d, result.scores().get("Y"));
        }

        @Test
        void newFormat_blankReason_excluded() throws Exception {
            String text = """
                    {"A": {"score": 100, "reason": "   "}}""";
            List<TaxonomyNode> nodes = List.of(node("A"));

            LlmService.ScoreParseResult result = parser.parseScoreParseResult(text, nodes, 100);

            assertFalse(result.reasons().containsKey("A"));
        }

        @Test
        void newFormat_missingScoreField_isNotExplicitZero() {
            String text = """
                    {"A": {"reason": "no score field"}}""";
            List<TaxonomyNode> nodes = List.of(node("A"));

            assertThrows(IllegalArgumentException.class, () -> parser.parseScoreParseResult(text, nodes, 0));
        }

        @Test
        void invalidJson_throwsException() {
            List<TaxonomyNode> nodes = List.of(node("A"));
            assertThrows(Exception.class, () ->
                    parser.parseScoreParseResult("not valid json", nodes, 100));
        }
    }

    // ── parseGeminiResponse ───────────────────────────────────────────────────

    @Nested
    class ParseGeminiResponse {

        @Test
        void validResponse_returnsScores() {
            String responseBody = """
                    {
                      "candidates": [{
                        "content": {
                          "parts": [{"text": "{\\"A\\": 60, \\"B\\": 40}"}]
                        }
                      }]
                    }""";
            List<TaxonomyNode> nodes = List.of(node("A"), node("B"));

            Map<String, Double> scores = parser.parseGeminiResponse(responseBody, nodes, 100);

            assertEquals(60d, scores.get("A"));
            assertEquals(40d, scores.get("B"));
        }

        @Test
        void nullText_returnsZeroScores() {
            String responseBody = """
                    {"candidates": []}""";
            List<TaxonomyNode> nodes = List.of(node("A"), node("B"));

            Map<String, Double> scores = parser.parseGeminiResponse(responseBody, nodes, 100);

            assertEquals(0d, scores.get("A"));
            assertEquals(0d, scores.get("B"));
        }

        @Test
        void malformedInnerJson_returnsZeroScores() {
            String responseBody = """
                    {
                      "candidates": [{
                        "content": {
                          "parts": [{"text": "not json"}]
                        }
                      }]
                    }""";
            List<TaxonomyNode> nodes = List.of(node("A"));

            Map<String, Double> scores = parser.parseGeminiResponse(responseBody, nodes, 100);

            assertEquals(0d, scores.get("A"));
        }
    }

    // ── parseOpenAiResponse ───────────────────────────────────────────────────

    @Nested
    class ParseOpenAiResponse {

        @Test
        void validResponse_returnsScores() {
            String responseBody = """
                    {
                      "choices": [{
                        "message": {"content": "{\\"X\\": 80, \\"Y\\": 20}"}
                      }]
                    }""";
            List<TaxonomyNode> nodes = List.of(node("X"), node("Y"));

            Map<String, Double> scores = parser.parseOpenAiResponse(responseBody, nodes, 100);

            assertEquals(80d, scores.get("X"));
            assertEquals(20d, scores.get("Y"));
        }

        @Test
        void nullText_returnsZeroScores() {
            String responseBody = """
                    {"choices": []}""";
            List<TaxonomyNode> nodes = List.of(node("X"));

            Map<String, Double> scores = parser.parseOpenAiResponse(responseBody, nodes, 100);

            assertEquals(0d, scores.get("X"));
        }
    }
    @Nested
    class ParseIndependentProductScores {

        @Test
        void doesNotNormalizeIndependentScores() throws Exception {
            List<TaxonomyNode> nodes = List.of(node("IP-1"), node("IP-2"));
            var result = parser.parseIndependentScoreParseResult(
                    "{\"IP-1\": {\"score\": 80, \"reason\": \"match\"}, "
                            + "\"IP-2\": {\"score\": 70, \"reason\": \"also match\"}}",
                    nodes, 50);

            assertEquals(80d, result.scores().get("IP-1"));
            assertEquals(70d, result.scores().get("IP-2"));
            assertEquals(150d, result.scores().values().stream().mapToDouble(Double::doubleValue).sum());
        }

        @Test
        void appliesSuitabilityThresholdWithoutRedistribution() throws Exception {
            List<TaxonomyNode> nodes = List.of(node("IP-1"), node("IP-2"));
            var result = parser.parseIndependentScoreParseResult(
                    "{\"IP-1\": {\"score\": 49, \"reason\": \"below threshold\"}, "
                            + "\"IP-2\": {\"score\": 90, \"reason\": \"strong match\"}}",
                    nodes, 50);

            assertEquals(0d, result.scores().get("IP-1"));
            assertEquals(90d, result.scores().get("IP-2"));
        }

        @Test
        void rejectsMissingOrUnknownProductKeys() {
            List<TaxonomyNode> nodes = List.of(node("IP-1"), node("IP-2"));

            assertThrows(IllegalArgumentException.class, () ->
                    parser.parseIndependentScoreParseResult(
                            "{\"IP-1\": {\"score\": 80, \"reason\": \"match\"}, "
                                    + "\"IP-X\": {\"score\": 20, \"reason\": \"unknown\"}}",
                            nodes, 50));
        }

        @Test
        void rejectsMalformedProductValuesInsteadOfTurningThemIntoFalseNegatives() {
            List<TaxonomyNode> nodes = List.of(node("IP-1"));

            assertThrows(IllegalArgumentException.class, () ->
                    parser.parseIndependentScoreParseResult(
                            "{\"IP-1\": {\"reason\": \"missing score\"}}", nodes, 50));
            assertThrows(IllegalArgumentException.class, () ->
                    parser.parseIndependentScoreParseResult(
                            "{\"IP-1\": {\"score\": 80, \"reason\": \" \"}}", nodes, 50));
        }
    }

}
