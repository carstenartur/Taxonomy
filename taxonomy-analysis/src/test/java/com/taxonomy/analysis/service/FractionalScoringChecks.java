package com.taxonomy.analysis.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.dto.AnalysisScoreSemantics;
import tools.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Authored catalogue-free cases, shared by the Maven/JUnit test and offline diagnosis. */
final class FractionalScoringChecks {
    static void parserAcceptsHalfAPercent() throws Exception {
        var parser = new LlmResponseParser(new JsonMapper());
        var result = parser.parseScoreParseResult("""
                {"first":{"score":0.5,"reason":"Half the requested contribution"},
                 "second":{"score":0.5,"reason":"The remaining contribution"}}
                """, List.of(node("first"), node("second")), 1);
        equal(.5, result.scores().get("first"));
        equal(.5, result.scores().get("second"));
    }
    static void normalizationPreservesBothPositiveChildren() {
        var parser = new LlmResponseParser(new JsonMapper());
        var result = parser.normalizeToParent(Map.of("first", 1, "second", 1), 1);
        equal(.5, result.get("first")); equal(.5, result.get("second"));
    }
    static void effectiveProductRelevanceKeepsSmallContribution() {
        equal(.5, AnalysisScoreSemantics.effectiveProductRelevance(1, 50));
    }
    static void customizedPromptsCarryTheDecimalContract() {
        var templates = new PromptTemplateService();
        templates.setTemplate("fixture", "Old customization: score is an integer. Budget {{PARENT_SCORE}}.");
        String prompt = templates.renderPrompt("fixture", "Authored fixture requirement", "two fixture children", .5, "first, second");
        if (!prompt.contains("decimal-percent-v2") || !prompt.contains("0.5 means 0.5%")
                || !prompt.contains("sum to 0.5")) throw new AssertionError("Effective custom prompt lacks the numeric contract");
    }
    static void exportUsesAVersionThatOldIntegerReadersMustReject() {
        var service = new SavedAnalysisService(new JsonMapper(), null);
        var saved = service.buildExport("Authored fixture", Map.of("first", .125), Map.of(), "fixture");
        if (saved.getVersion() != 4) throw new AssertionError("Decimal score export must declare version 4");
        saved.validateCoverageEvidence(); // A manual score export does not invent automatic coverage.
    }
    static void hierarchyAndSerializationRetainFractions() throws Exception {
        var parser = new LlmResponseParser(new JsonMapper());
        var quarters = parser.normalizeToParent(Map.of("a",1,"b",1,"c",1,"d",1), .5);
        for (var value : quarters.values()) equal(.125, value);
        double parent = .125;
        for (int i = 0; i < 100; i++) parent = parser.normalizeToParent(Map.of("single",1), parent).get("single");
        equal(.125,parent);
        var mapper = new JsonMapper();
        for (String json : List.of(
                "{\"scores\":{\"first\":85,\"zero\":0},\"rawScores\":{\"first\":0.125,\"zero\":0}}",
                "{\"rawScores\":{\"first\":0.125,\"zero\":0},\"scores\":{\"first\":85,\"zero\":0}}")) {
            var result = mapper.readValue(json, com.taxonomy.dto.AnalysisResult.class);
            equal(.125, result.getRawScores().get("first"));
            if (result.getRawScores().containsKey("unassessed")) throw new AssertionError("Absent assessment invented");
            var restored = mapper.readValue(mapper.writeValueAsString(result), com.taxonomy.dto.AnalysisResult.class);
            equal(.125, restored.getRawScores().get("first")); equal(0, restored.getRawScores().get("zero"));
        }
        var legacy = mapper.readValue("{\"scores\":{\"first\":85,\"zero\":0}}", com.taxonomy.dto.AnalysisResult.class);
        equal(85, legacy.getRawScores().get("first"));
        var saved = mapper.readValue("{\"version\":2,\"scores\":{\"first\":85}}", com.taxonomy.dto.SavedAnalysis.class);
        equal(85,saved.getScores().get("first")); saved.validateCoverageEvidence();
    }
    static void numericFailuresDoNotBecomeZeroEvidence() throws Exception {
        var parser = new LlmResponseParser(new JsonMapper());
        for (String value : List.of("-0.1", "100.000000000000000001", "1e-9999", "\"0.5\"", "null")) {
            boolean rejected = false;
            try { parser.parseScoreParseResult("{\"first\":{\"score\":" + value + ",\"reason\":\"Authored fixture\"}}", List.of(node("first")),1); }
            catch (IllegalArgumentException invalid) { rejected = true; }
            if (!rejected) throw new AssertionError("Invalid/unsupported score accepted: " + value);
        }
        equal(0, parser.normalizeToParent(Map.of("first",0),1).get("first"));
    }
    static void provisionalReasoningAcceptsDecimalScores() {
        var source = node("authored-capability"); source.setTaxonomyRoot("CP"); source.setNameEn("Time capture");
        var target = node("authored-service"); target.setTaxonomyRoot("CR"); target.setNameEn("Capture service");
        var catalogue = Map.of(source.getCode(), source, target.getCode(), target);
        var generator = new AnalysisRelationGenerator(new com.taxonomy.relations.service.RelationCompatibilityMatrix(),
                code -> java.util.Optional.ofNullable(catalogue.get(code)));
        var proposals = generator.generate(Map.of(source.getCode(), 50.125, target.getCode(), 75.5));
        if (proposals.isEmpty() || proposals.stream().anyMatch(proposal ->
                !proposal.getReasoning().contains("score 50.125") || !proposal.getReasoning().contains("score 75.5")))
            throw new AssertionError("Provisional reasoning lost the decimal scores");
    }
    static void equalWeightsRetainFractionalPrecision() {
        var parser = new LlmResponseParser(new JsonMapper());
        for (double parent : new double[] {100, 1, .5, .125, .0000001}) {
            var result = parser.normalizeToParent(Map.of("a", 1, "b", 1, "c", 1), parent);
            if (Math.abs(result.values().stream().mapToDouble(Double::doubleValue).sum() - parent) > Math.ulp(parent))
                throw new AssertionError("Child sum lost the parent budget: " + result);
            for (double value : result.values()) {
                if (!(value > 0) || Math.abs(value - parent / 3) > 2 * Math.ulp(parent / 3))
                    throw new AssertionError("Whole-point apportionment changed an equal child: " + result);
            }
        }
    }
    static void decimalRootAndRecoveredProductResponsesAreAccepted() throws Exception {
        var parser = new LlmResponseParser(new JsonMapper());
        var root = node("fixture");
        for (String value : List.of("0.125", "0.5", "0.999999999999999999", "1.000000000000000001", "99.5")) {
            var result = parser.parseScoreParseResult("{\"fixture\":{\"score\":" + value
                    + ",\"reason\":\"Authored root fixture\"}}", List.of(root), 100, ScoreAssessmentKind.ROOT_RELEVANCE);
            equal(new BigDecimal(value).doubleValue(), result.scores().get("fixture"));
        }
        String raw = "{\"first\":{\"score\":0.5,\"reason\":\"First contribution\"},"
                + "\"second\":{\"score\":0.125,\"reason\":\"Second contribution\"}";
        var result = parser.parseIndependentScoreParseResult(raw, List.of(node("first"), node("second")), 0);
        equal(.5, result.scores().get("first")); equal(.125, result.scores().get("second"));
    }
    static void equal(double expected, Number actual) {
        if (actual == null || new BigDecimal(actual.toString()).compareTo(BigDecimal.valueOf(expected)) != 0)
            throw new AssertionError("Expected " + expected + ", actual " + actual);
    }
    static TaxonomyNode node(String code) {
        var node = new TaxonomyNode(); node.setCode(code); node.setTaxonomyRoot("fixture"); return node;
    }
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "thirds" -> equalWeightsRetainFractionalPrecision();
            case "decimal-recovery" -> decimalRootAndRecoveredProductResponsesAreAccepted();
            case "hypothesis" -> provisionalReasoningAcceptsDecimalScores();
            case "parser" -> parserAcceptsHalfAPercent();
            case "normalization" -> normalizationPreservesBothPositiveChildren();
            case "product" -> effectiveProductRelevanceKeepsSmallContribution();
            case "custom-prompt" -> customizedPromptsCarryTheDecimalContract();
            case "export-version" -> exportUsesAVersionThatOldIntegerReadersMustReject();
            case "serialization" -> hierarchyAndSerializationRetainFractions();
            case "invalid" -> numericFailuresDoNotBecomeZeroEvidence();
            default -> throw new IllegalArgumentException(args[0]);
        }
        System.out.println("PASS " + args[0]);
    }
}
