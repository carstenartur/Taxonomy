package com.taxonomy.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Naming checks for maintained surfaces, not a filter on user content or reference catalogues. */
final class ScenarioAcceptanceNamingContract {
    private static final Pattern OUTDATED = Pattern.compile("civilian|zivil", Pattern.CASE_INSENSITIVE);
    private static final Set<String> TEXT = Set.of("java", "json", "xml", "properties", "md", "html", "yml", "yaml", "js", "mjs", "cjs", "sh", "txt");
    private static final String FIXTURE = "taxonomy-app/src/test/resources/scenarios/flood-information.json";
    private static final String CATALOGUE_BINDING = "\"BR-1228\": \"Civilian Roles\"";
    static final List<String> SELECTED_TESTS = List.of("ScenarioArchitectureAcceptanceTest", "ScenarioLlmPlaybackTest",
            "ReformulationScenarioAcceptanceTest", "ReformulationAuthoredScenarioTest",
            "ReformulationScenarioBrowserTest", "ReformulationBrowserTest");

    private ScenarioAcceptanceNamingContract() { }

    static void verifyNames(Path root) throws IOException {
        var roots = new ArrayList<Path>();
        for (String directory : List.of(".github", ".mvn", "docs/de", "docs/en", "docs/dev", "docs/features", "docs/testing")) {
            roots.add(root.resolve(directory));
        }
        try (var children = Files.list(root)) {
            children.filter(path -> path.getFileName().toString().startsWith("taxonomy-") && Files.isDirectory(path.resolve("src")))
                    .map(path -> path.resolve("src")).forEach(roots::add);
        }
        var failures = new ArrayList<String>();
        for (Path directory : roots) {
            if (!Files.isDirectory(directory)) throw new AssertionError("Missing maintained surface: " + directory);
            try (var files = Files.walk(directory)) {
                for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                    String relative = root.relativize(file).toString().replace('\\', '/');
                    if (relative.contains("/ScenarioAcceptanceNaming")) continue; // Negative tests name the rejected terms.
                    String name = file.getFileName().toString();
                    int dot = name.lastIndexOf('.');
                    if (dot < 0 || !TEXT.contains(name.substring(dot + 1))) continue;
                    if (OUTDATED.matcher(relative).find() || outdated(relative, Files.readString(file))) failures.add(relative);
                }
            }
        }
        for (String file : List.of("README.md", "pom.xml")) {
            if (outdated(file, Files.readString(root.resolve(file)))) failures.add(file);
        }
        if (!failures.isEmpty()) throw new AssertionError("Non-neutral maintained names: " + failures);
    }

    static boolean outdated(String relative, String text) {
        if (relative.equals(FIXTURE)) text = text.replace(CATALOGUE_BINDING, "");
        // Historical evidence links are not current product labels. Their captions are still checked.
        if (relative.endsWith(".md")) text = text.replaceAll("\\]\\([^)]*\\)", "](reference)");
        return OUTDATED.matcher(text).find();
    }

    static void verifyWiring(Path root) throws IOException {
        String selector = String.join(",", SELECTED_TESTS);
        String pom = Files.readString(root.resolve("pom.xml"));
        String manifest = Files.readString(root.resolve(".mvn/verification-suites.json"));
        String workflow = Files.readString(root.resolve(".github/workflows/scenario-acceptance.yml"));
        require(pom.contains("<id>scenario-acceptance</id>") && pom.contains("<test>" + selector + "</test>"), "Maven scenario selection changed");
        require(manifest.contains("\"scenario-acceptance\"") && manifest.contains(selector), "Manifest scenario selection changed");
        require(workflow.contains("test -Pscenario-acceptance"), "Workflow must execute the scenario profile");
        require(workflow.contains("jobs:\n  scenario:"), "Retain the existing required job identity");
        require(workflow.contains("check-scenario-documents --artifacts taxonomy-app/target/scenario-acceptance"), "Missing architecture document check");
        require(workflow.contains("check-scenario-documents --reformulation-only --artifacts taxonomy-app/target/reformulation-scenario-acceptance"), "Missing reformulation document check");
        require(workflow.contains("ReformulationScenarioAcceptanceTest.xml 1")
                && workflow.contains("ReformulationScenarioBrowserTest.xml 1")
                && workflow.contains("ReformulationAuthoredScenarioTest.xml 2")
                && workflow.contains("ScenarioLlmPlaybackTest.xml 17")
                && workflow.contains("ReformulationBrowserTest.xml 1"), "Named positive JUnit evidence must remain required");
        try (var sources = Files.walk(root.resolve("taxonomy-app/src/test/java"))) {
            var names = sources.filter(Files::isRegularFile).map(path -> path.getFileName().toString()).toList();
            for (String test : SELECTED_TESTS) require(names.stream().filter((test + ".java")::equals).count() == 1,
                    "Selected scenario test has no unique source: " + test);
        }
        require(Files.isRegularFile(root.resolve(FIXTURE)), "Missing flood-information fixture");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
