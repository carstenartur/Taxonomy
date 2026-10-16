package com.taxonomy.analysis.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LlmOuterObjectRecoveryTest {
    private final LlmResponseParser parser = new LlmResponseParser(new ObjectMapper());

    private static List<TaxonomyNode> nodes(String... codes) {
        return java.util.Arrays.stream(codes).map(code -> {
            var node = new TaxonomyNode();
            node.setCode(code);
            return node;
        }).toList();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "```json\n", "[IP] assessment:\n```json\n"})
    void acceptsCompleteProductAssessmentsWithOnlyTheOuterClosingBraceMissing(String prefix) throws Exception {
        String raw = prefix + """
                {
                  "IP-1365": {"score": 70, "reason": "Readiness reports for prophylaxis."},
                  "IP-1676": {"score": 60, "reason": "Collection of prophylaxis data."},
                  "IP-1775": {"score": 70, "reason": "Queries identify vaccination status."}
                """ + (prefix.isEmpty() ? "" : "```\n");

        var result = parser.parseIndependentScoreParseResult(raw, nodes("IP-1365", "IP-1676", "IP-1775"), 10);

        assertEquals(Map.of("IP-1365", 70d, "IP-1676", 60d, "IP-1775", 70d), result.scores());
        assertEquals("Queries identify vaccination status.", result.reasons().get("IP-1775"));
        // Extraction and stored raw evidence remain faithful to the provider reply.
        assertEquals(raw.substring(raw.indexOf('{')).trim(), parser.extractJson(raw));
    }

    @Test
    void acceptsCompleteCategoryAssessmentWithoutChangingNormalizationOrQuotedData() throws Exception {
        String reason = "Grippeschutz: \"Zitat\" { } [ ] ``` \\ Ende";
        String complete = new ObjectMapper().writeValueAsString(Map.of("BR", Map.of("score", 20, "reason", reason)));

        var result = parser.parseScoreParseResult(complete.substring(0, complete.length() - 1), nodes("BR"), 20);

        assertEquals(Map.of("BR", 20d), result.scores());
        assertEquals(reason, result.reasons().get("BR"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"IP-1365\":{\"score\":70,\"reason\":\"unfinished",
            "{\"IP-1365\":{\"score\":70,\"reason\":\"complete value\"",
            "{\"IP-1365\":{\"score\":70,\"reason\":\"complete child\"},",
            "{\"IP-1365\":{\"score\":70,\"reason\":\"complete child\"}\n```\ntrailing explanation",
            "{\"IP-1365\":{\"score\":70,\"reason\":\"complete child\"}]",
            "[{\"IP-1365\":{\"score\":70,\"reason\":\"complete child\"}}",
            "{\"IP-1365\":{\"score\":7",
            "{\"IP-1365\":{\"score\":70,\"reason\":\"unclosed\n```\n}",
            "{\"IP-1365\":{\"score\":70,\"reason\":\"An \"unescaped\" quote\"}"
    })
    void rejectsTruncatedContentArraysAndOtherMalformedStructures(String raw) {
        assertThrows(IllegalArgumentException.class,
                () -> parser.parseIndependentScoreParseResult(raw, nodes("IP-1365"), 10));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"IP-1365\":{\"score\":70,\"reason\":\"complete child\"}",
            "{\"IP-1365\":{\"score\":70,\"reason\":\"first\"},\"IP-1365\":{\"score\":60,\"reason\":\"duplicate\"}",
            "{\"IP-1365\":{\"score\":70,\"reason\":\"first\"},\"FOREIGN\":{\"score\":60,\"reason\":\"foreign\"}",
            "{\"IP-1365\":{\"score\":70,\"reason\":\"first\"},\"IP-1676\":{\"score\":101,\"reason\":\"out of range\"}",
            "{\"IP-1365\":{\"score\":70,\"reason\":\"first\"},\"IP-1676\":{\"score\":100.00000000000000001,\"reason\":\"out of range before rounding\"}",
            "{\"IP-1365\":{\"score\":70,\"reason\":\"first\"},\"IP-1676\":{\"score\":60,\"reason\":\"\"}",
            "{\"IP-1365\":{\"score\":70,\"reason\":\"first\"},\"IP-1676\":{\"reason\":\"missing score\"}"
    })
    void requiresEveryExpectedProductAndPreservesStrictValueValidation(String raw) {
        assertThrows(IllegalArgumentException.class,
                () -> parser.parseIndependentScoreParseResult(raw, nodes("IP-1365", "IP-1676"), 10));
    }

    @Test
    void outerEnvelopeRecoveryRetainsValidFractionalProductValues() throws Exception {
        String raw = "{\"IP-1365\":{\"score\":0.5,\"reason\":\"First contribution\"},"
                + "\"IP-1676\":{\"score\":0.125,\"reason\":\"Second contribution\"}";
        var result = parser.parseIndependentScoreParseResult(raw, nodes("IP-1365", "IP-1676"), 0);
        assertEquals(Map.of("IP-1365", .5, "IP-1676", .125), result.scores());
    }

    @Test
    void neverCompletesAPossiblyTruncatedBareNumber() {
        assertThrows(IllegalArgumentException.class,
                () -> parser.parseScoreParseResult("{\"BR\":2", nodes("BR"), 20));
    }
}
