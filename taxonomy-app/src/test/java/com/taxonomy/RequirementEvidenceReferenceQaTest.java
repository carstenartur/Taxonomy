package com.taxonomy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequirementEvidenceReferenceQaTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void completionFreshnessAllowsTheFirstStageAndRejectsMissingOrReusedResponses() {
        RequirementEvidenceLiveSessionTest.requireFreshCompletionIds(Set.of("first"), Set.of());
        RequirementEvidenceLiveSessionTest.requireFreshCompletionIds(Set.of("second"), Set.of("first"));
        assertThatThrownBy(() -> RequirementEvidenceLiveSessionTest.requireFreshCompletionIds(Set.of(), Set.of()))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> RequirementEvidenceLiveSessionTest.requireFreshCompletionIds(Set.of("first"), Set.of("first")))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void exactQuoteAndSupportedMappingPassButInventedEvidenceFails() {
        var reference = json.readTree("""
                {"referenceId":"independent-test","unsupportedDirectDeliveryNodes":{"WRONG":"Wrong domain"}}
                """);
        var scenario = json.readTree("""
                {"id":"operations","requirement":"Maintain backup operations.","expectedSubjectKinds":["PROCESS"],
                 "requiredMappingGroups":[{"ids":["BP-1503","BP-2009"],"contributionTypes":["DIRECT_SUBJECT"],"evidenceStatus":"SUPPORTED"}]}
                """);
        var analysis = (ObjectNode) json.readTree("""
                {"nodeAssessments":{"BP-1503":[{"requirementQuote":"Maintain backup operations.",
                "subjectKinds":["PROCESS"],"contributionType":"DIRECT_SUBJECT","evidenceStatus":"SUPPORTED",
                "scope":"DELIVERY","necessity":"REQUIRED","question":""}]}}
                """);
        assertThat(RequirementEvidenceReferenceQa.evaluate(reference, scenario, analysis).get("checksPassed")).isEqualTo(true);
        analysis.put("status", "PARTIAL");
        assertThat(RequirementEvidenceReferenceQa.evaluate(reference, scenario, analysis).get("outcome")).isEqualTo("INCONCLUSIVE_COVERAGE");
        ((ObjectNode) analysis.at("/nodeAssessments/BP-1503/0")).put("requirementQuote", "Keep all backups for 30 days.");
        assertThat(RequirementEvidenceReferenceQa.evaluate(reference, scenario, analysis).get("checksPassed")).isEqualTo(false);
    }

    @Test
    void missingAssessmentAndMandatoryWrongDomainCannotPassAsSuccessfulQa() {
        var reference = json.readTree("""
                {"referenceId":"independent-test","unsupportedDirectDeliveryNodes":{"WRONG":"Wrong domain"}}
                """);
        var scenario = json.readTree("""
                {"id":"product","requirement":"Buy backup storage."}
                """);
        assertThat(RequirementEvidenceReferenceQa.evaluate(reference, scenario, json.readTree("{}"))
                .get("checksPassed")).isEqualTo(false);
        var analysis = json.readTree("""
                {"nodeAssessments":{"WRONG":[{"requirementQuote":"Buy backup storage.",
                "evidenceStatus":"SUPPORTED","scope":"DELIVERY","necessity":"REQUIRED"}]}}
                """);
        assertThat(RequirementEvidenceReferenceQa.evaluate(reference, scenario, analysis).get("checksPassed")).isEqualTo(false);
    }
}
