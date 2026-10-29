package com.taxonomy.portfolio;

import com.taxonomy.portfolio.model.ArchitectureProject;
import com.taxonomy.portfolio.model.PortfolioTypes.*;
import com.taxonomy.portfolio.model.ProjectConflict;
import com.taxonomy.portfolio.model.ProjectRequirement;
import com.taxonomy.portfolio.service.ProjectConflictService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectConflictConfidenceSemanticsTest {
    private static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");
    private final ProjectConflictService service = new ProjectConflictService(null, null, null, null);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void responseIdentifiesFixedRuleWeightWithoutChangingExistingScore() {
        var json = mapper.valueToTree(service.toView(conflict()));

        assertThat(json.path("confidenceBasis").asText()).isEqualTo("UNCALIBRATED_RULE_WEIGHT");
        assertThat(json.path("confidence").asDouble()).isEqualTo(0.88);
        assertThat(json.path("status").asText()).isEqualTo("PROPOSED");
    }

    @Test
    void humanConfirmationDoesNotTurnRuleWeightIntoCalibratedProbability() {
        ProjectConflict conflict = conflict();
        conflict.review(ConflictStatus.CONFIRMED, "Both requirements apply to the same deployment.",
                "reviewer", NOW.plusSeconds(60));
        var json = mapper.valueToTree(service.toView(conflict));

        assertThat(json.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(json.path("reviewedBy").asText()).isEqualTo("reviewer");
        assertThat(json.path("confidenceBasis").asText()).isEqualTo("UNCALIBRATED_RULE_WEIGHT");
        assertThat(json.path("confidence").asDouble()).isEqualTo(0.88);
    }

    private static ProjectConflict conflict() {
        var project = new ArchitectureProject("private", "private", "architect", "P", "Hosting",
                null, ProjectStatus.ACTIVE, NOW);
        var cloud = requirement(project, "CLOUD");
        var local = requirement(project, "LOCAL");
        return new ProjectConflict(project, cloud, local, ConflictType.HOSTING, "a".repeat(64),
                "Hosting constraints", "Matching rule terms require human interpretation.", 0.88, NOW);
    }

    private static ProjectRequirement requirement(ArchitectureProject project, String key) {
        return new ProjectRequirement(project, key, key, RequirementStatus.DRAFT, 50,
                Criticality.MEDIUM, RequirementType.SECURITY, ReviewStatus.PROPOSED, "architect", NOW);
    }
}
