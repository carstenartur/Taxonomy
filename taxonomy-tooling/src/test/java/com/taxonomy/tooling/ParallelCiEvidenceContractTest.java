package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ParallelCiEvidenceContractTest {

    @Test
    void manualLocalReactorHasItsOwnRunAndRetainsEvidenceWhenMavenFails() throws Exception {
        String workflow = Files.readString(findRepositoryRoot().resolve(".github/workflows/ci-cd.yml"));
        String requested = "github.event_name == 'workflow_dispatch' && inputs.verify_test_local";
        assertThat(workflow).contains("verify_test_local:", "type: boolean\n        default: false",
                "github.run_id || github.ref", "if: " + requested,
                "if: github.event_name != 'workflow_dispatch' || !inputs.verify_test_local",
                "if: always() && (github.event_name != 'workflow_dispatch' || !inputs.verify_test_local)");
        int start = workflow.indexOf("\n  unfiltered-test-local:");
        assertThat(start).isGreaterThan(0);
        String job = workflow.substring(start);
        assertThat(job).contains("timeout-minutes: 360", "set -o pipefail",
                "./mvnw -B verify -Ptest-local 2>&1 | tee", "-Dmaven.build.cache.enabled=false",
                "check-junit-reports", "ProductDemoEndToEndTest-product-demo-isolated.xml", "ProductDemoProcessRecoveryTest.xml",
                "BrowserSessionIT.xml", "if: always()", "**/target/surefire-reports/TEST-*.xml",
                "**/target/failsafe-reports/TEST-*.xml", "maven.log", "source.txt")
                .doesNotContain("-DskipTests", "-DskipITs", "-Dtest=", "-Dit.test=", "continue-on-error:");
    }

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
                .doesNotContain("\n  ui-system-packages:")
                .contains("ui-shards:\n    name: UI shard / ${{ matrix.shard }}")
                .contains("plugin-profile:\n    name: Complete plugin packaging profile")
                .contains("verify:\n    name: Maven verification")
                .contains("needs: [application, ui-plan, ui-contracts]")
                .contains("needs: [application, production-image, app-test-shards, core, coverage-aggregate, observability, ui-contracts, ui-shards, interoperability-products, plugin-profile, change-scope, documentation]")
                .contains("if: always()")
                .contains("name: taxonomy-ui-application")
                .contains("pattern: app-test-shard-*-success")
                .contains("name: quality-reports-core")
                .contains("name: junit-plugin-profile")
                .contains("Stage source-bound original plugin reports")
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

        String application = workflow.substring(applicationStart, productionImageStart);
        assertThat(application)
                .contains("bash .github/scripts/package-ui-application.sh")
                .contains("run: bash .github/scripts/stage-ui-application.sh")
                .contains("name: taxonomy-ui-application");

        String core = workflow.substring(coreStart, coverageAggregateStart);
        assertThat(core)
                .contains("./mvnw -B verify -Pci,ci-app-test-shards-core")
                .contains("-Dtaxonomy.ui.skip=true")
                .contains("name: app-test-shard-core-attempt-${{ github.run_attempt }}-${{ job.status }}")
                .contains("core-reactor-outputs.tar.gz")
                .contains("*/target/jacoco.exec")
                .doesNotContain("Stage commit-bound UI application")
                .doesNotContain("Verify production Docker image build")
                .doesNotContain("Download all isolated application test evidence")
                .doesNotContain("Measure OpenTelemetry performance budget")
                .doesNotContain("needs: [app-test-shards]");

        String coverageAggregate = workflow.substring(coverageAggregateStart, observabilityStart);
        assertThat(coverageAggregate)
                .contains("path: target/app-test-shard-attempts")
                .contains("bash .github/scripts/select-app-test-shard-attempts.sh")
                .doesNotContain("merge-multiple: true")
                .contains("needs: [core, app-test-shards]")
                .contains("--verify true")
                .contains("--verify-inputs true")
                .contains("com.taxonomy.tooling.TaxonomyTooling check-app-test-shards")
                .contains("./mvnw -B process-test-classes -Pci,ci-app-test-shards-coverage")
                .contains("sha256sum --check target/core-reactor-files.sha256")
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
        assertThat(Files.readString(root.resolve("taxonomy-build/src/test/java/com/taxonomy/build/ProductionImageIT.java"))).contains(".github/scripts/build-production-image.sh").contains("org.opencontainers.image.revision");
        String appShards = workflow.substring(appShardsStart, coreStart);
        assertThat(appShards)
                .contains("matrix: ${{ fromJSON(needs.app-test-plan.outputs.matrix) }}")
                .contains("./mvnw -B -pl taxonomy-tooling,taxonomy-app -am verify -P\"ci,$APP_TEST_PROFILE\"")
                .contains("Stage digest-bound shard reports and coverage")
                .contains("app-test-shard-${{ matrix.shard }}-attempt-${{ github.run_attempt }}-${{ job.status }}");
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
                .contains("<phase>validate</phase>")
                .contains("<argument>check-app-test-shards</argument>");
        assertThat(buildPom)
                .contains("<id>coverage-policy-only</id>")
                .contains("**/ReactorCoveragePolicyIT.java");
        assertThat(cacheConfig)
                .contains("<reconcile propertyName=\"groups\"/>")
                .contains("<reconcile propertyName=\"excludedGroups\"/>");

        String uiShards = workflow.substring(uiShardsStart, finalGateStart);
        assertThat(uiShards)
                .contains("needs: [application, ui-plan, ui-contracts]")
                .contains("name: UI system packages / Paketinstallation (download only)")
                .contains("name: UI system packages / Paketinstallation (offline)")
                .contains("key: ${{ steps.cache-key.outputs.key }}")
                .doesNotContain("needs.ui-system-packages")
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
                .contains("verify-quality-publication")
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

    @Test
    void selectsOneCompleteSuccessfulAttemptPerShard(@TempDir Path root)
            throws Exception {
        Files.createDirectories(root.resolve(".mvn"));
        Files.writeString(root.resolve(".mvn/verification-suites.json"),
                "{\"applicationTestShards\":{\"groups\":{\"browser\":{},\"core\":{}}}}");
        Path latestBrowser = shardAttempt(root, "browser", 10, "success");
        Files.writeString(shardAttempt(root, "browser", 2, "success").resolve("old-only.xml"), "old");
        Files.writeString(shardAttempt(root, "browser", 9, "failure").resolve("failed-only.xml"), "failed");
        shardAttempt(root, "core", 1, "success");

        var select = new ProcessBuilder("bash", findRepositoryRoot()
                .resolve(".github/scripts/select-app-test-shard-attempts.sh").toString());
        select.directory(root.toFile()).redirectErrorStream(true);
        select.environment().put("GITHUB_RUN_ATTEMPT", "10");
        select.environment().put("GITHUB_RUN_ID", "42");
        Process selected = select.start();
        String output = new String(selected.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(selected.waitFor()).as(output).isZero();
        Path inputs = root.resolve("target/app-test-shards");
        assertThat(Files.readString(inputs.resolve("browser/manifest.json"))).contains("42.10.browser");
        assertThat(Files.readString(inputs.resolve("core/manifest.json"))).contains("42.1.core");
        assertThat(Files.exists(inputs.resolve("browser/old-only.xml"))).isFalse();
        assertThat(Files.exists(inputs.resolve("browser/failed-only.xml"))).isFalse();

        Files.move(inputs, root.resolve("first-result"));
        Files.writeString(latestBrowser.resolve("manifest.json"), "{\"buildId\":\"41.10.browser\"}");
        Process wrongRun = select.start();
        assertThat(wrongRun.waitFor()).isNotZero();
        assertThat(Files.exists(inputs)).isFalse();
    }

    private static Path shardAttempt(Path root, String shard, int attempt, String status) throws Exception {
        Path directory = root.resolve("target/app-test-shard-attempts")
                .resolve("app-test-shard-" + shard + "-attempt-" + attempt + "-" + status).resolve(shard);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("manifest.json"),
                "{\"buildId\":\"42." + attempt + "." + shard + "\"}");
        return directory;
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
