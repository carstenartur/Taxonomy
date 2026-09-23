package com.taxonomy.analysis.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the existing parser, not a second JSON validator or a repair routine. */
class LlmJsonValidationTest {
    private final LlmResponseParser parser = new LlmResponseParser(new ObjectMapper());

    private static List<TaxonomyNode> roots() {
        var node = new TaxonomyNode();
        node.setCode("BR");
        return List.of(node);
    }

    @Test
    void missingOuterBraceBeforeMarkdownEndHasConciseDiagnosticAndOriginalCause() {
        String raw = """
                ```json
                {
                  "BR": {"score": 20, "reason": "Safety management"}
                ```
                """;
        var error = assertThrows(IllegalArgumentException.class,
                () -> parser.parseScoreParseResult(raw, roots(), 20));
        assertTrue(error.getMessage().startsWith("Invalid JSON in LLM response"));
        assertTrue(error.getMessage().contains("line 3, column"), error.getMessage());
        assertTrue(error.getMessage().contains("extracted JSON"));
        assertTrue(error.getMessage().contains("LLM communication log"));
        assertFalse(error.getMessage().contains("REDACTED"));
        assertFalse(error.getMessage().contains("Safety management"));
        assertInstanceOf(StreamReadException.class, error.getCause());
        assertTrue(error.getMessage().length() < 300);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"BR\":{\"score\":20}",
            "{\"BR\":{\"score\":20,\"reason\":\"An \"unescaped\" quote\"}}",
            "{\"BR\":20,}",
            "{\"BR\":20,\"CP\":secret_response_text}"
    })
    void malformedJsonIsRejectedWithoutCopyingResponseContentsIntoMainError(String raw) {
        var error = assertThrows(IllegalArgumentException.class,
                () -> parser.parseScoreParseResult(raw, roots(), 20));
        assertTrue(error.getMessage().startsWith("Invalid JSON in LLM response"));
        assertFalse(error.getMessage().contains("secret_response_text"));
        assertInstanceOf(StreamReadException.class, error.getCause());
    }

    @Test
    void legitimateQuotedBracesBackticksAndUnicodeRemainData() throws Exception {
        String reason = "Arbeitsschutz \"Zitat\" { } [ ] ``` \u00e4 \\ $ {{PARENT_SCORE}}";
        String raw = new ObjectMapper().writeValueAsString(Map.of("BR", Map.of("score", 20, "reason", reason)));
        var parsed = parser.parseScoreParseResult("```json\n" + raw + "\n```", roots(), 20);
        assertEquals(Map.of("BR", 20), parsed.scores());
        assertEquals(reason, parsed.reasons().get("BR"));
    }

    @Test
    void independentScoringUsesTheSameSyntaxDiagnostic() {
        var error = assertThrows(IllegalArgumentException.class, () -> parser.parseIndependentScoreParseResult(
                "{\"BR\":{\"score\":20,\"reason\":\"complete child\"}\n```", roots(), 10));
        assertTrue(error.getMessage().startsWith("Invalid JSON in LLM response"));
    }
}
