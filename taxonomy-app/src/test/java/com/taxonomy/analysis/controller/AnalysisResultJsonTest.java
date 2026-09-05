package com.taxonomy.analysis.controller;

import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.catalog.service.TaxonomyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises actual JSON binding, not only direct calls to the compatibility setters. */
@SpringBootTest
class AnalysisResultJsonTest {
    @Autowired
    private TaxonomyService taxonomyService;

    // Maven retains parameter names: the default mapper may use the public constructor.
    // The second mapper separately proves the bean/setter compatibility path.
    private static final List<JsonMapper> MAPPERS = List.of(
            JsonMapper.builder().build(),
            JsonMapper.builder().disable(MapperFeature.DETECT_PARAMETER_NAMES).build());

    @Test
    void nullRawEvidenceRetainsLegacyScoresRegardlessOfPropertyOrder() throws Exception {
        for (JsonMapper mapper : MAPPERS) {
            for (String json : List.of(
                    "{\"scores\":{\"IP\":40},\"rawScores\":null}",
                    "{\"rawScores\":null,\"scores\":{\"IP\":40}}",
                    "{\"scores\":{\"IP\":40}}")) {
                AnalysisResult result = mapper.readValue(json, AnalysisResult.class);
                assertEquals(Map.of("IP", 40), result.getRawScores(), json);
                assertEquals(Map.of("IP", 40), result.getScores(), json);
            }
        }
    }

    @Test
    void anExplicitEmptyRawMapCannotBeRepopulatedByLegacyScores() throws Exception {
        for (JsonMapper mapper : MAPPERS) {
            for (String json : List.of(
                    "{\"scores\":{\"IP\":40},\"rawScores\":{}}",
                    "{\"rawScores\":{},\"scores\":{\"IP\":40}}")) {
                AnalysisResult result = mapper.readValue(json, AnalysisResult.class);
                assertTrue(result.getRawScores().isEmpty(), json);
                assertTrue(result.getScores().isEmpty(), json);
                assertTrue(result.getScoreDetails().isEmpty(), json);
            }
        }
    }

    @Test
    void nonNullRawEvidenceWinsThroughConstructorAndSetterBinding() throws Exception {
        for (JsonMapper mapper : MAPPERS) {
            for (String json : List.of(
                    "{\"scores\":{\"IP\":32},\"rawScores\":{\"IP\":80}}",
                    "{\"rawScores\":{\"IP\":80},\"scores\":{\"IP\":32}}",
                    "{\"scores\":{},\"rawScores\":{\"IP\":80}}",
                    "{\"rawScores\":{\"IP\":80},\"scores\":{}}")) {
                assertEquals(Map.of("IP", 80),
                        mapper.readValue(json, AnalysisResult.class).getRawScores(), json);
            }
        }
    }

    @Test
    void absentEvidenceCannotRetainInjectedDerivedViews() throws Exception {
        for (JsonMapper mapper : MAPPERS) {
            for (String prefix : List.of("", "\"rawScores\":null,", "\"rawScores\":{},")) {
                AnalysisResult result = mapper.readValue("{" + prefix
                        + "\"effectiveScores\":{\"IP\":99},"
                        + "\"productSuitabilityScores\":{\"IP\":99},"
                        + "\"scoreSemanticsWarnings\":[\"stale\"],"
                        + "\"scoreSemanticsFingerprintSha256\":\"stale\"}", AnalysisResult.class);
                assertTrue(result.getRawScores().isEmpty());
                assertTrue(result.getScores().isEmpty());
                assertTrue(result.getProductSuitabilityScores().isEmpty());
                assertTrue(result.getScoreSemanticsWarnings().isEmpty());
                assertEquals(new AnalysisResult().getScoreSemanticsFingerprintSha256(),
                        result.getScoreSemanticsFingerprintSha256());
            }
        }
    }

    @Test
    void repeatedJsonRoundTripsPreserveRawEvidenceAndFingerprint() throws Exception {
        for (JsonMapper mapper : MAPPERS) {
            AnalysisResult result = mapper.readValue(
                    "{\"scores\":{\"IP\":32},\"rawScores\":{\"IP\":80}}", AnalysisResult.class);
            String fingerprint = result.getScoreSemanticsFingerprintSha256();
            for (int round = 0; round < 3; round++) {
                result = mapper.readValue(mapper.writeValueAsBytes(result), AnalysisResult.class);
                assertEquals(Map.of("IP", 80), result.getRawScores());
                assertEquals(fingerprint, result.getScoreSemanticsFingerprintSha256());
            }
        }
    }

    @Test
    void realCatalogueProductDoesNotLoseRelevanceOnRepeatedSnapshotRoundTrips() throws Exception {
        List<TaxonomyNodeDto> tree = taxonomyService.getFullTree();
        TaxonomyNodeDto product = findProduct(tree);
        assertTrue(product != null, "The real catalogue must contain a concrete product");
        String code = product.getCode();
        String parent = product.getParentCode();
        assertTrue(parent != null && !parent.equals(code));
        for (JsonMapper mapper : MAPPERS) {
            AnalysisResult result = new AnalysisResult(Map.of(parent, 40, code, 80), tree);
            String fingerprint = result.getScoreSemanticsFingerprintSha256();
            for (int round = 0; round < 3; round++) {
                result = mapper.readValue(mapper.writeValueAsBytes(result), AnalysisResult.class);
                assertEquals(80, result.getRawScores().get(code));
                assertEquals(80, result.getProductSuitabilityScores().get(code));
                assertEquals(32, result.getScores().get(code));
                assertEquals(fingerprint, result.getScoreSemanticsFingerprintSha256());
            }
        }
    }

    private static TaxonomyNodeDto findProduct(List<TaxonomyNodeDto> nodes) {
        if (nodes == null) return null;
        for (TaxonomyNodeDto node : nodes) {
            if ("PRODUCT".equals(node.getAnalysisRole())) return node;
            TaxonomyNodeDto nested = findProduct(node.getChildren());
            if (nested != null) return nested;
        }
        return null;
    }
}
