package com.taxonomy.build;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseNotesSourceAlignmentTest {

    private static final Pattern BASELINE_ENTRY =
            Pattern.compile("\\\"ruleId\\\"\\s*:");
    private static final Pattern RELEASE_NOTES_BASELINE_COUNT =
            Pattern.compile(
                    "migration baseline contains exactly (\\d+) pre-existing findings",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern TEMPLATE_HEADER =
            Pattern.compile("X-Taxonomy-Template-[A-Za-z0-9-]+");

    @Test
    void codeQlMigrationClaimsMatchTheCheckedInBaseline() throws IOException {
        Path root = repositoryRoot();
        String releaseNotes = Files.readString(root.resolve("release_notes.md"));
        String baseline = Files.readString(
                root.resolve(".github/codeql-sarif-baseline.json"));

        long baselineEntries = BASELINE_ENTRY.matcher(baseline).results().count();
        Matcher documentedCount = RELEASE_NOTES_BASELINE_COUNT.matcher(releaseNotes);

        assertThat(documentedCount.find())
                .as("release notes publish one machine-checkable baseline count")
                .isTrue();
        assertThat(Long.parseLong(documentedCount.group(1)))
                .as("documented CodeQL baseline count")
                .isEqualTo(baselineEntries);
        assertThat(baseline)
                .doesNotContain("SecurityDataInitializer.java");
        assertThat(releaseNotes)
                .doesNotContain(
                        "replacement of startup-log delivery for a generated local bootstrap password")
                .contains("owner-only bootstrap credential file")
                .contains("logs only its non-secret path");
    }

    @Test
    void decisionReportTemplateProvenanceClaimsMatchTheHttpBoundary()
            throws IOException {
        Path root = repositoryRoot();
        String releaseNotes = Files.readString(root.resolve("release_notes.md"));
        String headerSource = Files.readString(root.resolve(
                "taxonomy-app/src/main/java/com/taxonomy/architecture/decision/"
                        + "DecisionReportTemplateHeaders.java"));

        Set<String> headers = new LinkedHashSet<>();
        TEMPLATE_HEADER.matcher(headerSource).results()
                .map(result -> result.group())
                .forEach(headers::add);

        assertThat(headers)
                .containsExactly(
                        "X-Taxonomy-Template-Id",
                        "X-Taxonomy-Template-Commit",
                        "X-Taxonomy-Template-SHA256",
                        "X-Taxonomy-Template-Schema-Version");
        assertThat(releaseNotes)
                .contains("Taxonomy.Template.SchemaVersion");
        headers.forEach(header -> assertThat(releaseNotes).contains(header));
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("release_notes.md"))
                    && Files.isRegularFile(candidate.resolve(
                            ".github/codeql-sarif-baseline.json"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "Could not locate the Taxonomy repository root");
    }
}
