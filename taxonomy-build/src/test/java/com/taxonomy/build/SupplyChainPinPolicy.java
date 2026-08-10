package com.taxonomy.build;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Rejects mutable external GitHub Action references and production image tags. */
final class SupplyChainPinPolicy {

    private static final Pattern ACTION = Pattern.compile(
            "^\\s*-?\\s*uses:\\s*([^\\s#]+)");
    private static final Pattern SHA_REFERENCE = Pattern.compile(
            "^[^@]+@[0-9a-fA-F]{40}$");
    private static final Pattern FROM = Pattern.compile(
            "^\\s*FROM\\s+([^\\s]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMPOSE_IMAGE = Pattern.compile(
            "^\\s*image:\\s*([^\\s#]+)");
    private static final Pattern DIGEST_REFERENCE = Pattern.compile(
            "^[^@]+@sha256:[0-9a-fA-F]{64}$");

    private final ObjectMapper objectMapper = new ObjectMapper();

    Inspection inspect(Path repositoryRoot) {
        Path root = repositoryRoot.toAbsolutePath().normalize();
        List<String> violations = new ArrayList<>();
        int actionCount = inspectActions(root, violations);
        int imageCount = inspectImages(root, violations);
        return new Inspection(
                violations.isEmpty(),
                actionCount,
                imageCount,
                List.copyOf(violations));
    }

    void writeReport(Path output, Inspection inspection) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("status", inspection.passed() ? "PASS" : "FAIL");
        report.put("checkedExternalActions", inspection.checkedExternalActions());
        report.put("checkedProductionImages", inspection.checkedProductionImages());
        report.put("violations", inspection.violations());
        try {
            Path absolute = output.toAbsolutePath().normalize();
            Files.createDirectories(absolute.getParent());
            Files.writeString(
                    absolute,
                    objectMapper.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(report) + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException(
                    "Cannot write supply-chain pin report " + output,
                    error);
        }
    }

    private int inspectActions(Path root, List<String> violations) {
        Path workflows = root.resolve(".github/workflows");
        if (!Files.isDirectory(workflows)) {
            return 0;
        }
        List<Path> workflowFiles;
        try (Stream<Path> stream = Files.list(workflows)) {
            workflowFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase();
                        return name.endsWith(".yml") || name.endsWith(".yaml");
                    })
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException error) {
            throw new IllegalArgumentException(
                    "Cannot list GitHub workflows below " + workflows,
                    error);
        }

        int checked = 0;
        for (Path workflow : workflowFiles) {
            List<String> lines = readLines(workflow);
            for (int index = 0; index < lines.size(); index++) {
                Matcher matcher = ACTION.matcher(lines.get(index));
                if (!matcher.find()) {
                    continue;
                }
                String reference = stripQuotes(matcher.group(1));
                if (reference.startsWith("./")) {
                    continue;
                }
                checked++;
                if (!SHA_REFERENCE.matcher(reference).matches()) {
                    violations.add(relative(root, workflow) + ":" + (index + 1)
                            + ": mutable action reference " + reference);
                }
            }
        }
        return checked;
    }

    private int inspectImages(Path root, List<String> violations) {
        int checked = 0;
        Path dockerfile = root.resolve("Dockerfile");
        if (Files.isRegularFile(dockerfile)) {
            List<String> lines = readLines(dockerfile);
            for (int index = 0; index < lines.size(); index++) {
                Matcher matcher = FROM.matcher(lines.get(index));
                if (!matcher.find()) {
                    continue;
                }
                String image = matcher.group(1);
                if ("scratch".equalsIgnoreCase(image) || image.startsWith("${")) {
                    continue;
                }
                checked++;
                if (!DIGEST_REFERENCE.matcher(image).matches()) {
                    violations.add("Dockerfile:" + (index + 1)
                            + ": production build image is not digest-pinned: " + image);
                }
            }
        }

        Path compose = root.resolve("docker-compose.prod.yml");
        if (Files.isRegularFile(compose)) {
            List<String> lines = readLines(compose);
            for (int index = 0; index < lines.size(); index++) {
                Matcher matcher = COMPOSE_IMAGE.matcher(lines.get(index));
                if (!matcher.find()) {
                    continue;
                }
                String image = stripQuotes(matcher.group(1));
                if (image.startsWith("${")) {
                    continue;
                }
                checked++;
                if (!DIGEST_REFERENCE.matcher(image).matches()) {
                    violations.add("docker-compose.prod.yml:" + (index + 1)
                            + ": production image is not digest-pinned: " + image);
                }
            }
        }
        return checked;
    }

    private static List<String> readLines(Path path) {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalArgumentException("Cannot read " + path, error);
        }
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path.toAbsolutePath().normalize()).toString()
                .replace('\\', '/');
    }

    record Inspection(
            boolean passed,
            int checkedExternalActions,
            int checkedProductionImages,
            List<String> violations) {

        String summary() {
            if (passed) {
                return "All external GitHub Actions and production images are immutably pinned ("
                        + checkedExternalActions + " actions, "
                        + checkedProductionImages + " images).";
            }
            return "Supply-chain pinning violations:\n- "
                    + String.join("\n- ", violations);
        }
    }
}
