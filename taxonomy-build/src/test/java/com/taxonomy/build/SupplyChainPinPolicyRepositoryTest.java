package com.taxonomy.build;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Repository-wide gate for immutable workflow actions and production images. */
class SupplyChainPinPolicyRepositoryTest {

    @Test
    void repositorySupplyChainReferencesAreImmutableAndEvidenceIsPublished() {
        Path root = findRepositoryRoot();
        Path report = root.resolve("target/supply-chain-pins.json");
        SupplyChainPinPolicy policy = new SupplyChainPinPolicy();
        SupplyChainPinPolicy.Inspection inspection = policy.inspect(root);
        policy.writeReport(report, inspection);
        System.out.println(inspection.summary());

        assertThat(inspection.passed())
                .as("supply-chain pin policy; inspect %s%n%s",
                        report, inspection.summary())
                .isTrue();
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve(".github/workflows"))
                    && Files.isRegularFile(current.resolve("Dockerfile"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate Taxonomy repository root");
    }
}
