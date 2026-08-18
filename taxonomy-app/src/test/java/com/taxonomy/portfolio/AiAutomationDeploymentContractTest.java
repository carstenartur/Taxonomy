package com.taxonomy.portfolio;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AiAutomationDeploymentContractTest {

    @Test
    void applicationEnvAndHelmDefaultsRemainFailClosed() throws Exception {
        Path root = repositoryRoot();
        String defaults = read(root,
                "taxonomy-app/src/main/resources/ai-automation-defaults.properties");
        String configuration = read(root,
                "taxonomy-app/src/main/java/com/taxonomy/portfolio/config/"
                        + "AiAutomationDefaultsConfiguration.java");
        String environment = read(root, ".env.example");
        String helm = read(root, "deploy/helm/taxonomy/values.yaml");

        assertThat(configuration)
                .contains("@PropertySource(\"classpath:ai-automation-defaults.properties\")");
        assertThat(defaults)
                .contains("taxonomy.ai.cost-policy=${TAXONOMY_AI_COST_POLICY:METERED}")
                .contains("taxonomy.ai.autopilot.enabled=${TAXONOMY_AI_AUTOPILOT_ENABLED:false}")
                .contains("taxonomy.ai.autopilot.provider=${TAXONOMY_AI_AUTOPILOT_PROVIDER:}")
                .contains("taxonomy.ai.coordinator.max-concurrent-operations=")
                .contains("taxonomy.ai.coordinator.queue-capacity=")
                .contains("taxonomy.ai.autopilot.max-project-requirements=");
        assertThat(environment)
                .contains("TAXONOMY_AI_COST_POLICY=METERED")
                .contains("TAXONOMY_AI_AUTOPILOT_ENABLED=false")
                .contains("TAXONOMY_AI_AUTOPILOT_PROVIDER=")
                .contains("TAXONOMY_AI_COORDINATOR_MAX_CONCURRENT_OPERATIONS=4")
                .contains("TAXONOMY_AI_COORDINATOR_QUEUE_CAPACITY=100");
        assertThat(helm)
                .contains("TAXONOMY_AI_COST_POLICY: METERED")
                .contains("TAXONOMY_AI_AUTOPILOT_ENABLED: \"false\"")
                .contains("TAXONOMY_AI_AUTOPILOT_PROVIDER: \"\"")
                .contains("TAXONOMY_AI_AUTOPILOT_MAX_PROJECT_REQUIREMENTS: \"50\"")
                .contains("TAXONOMY_AI_COORDINATOR_MAX_CONCURRENT_OPERATIONS: \"4\"")
                .contains("TAXONOMY_AI_COORDINATOR_QUEUE_CAPACITY: \"100\"");
    }

    @Test
    void projectRoutesAndHumanReviewBoundaryAreDocumentedInBothLanguages()
            throws Exception {
        Path root = repositoryRoot();
        String controller = read(root,
                "taxonomy-app/src/main/java/com/taxonomy/portfolio/controller/"
                        + "ProjectAutopilotController.java");
        String english = read(root, "docs/en/COPILOT_AUTOPILOT.md");
        String german = read(root, "docs/de/COPILOT_AUTOPILOT.md");

        assertThat(controller)
                .contains("@RequestMapping(\"/api/projects/{projectId}/autopilot\")")
                .contains("@PostMapping(\"/run\")");
        assertThat(english)
                .contains("POST /api/projects/{projectId}/autopilot/run")
                .contains("Generated solutions remain `PROPOSED`")
                .contains("authorize procurement");
        assertThat(german)
                .contains("POST /api/projects/{projectId}/autopilot/run")
                .contains("Lösungen bleiben `PROPOSED`")
                .contains("Beschaffung autorisieren");
    }

    private static String read(Path root, String relative) throws Exception {
        return Files.readString(root.resolve(relative), StandardCharsets.UTF_8);
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isDirectory(current.resolve("taxonomy-app"))
                    && Files.isDirectory(current.resolve("deploy/helm/taxonomy"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate Taxonomy repository root");
    }
}
