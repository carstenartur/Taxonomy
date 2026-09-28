package com.taxonomy.acceptance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class OnnxReferenceEvaluationTest {
    @TempDir Path temporary;

    @Test void missingModelCannotPublishOldSuccessOrDropUnexecutedCases() throws Exception {
        Path outputDirectory = temporary.resolve("reports");
        Files.createDirectories(outputDirectory);
        Files.writeString(outputDirectory.resolve("report.json"), "STALE SUCCESS");
        Path jar = temporary.resolve("metadata-only-test.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("BOOT-INF/classes/git.properties"));
            output.write(("git.commit.id=" + "a".repeat(40) + "\ngit.dirty=false\n")
                    .getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        // Fails before any HTTP call: no ONNX model, provider or fabricated responses.
        assertThrows(java.io.IOException.class, () -> OnnxReferenceEvaluation.verify(
                URI.create("http://127.0.0.1:1"), "AUTHORIZATION_MUST_NOT_APPEAR", temporary, jar, outputDirectory));
        String text = Files.readString(outputDirectory.resolve("report.json"));
        var report = new ObjectMapper().readTree(text);
        assertEquals("ERROR", report.path("status").stringValue());
        assertEquals("LOCAL_ONNX_EVALUATION_ATTEMPT", report.path("evidenceKind").stringValue());
        assertEquals(12, report.path("cases").size());
        for (var row : report.path("cases")) {
            assertEquals("NOT_RUN", row.path("status").stringValue());
            assertTrue(row.path("measurement").isNull());
        }
        assertFalse(text.contains("AUTHORIZATION_MUST_NOT_APPEAR"));
        assertFalse(text.contains("STALE SUCCESS"));
    }
}
