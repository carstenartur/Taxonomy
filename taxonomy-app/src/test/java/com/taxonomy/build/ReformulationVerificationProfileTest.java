package com.taxonomy.build;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ReformulationVerificationProfileTest {
    @Test
    void preservesMavenOwnedSelectionAndPositiveRepositoryGuardEvidence() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (root != null && !Files.isRegularFile(root.resolve(".mvn/verification-suites.json"))) {
            root = root.getParent();
        }
        if (root == null) throw new AssertionError("Repository root not found");
        ReformulationVerificationProfileCheck.verify(root);
    }
}
