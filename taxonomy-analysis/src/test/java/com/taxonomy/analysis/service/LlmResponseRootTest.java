package com.taxonomy.analysis.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LlmResponseRootTest {
    private static final String SCORE_OBJECT =
            "{\"IP\":{\"score\":100,\"reason\":\"Use [brackets] and {braces}.\"}}";
    private final LlmResponseParser parser = new LlmResponseParser(new ObjectMapper());

    private static Stream<String> arrayResponses() {
        return Stream.of(
                "[" + SCORE_OBJECT + "]",
                "```json\n[" + SCORE_OBJECT + "]\n```",
                "Here is the result:\n[" + SCORE_OBJECT + "]",
                "[" + SCORE_OBJECT + "," + SCORE_OBJECT + "]",
                "[" + SCORE_OBJECT);
    }

    @ParameterizedTest
    @MethodSource("arrayResponses")
    void arrayRootsNeverBecomeSuccessfulScores(String response) {
        var node = new TaxonomyNode();
        node.setCode("IP");
        var nodes = List.of(node);
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> parser.parseScoreParseResult(response, nodes, 100)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> parser.parseIndependentScoreParseResult(response, nodes, 50)));
    }

    @Test
    void anObjectWithQuotedArrayAndObjectDelimitersStillParses() throws Exception {
        var node = new TaxonomyNode();
        node.setCode("IP");
        var nodes = List.of(node);
        String response = "Here is the result:\n```json\n" + SCORE_OBJECT + "\n```";
        assertEquals(SCORE_OBJECT, parser.extractJson(response));
        assertEquals(100, parser.parseScoreParseResult(response, nodes, 100).scores().get("IP"));
        assertEquals(100, parser.parseIndependentScoreParseResult(response, nodes, 50).scores().get("IP"));
    }
}
