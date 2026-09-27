package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeatureTestOwnershipTest {
    @TempDir Path temporary;

    @Test void featureTestsStayWithTheirOwnersWithoutDuplicateApplicationCopies() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve(".mvn/verification-suites.json"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Taxonomy checkout not found");
        FeatureTestOwnershipContract.verify(root);
    }

    @Test void aMissingMovedTestIsNotAccepted() throws Exception {
        assertThatThrownBy(() -> FeatureTestOwnershipContract.verify(temporary))
                .isInstanceOf(AssertionError.class).hasMessageContaining("Missing owned test source");
    }

    @Test void duplicateApplicationTestsAreNotAccepted() throws Exception {
        for (var entry : FeatureTestOwnershipContract.OWNERS.entrySet()) {
            Path owned = temporary.resolve(entry.getValue()).resolve("src/test/java").resolve(entry.getKey());
            Files.createDirectories(owned.getParent()); Files.writeString(owned, "test source");
        }
        var entry = FeatureTestOwnershipContract.OWNERS.entrySet().iterator().next();
        Path duplicate = temporary.resolve("taxonomy-app/src/test/java").resolve(entry.getKey());
        Files.createDirectories(duplicate.getParent()); Files.writeString(duplicate, "duplicate");
        assertThatThrownBy(() -> FeatureTestOwnershipContract.verify(temporary))
                .isInstanceOf(AssertionError.class).hasMessageContaining("not taxonomy-app");
    }
}
