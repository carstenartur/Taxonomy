package com.taxonomy.analysis.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import com.taxonomy.dto.RequirementNodeAssessment;
import com.taxonomy.dto.RequirementNodeAssessment.*;

import static org.junit.jupiter.api.Assertions.*;

class RequirementAssessmentParserTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SOURCE = "Use the existing archive. If required, duplicate it to tape.";
    private static final String CLAIM = """
            {"requirementQuote":"Use the existing archive.","subjectKinds":["PRODUCT"],"requirementKinds":["FUNCTIONAL"],
             "contributionType":"SUPPORTS","evidenceStatus":"SUPPORTED","evidenceBasis":"INFERRED",
             "necessity":"REQUIRED","scope":"EXISTING_ENVIRONMENT","condition":"","rationale":"The archive enables recovery.","question":""}
            """;
    private static final String OPEN = """
            {"requirementQuote":"","subjectKinds":["PROCESS"],"requirementKinds":["FUNCTIONAL"],
             "contributionType":"DIRECT_SUBJECT","evidenceStatus":"UNRESOLVED","evidenceBasis":"UNSPECIFIED",
             "necessity":"ALTERNATIVE","scope":"UNSPECIFIED","condition":"","rationale":"Destination is unknown.","question":"Which tape library is used?"}
            """;

    @Test
    void supportedIndirectAndUnresolvedDirectClaimsSurviveInPassageOrder() throws Exception {
        var parsed = parse(response(CLAIM + "," + OPEN), SOURCE);
        assertEquals(Map.of("BP", 5d), parsed.scores());
        var claims = parsed.nodeAssessments().get("BP");
        assertEquals(List.of(EvidenceStatus.SUPPORTED, EvidenceStatus.UNRESOLVED),
                claims.stream().map(RequirementNodeAssessment::evidenceStatus).toList());
        assertEquals(ContributionType.SUPPORTS, claims.getFirst().contributionType());
        assertEquals(Scope.EXISTING_ENVIRONMENT, claims.getFirst().scope());
        assertEquals(ContributionType.DIRECT_SUBJECT, claims.getLast().contributionType());
        assertEquals("Which tape library is used?", claims.getLast().question());
    }

    @Test
    void conflictAndNeutralStatesAreIndependentOfScore() throws Exception {
        String conflict = CLAIM.replace("SUPPORTED", "CONFLICT");
        String neutral = CLAIM.replace("SUPPORTED", "OUT_OF_SCOPE")
                .replace("Use the existing archive.", "").replace("INFERRED", "UNSPECIFIED");
        var claims = parse(response(conflict + "," + neutral), SOURCE).nodeAssessments().get("BP");
        assertEquals(List.of(EvidenceStatus.CONFLICT, EvidenceStatus.OUT_OF_SCOPE),
                claims.stream().map(RequirementNodeAssessment::evidenceStatus).toList());
    }

    @Test
    void rejectsClaimsWithoutOriginalEvidenceOrRequiredContext() {
        for (String invalid : List.of(
                CLAIM.replace("Use the existing archive.", "Use the catalogue archive."),
                CLAIM.replace("Use the existing archive.", "use the existing archive."),
                CLAIM.replace("REQUIRED", "OPTIONAL"),
                CLAIM.replace("REQUIRED", "ALTERNATIVE"),
                OPEN.replace("Which tape library is used?", ""),
                OPEN.replace("\"evidenceBasis\":\"UNSPECIFIED\"", "\"evidenceBasis\":\"EXPLICIT\""),
                CLAIM.replace("INFERRED", "UNSPECIFIED"),
                CLAIM.replace("\"PRODUCT\"", "\"PRODUCT\",\"PRODUCT\""),
                CLAIM.replace("\"question\"", "\"extra\":42,\"question\""),
                CLAIM + "," + CLAIM,
                String.join(",", java.util.Collections.nCopies(9, CLAIM)))) {
            assertThrows(IllegalArgumentException.class, () -> parse(response(invalid), SOURCE), invalid);
        }
    }

    @Test
    void conditionalClaimsSurviveWithVerbatimQuotes() throws Exception {
        String optional = CLAIM.replace("REQUIRED", "OPTIONAL").replace("\"condition\":\"\"", "\"condition\":\"If required\"")
                .replace("Use the existing archive.", "If required, duplicate it to tape.");
        String alternative = optional.replace("OPTIONAL", "ALTERNATIVE").replace("SUPPORTS", "PROVIDES");
        var claims = parse(response(optional + "," + alternative), SOURCE).nodeAssessments().get("BP");
        assertEquals(List.of(Necessity.OPTIONAL, Necessity.ALTERNATIVE), claims.stream().map(RequirementNodeAssessment::necessity).toList());
        assertEquals("If required, duplicate it to tape.", claims.getFirst().requirementQuote());
        assertEquals("If required", claims.getFirst().condition());
    }

    @Test
    void legacyReplyLeavesEvidenceAbsent() throws Exception {
        assertTrue(parse("""
                {"BP":{"score":5,"reason":"Old provider"}}
                """, SOURCE).nodeAssessments().isEmpty());
    }

    @Test
    void typedRootCanRetainEvidenceWithoutAllocatingAScore() throws Exception {
        var parsed = parse("""
                {"BP":{"status":"UNRESOLVED","score":null,"reason":"Missing destination","question":"Where?",
                       "assessments":[%s]}}
                """.formatted(OPEN), SOURCE);
        assertTrue(parsed.scores().isEmpty());
        assertEquals(EvidenceStatus.UNRESOLVED, parsed.nodeAssessments().get("BP").getFirst().evidenceStatus());
    }

    @Test
    void productThresholdLeavesSupportedEvidenceAndCatalogueOrderIntact() throws Exception {
        var first = new TaxonomyNode();
        first.setCode("IP-1");
        var second = new TaxonomyNode();
        second.setCode("IP-2");
        var parsed = new LlmResponseParser(JSON).parseIndependentScoreParseResult("""
                {"IP-2":{"score":7,"reason":"Relevant archive","assessments":[%s]},
                 "IP-1":{"score":8,"reason":"Relevant backup","assessments":[%s]}}
                """.formatted(CLAIM, CLAIM), List.of(first, second), 50, SOURCE);
        assertEquals(Map.of("IP-1", 0d, "IP-2", 0d), parsed.scores());
        assertEquals(List.of("IP-1", "IP-2"), List.copyOf(parsed.nodeAssessments().keySet()));
        assertEquals(EvidenceStatus.SUPPORTED, parsed.nodeAssessments().get("IP-2").getFirst().evidenceStatus());
    }

    @Test
    void completionSchemasPermitSourceEvidenceInExistingNodeObjects() {
        var root = AssessmentCompletion.forRoot("BP", 1000);
        var child = AssessmentCompletion.forChildren(List.of("BP-2"), 1000);
        var rootNode = JSON.valueToTree(root.schema()).path("properties").path("BP").path("anyOf").path(0);
        var childNode = JSON.valueToTree(child.schema()).path("$defs").path("assessment");
        assertTrue(rootNode.path("properties").has("assessments"));
        assertTrue(childNode.path("properties").has("assessments"));
    }

    @Test
    void renderedPromptDistinguishesContributionDirectionAndConditionalObligations() {
        var templates = new PromptTemplateService();
        templates.setTemplate("BP", "Business Requirement: {{BUSINESS_TEXT}}");
        String prompt = templates.renderPrompt("BP", SOURCE, "BP-1: Example", 100, "BP-1");
        assertTrue(prompt.contains("DIRECT_SUBJECT:") && prompt.contains("own function"));
        assertTrue(prompt.contains("SUPPORTS:") && prompt.contains("downstream"));
        assertTrue(prompt.contains("REQUIRES:") && prompt.contains("depends"));
        assertTrue(prompt.contains("original passage") && prompt.contains("REQUIRED can be conditional"));
    }

    private static String response(String claims) {
        return """
                {"BP":{"score":5,"reason":"Ranking explanation","assessments":[%s]}}
                """.formatted(claims);
    }

    private static LlmService.ScoreParseResult parse(String response, String source) throws Exception {
        var root = new TaxonomyNode();
        root.setCode("BP");
        root.setTaxonomyRoot("BP");
        var parser = new LlmResponseParser(JSON);
        return parser.parseScoreParseResult(response, List.of(root), 100d, ScoreAssessmentKind.ROOT_RELEVANCE, source);
    }
}
