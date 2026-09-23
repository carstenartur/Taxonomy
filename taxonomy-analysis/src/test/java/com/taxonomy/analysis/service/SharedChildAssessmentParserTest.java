package com.taxonomy.analysis.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SharedChildAssessmentParserTest {
    private final LlmResponseParser parser = new LlmResponseParser(new ObjectMapper());

    private static TaxonomyNode node(String id) {
        var node = new TaxonomyNode();
        node.setCode(id);
        return node;
    }

    @Test
    void independentProductPolicyUsesCompleteSharedChildSetWithoutNormalizing() throws Exception {
        var scores = parser.parseIndependentScoreParseResult("""
                {"P2":{"score":70,"reason":"also suitable"},
                 "P1":{"score":80,"reason":"suitable"}}
                """, List.of(node("P1"), node("P2")), 50);
        assertEquals(Map.of("P1", 80, "P2", 70), scores.scores());
        assertEquals(List.of("P1", "P2"), List.copyOf(scores.scores().keySet()));
        assertThrows(IllegalArgumentException.class, () ->
                parser.parseIndependentScoreParseResult(
                        "{\"P1\":{\"score\":80,\"reason\":\"suitable\"}}",
                        List.of(node("P1"), node("P2")), 50));
    }

    @Test
    void sameDecoderAcceptsNonNumericChildPolicyButNotMissingForeignOrDuplicateAnswers() {
        assertEquals(Map.of("A", "DESCEND", "B", "UNRESOLVED"),
                parser.parseChildAssessment("""
                        ```json
                        {"A":"DESCEND","B":"UNRESOLVED"}
                        ```
                        """, List.of("A", "B"), (id, value) -> (String) value));
        for (String invalid : List.of("{}", "{\"foreign\":\"DESCEND\"}",
                "{\"A\":\"DESCEND\",\"A\":\"REJECT\"}",
                "{\"A\":{\"decision\":\"DESCEND\",\"decision\":\"REJECT\"}}")) {
            assertThrows(RuntimeException.class, () ->
                    parser.parseChildAssessment(invalid, List.of("A"), (id, value) -> value), invalid);
        }
    }

    @Test
    void duplicateCandidateIdsAreRejectedEvenIfJsonKeysLookComplete() {
        assertThrows(IllegalArgumentException.class, () -> parser.parseChildAssessment(
                "{\"A\":\"DESCEND\"}", List.of("A", "A"), (id, value) -> value));
    }

    @Test
    void categoryKeepsParentBudgetButRequiresTheSharedCompleteChildSet() throws Exception {
        var scores = parser.parseScoreParseResult("{\"A\":60,\"B\":20}", List.of(node("A"), node("B")), 40);
        assertEquals(Map.of("A", 30, "B", 10), scores.scores());
        assertThrows(IllegalArgumentException.class, () -> parser.parseScoreParseResult(
                "{\"A\":100}", List.of(node("A"), node("B")), 100));
        assertThrows(IllegalArgumentException.class, () -> parser.parseScoreParseResult(
                "{\"A\":{\"reason\":\"missing score\"}}", List.of(node("A")), 0));
    }
}
