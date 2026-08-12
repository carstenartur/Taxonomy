package com.taxonomy.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Prevents new browser transport calls outside the central JavaScript API layer.
 *
 * <p>The fixed inventory protects literal {@code /api/} calls while the Git
 * baseline makes every remaining direct {@code fetch()} call monotonically
 * non-increasing. API clients and the base-path bootstrap wrapper remain the only
 * transport owners.</p>
 */
final class FrontendApiBoundaryPolicy {

    private static final String STATIC_JS =
            "taxonomy-app/src/main/resources/static/js";
    private static final String TEMPLATE =
            "taxonomy-app/src/main/resources/templates/index.html";
    private static final String TEMPLATE_KEY = "templates/index.html";
    private static final Pattern DIRECT_FETCH = Pattern.compile("\\bfetch\\s*\\(");
    private static final Pattern DIRECT_API_FETCH = Pattern.compile(
            "\\bfetch\\s*\\(\\s*['\"`]\\/api\\/");
    private static final Set<String> INFRASTRUCTURE_EXCEPTIONS = Set.of(
            "taxonomy-i18n.js");
    private static final Set<String> LEGACY_ALLOWLIST = Set.of(
            "taxonomy-i18n.js",
            "core/taxonomy-analysis.js",
            "core/taxonomy-browse.js",
            "core/taxonomy-scoring.js",
            "relations/taxonomy-coverage.js",
            "relations/taxonomy-quality.js",
            "relations/taxonomy-relations.js",
            "shared/taxonomy-about.js",
            "shared/taxonomy-action-guards.js",
            "shared/taxonomy-dsl-editor.js",
            "shared/taxonomy-export.js",
            "shared/taxonomy-graph.js",
            "shared/taxonomy-search.js",
            "versioning/taxonomy-context-bar.js",
            "versioning/taxonomy-context-compare.js",
            "versioning/taxonomy-context-transfer.js",
            "versioning/taxonomy-history-search.js",
            "versioning/taxonomy-variants.js",
            "versioning/taxonomy-versions.js",
            "versioning/taxonomy-viewcontext.js",
            "workspace/taxonomy-git-status.js",
            "workspace/taxonomy-merge-resolution.js",
            "workspace/taxonomy-workspace-provisioning.js",
            "workspace/taxonomy-workspace-sync.js");

    Inspection inspect(
            Path repositoryRoot,
            String baseRef,
            RevisionTextReader revisionReader) {
        if (baseRef == null || baseRef.isBlank()) {
            throw new IllegalArgumentException("frontend API baseline ref must not be blank");
        }
        Path root = repositoryRoot.toAbsolutePath().normalize();
        CurrentScan current = scanCurrent(root);
        Map<String, Integer> baseline = baselineCounts(
                baseRef, current.fetchCounts().keySet(), revisionReader);
        return evaluate(current, baseline, baseRef);
    }

    CurrentScan scanCurrent(Path repositoryRoot) {
        Path root = repositoryRoot.toAbsolutePath().normalize();
        Path jsRoot = root.resolve(STATIC_JS);
        if (!Files.isDirectory(jsRoot)) {
            throw new IllegalArgumentException(
                    "frontend JavaScript root is missing: " + jsRoot);
        }

        Map<String, Integer> fetchCounts = new TreeMap<>();
        Map<String, Integer> legacyApiInventory = new TreeMap<>();
        List<String> fixedInventoryViolations = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> stream = Files.walk(jsRoot)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(FrontendApiBoundaryPolicy::isJavaScript)
                    .sorted()
                    .toList();
        } catch (IOException error) {
            throw new IllegalArgumentException(
                    "cannot inspect frontend JavaScript below " + jsRoot, error);
        }

        for (Path path : files) {
            String relative = jsRoot.relativize(path).toString().replace('\\', '/');
            String text = readUtf8(path);
            int fetchCount = countDirectFetch(text);
            fetchCounts.put(relative, fetchCount);
            List<Integer> apiLines = matchLines(text, DIRECT_API_FETCH);
            if (apiLines.isEmpty() || relative.startsWith("api/")) {
                continue;
            }
            if (LEGACY_ALLOWLIST.contains(relative)) {
                legacyApiInventory.put(relative, apiLines.size());
            } else {
                fixedInventoryViolations.add(relative
                        + ": direct /api fetch at lines " + apiLines);
            }
        }

        Path template = root.resolve(TEMPLATE);
        if (Files.isRegularFile(template)) {
            String text = readUtf8(template);
            fetchCounts.put(TEMPLATE_KEY, countDirectFetch(text));
            List<Integer> templateLines = matchLines(text, DIRECT_API_FETCH);
            if (!templateLines.isEmpty()) {
                legacyApiInventory.put(TEMPLATE_KEY, templateLines.size());
            }
        }

