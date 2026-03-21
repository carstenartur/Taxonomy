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
            Map<String, Integer> zeros = parser.zeroScores(nodes);

            assertEquals(3, zeros.size());
            assertEquals(0, zeros.get("BP"));
            assertEquals(0, zeros.get("CP"));
            assertEquals(0, zeros.get("CR"));
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
            Map<String, Integer> scores = new LinkedHashMap<>();
            scores.put("A", 60);
            scores.put("B", 30);
            scores.put("C", 10);

            Map<String, Integer> result = parser.normalizeToParent(scores, 50);
            int sum = result.values().stream().mapToInt(Integer::intValue).sum();
            assertEquals(50, sum);
        }

        @Test
        void zeroTotal_returnsOriginalScores() {
            Map<String, Integer> scores = Map.of("A", 0, "B", 0);
            Map<String, Integer> result = parser.normalizeToParent(scores, 100);
            assertEquals(0, result.get("A"));
            assertEquals(0, result.get("B"));
        }

        @Test
        void exactSumMatchingTarget_preservesValues() {
            Map<String, Integer> scores = new LinkedHashMap<>();
            scores.put("A", 30);
            scores.put("B", 20);

            Map<String, Integer> result = parser.normalizeToParent(scores, 50);
            assertEquals(30, result.get("A"));
            assertEquals(20, result.get("B"));
        }

        @Test
        void largestRemainderDistributesCorrectly() {
            // 33, 33, 34 would be expected for three equal values normalized to 100
            Map<String, Integer> scores = new LinkedHashMap<>();
            scores.put("A", 1);
            scores.put("B", 1);
            scores.put("C", 1);

            Map<String, Integer> result = parser.normalizeToParent(scores, 100);
            int sum = result.values().stream().mapToInt(Integer::intValue).sum();
            assertEquals(100, sum);
            // Each should be ~33 or 34
            for (int v : result.values()) {
                assertTrue(v >= 33 && v <= 34, "Each value should be 33 or 34, got " + v);
            }
        }

        @Test
        void singleEntry_getsFullTarget() {
            Map<String, Integer> scores = Map.of("ONLY", 42);
            Map<String, Integer> result = parser.normalizeToParent(scores, 80);
            assertEquals(80, result.get("ONLY"));
        }
    }

    // ── normalizeToHundred ────────────────────────────────────────────────────

    @Nested
    class NormalizeToHundred {

        @Test
        void normalizesToExactlyOneHundred() {
            Map<String, Integer> scores = new LinkedHashMap<>();
            scores.put("X", 30);
            scores.put("Y", 70);

            Map<String, Integer> result = parser.normalizeToHundred(scores);
            assertEquals(100, result.values().stream().mapToInt(Integer::intValue).sum());
            assertEquals(30, result.get("X"));
            assertEquals(70, result.get("Y"));
        }

        @Test
        void unevenDistribution_sumsToHundred() {
            Map<String, Integer> scores = new LinkedHashMap<>();
            scores.put("A", 10);
            scores.put("B", 20);
            scores.put("C", 30);

            Map<String, Integer> result = parser.normalizeToHundred(scores);
            assertEquals(100, result.values().stream().mapToInt(Integer::intValue).sum());
        }
    }

    // ── parseScoreParseResult ─────────────────────────────────────────────────

    @Nested
    class ParseScoreParseResult {

        @Test
        void oldFormat_integerValues() throws Exception {
            String text = "{\"BP\": 60, \"CP\": 40}";
            List<TaxonomyNode> nodes = List.of(node("BP"), node("CP"));

            LlmService.ScoreParseResult result = parser.parseScoreParseResult(text, nodes, 100);

            assertEquals(60, result.scores().get("BP"));
            assertEquals(40, result.scores().get("CP"));
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

            assertEquals(70, result.scores().get("BP"));
            assertEquals(30, result.scores().get("CP"));
            assertEquals("Relevant", result.reasons().get("BP"));
            assertEquals("Partially relevant", result.reasons().get("CP"));
            assertNull(result.discrepancy());
        }

        @Test
        void missingNodes_getZero() throws Exception {
            String text = "{\"BP\": 100}";
            List<TaxonomyNode> nodes = List.of(node("BP"), node("CP"), node("CR"));

            LlmService.ScoreParseResult result = parser.parseScoreParseResult(text, nodes, 100);

            assertEquals(0, result.scores().get("CP"));
            assertEquals(0, result.scores().get("CR"));
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
            assertEquals(100, d.expectedParentScore());
            assertEquals(140, d.actualChildSum());
        }

        @Test
        void sumLessThanParent_normalizesUpward() throws Exception {
            String text = "{\"A\": 10, \"B\": 10}";
            List<TaxonomyNode> nodes = List.of(node("A"), node("B"));

            LlmService.ScoreParseResult result = parser.parseScoreParseResult(text, nodes, 100);

            int sum = result.scores().values().stream().mapToInt(Integer::intValue).sum();
            assertEquals(100, sum);
            assertNull(result.discrepancy());
        }

        @Test
        void exactMatch_noNormalization() throws Exception {
            String text = "{\"A\": 75, \"B\": 25}";
            List<TaxonomyNode> nodes = List.of(node("A"), node("B"));

            LlmService.ScoreParseResult result = parser.parseScoreParseResult(text, nodes, 100);

            assertEquals(75, result.scores().get("A"));
            assertEquals(25, result.scores().get("B"));
            assertNull(result.discrepancy());
        }

        @Test
        void markdownWrappedJson_parsed() throws Exception {
            String text = "```json\n{\"X\": 50, \"Y\": 50}\n```";
            List<TaxonomyNode> nodes = List.of(node("X"), node("Y"));

            LlmService.ScoreParseResult result = parser.parseScoreParseResult(text, nodes, 100);

            assertEquals(50, result.scores().get("X"));
            assertEquals(50, result.scores().get("Y"));
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
        void newFormat_missingScoreField_defaultsToZero() throws Exception {
            String text = """
                    {"A": {"reason": "no score field"}}""";
            List<TaxonomyNode> nodes = List.of(node("A"));

            LlmService.ScoreParseResult result = parser.parseScoreParseResult(text, nodes, 0);

            assertEquals(0, result.scores().get("A"));
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

            Map<String, Integer> scores = parser.parseGeminiResponse(responseBody, nodes, 100);

            assertEquals(60, scores.get("A"));
            assertEquals(40, scores.get("B"));
        }

        @Test
        void nullText_returnsZeroScores() {
            String responseBody = """
                    {"candidates": []}""";
            List<TaxonomyNode> nodes = List.of(node("A"), node("B"));

            Map<String, Integer> scores = parser.parseGeminiResponse(responseBody, nodes, 100);

            assertEquals(0, scores.get("A"));
            assertEquals(0, scores.get("B"));
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

            Map<String, Integer> scores = parser.parseGeminiResponse(responseBody, nodes, 100);

            assertEquals(0, scores.get("A"));
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

            Map<String, Integer> scores = parser.parseOpenAiResponse(responseBody, nodes, 100);

            assertEquals(80, scores.get("X"));
            assertEquals(20, scores.get("Y"));
        }

        @Test
        void nullText_returnsZeroScores() {
            String responseBody = """
                    {"choices": []}""";
            List<TaxonomyNode> nodes = List.of(node("X"));

            Map<String, Integer> scores = parser.parseOpenAiResponse(responseBody, nodes, 100);

            assertEquals(0, scores.get("X"));
        }
    }
}
