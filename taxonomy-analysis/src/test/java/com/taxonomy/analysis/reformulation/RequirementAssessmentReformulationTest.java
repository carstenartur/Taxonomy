package com.taxonomy.analysis.reformulation;

import com.taxonomy.reformulation.NodeSynthesisInput;
import com.taxonomy.reformulation.ReconciliationInput;
import com.taxonomy.reformulation.ReformulationBaseline;
import com.taxonomy.reformulation.Section;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RequirementAssessmentReformulationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ORIGINAL = "Existing backups support recovery. Process responsibilities remain open.";

    @Test
    void nodePromptReceivesOnlyRelevantRecordedAssessmentsWithoutChangingSourceOrPromotingContext() {
        var baseline = baseline();
        var input = new NodeSynthesisInput(baseline,"BP-1503",null,"Service operations",List.of(),List.of(),
                List.of(),Map.of(),List.of(),List.of(),"Preserve the exact source and open decisions.");
        String prompt = new ReformulationPromptBuilder(JSON).build(input,null);
        var data = JSON.readTree(prompt.substring(prompt.indexOf("\nINPUT_DATA_JSON\n") + "\nINPUT_DATA_JSON\n".length()));
        assertThat(data.at("/baseline/originalText").asText()).isEqualTo(ORIGINAL);
        assertThat(data.at("/nodeAssessments/BP-1503/0/question").asText()).isEqualTo("Who owns recovery?");
        assertThat(data.at("/nodeAssessments/BP-1503/0/scope").asText()).isEqualTo("EXISTING_ENVIRONMENT");
        assertThat(data.at("/nodeAssessments/CR-1091").isMissingNode()).isTrue();
        assertThat(prompt).contains("recorded interpretations", "not human approval", "conditions", "open questions");
        assertThat(baseline.originalText()).isEqualTo(ORIGINAL);
    }

    @Test
    void reconciliationKeepsSelectedEvidenceAndExactConditionsWithoutAddingUnrelatedBranches() {
        var section = new Section("BP-1503","BP","Operations","",List.of(),List.of(),List.of());
        var input = new ReconciliationInput(baseline(),1,List.of(section),List.of(),List.of(),List.of(),Map.of(),Map.of(),List.of());
        String prompt = new ReconcilePromptBuilder(JSON).build(input,null);
        var data = JSON.readTree(prompt.substring(prompt.indexOf("\nRECONCILIATION_DATA_JSON\n") + "\nRECONCILIATION_DATA_JSON\n".length()));
        assertThat(data.at("/frozenNodeContext/nodeAssessments/BP-1503/0/condition").asText()).isEqualTo("When requested by the service owner");
        assertThat(data.at("/frozenNodeContext/nodeAssessments/CR-1091").isMissingNode()).isTrue();
        assertThat(data.at("/baseline/originalText").asText()).isEqualTo(ORIGINAL);
        assertThat(prompt).contains("recorded interpretations", "not human approval");
    }

    private static ReformulationBaseline baseline() {
        var scope = new ReformulationBaseline.Scope("repo","workspace","draft",1,1);
        String payload = """
                {"nodeAssessments":{"BP-1503":[{"requirementQuote":"Process responsibilities remain open.",
                  "subjectKinds":["PROCESS"],"requirementKinds":["ORGANIZATIONAL"],"contributionType":"SUPPORTS",
                  "evidenceStatus":"UNRESOLVED","evidenceBasis":"INFERRED","necessity":"CONTEXT",
                  "scope":"EXISTING_ENVIRONMENT","condition":"When requested by the service owner",
                  "rationale":"Existing operations need a defined owner.","question":"Who owns recovery?"}],
                  "CR-1091":[{"requirementQuote":"Existing backups support recovery.","rationale":"Unrelated branch for this node."}]}}
                """;
        return ReformulationBaseline.freeze(new ReformulationBaseline.Source(scope,1,ORIGINAL),
                new ReformulationBaseline.Snapshot(scope,"snapshot",1,payload),
                Map.of("reformulationPrompt","Frozen node instructions", "reconcilePrompt","Frozen reconciliation instructions"),"en","v1");
    }
}
