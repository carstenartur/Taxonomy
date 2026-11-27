package com.taxonomy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

class RequirementEvidenceSourceFidelityQaTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonNode REFERENCE = JSON.readTree("{\"referenceId\":\"altscherbitz-backup-2026-v1\"}");

    @Test
    void exactQuotesWithoutSemanticReviewRemainUnreviewed() {
        var scenario = scenario("Langfristige Aufbewahrung von Sicherungsdaten.");
        var analysis = analysis(scenario, "Langfristiger Speicher ist gefordert.");
        assertThat(evaluate(scenario, analysis, null).path("outcome").asText()).isEqualTo("UNREVIEWED");
    }

    @Test
    void inventedQuantityFailsEvenWhenTheQuotationIsValid() {
        var scenario = scenario("Langfristige Aufbewahrung von Sicherungsdaten.");
        var analysis = analysis(scenario, "30 Tage Aufbewahrung sind vorgeschrieben.");
        assertFailure(scenario, analysis, "UNSUPPORTED_QUANTITY");
        for (String invented : java.util.List.of("Ein RTO von 4 Stunden ist vorgeschrieben.",
                "Ein RPO von 15 Minuten ist einzuhalten.", "Die Sicherung muss täglich laufen.")) {
            claim(analysis).put("rationale", invented);
            assertFailure(scenario, analysis, "UNSUPPORTED_QUANTITY");
        }
        claim(analysis).put("rationale", "Eine Frist von 30 Tagen ist nicht festgelegt.");
        assertThat(evaluate(scenario, analysis, null).path("deterministicFindings").isEmpty()).isTrue();
    }

    @Test
    void existingInfrastructureIsNotNewDelivery() {
        var scenario = scenario("Die bestehende Umgebung nutzt VEEAM, ein NetApp-Storage-System und ein Tape-System.");
        var analysis = analysis(scenario, "Die genannten Systeme sind neu zu liefern.");
        assertFailure(scenario, analysis, "EXISTING_AS_DELIVERY");
        claim(analysis).put("scope", "EXISTING_ENVIRONMENT");
        assertThat(evaluate(scenario, analysis, null).path("deterministicFindings").isEmpty()).isTrue();
    }

    @Test
    void theQuotedSecondSiteCannotBeReplacedWithTheFirstSite() {
        var scenario = scenario("Das zusätzliche Storage-System ist an einem zweiten Standort zu installieren.");
        var analysis = analysis(scenario, "Das zusätzliche System ist ausschließlich am ersten Standort zu installieren.");
        assertFailure(scenario, analysis, "SITE_CHANGED");
    }

    @Test
    void conditionalOptimizationRequiresBothNeedAndConsultation() {
        var scenario = scenario("Bestehende Betriebsprozesse sind bei Bedarf in Abstimmung mit dem Auftraggeber zu optimieren.");
        var analysis = analysis(scenario, "Die Betriebsprozesse müssen optimiert werden.");
        assertFailure(scenario, analysis, "OPTIMIZATION_CONDITION_LOST");
        claim(analysis).put("condition", "bei Bedarf");
        assertFailure(scenario, analysis, "OPTIMIZATION_CONDITION_LOST");
        claim(analysis).put("condition", "bei Bedarf in Abstimmung mit dem Auftraggeber");
        assertThat(evaluate(scenario, analysis, null).path("deterministicFindings").isEmpty()).isTrue();
    }

    @Test
    void anOpenPassageCannotBecomeAnExplicitMandatoryDelivery() {
        var scenario = scenario("Die Anforderungen an die Betriebsabläufe und die Abnahme sind in dieser Fassung noch offen.");
        var analysis = analysis(scenario, "Der Auftragnehmer muss den Abnahmeprozess ausführen.");
        assertFailure(scenario, analysis, "OPEN_DETAIL_PROMOTED");
    }

    @Test
    void reviewMustCoverEachUnchangedClaimAndKeepJudgementFindings() {
        var scenario = scenario("Langfristige Aufbewahrung von Sicherungsdaten.");
        var analysis = analysis(scenario, "Langfristiger Speicher ist gefordert.");
        var review = RequirementEvidenceSourceFidelityQa.reviewTemplate(REFERENCE, scenario, analysis);
        var decision = (ObjectNode) review.at("/claims/0");
        decision.put("verdict", "ACCEPTED");
        decision.put("rationale", "Storage contribution follows from the stated retention function.");
        assertThat(evaluate(scenario, analysis, review).path("outcome").asText()).isEqualTo("PASSED_REVIEWED_SCOPE");
        decision.put("verdict", "ISSUE");
        assertThat(evaluate(scenario, analysis, review).path("outcome").asText()).isEqualTo("FAILED_SEMANTIC_REVIEW");
        decision.put("verdict", "QUALIFIED");
        assertThat(evaluate(scenario, analysis, review).path("outcome").asText()).isEqualTo("REVIEWED_WITH_QUALIFICATIONS");
        claim(analysis).put("rationale", "A different interpretation requires a fresh review.");
        assertThat(evaluate(scenario, analysis, review).path("reviewStatus").asText()).isEqualTo("INCOMPLETE_OR_STALE");
    }

    private static ObjectNode scenario(String text) {
        return JSON.createObjectNode().put("id", "case").put("requirement", text);
    }

    private static ObjectNode analysis(JsonNode scenario, String rationale) {
        var a = JSON.createObjectNode().put("status", "SUCCESS");
        a.putObject("nodeAssessments").putArray("CR-1091").addObject()
                .put("requirementQuote", scenario.path("requirement").asText())
                .put("evidenceStatus", "SUPPORTED").put("evidenceBasis", "EXPLICIT")
                .put("necessity", "REQUIRED").put("scope", "DELIVERY")
                .put("contributionType", "PROVIDES").put("condition", "")
                .put("rationale", rationale).put("question", "");
        return a;
    }

    private static ObjectNode claim(JsonNode analysis) {
        return (ObjectNode) analysis.at("/nodeAssessments/CR-1091/0");
    }

    private static JsonNode evaluate(JsonNode scenario, JsonNode analysis, JsonNode review) {
        return JSON.valueToTree(RequirementEvidenceSourceFidelityQa.evaluate(REFERENCE, scenario, analysis, review));
    }

    private static void assertFailure(JsonNode scenario, JsonNode analysis, String code) {
        var result = evaluate(scenario, analysis, null);
        assertThat(result.path("outcome").asText()).isEqualTo("FAILED_SCOPED_CHECKS");
        assertThat(result.path("deterministicFindings").toString()).contains(code);
    }
}
