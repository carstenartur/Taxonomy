package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ParallelCiEvidenceContractTest {

    @Test
    void ciKeepsOneRequiredGateWhileParallelizingIndependentEvidence()
            throws Exception {
        Path root = findRepositoryRoot();
        String workflow = Files.readString(
                root.resolve(".github/workflows/ci-cd.yml"),
                StandardCharsets.UTF_8);
        String finalizer = Files.readString(
                root.resolve(".github/scripts/finalize-quality-evidence.sh"),
                StandardCharsets.UTF_8);
        String coveragePom = Files.readString(
                root.resolve("taxonomy-coverage/pom.xml"),
                StandardCharsets.UTF_8);
        String appPom = Files.readString(
                root.resolve("taxonomy-app/pom.xml"),
                StandardCharsets.UTF_8);
        String buildPom = Files.readString(
                root.resolve("taxonomy-build/pom.xml"),
                StandardCharsets.UTF_8);
        String cacheConfig = Files.readString(
                root.resolve(".mvn/maven-build-cache-config.xml"),
                StandardCharsets.UTF_8);
        String shardCatalogue = Files.readString(
                root.resolve(".mvn/verification-suites.json"),
                StandardCharsets.UTF_8);

        assertThat(workflow)
                .contains("application:\n    name: Commit-bound UI application")
                .contains("production-image:\n    name: Production Docker image verification")
                .contains("app-test-shards:\n    name: Application test shard / ${{ matrix.shard }}")
                .contains("core:\n    name: Core reactor verification")
                .contains("observability:\n    name: OpenTelemetry performance budget")
                .contains("ui-contracts:\n    name: UI contract verification")
                .contains("ui-system-packages:\n    name: UI system packages / Paketvorbereitung")
                .contains("ui-shards:\n    name: UI shard / ${{ matrix.shard }}")
                .contains("plugin-profile:\n    name: Complete plugin packaging profile")
                .contains("verify:\n    name: Maven verification")
                .contains("needs: [application, ui-plan, ui-contracts, ui-system-packages]")
                .contains("needs: [application, production-image, app-test-shards, core, coverage-aggregate, observability, ui-contracts, ui-shards, interoperability-products, plugin-profile]")
                .contains("if: always()")
                .contains("name: taxonomy-ui-application")
                .contains("pattern: app-test-shard-*")
                .contains("name: quality-reports-core")
                .contains("pattern: 'ui-verification-*'")
                .contains("merge-multiple: true")
                .contains("name: quality-reports")
                .contains("name: ui-verification");

        int applicationStart = workflow.indexOf("  application:");
        int productionImageStart = workflow.indexOf("  production-image:");
        int appTestShardsStart = workflow.indexOf("  app-test-shards:");
        int coreStart = workflow.indexOf("  core:");
        int coverageAggregateStart = workflow.indexOf("  coverage-aggregate:");
        int observabilityStart = workflow.indexOf("  observability:");
        int uiShardsStart = workflow.indexOf("  ui-shards:");
        int finalGateStart = workflow.indexOf("  verify:");
        assertThat(applicationStart).isGreaterThanOrEqualTo(0);
        assertThat(productionImageStart).isGreaterThan(applicationStart);
        assertThat(appTestShardsStart).isGreaterThan(productionImageStart);
        assertThat(coreStart).isGreaterThan(appTestShardsStart);
        assertThat(coverageAggregateStart).isGreaterThan(coreStart);
        assertThat(observabilityStart).isGreaterThan(coverageAggregateStart);
        assertThat(uiShardsStart).isGreaterThan(observabilityStart);
        assertThat(finalGateStart).isGreaterThan(uiShardsStart);

        String application = workflow.substring(applicationStart, coreStart);
        assertThat(application)
                .contains("run: bash .github/scripts/package-ui-application.sh")
                .contains("run: bash .github/scripts/stage-ui-application.sh")
                .contains("name: taxonomy-ui-application");

        String core = workflow.substring(coreStart, coverageAggregateStart);
        assertThat(core)
                .contains("./mvnw -B verify -Pci,ci-app-test-shards-core")
                .contains("-Dtaxonomy.ui.skip=true")
                .contains("name: app-test-shard-core")
                .contains("core-reactor-outputs.tar.gz")
                .contains("*/target/jacoco.exec")
                .doesNotContain("Stage commit-bound UI application")
                .doesNotContain("Verify production Docker image build")
                .doesNotContain("Download all isolated application test evidence")
                .doesNotContain("Measure OpenTelemetry performance budget")
                .doesNotContain("needs: [app-test-shards]");

        String coverageAggregate = workflow.substring(coverageAggregateStart, observabilityStart);
        assertThat(coverageAggregate)
                .contains("needs: [core, app-test-shards]")
                .contains("--verify true")
                .contains("--verify-inputs true")
                .contains("com.taxonomy.tooling.TaxonomyTooling check-app-test-shards")
                .contains("./mvnw -B verify -Pci,ci-app-test-shards-coverage")
                .contains("name: quality-reports-coverage")
                .contains("core-reactor-outputs.tar.gz")
                .doesNotContain("./mvnw -B -pl taxonomy-coverage");

        int imageStart = workflow.indexOf("  production-image:");
        int planStart = workflow.indexOf("  app-test-plan:");
        int appShardsStart = workflow.indexOf("  app-test-shards:");
        assertThat(imageStart).isGreaterThan(applicationStart);
        assertThat(planStart).isGreaterThan(imageStart);
        assertThat(appShardsStart).isGreaterThan(planStart);
        String image = workflow.substring(imageStart, planStart);
        assertThat(image).contains("verify -Pimage");
        assertThat(Files.readString(root.resolve("taxonomy-build/src/test/java/com/taxonomy/build/ProductionImageIT.java"))).contains("\"docker\",\"build\"").contains("org.opencontainers.image.revision");
        String appShards = workflow.substring(appShardsStart, coreStart);
        assertThat(appShards)
                .contains("matrix: ${{ fromJSON(needs.app-test-plan.outputs.matrix) }}")
                .contains("./mvnw -B -pl taxonomy-tooling,taxonomy-app -am verify -P\"ci,$APP_TEST_PROFILE\"")
                .contains("Stage digest-bound shard reports and coverage")
                .contains("app-test-shard-${{ matrix.shard }}");
        assertThat(shardCatalogue).contains("\"applicationTestShards\"")
                .contains("ci-app-test-shard-recovery")
                .contains("ci-app-test-shard-browser")
                .contains("IntegrationPublicationBoundaryRestartTest")
                .contains("ReformulationScenarioBrowserTest");
        assertThat(coveragePom)
                .contains("<id>merge-app-test-shard-executions</id>")
                .contains("<include>*/coverage.exec</include>")
                .contains("jacoco-shards-merged.exec")
                .contains("${taxonomy.app.shard.coverageInclude}");
        assertThat(appPom)
                .contains("<id>stage-core-app-test-shard</id>")
                .contains("<phase>post-integration-test</phase>");
        assertThat(buildPom)
                .contains("<id>verify-complete-app-test-shard-inventory</id>")
                .contains("<phase>test</phase>")
                .contains("<argument>check-app-test-shards</argument>");
        assertThat(buildPom)
                .contains("<id>coverage-policy-only</id>")
                .contains("**/ReactorCoveragePolicyIT.java");
        assertThat(cacheConfig)
                .contains("<reconcile propertyName=\"groups\"/>")
                .contains("<reconcile propertyName=\"excludedGroups\"/>");

        String uiShards = workflow.substring(uiShardsStart, finalGateStart);
        assertThat(uiShards)
                .contains("needs: [application, ui-plan, ui-contracts, ui-system-packages]")
                .doesNotContain("needs: [core");

        String finalGate = workflow.substring(finalGateStart);
        assertThat(finalGate)
                .contains("APPLICATION_RESULT: ${{ needs.application.result }}")
                .contains("PRODUCTION_IMAGE_RESULT: ${{ needs.production-image.result }}")
                .contains("APP_TEST_SHARDS_RESULT: ${{ needs.app-test-shards.result }}")
                .contains("COVERAGE_AGGREGATE_RESULT: ${{ needs.coverage-aggregate.result }}")
                .contains("INTEROPERABILITY_RESULT: ${{ needs.interoperability-products.result }}")
                .contains("PLUGIN_PROFILE_RESULT: ${{ needs.plugin-profile.result }}")
                .contains("Require every authoritative lane")
                .contains("Verify complete digest-bound UI evidence")
                .contains("run: bash .github/scripts/finalize-quality-evidence.sh");
        assertThat(core)
                .contains("cache: maven")
                .doesNotContain(".m2/build-cache")
                .doesNotContain("maven.build.cache.skipCache=true");
        assertThat(finalizer)
                .contains("verify-quality-publication.py")
                .contains("--expected-commit \"$GITHUB_SHA\"");
    }

    @Test
    void uiShardSelectionAndConsolidationRemainRepositoryAndMavenOwned()
            throws Exception {
        Path root = findRepositoryRoot();
        String pom = Files.readString(
                root.resolve(".github/ui-verification-pom.xml"),
                StandardCharsets.UTF_8);
        String packageJson = Files.readString(
                root.resolve(".github/package.json"),
                StandardCharsets.UTF_8);
        String shardPlan = Files.readString(
                root.resolve(".github/ui-shards.json"),
                StandardCharsets.UTF_8);
        String applicationVerifier = Files.readString(
                root.resolve(".github/scripts/verify-ui-application.sh"),
                StandardCharsets.UTF_8);
        String verifier = Files.readString(
                root.resolve(".github/scripts/verify-ui-shards.mjs"),
                StandardCharsets.UTF_8);

        assertThat(pom)
                .contains("<id>contracts</id>")
                .contains("<id>shard</id>")
                .contains("<id>evidence-gate</id>")
                .contains("**/UiScenarioIT.java")
                .contains("run verify:ui-shards");
        assertThat(packageJson)
                .contains("\"test:ui-shard-plan\"")
                .contains("\"verify:ui-contracts\"")
                .contains("\"verify:ui-shard\"")
                .contains("\"verify:ui-shards\"");
        assertThat(shardPlan)
                .contains("\"schemaVersion\": 1")
                .contains("\"shards\"")
                .contains("role-state/mobile-admin-webkit")
                .contains("special-modes/text-spacing-and-offline");
        assertThat(applicationVerifier)
                .contains("expected_source_tree=$(git rev-parse")
                .contains("UI application source tree is");
        assertThat(verifier)
                .contains("applicationArtifactSha256")
                .contains("createReadStream")
                .contains("execFileAsync")
                .contains("UI application source tree mismatch")
                .contains("UI shard evidence directory mismatch")
                .contains("Consolidated UI scenario inventory mismatch")
                .contains("appears in both")
                .contains("actualApplicationSha256");
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isRegularFile(current.resolve(
                            ".github/workflows/ci-cd.yml"))
                    && Files.isRegularFile(current.resolve(
                            "taxonomy-tooling/pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate Taxonomy repository root");
    }
}
