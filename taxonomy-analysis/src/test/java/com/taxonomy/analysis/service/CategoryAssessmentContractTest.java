package com.taxonomy.analysis.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Category semantics use the same complete child set, but keep parent normalization. */
class CategoryAssessmentContractTest {
    private final LlmResponseParser parser = new LlmResponseParser(JsonMapper.builder().build());
    private final List<TaxonomyNode> nodes = List.of(node("BP"), node("CP"));

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"BP\":100}",
        "{\"BP\":60,\"CP\":40,\"IP\":20}",
        "{\"BP\":{\"reason\":\"no score\"},\"CP\":100}",
        "{\"BP\":null,\"CP\":100}",
        "{\"BP\":\"60\",\"CP\":40}",
        "{\"BP\":true,\"CP\":40}",
        "{\"BP\":-1,\"CP\":100}",
        "{\"BP\":101,\"CP\":0}",
        "{\"BP\":60.5,\"CP\":39.5}",
        "{\"BP\":4294967296,\"CP\":100}",
        "{\"BP\":60,\"BP\":0,\"CP\":40}"
    })
    void invalidAssessmentCannotCreateZeroOrNormalizedEvidence(String response) {
        assertThrows(RuntimeException.class, () -> parser.parseScoreParseResult(response, nodes, 100));
    }

    @Test
    void completeScoresKeepOfferedOrderReasonsAndParentBudget() throws Exception {
        var result = parser.parseScoreParseResult(
                "{\"CP\":{\"score\":20,\"reason\":\"support\"},\"BP\":60}", nodes, 40);
        assertEquals(Map.of("BP", 30, "CP", 10), result.scores());
        assertEquals(List.of("BP", "CP"), List.copyOf(result.scores().keySet()));
        assertEquals("support", result.reasons().get("CP"));
        assertNotNull(result.discrepancy());
    }

    @Test
    void explicitZerosAreValidNegativeEvidence() throws Exception {
        assertEquals(Map.of("BP", 0, "CP", 0), parser.parseScoreParseResult(
                "{\"BP\":0,\"CP\":{\"score\":0,\"reason\":\"not requested\"}}", nodes, 100).scores());
    }

    @Test
    void zeroParentBudgetDoesNotMakeAnOmittedDecisionValid() {
        assertThrows(RuntimeException.class, () -> parser.parseScoreParseResult("{\"BP\":0}", nodes, 0));
    }

    @Test
    void duplicateOfferedIdsAreRejected() {
        assertThrows(RuntimeException.class, () -> parser.parseScoreParseResult(
                "{\"BP\":100}", List.of(node("BP"), node("BP")), 100));
    }

    private static TaxonomyNode node(String code) {
        TaxonomyNode node = new TaxonomyNode(); node.setCode(code); node.setTaxonomyRoot(code);
        return node;
    }
}
