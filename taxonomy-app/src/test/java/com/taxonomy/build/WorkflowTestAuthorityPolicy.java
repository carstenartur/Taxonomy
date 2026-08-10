package com.taxonomy.build;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Repository policy that keeps executable test selection inside Maven-owned
 * profiles instead of duplicating it in GitHub Actions workflows.
 *
 * <p>This class deliberately lives in test sources: it is build verification,
 * not application runtime functionality. The policy contains no GitHub-specific
 * API calls and can therefore be exercised with isolated filesystem fixtures.</p>
 */
final class WorkflowTestAuthorityPolicy {

    static final String CANONICAL_COMMAND = "./mvnw -B verify -Pci";

    private static final Set<String> BLOCK_MARKERS = Set.of("|", ">", "|-", ">-");
    private static final Set<String> REMOVED_WORKFLOWS = Set.of(
            "accessibility.yml",
            "archimate-import-evidence.yml",
            "core-integration.yml",
            "docs-link-check.yml",
            "document-import-evidence.yml",
            "frontend-architecture.yml",
            "generate-screenshots.yml",
            "hibernate-search-alignment.yml",
            "pipeline-tests.yml",
            "qa-architecture-evidence.yml",
            "ui-acceptance.yml",
            "ui-primary-workflow-acceptance.yml",
            "ui-role-state-acceptance.yml",
            "ui-special-modes-acceptance.yml");

    private static final List<PatternRule> DIRECT_TEST_RULES = List.of(
            new PatternRule(
                    "direct Maven executable",
                    Pattern.compile("(?<![./])\\bmvn(?:\\.cmd)?\\b")),
            new PatternRule(
                    "direct browser/a11y script",
                    Pattern.compile(
                            "\\bnode\\s+\\.github/scripts/(?:ui-|accessibility-audit)")),
            new PatternRule(
                    "workflow-owned Java test selection",
                    Pattern.compile("(?:-Dtest=|-Dit\\.test=|failsafe:integration-test)")),
            new PatternRule(
                    "workflow-owned local quality test",
                    Pattern.compile(
                            "python3\\s+\\.github/scripts/(?:test-check-coverage|"
                                    + "check-coverage|check-doc-links|"
                                    + "check-frontend-api-boundaries|"
                                    + "check-hibernate-search-alignment|"
                                    + "check-dependency-hygiene)\\.py")));

    private final ObjectMapper objectMapper = new ObjectMapper();

    Inspection inspect(Path repositoryRoot) throws IOException {
        Path root = repositoryRoot.toAbsolutePath().normalize();
        Path workflowsDirectory = root.resolve(".github/workflows");
        Path catalogPath = root.resolve(".mvn/verification-suites.json");
        List<String> errors = new ArrayList<>();

        Map<String, Object> catalog = objectMapper.readValue(
                Files.readString(catalogPath),
                new TypeReference<Map<String, Object>>() { });
        if (!CANONICAL_COMMAND.equals(catalog.get("canonicalCommand"))) {
            errors.add("verification catalogue must declare '" + CANONICAL_COMMAND + "'");
        }

        Set<String> classifiedWorkflows = classifiedWorkflows(catalog, errors);
        Set<Path> workflowPaths = workflowPaths(workflowsDirectory, errors);
        Set<String> workflowFiles = new TreeSet<>();
        workflowPaths.forEach(path -> workflowFiles.add(path.getFileName().toString()));

        Set<String> unexpected = difference(workflowFiles, classifiedWorkflows);
        if (!unexpected.isEmpty()) {
            errors.add("unclassified workflows remain: " + String.join(", ", unexpected));
        }

        Set<String> missing = difference(classifiedWorkflows, workflowFiles);
        if (!missing.isEmpty()) {
            errors.add("documented workflows missing: " + String.join(", ", missing));
        }

        Set<String> lingering = new TreeSet<>(workflowFiles);
        lingering.retainAll(REMOVED_WORKFLOWS);
        if (!lingering.isEmpty()) {
            errors.add("redundant workflows were not removed: "
                    + String.join(", ", lingering));
        }

        for (Path path : workflowPaths) {
            String commands = runBlocks(Files.readString(path));
            for (PatternRule rule : DIRECT_TEST_RULES) {
                if (rule.pattern().matcher(commands).find()) {
                    errors.add(relativePath(root, path) + " contains "
                            + rule.description() + "; invoke a Maven profile instead");
                }
            }
        }

        verifyCanonicalCiCommand(workflowsDirectory, errors);
        verifyDatabaseProfiles(workflowsDirectory, errors);
        return new Inspection(List.copyOf(errors), workflowFiles.size());
    }

