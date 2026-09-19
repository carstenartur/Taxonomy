package com.taxonomy;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Final #628 contract: no planned feature implementation may fall back into the Boot app. */
class ArchitectureCompletionTest {
    @Test
    void allSevenRuntimeContextsAreLibrariesWithoutApplicationBackDependencies() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve(".github/architecture-contexts.json"))) {
            root = root.getParent();
        }
        assertThat(root).as("repository root").isNotNull();
        Map<String, List<String>> contexts = new LinkedHashMap<>();
        contexts.put("knowledge", List.of("catalog", "relations", "search"));
        contexts.put("workspace", List.of("workspace", "versioning", "editor"));
        contexts.put("templates", List.of("templates"));
        contexts.put("interop", List.of("interop"));
        contexts.put("architecture", List.of("architecture"));
        contexts.put("analysis", List.of("analysis"));
        contexts.put("portfolio", List.of("portfolio"));
        Path appSources = root.resolve("taxonomy-app/src/main/java");
        Set<String> applicationClasses = new HashSet<>();
        try (var sources = Files.walk(appSources)) {
            sources.filter(p -> p.toString().endsWith(".java")).forEach(p ->
                    applicationClasses.add(appSources.relativize(p).toString()
                            .replace(java.io.File.separatorChar, '.').replaceAll("\\.java$", "")));
        }
        var forbidden = new DescribedPredicate<JavaClass>("application-owned implementation") {
            @Override public boolean test(JavaClass type) {
                return applicationClasses.contains(type.getName().split("\\$")[0]);
            }
        };
        for (var context : contexts.entrySet()) {
            String moduleName = "taxonomy-" + context.getKey();
            Path module = root.resolve(moduleName);
            assertThat(module.resolve("pom.xml")).as("required physical module %s", moduleName).isRegularFile();
            for (String prefix : context.getValue()) {
                Path sourcePackage = module.resolve("src/main/java/com/taxonomy/" + prefix);
                assertThat(sourcePackage).as("owner of %s", prefix).isDirectory();
                assertThat(appSources.resolve("com/taxonomy/" + prefix))
                        .as("no residual %s implementation in composition root", prefix).doesNotExist();
            }
            Path output = module.resolve("target/classes");
            assertThat(output).as("compiled library %s", moduleName).isDirectory();
            var classes = new ClassFileImporter().importPath(output);
            assertThat(classes).isNotEmpty();
            noClasses().should().dependOnClassesThat(forbidden).check(classes);
            if (context.getKey().equals("analysis") || context.getKey().equals("portfolio")) {
                noClasses().should().dependOnClassesThat().resideInAnyPackage(
                        "com.taxonomy.catalog.repository..", "com.taxonomy.relations.repository..",
                        "com.taxonomy.versioning.repository..", "com.taxonomy.workspace.repository..",
                        "com.taxonomy.workspace.storage..")
                        .check(classes);
            }

            for (var type : classes) {
                if (type.getName().contains("$") || type.getName().endsWith("package-info")) continue;
                Class<?> runtimeType = Class.forName(type.getName(), false, getClass().getClassLoader());
                assertThat(runtimeType.getProtectionDomain().getCodeSource().getLocation().toString())
                        .as("runtime class owner %s", type.getName()).contains(moduleName);
            }
        }
    }
    @Test
    void everyRetainedExceptionHasARealPhysicalOwnerAndAcceptanceRecord() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve(".github/architecture-contexts.json"))) {
            root = root.getParent();
        }
        assertThat(root).isNotNull();
        var ledger = new tools.jackson.databind.ObjectMapper().readTree(
                Files.readString(root.resolve(".github/architecture-exceptions.json")));
        Set<String> permitted = Set.of("taxonomy-knowledge", "taxonomy-workspace", "taxonomy-architecture",
                "taxonomy-analysis", "taxonomy-portfolio", "taxonomy-interop", "taxonomy-templates", "taxonomy-app");
        for (var entry : ledger.path("exceptions")) {
            String owner = entry.path("ownerModule").asText();
            assertThat(permitted).as("physical owner for exception %s", entry.path("id").asText()).contains(owner);
            assertThat(root.resolve(owner).resolve("pom.xml")).isRegularFile();
            assertThat(entry.path("ownershipReview").asText()).isEqualTo("docs/dev/MODULE_EXTRACTION_COMPLETION.md");
            assertThat(root.resolve(entry.path("ownershipReview").asText())).isRegularFile();
        }
    }

}
