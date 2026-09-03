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
            "suitable for import into tools such as Archi or Sparx EA",
            "importable into Archi, BiZZdesign, MEGA",
            "other ArchiMate-compatible tools",
            "for Archi or similar tools",
            "All exports use open formats",
            "Click **📥 Visio** to",
            "Click **📥 ArchiMate** to",
            "#965–#967",
            "production-ready editable Visio");

    private static final List<String> FORBIDDEN_GERMAN_CLAIMS = List.of(
            "kompatibel mit Visio 2013 und höher",
            "zum Import in Tools wie Archi oder Sparx EA geeignet",
            "geeignet für den Import in Tools wie Archi",
            "importierbar in Archi, BiZZdesign, MEGA",
            "andere ArchiMate-kompatible Tools",
            "für Archi oder ähnliche Tools",
            "f\\u00fcr Archi oder \\u00e4hnliche Tools",
            "Alle Exporte nutzen offene Formate",
            "Klicken Sie auf **📥 Visio**, um",
            "Klicken Sie auf **📥 ArchiMate**, um",
            "#965–#967",
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
    void publicArchitectureSurfacesReuseTheBoundedExportWording()
            throws IOException {
        Path repository = findRepositoryRoot();
        String englishArchitecture = read(repository, "docs/en/ARCHITECTURE.md");
        String germanArchitecture = read(repository, "docs/de/ARCHITECTURE.md");
        String englishGuide = read(repository, "docs/en/USER_GUIDE.md");
        String germanGuide = read(repository, "docs/de/USER_GUIDE.md");
        String index = read(repository,
                "taxonomy-app/src/main/resources/templates/index.html");
        String messages = read(repository,
                "taxonomy-app/src/main/resources/i18n/messages.properties");
        String germanMessages = read(repository,
                "taxonomy-app/src/main/resources/i18n/messages_de.properties");

        assertThat(englishArchitecture)
                .contains("Experimental bounded ArchiMate 3.1 subset")
                .contains("Experimental bounded Visio 2012 VSDX subset")
                .contains("independent-tool interoperability")
                .contains("Microsoft Visio desktop")
                .contains("FEATURE_MATRIX.md#architecture-export-support-boundary");
        assertThat(germanArchitecture)
                .contains("Experimentelle begrenzte ArchiMate-3.1-Teilmenge")
                .contains("Experimentelle begrenzte Visio-2012-VSDX-Teilmenge")
                .contains("Interoperabilität mit unabhängigen Werkzeugen")
                .contains("Microsoft-Visio-Desktop-Zertifizierung")
                .contains("FEATURE_MATRIX.md#unterstützungsgrenze-der-architekturexporte");
        assertThat(englishGuide)
                .contains("experimental bounded ArchiMate 3.1 subset")
                .contains("experimental bounded Visio 2012 VSDX subset")
                .contains("independent-tool interoperability")
                .contains("Microsoft Visio desktop")
                .contains("Click **📥 Visio 2012 subset**")
                .contains("Click **📥 ArchiMate 3.1 subset**")
                .contains("FEATURE_MATRIX.md#architecture-export-support-boundary")
                .contains("https://github.com/carstenartur/Taxonomy/issues/965")
                .contains("https://github.com/carstenartur/Taxonomy/issues/966")
                .contains("https://github.com/carstenartur/Taxonomy/issues/967");
        assertThat(germanGuide)
                .contains("experimentelle begrenzte ArchiMate-3.1-Teilmenge")
                .contains("experimentelle begrenzte Visio-2012-VSDX-Teilmenge")
                .contains("Interoperabilität mit unabhängigen Werkzeugen")
                .contains("Microsoft Visio Desktop")
                .contains("Klicken Sie auf **📥 Visio 2012 subset**")
                .contains("Klicken Sie auf **📥 ArchiMate 3.1 subset**")
                .contains("FEATURE_MATRIX.md#unterstützungsgrenze-der-architekturexporte")
                .contains("https://github.com/carstenartur/Taxonomy/issues/965")
                .contains("https://github.com/carstenartur/Taxonomy/issues/966")
                .contains("https://github.com/carstenartur/Taxonomy/issues/967");
        assertThat(index)
                .contains("ArchiMate 3.1 subset")
                .contains("Visio 2012 subset")
                .contains("mapping and loss manifest pending")
                .contains("Microsoft Visio desktop certification pending");
        assertThat(messages)
                .contains("experimental bounded ArchiMate 3.1 subset")
                .contains("experimental bounded Visio 2012 subset")
                .contains("mapping and loss manifest pending")
                .contains("Microsoft Visio desktop certification pending");
        assertThat(germanMessages)
                .contains("Experimentelle begrenzte ArchiMate-3.1-Teilmenge")
                .contains("Experimentelle begrenzte Visio-2012-Teilmenge")
                .contains("Mapping- und Verlustmanifest ausstehend")
                .contains("Microsoft-Visio-Desktop-Zertifizierung ausstehend");

        assertNoForbiddenClaims(
                List.of(englishArchitecture, englishGuide, index, messages),
                FORBIDDEN_ENGLISH_CLAIMS);
        assertNoForbiddenClaims(
                List.of(germanArchitecture, germanGuide, index, germanMessages),
                FORBIDDEN_GERMAN_CLAIMS);
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

    private static void assertNoForbiddenClaims(
            List<String> surfaces, List<String> forbiddenClaims) {
        for (String surface : surfaces) {
            for (String claim : forbiddenClaims) {
                assertThat(surface).doesNotContain(claim);
            }
        }
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
