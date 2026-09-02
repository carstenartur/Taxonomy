package com.taxonomy.portfolio.workbench;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureSnapshotExportUiContractTest {

    @Test
    void workbenchOffersSnapshotBoundArchiMateAndVisioDownloads()
            throws IOException {
        Path repository = findRepositoryRoot();
        String template = Files.readString(repository.resolve(
                "taxonomy-app/src/main/resources/templates/"
                        + "architecture-workbench.html"));
        String api = Files.readString(repository.resolve(
                "taxonomy-app/src/main/resources/static/js/api/"
                        + "architecture-workbench-api.js"));
        String adapter = Files.readString(repository.resolve(
                "taxonomy-app/src/main/resources/static/js/"
                        + "architecture-workbench-export.js"));
        String controller = Files.readString(repository.resolve(
                "taxonomy-app/src/main/java/com/taxonomy/portfolio/workbench/"
                        + "ArchitectureWorkbenchController.java"));

        assertThat(template)
                .contains("downloadArchitectureArchiMate")
                .contains("downloadArchitectureVisio")
                .contains("architecture-workbench-export.js")
                .contains("Experimental architecture export formats")
                .contains("Download ArchiMate 3.1 subset")
                .contains("mapping and loss manifest pending")
                .contains("Download Visio 2012 subset")
                .contains("Microsoft Visio desktop certification pending")
                .doesNotContain(">\n            Download ArchiMate\n")
                .doesNotContain(">\n            Download Visio\n");
        assertThat(controller)
                .contains("schema-validated ArchiMate 3.1 supported subset")
                .contains("experimental bounded Visio 2012 VSDX package")
                .doesNotContain("as editable Visio VSDX");
        assertThat(api)
                .contains("archiMateUrl")
                .contains("'.archimate.xml'")
                .contains("visioUrl")
                .contains("'.vsdx'")
                .doesNotContain("businessText")
                .doesNotContain("analyze");
        assertThat(adapter)
                .contains("ArchitectureWorkbenchApi.archiMateUrl")
                .contains("ArchitectureWorkbenchApi.visioUrl")
                .doesNotContain("fetch(")
                .doesNotContain("businessText")
                .doesNotContain("analyze");
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir"))
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
