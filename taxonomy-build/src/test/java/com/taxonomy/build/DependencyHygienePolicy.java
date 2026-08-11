package com.taxonomy.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic policy for the packaged CycloneDX component set. */
final class DependencyHygienePolicy {

    private static final Set<String> INTENDED_PDFBOX_COMPONENTS = Set.of(
            "pdfbox", "pdfbox-io", "fontbox");
    private static final Pattern VERSION_MAJOR = Pattern.compile("^(\\d+)");

    Evaluation evaluate(
            List<Component> components,
            List<ReviewedException> exceptions,
            String expectedPdfBoxVersion) {
        if (expectedPdfBoxVersion == null || expectedPdfBoxVersion.isBlank()) {
            throw new IllegalArgumentException(
                    "expected PDFBox version must not be blank");
        }

        Set<Component> excepted = exceptions.stream()
                .map(ReviewedException::component)
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
        List<Component> pdfbox = components.stream()
                .filter(component -> "org.apache.pdfbox".equals(component.group()))
                .sorted()
                .toList();
        List<Component> disallowed = components.stream()
                .filter(this::isDisallowed)
                .filter(component -> !excepted.contains(component))
                .distinct()
                .sorted()
                .toList();

        Map<String, Set<String>> intendedVersions = new LinkedHashMap<>();
        INTENDED_PDFBOX_COMPONENTS.stream().sorted().forEach(
                name -> intendedVersions.put(name, new LinkedHashSet<>()));
        for (Component component : pdfbox) {
            if (excepted.contains(component)) {
                continue;
            }
            Set<String> versions = intendedVersions.get(component.name());
            if (versions != null) {
                versions.add(component.version());
            }
        }

        List<String> missing = intendedVersions.entrySet().stream()
                .filter(entry -> entry.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        Set<String> resolvedVersionSet = new LinkedHashSet<>();
        intendedVersions.values().forEach(resolvedVersionSet::addAll);
        List<String> resolvedVersions = resolvedVersionSet.stream().sorted().toList();
        boolean expectedMismatch = intendedVersions.values().stream()
                .flatMap(Set::stream)
                .anyMatch(version -> !expectedPdfBoxVersion.equals(version));

        boolean failed = !disallowed.isEmpty()
                || !missing.isEmpty()
                || resolvedVersions.size() > 1
                || expectedMismatch;
        StringBuilder report = new StringBuilder(
                "Taxonomy packaged dependency hygiene\n\n");
        for (Component component : pdfbox) {
            report.append("PDFBox family: ")
                    .append(component.coordinate()).append('\n');
        }
        report.append('\n');
        if (!exceptions.isEmpty()) {
            report.append("Active reviewed exceptions: ")
                    .append(exceptions.size()).append('\n');
        }
        if (!disallowed.isEmpty()) {
            report.append("BANNED packaged components:\n");
            disallowed.forEach(component -> report.append("- ")
                    .append(component.coordinate()).append('\n'));
        }
        if (!missing.isEmpty()) {
            report.append("Missing intended PDFBox components: ")
                    .append(String.join(", ", missing)).append('\n');
        }
        if (resolvedVersions.size() > 1) {
            report.append("PDFBox family versions are not aligned: ")
                    .append(String.join(", ", resolvedVersions)).append('\n');
        }
        if (expectedMismatch) {
            report.append("PDFBox family does not match expected version ")
                    .append(expectedPdfBoxVersion).append(": ")
                    .append(String.join(", ", resolvedVersions)).append('\n');
        }
        report.append('\n').append(failed ? "Result: FAIL\n" : "Result: PASS\n");
        return new Evaluation(!failed, report.toString());
    }

    void writeReport(Path path, String report) {
        try {
            Path absolute = path.toAbsolutePath().normalize();
            Files.createDirectories(absolute.getParent());
            Files.writeString(absolute, report, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException(
                    "cannot write dependency hygiene report " + path, error);
        }
    }

    static Integer versionMajor(String version) {
        if (version == null) {
            return null;
        }
        Matcher matcher = VERSION_MAJOR.matcher(version);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    private boolean isDisallowed(Component component) {
        if ("org.apache.pdfbox".equals(component.group())) {
            Integer major = versionMajor(component.version());
            return major == null || major < 3 || "xmpbox".equals(component.name());
        }
        if ("com.vladsch.flexmark".equals(component.group())
                && "flexmark-pdf-converter".equals(component.name())) {
            return true;
        }
        return component.group().startsWith("com.openhtmltopdf")
                && component.name().toLowerCase(Locale.ROOT).contains("pdfbox");
    }

    record Component(String group, String name, String version)
            implements Comparable<Component> {

        Component {
            group = group == null ? "" : group;
            name = name == null ? "" : name;
            version = version == null ? "" : version;
        }

        String coordinate() {
            return group + ":" + name + ":" + version;
        }

        @Override
        public int compareTo(Component other) {
            return coordinate().compareTo(other.coordinate());
        }
    }

    record ReviewedException(
            Component component,
            String owner,
            String rationale,
            LocalDate expires,
            String removalCondition) {
    }

    record Evaluation(boolean passed, String report) {
    }
}
