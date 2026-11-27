package com.taxonomy.build;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Rejects mutable supply-chain references and unapproved deployment image registries. */
final class SupplyChainPinPolicy {

    private static final Pattern ACTION = Pattern.compile(
            "^\\s*-?\\s*uses:\\s*([^\\s#]+)");
    private static final Pattern SHA_REFERENCE = Pattern.compile(
            "^[^@]+@[0-9a-fA-F]{40}$");
    private static final Pattern FROM = Pattern.compile(
            "^\\s*FROM\\s+([^\\s]+)(?:\\s+AS\\s+([^\\s#]+))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMPOSE_IMAGE = Pattern.compile(
            "^\\s*image:\\s*([^\\s#]+)");
    private static final Pattern DIGEST_REFERENCE = Pattern.compile(
            "^[^@]+@sha256:[0-9a-fA-F]{64}$");
    private static final Set<String> APPROVED_DEPLOYMENT_REGISTRIES =
            Set.of("docker.io", "ghcr.io");

    private final ObjectMapper objectMapper = new ObjectMapper();

    Inspection inspect(Path repositoryRoot) {
        Path root = repositoryRoot.toAbsolutePath().normalize();
        List<String> violations = new ArrayList<>();
        int actionCount = inspectActions(root, violations);
        int imageCount = inspectImages(root, violations);
        inspectDeploymentRegistries(root, violations);
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
                        String name = path.getFileName().toString()
                                .toLowerCase(Locale.ROOT);
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
            Set<String> stages = new HashSet<>();
            List<String> lines = readLines(dockerfile);
            for (int index = 0; index < lines.size(); index++) {
                Matcher matcher = FROM.matcher(lines.get(index));
                if (!matcher.find()) {
                    continue;
                }
                String image = matcher.group(1);
                // Only earlier stages are internal; a new alias cannot exempt its own base.
                boolean internalStage = stages.contains(image.toLowerCase(Locale.ROOT));
                if (matcher.group(2) != null) {
                    stages.add(matcher.group(2).toLowerCase(Locale.ROOT));
                }
                if (internalStage || "scratch".equalsIgnoreCase(image) || image.startsWith("${")) {
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

    private void inspectDeploymentRegistries(Path root, List<String> violations) {
        Path deploy = root.resolve("deploy");
        if (!Files.isDirectory(deploy)) {
            return;
        }
        List<Path> files;
        try (Stream<Path> stream = Files.walk(deploy)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(path -> isStaticDeploymentYaml(deploy, path))
                    .sorted(Comparator.comparing(path -> relative(root, path)))
                    .toList();
        } catch (IOException error) {
            throw new IllegalArgumentException("Cannot list deployment YAML below " + deploy, error);
        }
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        for (Path file : files) {
            Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            try {
                for (Object document : yaml.loadAll(String.join("\n", readLines(file)))) {
                    inspectDeploymentValue(root, file, document, visited, violations);
                }
            } catch (YAMLException error) {
                throw new IllegalArgumentException(
                        "Cannot parse deployment YAML " + relative(root, file), error);
            }
        }
    }

    private static boolean isStaticDeploymentYaml(Path deploy, Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".yaml") && !name.endsWith(".yml")) {
            return false;
        }
        // Helm templates are checked after rendering; every values overlay is static input.
        Path relative = deploy.relativize(path);
        if (!name.startsWith("values") && relative.startsWith("helm")) {
            for (Path segment : relative) {
                if (segment.toString().equals("templates")) {
                    return false;
                }
            }
        }
        return true;
    }

    private void inspectDeploymentValue(Path root, Path file, Object value,
            Set<Object> visited, List<String> violations) {
        if (value instanceof Map<?, ?> mapping && visited.add(value)) {
            Object image = mapping.get("image");
            if (image instanceof String reference) {
                inspectDeploymentRegistry(root, file, reference, violations);
            } else if (image instanceof Map<?, ?> imageMapping
                    && imageMapping.get("repository") instanceof String reference) {
                inspectDeploymentRegistry(root, file, reference, violations);
            }
            for (Object child : mapping.values()) {
                inspectDeploymentValue(root, file, child, visited, violations);
            }
        } else if (value instanceof List<?> list && visited.add(value)) {
            for (Object child : list) {
                inspectDeploymentValue(root, file, child, visited, violations);
            }
        }
    }

    private void inspectDeploymentRegistry(Path root, Path file, String image,
            List<String> violations) {
        String reference = image.strip();
        int slash = reference.indexOf('/');
        if (slash < 0 || reference.startsWith("${")) {
            return;
        }
        String registry = reference.substring(0, slash);
        // Without an explicit host, Docker resolves a namespace/image through Docker Hub.
        boolean explicit = registry.contains(".") || registry.contains(":")
                || registry.equals("localhost");
        if (explicit && !APPROVED_DEPLOYMENT_REGISTRIES.contains(registry)) {
            violations.add(relative(root, file) + ": unapproved deployment image registry "
                    + registry + " in " + reference);
        }
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
