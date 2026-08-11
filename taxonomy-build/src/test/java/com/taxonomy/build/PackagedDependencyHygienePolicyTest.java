package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PackagedDependencyHygienePolicyTest {

    private static final String EXPECTED = "3.0.8";

    private final PackagedDependencyHygienePolicy policy =
            new PackagedDependencyHygienePolicy();

    @Test
    void acceptsOneAlignedPdfboxFamilyAndPublishesStableEvidence(
            @TempDir Path root) throws Exception {
        Path sbom = writeSbom(root, """
                {"components":[
                  {"group":"org.apache.pdfbox","name":"pdfbox","version":"3.0.8"},
                  {"group":"org.apache.pdfbox","name":"fontbox","version":"3.0.8"},
                  {"group":"org.apache.pdfbox","name":"pdfbox-io","version":"3.0.8"},
                  {"group":"org.example","name":"unrelated","version":"1.0"}
                ]}
                """);

        PackagedDependencyHygienePolicy.Inspection inspection =
                policy.inspect(sbom, EXPECTED);
        String report = policy.report(sbom, inspection);

        assertThat(inspection.passed()).isTrue();
        assertThat(report)
                .contains("org.apache.pdfbox:pdfbox = 3.0.8")
                .contains("org.apache.pdfbox:fontbox = 3.0.8")
                .contains("Result: PASS")
                .doesNotContain("org.example:unrelated");
    }

    @Test
    void resolvesMavenCoordinatesFromPurlWhenGroupFieldsAreAbsent(
            @TempDir Path root) throws Exception {
        Path sbom = writeSbom(root, """
                {"components":[
                  {"name":"pdfbox","version":"3.0.8",
                   "purl":"pkg:maven/org.apache.pdfbox/pdfbox@3.0.8?type=jar"}
                ]}
                """);

        assertThat(policy.inspect(sbom, EXPECTED).passed()).isTrue();
    }

    @Test
    void rejectsEveryMismatchedPdfboxComponentInOneDecision(
            @TempDir Path root) throws Exception {
        Path sbom = writeSbom(root, """
                {"components":[
                  {"group":"org.apache.pdfbox","name":"pdfbox","version":"3.0.8"},
                  {"group":"org.apache.pdfbox","name":"fontbox","version":"2.0.35"},
                  {"group":"org.apache.pdfbox","name":"pdfbox-io","version":"3.0.7"}
                ]}
                """);

        PackagedDependencyHygienePolicy.Inspection inspection =
                policy.inspect(sbom, EXPECTED);

        assertThat(inspection.passed()).isFalse();
        assertThat(inspection.violations())
                .anyMatch(value -> value.contains("fontbox")
                        && value.contains("expected only 3.0.8"))
                .anyMatch(value -> value.contains("pdfbox-io")
                        && value.contains("expected only 3.0.8"));
    }

    @Test
    void rejectsLegacyConverterFamiliesEvenWhenPdfboxItselfIsAligned(
            @TempDir Path root) throws Exception {
        Path sbom = writeSbom(root, """
                {"components":[
                  {"group":"org.apache.pdfbox","name":"pdfbox","version":"3.0.8"},
                  {"group":"org.apache.pdfbox","name":"xmpbox","version":"3.0.8"},
                  {"group":"com.vladsch.flexmark","name":"flexmark-pdf-converter","version":"0.64.8"},
                  {"group":"com.openhtmltopdf","name":"openhtmltopdf-pdfbox","version":"1.0.10"}
                ]}
                """);

        PackagedDependencyHygienePolicy.Inspection inspection =
                policy.inspect(sbom, EXPECTED);

        assertThat(inspection.passed()).isFalse();
        assertThat(inspection.violations())
                .anyMatch(value -> value.contains("xmpbox"))
                .anyMatch(value -> value.contains("flexmark PDF converter"))
                .anyMatch(value -> value.contains("OpenHTML PDFBox adapter"));
    }

    @Test
    void rejectsMultipleVersionsForTheSamePackagedCoordinate(
            @TempDir Path root) throws Exception {
        Path sbom = writeSbom(root, """
                {"components":[
                  {"group":"org.apache.pdfbox","name":"pdfbox","version":"3.0.8"},
                  {"group":"org.apache.pdfbox","name":"pdfbox","version":"2.0.35"}
                ]}
                """);

        PackagedDependencyHygienePolicy.Inspection inspection =
                policy.inspect(sbom, EXPECTED);

        assertThat(inspection.passed()).isFalse();
        assertThat(inspection.violations())
                .anyMatch(value -> value.contains("pdfbox resolved to 2.0.35, 3.0.8"));
    }

    @Test
    void failsClosedWhenTheRootPdfboxArtifactIsMissing(@TempDir Path root)
            throws Exception {
        Path sbom = writeSbom(root, """
                {"components":[
                  {"group":"org.apache.pdfbox","name":"fontbox","version":"3.0.8"}
                ]}
                """);

        assertThat(policy.inspect(sbom, EXPECTED).violations())
                .contains("Packaged SBOM does not contain org.apache.pdfbox:pdfbox");
    }

    @Test
    void rejectsMissingMalformedAndIncompleteSbomInputs(@TempDir Path root)
            throws Exception {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy.inspect(root.resolve("missing.json"), EXPECTED))
                .withMessageContaining("SBOM not found");

        Path malformed = root.resolve("malformed.json");
        Files.writeString(malformed, "not json");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy.inspect(malformed, EXPECTED))
                .withMessageContaining("Cannot read packaged SBOM");

        Path noComponents = root.resolve("no-components.json");
        Files.writeString(noComponents, "{}");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy.inspect(noComponents, EXPECTED))
                .withMessageContaining("components array");

        Path noVersion = writeSbom(root.resolve("incomplete"), """
                {"components":[
                  {"group":"org.apache.pdfbox","name":"pdfbox"}
                ]}
                """);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy.inspect(noVersion, EXPECTED))
                .withMessageContaining("has no non-blank version");
    }

    @Test
    void writesTheHumanReadableReportToTheRequestedEvidencePath(
            @TempDir Path root) throws Exception {
        Path sbom = writeSbom(root, """
                {"components":[
                  {"group":"org.apache.pdfbox","name":"pdfbox","version":"3.0.8"}
                ]}
                """);
        PackagedDependencyHygienePolicy.Inspection inspection =
                policy.inspect(sbom, EXPECTED);
        Path report = root.resolve("target/dependency-hygiene-report.txt");

        policy.writeReport(report, policy.report(sbom, inspection));

        assertThat(report).hasContent("Packaged dependency hygiene\n\n"
                + "Source: " + sbom.toAbsolutePath().normalize() + "\n"
                + "Expected PDFBox version: 3.0.8\n\n"
                + "Relevant packaged components:\n"
                + "- org.apache.pdfbox:pdfbox = 3.0.8\n\n"
                + "Result: PASS\n");
    }

    private static Path writeSbom(Path root, String content) throws Exception {
        Files.createDirectories(root);
        Path sbom = root.resolve("taxonomy-sbom.json");
        Files.writeString(sbom, content);
        return sbom;
    }
}
