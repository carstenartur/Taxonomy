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

        assertThat(workflow)
                .contains("core:\n    name: Core reactor verification")
                .contains("observability:\n    name: OpenTelemetry performance budget")
                .contains("ui-contracts:\n    name: UI contract verification")
                .contains("ui-shards:\n    name: UI shard / ${{ matrix.shard }}")
                .contains("verify:\n    name: Maven verification")
                .contains("needs: [core, observability, ui-contracts, ui-shards]")
                .contains("if: always()")
                .contains("name: taxonomy-ui-application")
                .contains("name: quality-reports-core")
                .contains("pattern: 'ui-verification-*'")
                .contains("merge-multiple: true")
                .contains("name: quality-reports")
                .contains("name: ui-verification");

        int coreStart = workflow.indexOf("  core:");
        int observabilityStart = workflow.indexOf("  observability:");
        int finalGateStart = workflow.indexOf("  verify:");
        assertThat(coreStart).isGreaterThanOrEqualTo(0);
        assertThat(observabilityStart).isGreaterThan(coreStart);
        assertThat(finalGateStart).isGreaterThan(observabilityStart);

        String core = workflow.substring(coreStart, observabilityStart);
        assertThat(core)
                .contains("./mvnw -B verify -Pci")
                .contains("-Dtaxonomy.ui.skip=true")
                .contains("::error::Docker image revision label")
                .contains("::error::Docker image runtime user")
                .doesNotContain("Measure OpenTelemetry performance budget");

        String finalGate = workflow.substring(finalGateStart);
        assertThat(finalGate)
                .contains("Require every authoritative lane")
                .contains("Verify complete digest-bound UI evidence")
                .contains("run: bash .github/scripts/finalize-quality-evidence.sh");
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
                .contains("run verify:ui-shard")
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
