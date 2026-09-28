package com.taxonomy.build;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;

class OnnxProfileWiringTest {
    @Test void onnxProfileSuppliesBothTestJvmsWithoutASecondProfile() throws Exception {
        OnnxProfileContract.verifyPom(pom());
    }

    @Test void missingModelPathCannotPassTheWiringContract() throws Exception {
        String broken = pom().replace("TAXONOMY_EMBEDDING_MODEL_DIR", "UNUSED_MODEL_DIRECTORY");
        assertThrows(AssertionError.class, () -> OnnxProfileContract.verifyPom(broken));
    }

    @Test void mavenPropertyAloneDoesNotEnableConditionalIntegrationTests() throws Exception {
        String broken = pom().replace("<runOnnxTests>${runOnnxTests}</runOnnxTests>", "");
        assertThrows(AssertionError.class, () -> OnnxProfileContract.verifyPom(broken));
    }

    @Test void workflowRequiresExecutionEvidenceAndPreservesEarlyFailures() throws Exception {
        OnnxProfileContract.verifyWorkflow(Files.readString(root().resolve(".github/workflows/local-onnx-reference.yml")));
    }

    private static String pom() throws Exception {
        return Files.readString(root().resolve("taxonomy-app/pom.xml"));
    }

    private static Path root() {
        Path path = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (path != null && !Files.isRegularFile(path.resolve("taxonomy-app/pom.xml"))) path = path.getParent();
        if (path == null) throw new IllegalStateException("Taxonomy checkout not found");
        return path;
    }
}