    private static Set<String> classifiedWorkflows(
            Map<String, Object> catalog,
            List<String> errors) {
        Object rawResponsibilities = catalog.get("workflowResponsibilities");
        if (!(rawResponsibilities instanceof Map<?, ?> responsibilities)
                || responsibilities.isEmpty()) {
            errors.add("verification catalogue must classify workflow responsibilities");
            return Set.of();
        }

        Set<String> classified = new TreeSet<>();
        Set<String> invalid = new TreeSet<>();
        for (Map.Entry<?, ?> entry : responsibilities.entrySet()) {
            Object rawName = entry.getKey();
            Object rawPurpose = entry.getValue();
            if (!(rawName instanceof String name)
                    || !isWorkflowName(name)
                    || !(rawPurpose instanceof String purpose)
                    || purpose.isBlank()) {
                invalid.add(String.valueOf(rawName));
                continue;
            }
            classified.add(name);
        }
        if (!invalid.isEmpty()) {
            errors.add("verification catalogue contains invalid workflow responsibilities: "
                    + String.join(", ", invalid));
        }
        return classified;
    }

    private static Set<Path> workflowPaths(
            Path workflowsDirectory,
            List<String> errors) throws IOException {
        if (!Files.isDirectory(workflowsDirectory)) {
            errors.add("workflow directory is missing: .github/workflows");
            return Set.of();
        }
        Set<Path> paths = new TreeSet<>(Comparator.comparing(Path::toString));
        try (Stream<Path> stream = Files.list(workflowsDirectory)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> isWorkflowName(path.getFileName().toString()))
                    .forEach(paths::add);
        }
        return paths;
    }

    private static void verifyCanonicalCiCommand(
            Path workflowsDirectory,
            List<String> errors) throws IOException {
        Path ci = workflowsDirectory.resolve("ci-cd.yml");
        if (!Files.isRegularFile(ci)) {
            errors.add("ci-cd.yml is missing");
            return;
        }
        if (!Files.readString(ci).contains(CANONICAL_COMMAND)) {
            errors.add("ci-cd.yml must invoke the canonical Maven command unchanged");
        }
    }

    private static void verifyDatabaseProfiles(
            Path workflowsDirectory,
            List<String> errors) throws IOException {
        Path database = workflowsDirectory.resolve("database-compatibility.yml");
        if (!Files.isRegularFile(database)) {
            errors.add("database-compatibility.yml is missing");
            return;
        }
        String text = Files.readString(database);
        for (String profile : List.of(
                "database-postgres", "database-mssql", "database-oracle")) {
            if (!text.contains("-P" + profile)) {
                errors.add("database workflow must invoke Maven-owned profile " + profile);
            }
        }
    }

    static String runBlocks(String text) {
        List<String> lines = text.lines().toList();
        List<String> result = new ArrayList<>();
        Pattern runPattern = Pattern.compile("^(\\s*)(?:-\\s+)?run:\\s*(.*)$");
        int index = 0;
        while (index < lines.size()) {
            String line = lines.get(index);
            Matcher match = runPattern.matcher(line);
            if (!match.matches()) {
                index++;
                continue;
            }

            int indent = match.group(1).length();
            String tail = match.group(2);
            if (!BLOCK_MARKERS.contains(tail)) {
                result.add(tail);
                index++;
                continue;
            }

            index++;
            while (index < lines.size()) {
                String candidate = lines.get(index);
                String stripped = candidate.stripLeading();
                if (!stripped.isEmpty()
                        && leadingWhitespace(candidate) <= indent) {
                    break;
                }
                result.add(stripped);
                index++;
            }
        }
        return String.join("\n", result);
    }

    private static int leadingWhitespace(String value) {
        return value.length() - value.stripLeading().length();
    }

    private static boolean isWorkflowName(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.endsWith(".yml") || lower.endsWith(".yaml");
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> result = new TreeSet<>(left);
        result.removeAll(right);
        return result;
    }

    private static String relativePath(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    record Inspection(List<String> errors, int workflowCount) {
        Inspection {
            errors = List.copyOf(errors);
        }
    }

    private record PatternRule(String description, Pattern pattern) {
    }
}
