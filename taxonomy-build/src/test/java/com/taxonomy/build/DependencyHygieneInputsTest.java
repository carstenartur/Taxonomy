package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class DependencyHygieneInputsTest {

    private final DependencyHygieneInputs inputs = new DependencyHygieneInputs();

    @Test
    void readsCycloneDxComponents(@TempDir Path root) throws Exception {
        Path sbom = write(root.resolve("bom.json"), validSbom("3.0.8"));

        var components = inputs.readComponents(sbom);

        assertThat(components)
                .extracting(DependencyHygienePolicy.Component::coordinate)
                .containsExactly(
                        "org.apache.pdfbox:pdfbox:3.0.8",
                        "org.apache.pdfbox:pdfbox-io:3.0.8",
                        "org.apache.pdfbox:fontbox:3.0.8");
    }

    @Test
    void rejectsMalformedOrEmptySbom(@TempDir Path root) throws Exception {
        Path malformed = write(root.resolve("malformed.json"), "not-json");
        Path empty = write(root.resolve("empty.json"), "{\"components\":[]}");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> inputs.readComponents(malformed))
                .withMessageContaining("cannot read SBOM");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> inputs.readComponents(empty))
                .withMessageContaining("contains no dependency components");
    }

    @Test
    void materializesModuleSbomAndXmlAtCanonicalRootPath(@TempDir Path root)
            throws Exception {
        Path moduleJson = write(
                root.resolve("taxonomy-app/target/taxonomy-sbom.json"),
                validSbom("3.0.8"));
        Path moduleXml = write(
                root.resolve("taxonomy-app/target/taxonomy-sbom.xml"),
                "<bom/>\n");
        Path requested = root.resolve("target/taxonomy-sbom.json");

        DependencyHygieneInputs.MaterializedSbom materialized =
                inputs.materializeRequestedSbom(root, requested);

        assertThat(materialized.sourcePath()).isEqualTo(moduleJson);
        assertThat(materialized.requestedPath()).isEqualTo(requested);
        assertThat(Files.readString(requested)).isEqualTo(Files.readString(moduleJson));
        assertThat(Files.readString(root.resolve("target/taxonomy-sbom.xml")))
                .isEqualTo(Files.readString(moduleXml));
        assertThat(materialized.components()).hasSize(3);
    }

    @Test
    void skipsBrokenCandidateAndUsesNextDeterministicFallback(@TempDir Path root)
            throws Exception {
        Path requested = write(root.resolve("target/taxonomy-sbom.json"), "{}");
        Path fallback = write(
                root.resolve("taxonomy-app/target/bom.json"),
                validSbom("3.0.8"));

        DependencyHygieneInputs.MaterializedSbom materialized =
                inputs.materializeRequestedSbom(root, requested);

        assertThat(materialized.sourcePath()).isEqualTo(fallback);
        assertThat(Files.readString(requested)).isEqualTo(Files.readString(fallback));
    }

    @Test
    void failsWhenNoUsableSbomExists(@TempDir Path root) throws Exception {
        write(root.resolve("target/taxonomy-sbom.json"), "{}");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> inputs.materializeRequestedSbom(
                        root, root.resolve("target/taxonomy-sbom.json")))
                .withMessageContaining("no usable CycloneDX SBOM available")
                .withMessageContaining("contains no dependency components");
    }

    @Test
    void loadsCompleteUnexpiredReviewedExceptions(@TempDir Path root) throws Exception {
        Path ledger = write(root.resolve("exceptions.json"), """
                [
                  {
                    "group": "org.apache.pdfbox",
                    "name": "pdfbox",
                    "version": "2.0.31",
                    "owner": "dependency-owner",
                    "rationale": "temporary compatibility",
                    "expires": "2026-08-10",
                    "removalCondition": "remove legacy consumer"
                  }
                ]
                """);

        var exceptions = inputs.loadExceptions(
                ledger, LocalDate.of(2026, 8, 10));

        assertThat(exceptions).singleElement().satisfies(exception -> {
            assertThat(exception.component().coordinate())
                    .isEqualTo("org.apache.pdfbox:pdfbox:2.0.31");
            assertThat(exception.owner()).isEqualTo("dependency-owner");
            assertThat(exception.expires()).isEqualTo(LocalDate.of(2026, 8, 10));
        });
    }

    @Test
    void rejectsExpiredIncompleteDuplicateAndNonArrayLedgers(@TempDir Path root)
            throws Exception {
        Path expired = write(root.resolve("expired.json"), exceptionArray(
                "2026-08-09", true, false));
        Path incomplete = write(root.resolve("incomplete.json"), exceptionArray(
                "2026-08-11", false, false));
        Path duplicate = write(root.resolve("duplicate.json"), exceptionArray(
                "2026-08-11", true, true));
        Path nonArray = write(root.resolve("object.json"), "{}");
        LocalDate today = LocalDate.of(2026, 8, 10);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> inputs.loadExceptions(expired, today))
                .withMessageContaining("expired dependency exception");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> inputs.loadExceptions(incomplete, today))
                .withMessageContaining("missing non-blank fields")
                .withMessageContaining("removalCondition");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> inputs.loadExceptions(duplicate, today))
                .withMessageContaining("duplicate dependency exception");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> inputs.loadExceptions(nonArray, today))
                .withMessageContaining("must be a JSON array");
    }

    private static String validSbom(String version) {
        return """
                {
                  "bomFormat": "CycloneDX",
                  "components": [
                    {"group":"org.apache.pdfbox","name":"pdfbox","version":"%s"},
                    {"group":"org.apache.pdfbox","name":"pdfbox-io","version":"%s"},
                    {"group":"org.apache.pdfbox","name":"fontbox","version":"%s"}
                  ]
                }
                """.formatted(version, version, version);
    }

    private static String exceptionArray(
            String expires,
            boolean includeRemovalCondition,
            boolean duplicate) {
        String removal = includeRemovalCondition
                ? ",\"removalCondition\":\"remove legacy consumer\""
                : "";
        String entry = """
                {"group":"org.apache.pdfbox","name":"pdfbox","version":"2.0.31",
                 "owner":"dependency-owner","rationale":"temporary compatibility",
                 "expires":"%s"%s}
                """.formatted(expires, removal);
        return duplicate ? "[" + entry + "," + entry + "]" : "[" + entry + "]";
    }

    private static Path write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }
}
