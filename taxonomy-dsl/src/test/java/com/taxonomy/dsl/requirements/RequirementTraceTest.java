package com.taxonomy.dsl.requirements;

import com.taxonomy.dsl.command.ArchitectureSemanticPatch;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequirementTraceTest {
    @Test void repeatedContributionIsIdempotentAndHistoryKeepsItsOriginalVersion() {
        String first = RequirementTrace.contribute(RequirementRealizationTest.source("Record events", 1), "PROJECT__REQ-1", "FOLLOWUP");
        assertThat(RequirementTrace.contribute(first, "PROJECT__REQ-1", "FOLLOWUP")).isEqualTo(first);
        String second = RequirementRealizationTest.source("Record every event", 2);
        var next = ArchitectureSemanticPatch.index(second).get("requirement:PROJECT__REQ-1");
        second = ArchitectureSemanticPatch.replace(second, next, RequirementTrace.retain(next, ArchitectureSemanticPatch.index(first).get("requirement:PROJECT__REQ-1")));
        second = RequirementTrace.contribute(second, "PROJECT__REQ-1", "FOLLOWUP");
        assertThat(RequirementTrace.contributions(ArchitectureSemanticPatch.index(second).get("requirement:PROJECT__REQ-1")))
                .containsExactlyInAnyOrder(new RequirementTrace.Contribution("FOLLOWUP", 1, RequirementRealization.textHash("Record events")),
                        new RequirementTrace.Contribution("FOLLOWUP", 2, RequirementRealization.textHash("Record every event")));
    }

    @Test void relatedRequirementsKeepExactVersionsAndDoNotChangeOriginalTextOrIdentity() {
        String original = RequirementRealizationTest.source("Record events", 1);
        String linked = RequirementTrace.link(original, "PROJECT__REQ-1", "NEXT", "REQ-2", 3, RequirementRealization.textHash("Related requirement"));
        assertThat(RequirementTrace.link(linked, "PROJECT__REQ-1", "NEXT", "REQ-2", 3, RequirementRealization.textHash("Related requirement"))).isEqualTo(linked);
        var requirement = ArchitectureSemanticPatch.index(linked).get("requirement:PROJECT__REQ-1");
        assertThat(requirement.property("text")).isEqualTo("Record events");
        assertThat(requirement.property("x-project-key")).isEqualTo("PROJECT");
        assertThat(RequirementTrace.links(requirement)).containsExactly(new RequirementTrace.Link("NEXT", "REQ-2", 3,
                RequirementRealization.textHash("Related requirement")));
        assertThat(linked).startsWith("# Original document annotation\n");
    }
}