        Set<String> reducible = new TreeSet<>(LEGACY_ALLOWLIST);
        reducible.removeAll(legacyApiInventory.keySet());
        return new CurrentScan(
                Collections.unmodifiableMap(fetchCounts),
                Collections.unmodifiableMap(legacyApiInventory),
                List.copyOf(fixedInventoryViolations),
                Collections.unmodifiableSet(reducible));
    }

    Inspection evaluate(
            CurrentScan current,
            Map<String, Integer> baselineCounts,
            String baseRef) {
        List<String> failures = new ArrayList<>(current.fixedInventoryViolations());
        Map<String, Integer> currentDebt = debtOnly(current.fetchCounts());
        Map<String, Integer> baselineDebt = debtOnly(baselineCounts);

        for (Map.Entry<String, Integer> entry : currentDebt.entrySet()) {
            int previous = baselineDebt.getOrDefault(entry.getKey(), 0);
            if (previous == 0) {
                failures.add(entry.getKey() + ": introduces " + entry.getValue()
                        + " direct fetch() call(s); use static/js/api instead");
            } else if (entry.getValue() > previous) {
                failures.add(entry.getKey() + ": direct fetch() count increased from "
                        + previous + " to " + entry.getValue());
            }
        }

        int currentTotal = currentDebt.values().stream().mapToInt(Integer::intValue).sum();
        int baselineTotal = baselineDebt.values().stream().mapToInt(Integer::intValue).sum();
        if (currentTotal > baselineTotal) {
            failures.add("legacy direct fetch() debt increased from "
                    + baselineTotal + " to " + currentTotal);
        }

        StringBuilder report = new StringBuilder("Frontend API boundary\n\n")
                .append("Baseline: ").append(baseRef).append('\n')
                .append("Legacy direct fetch debt: ").append(currentTotal)
                .append(" call(s) in ").append(currentDebt.size()).append(" file(s); baseline ")
                .append(baselineTotal).append(" call(s) in ")
                .append(baselineDebt.size()).append(" file(s).\n\n")
                .append("Legacy literal /api inventory:\n");
        current.legacyApiInventory().forEach((path, count) -> report
                .append("- ").append(path).append(": ").append(count).append('\n'));
        if (!current.reducibleAllowlist().isEmpty()) {
            report.append("\nAllowlist entries ready for removal:\n");
            current.reducibleAllowlist().forEach(path -> report
                    .append("- ").append(path).append('\n'));
        }
        if (!failures.isEmpty()) {
            report.append("\nViolations:\n");
            failures.forEach(failure -> report.append("- ").append(failure).append('\n'));
        }
        report.append("\nResult: ")
                .append(failures.isEmpty() ? "PASS\n" : "FAIL\n");
        return new Inspection(
                failures.isEmpty(),
                List.copyOf(failures),
                currentTotal,
                baselineTotal,
                report.toString());
    }

    void writeReport(Path output, String report) {
        try {
            Path absolute = output.toAbsolutePath().normalize();
            Files.createDirectories(absolute.getParent());
            Files.writeString(absolute, report, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException(
                    "cannot write frontend API boundary report " + output, error);
        }
    }

    static int countDirectFetch(String text) {
        int count = 0;
        Matcher matcher = DIRECT_FETCH.matcher(text);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    static boolean isTransportOwner(String relativePath) {
        String normalized = relativePath.replace('\\', '/');
        return normalized.startsWith("api/")
                || INFRASTRUCTURE_EXCEPTIONS.contains(normalized);
    }

    private Map<String, Integer> baselineCounts(
            String baseRef,
            Set<String> currentPaths,
            RevisionTextReader reader) {
        Set<String> comparisonPaths = new TreeSet<>(currentPaths);
        String prefix = STATIC_JS + "/";
        for (String repositoryPath : reader.paths(baseRef, STATIC_JS)) {
            String normalized = repositoryPath.replace('\\', '/');
            if (!normalized.startsWith(prefix)) {
                continue;
            }
            String relative = normalized.substring(prefix.length());
            if (isJavaScript(relative)) {
                comparisonPaths.add(relative);
            }
        }
        if (reader.paths(baseRef, TEMPLATE).stream()
                .map(path -> path.replace('\\', '/'))
                .anyMatch(TEMPLATE::equals)) {
            comparisonPaths.add(TEMPLATE_KEY);
        }

        Map<String, Integer> counts = new TreeMap<>();
        for (String relative : comparisonPaths) {
            Optional<String> text = reader.read(
                    baseRef, repositoryPath(relative));
            if (text.isEmpty()) {
                continue;
            }
            counts.put(relative, countDirectFetch(text.get()));
        }
        return counts;
    }

    private static String repositoryPath(String comparisonPath) {
        return TEMPLATE_KEY.equals(comparisonPath)
                ? TEMPLATE : STATIC_JS + "/" + comparisonPath;
    }

    private static Map<String, Integer> debtOnly(Map<String, Integer> counts) {
        Map<String, Integer> result = new TreeMap<>();
        counts.forEach((path, count) -> {
            if (!isTransportOwner(path) && count > 0) {
                result.put(path, count);
            }
        });
        return Collections.unmodifiableMap(result);
    }

    private static List<Integer> matchLines(String text, Pattern pattern) {
        List<Integer> result = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            result.add(1 + Math.toIntExact(text.substring(0, matcher.start())
                    .chars().filter(character -> character == '\n').count()));
        }
        return result;
    }

    private static boolean isJavaScript(Path path) {
        return isJavaScript(path.getFileName().toString());
    }

    private static boolean isJavaScript(String name) {
        return name.endsWith(".js") || name.endsWith(".mjs");
    }

    private static String readUtf8(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalArgumentException("cannot read " + path, error);
        }
    }

    @FunctionalInterface
    interface RevisionTextReader {
        Optional<String> read(String revision, String repositoryPath);

        default Set<String> paths(String revision, String repositoryPathPrefix) {
            return Set.of();
        }
    }

    record CurrentScan(
            Map<String, Integer> fetchCounts,
            Map<String, Integer> legacyApiInventory,
            List<String> fixedInventoryViolations,
            Set<String> reducibleAllowlist) {
    }

    record Inspection(
            boolean passed,
            List<String> failures,
            int currentDebt,
            int baselineDebt,
            String report) {
    }
}
