package com.taxonomy.portfolio.workbench;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ArchitectureExportDocumentationContractTest {

    private static final List<String> FORBIDDEN_ENGLISH_CLAIMS = List.of(
            "compatible with Visio 2013 and later",
            "suitable for import into tools such as Archi",
            "importable into Archi, BiZZdesign, MEGA",
            "production-ready editable Visio");

    private static final List<String> FORBIDDEN_GERMAN_CLAIMS = List.of(
            "kompatibel mit Visio 2013 und höher",
            "zum Import in Tools wie Archi oder Sparx EA geeignet",
            "importierbar in Archi, BiZZdesign, MEGA",
            "produktionsreifer editierbarer Visio");

    @Test
    void bilingualMatricesDistinguishGenerationFromCertification()
            throws IOException {
        Path repository = findRepositoryRoot();
        String english = read(repository, "docs/en/FEATURE_MATRIX.md");
        String german = read(repository, "docs/de/FEATURE_MATRIX.md");

        assertThat(english)
                .contains("Architecture export support boundary")
                .contains("Snapshot-bound browser/SVG/vector PDF views")
                .contains("Mermaid/JSON architecture projections")
                .contains("Experimental bounded ArchiMate 3.1 subset")
                .contains("mapping and loss manifest pending")
                .contains("Independent-tool interoperability is not certified")
                .contains("Experimental bounded Visio 2012 subset")
                .contains("Microsoft Visio desktop certification pending")
                .contains("open, edit, save, and reopen")
                .contains("does not invoke the LLM")
                .contains("do not infer snapshot equivalence without endpoint evidence")
                .contains("#964")
                .contains("#965")
                .contains("#966")
                .contains("#967")
                .doesNotContain("Export (ArchiMate/Visio/Mermaid/JSON)")
                .doesNotContain("](#architecture-export-support-boundary)");

        assertThat(german)
                .contains("Unterstützungsgrenze der Architekturexporte")
                .contains("Snapshot-gebundene Browser-/SVG-/Vektor-PDF-Ansichten")
                .contains("Mermaid-/JSON-Architekturprojektionen")
                .contains("Experimentelle begrenzte ArchiMate-3.1-Teilmenge")
                .contains("Mapping- und Verlustmanifest ausstehend")
                .contains("Interoperabilität mit unabhängigen Werkzeugen ist nicht zertifiziert")
                .contains("Experimentelle begrenzte Visio-2012-Teilmenge")
                .contains("Microsoft-Visio-Desktop-Zertifizierung ausstehend")
                .contains("Öffnen, Bearbeiten, Speichern und erneutes Öffnen")
                .contains("ruft das LLM nicht auf")
                .contains("darf keine Snapshot-Gleichheit angenommen werden")
                .contains("#964")
                .contains("#965")
                .contains("#966")
                .contains("#967")
                .doesNotContain("Export (ArchiMate/Visio/Mermaid/JSON)")
                .doesNotContain("](#unterstützungsgrenze-der-architekturexporte)");

        for (String claim : FORBIDDEN_ENGLISH_CLAIMS) {
            assertThat(english).doesNotContain(claim);
        }
        for (String claim : FORBIDDEN_GERMAN_CLAIMS) {
            assertThat(german).doesNotContain(claim);
        }
    }

    @Test
    void workbenchLabelsRemainAlignedWithTheBoundary()
            throws IOException {
        Path repository = findRepositoryRoot();
        String template = read(repository,
                "taxonomy-app/src/main/resources/templates/"
                        + "architecture-workbench.html");
        String controller = read(repository,
                "taxonomy-app/src/main/java/com/taxonomy/portfolio/workbench/"
                        + "ArchitectureWorkbenchController.java");

        assertThat(template)
                .contains("Experimental architecture export formats")
                .contains("Download ArchiMate 3.1 subset")
                .contains("mapping and loss manifest pending")
                .contains("Download Visio 2012 subset")
                .contains("Microsoft Visio desktop certification pending");
        assertThat(controller)
                .contains("schema-validated ArchiMate 3.1 supported subset")
                .contains("experimental bounded Visio 2012 VSDX package")
                .doesNotContain("as editable Visio VSDX");
    }

    private static String read(Path repository, String relativePath)
            throws IOException {
        return Files.readString(repository.resolve(relativePath));
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty(
                        "maven.multiModuleProjectDirectory", "."))
                .toAbsolutePath()
                .normalize();
        while (current != null) {
            if (Files.isRegularFile(
                    current.resolve(".mvn/verification-suites.json"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException(
                "Unable to locate Taxonomy repository root");
    }
}
