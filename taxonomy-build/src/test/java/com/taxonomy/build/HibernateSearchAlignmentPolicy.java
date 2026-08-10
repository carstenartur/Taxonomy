package com.taxonomy.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic policy for the resolved Hibernate Search, ORM and Lucene set. */
final class HibernateSearchAlignmentPolicy {

    private static final Pattern COORDINATE = Pattern.compile(
            "(?<group>org\\.hibernate\\.search|org\\.hibernate\\.orm|org\\.apache\\.lucene):"
                    + "(?<artifact>[A-Za-z0-9_.-]+):"
                    + "(?:[A-Za-z0-9_.-]+:)*"
                    + "(?<version>[0-9][^:\\s]*)");

    Evaluation evaluate(
            Path dependencyTree,
            String expectedSearchVersion,
            String expectedOrmPrefix,
            String expectedLuceneVersion) {
        requireText(expectedSearchVersion, "expectedSearchVersion");
        requireText(expectedOrmPrefix, "expectedOrmPrefix");
        requireText(expectedLuceneVersion, "expectedLuceneVersion");

        final String text;
        try {
            text = Files.readString(dependencyTree);
        } catch (IOException error) {
            throw new IllegalArgumentException(
                    "Cannot read Hibernate Search dependency tree " + dependencyTree,
                    error);
        }

        Map<Coordinate, Set<String>> resolved = parse(text);
        Map<String, Set<String>> searchEntries = new LinkedHashMap<>();
        resolved.entrySet().stream()
                .filter(entry -> "org.hibernate.search".equals(entry.getKey().group()))
                .sorted(Map.Entry.comparingByKey(
                        Comparator.comparing(Coordinate::artifact)))
                .forEach(entry -> searchEntries.put(
                        entry.getKey().artifact(), entry.getValue()));

        Set<String> ormVersions = resolved.getOrDefault(
                new Coordinate("org.hibernate.orm", "hibernate-core"), Set.of());
        Set<String> luceneVersions = resolved.getOrDefault(
                new Coordinate("org.apache.lucene", "lucene-core"), Set.of());

        List<String> failures = new ArrayList<>();
        if (searchEntries.isEmpty()) {
            failures.add("No org.hibernate.search artifacts were found in the dependency tree");
        }
        searchEntries.forEach((artifact, versions) -> {
            if (!versions.equals(Set.of(expectedSearchVersion))) {
                failures.add(artifact + " resolved to " + sorted(versions)
                        + ", expected only " + expectedSearchVersion);
            }
        });
        if (ormVersions.size() != 1
                || !ormVersions.iterator().next().startsWith(expectedOrmPrefix)) {
            failures.add("hibernate-core resolved to " + sorted(ormVersions)
                    + ", expected one " + expectedOrmPrefix + "x version");
        }
        if (!luceneVersions.equals(Set.of(expectedLuceneVersion))) {
            failures.add("lucene-core resolved to " + sorted(luceneVersions)
                    + ", expected " + expectedLuceneVersion);
        }

        StringBuilder report = new StringBuilder(
                "Hibernate Search dependency alignment\n\n");
        searchEntries.forEach((artifact, versions) -> report
                .append("- org.hibernate.search:")
                .append(artifact)
                .append(" = ")
                .append(joined(versions))
                .append('\n'));
        report.append("- org.hibernate.orm:hibernate-core = ")
                .append(joinedOrMissing(ormVersions))
                .append('\n')
                .append("- org.apache.lucene:lucene-core = ")
                .append(joinedOrMissing(luceneVersions))
                .append("\n\n")
                .append("Result: ")
                .append(failures.isEmpty() ? "PASS" : "FAIL")
                .append('\n');
        failures.forEach(failure -> report.append("- ").append(failure).append('\n'));
        return new Evaluation(failures.isEmpty(), report.toString(), List.copyOf(failures));
    }

    static Map<Coordinate, Set<String>> parse(String text) {
        Map<Coordinate, Set<String>> resolved = new LinkedHashMap<>();
        Matcher matcher = COORDINATE.matcher(text == null ? "" : text);
        while (matcher.find()) {
            Coordinate coordinate = new Coordinate(
                    matcher.group("group"), matcher.group("artifact"));
            resolved.computeIfAbsent(coordinate, ignored -> new LinkedHashSet<>())
                    .add(matcher.group("version"));
        }
        Map<Coordinate, Set<String>> immutable = new LinkedHashMap<>();
        resolved.forEach((coordinate, versions) -> immutable.put(
                coordinate,
                Collections.unmodifiableSet(new LinkedHashSet<>(versions))));
        return Collections.unmodifiableMap(immutable);
    }

    private static List<String> sorted(Set<String> versions) {
        return versions.stream().sorted().toList();
    }

    private static String joined(Set<String> versions) {
        return String.join(", ", sorted(versions));
    }

    private static String joinedOrMissing(Set<String> versions) {
        return versions.isEmpty() ? "missing" : joined(versions);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }

    record Coordinate(String group, String artifact) {
    }

    record Evaluation(boolean passed, String report, List<String> failures) {
    }
}
