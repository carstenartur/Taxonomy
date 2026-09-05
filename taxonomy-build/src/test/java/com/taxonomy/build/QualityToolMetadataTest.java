package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Configuration alignment only: scanner execution requires separate run evidence. */
class QualityToolMetadataTest {
    private static final Pattern CODEQL_USE = Pattern.compile(
            "(?m)^\\h*(?:-\\h+)?uses:\\h*(['\"]?)(github/codeql-action(?:/[A-Za-z0-9_-]+)?)"
                    + "@([^\\s'\"#]+)\\1\\h*(?:#.*)?$");
    private static final Pattern METADATA = Pattern.compile(
            "--tool\\h+(['\"])codeql-action=([^'\"\\r\\n]+)\\1");
    private static final String FIRST = "a".repeat(40);
    private static final String SECOND = "b".repeat(40);

    @Test
    void publishedConfigurationMatchesEveryCodeqlWorkflowUse() throws IOException {
        Path root = repositoryRoot();
        verify(readWorkflows(root),
                Files.readString(root.resolve(".github/scripts/finalize-quality-evidence.sh")));
    }

    @Test
    void aDifferentPinInAnotherWorkflowIsNotMissed(@TempDir Path root) throws IOException {
        Path workflows = Files.createDirectories(root.resolve(".github/workflows"));
        Files.writeString(workflows.resolve("codeql.yml"), workflow(FIRST));
        Files.writeString(workflows.resolve("security.yaml"), workflow(FIRST));
        Files.writeString(workflows.resolve("notes.txt"), workflow(SECOND));
        Files.createDirectory(workflows.resolve("not-a-file.yml"));
        assertDoesNotThrow(() -> verify(readWorkflows(root), metadata(FIRST)));

        Files.writeString(workflows.resolve("security.yaml"),
                "steps:\n  - uses: github/codeql-action/upload-sarif@" + SECOND + "\n");
        assertThrows(AssertionError.class, () -> verify(readWorkflows(root), metadata(FIRST)));
    }

    @Test
    void oldVersionOnlyLabelCannotMasqueradeAsPinnedIdentity() {
        assertThrows(AssertionError.class, () -> verify(workflow(FIRST),
                "--tool 'codeql-action=v4.37.6'"));
    }

    @Test
    void changingTheWorkflowPinRequiresUpdatingTheMetadata() {
        assertThrows(AssertionError.class, () -> verify(workflow(SECOND), metadata(FIRST)));
        assertDoesNotThrow(() -> verify(workflow(SECOND), metadata(SECOND)));
    }

    @Test
    void mixedStagePinsCannotBePublishedAsOneIdentity() {
        assertThrows(AssertionError.class, () -> verify(
                workflow(FIRST) + "  - uses: github/codeql-action/analyze@" + SECOND + "\n",
                metadata(FIRST)));
    }

    @Test
    void absentAndMutableCodeqlReferencesAreRejected() {
        assertThrows(AssertionError.class, () -> verify("jobs: {}\n", metadata(FIRST)));
        assertThrows(AssertionError.class, () -> verify(workflow("v4"), metadata(FIRST)));
    }

    @Test
    void missingOrDuplicateMetadataIsRejected() {
        assertThrows(AssertionError.class, () -> verify(workflow(FIRST), "--tool 'java=21'"));
        assertThrows(AssertionError.class, () -> verify(workflow(FIRST),
                metadata(FIRST) + "\n" + metadata(FIRST)));
        assertThrows(AssertionError.class, () -> verify(workflow(FIRST),
                "# " + metadata(FIRST)));
    }

    @Test
    void commentsAreNotVersionAuthorityAndQuotedPinsRemainSupported() {
        String yaml = "steps:\n"
                + "  - uses: 'github/codeql-action/init@" + FIRST + "' # misleading v0.0.0\n"
                + "  - name: Analyze\n"
                + "    uses: \"github/codeql-action/analyze@" + FIRST + "\"\n";
        assertDoesNotThrow(() -> verify(yaml, metadata(FIRST)));
    }

    private static String readWorkflows(Path root) throws IOException {
        StringBuilder content = new StringBuilder();
        try (var files = Files.list(root.resolve(".github/workflows"))) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".yml") || name.endsWith(".yaml");
                    }).sorted().toList()) {
                content.append(Files.readString(file)).append('\n');
            }
        }
        return content.toString();
    }

    private static void verify(String workflow, String finalizer) {
        var references = CODEQL_USE.matcher(workflow).results().map(match -> match.group(3)).toList();
        assertFalse(references.isEmpty(), "No supported CodeQL uses entries found");
        for (String reference : references) {
            assertTrue(reference.matches("[0-9a-fA-F]{40}"), "CodeQL action must be commit-pinned");
        }
        var pins = references.stream().map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertEquals(1, pins.size(), "One metadata entry cannot describe differing CodeQL pins");
        String executableLines = finalizer.lines()
                .filter(line -> !line.stripLeading().startsWith("#"))
                .collect(Collectors.joining("\n"));
        var values = METADATA.matcher(executableLines).results().map(match -> match.group(2)).toList();
        assertEquals(1, values.size(), "Publish exactly one literal CodeQL configuration identity");
        assertEquals("configured:github/codeql-action@" + pins.iterator().next(), values.getFirst(),
                "Quality metadata drifted from the workflow; update both in the same change");
    }

    private static String workflow(String pin) {
        return "steps:\n  - uses: github/codeql-action/init@" + pin + " # release label\n"
                + "  - uses: github/codeql-action/analyze@" + pin + "\n";
    }

    private static String metadata(String pin) {
        return "--tool 'codeql-action=configured:github/codeql-action@" + pin + "'";
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve(".github/scripts/finalize-quality-evidence.sh"))
                    && Files.isDirectory(candidate.resolve("taxonomy-build"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Cannot locate the Taxonomy repository root");
    }
}
