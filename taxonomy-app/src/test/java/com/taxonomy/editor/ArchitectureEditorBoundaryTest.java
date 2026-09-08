package com.taxonomy.editor;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

@AnalyzeClasses(packages = "com.taxonomy", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureEditorBoundaryTest {
    @ArchTest
    static final ArchRule editorDoesNotWriteArchitectureJpa = noClasses().that().resideInAPackage("com.taxonomy.editor..")
            .should().dependOnClassesThat().resideInAnyPackage("com.taxonomy.catalog.repository..",
                    "com.taxonomy.relations.repository..", "jakarta.persistence..", "org.springframework.data.jpa..");

    @ArchTest
    static final ArchRule httpDoesNotCommitGitDirectly = noClasses().that().haveSimpleName("ArchitectureEditorController")
            .should().dependOnClassesThat().haveSimpleName("DslGitRepository");

    @Test
    void rendererAndSessionCannotPerformTransportOrPersistCanvasState() throws Exception {
        Path js = Path.of("src/main/resources/static/js");
        for (String file : java.util.List.of("architecture-editor.js", "architecture-editor-renderer.js")) {
            String source = Files.readString(js.resolve(file));
            assertThat(source).doesNotContain("fetch(", "XMLHttpRequest", "localStorage", "sessionStorage", "sendJson(");
        }
        String renderer = Files.readString(js.resolve("architecture-editor-renderer.js"));
        assertThat(renderer).doesNotContain("ArchitectureEditorApi", "TaxonomyApiClient", "JSON.stringify", "forceSimulation");
        String featureClient = Files.readString(js.resolve("api/architecture-editor-api.js"));
        assertThat(featureClient).doesNotContain("fetch(", "XMLHttpRequest");
        assertThat(featureClient).contains("TaxonomyApiClient", "If-Match", "retries: 0");
    }
}
