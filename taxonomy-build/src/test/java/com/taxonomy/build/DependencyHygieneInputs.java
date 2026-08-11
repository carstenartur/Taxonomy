package com.taxonomy.build;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** Reads and materializes the inputs consumed by {@link DependencyHygienePolicy}. */
final class DependencyHygieneInputs {

    private static final Set<String> REQUIRED_EXCEPTION_FIELDS = Set.of(
            "group", "name", "version", "owner", "rationale", "expires",
            "removalCondition");

    private final ObjectMapper objectMapper = new ObjectMapper();

    MaterializedSbom materializeRequestedSbom(Path repositoryRoot, Path requested) {
        Path root = repositoryRoot.toAbsolutePath().normalize();
        Path requestedPath = requested.isAbsolute()
                ? requested.toAbsolutePath().normalize()
                : root.resolve(requested).normalize();
        List<String> errors = new ArrayList<>();

        for (Path candidate : candidateSboms(root, requestedPath)) {
            if (!Files.isRegularFile(candidate)) {
                continue;
            }
            final List<DependencyHygienePolicy.Component> components;
            try {
                components = readComponents(candidate);
            } catch (IllegalArgumentException error) {
                errors.add(relativeOrAbsolute(root, candidate) + ": " + error.getMessage());
                continue;
            }

            if (!candidate.equals(requestedPath)) {
                copy(candidate, requestedPath);
                Path candidateXml = replaceExtension(candidate, ".xml");
                if (Files.isRegularFile(candidateXml)) {
                    copy(candidateXml, replaceExtension(requestedPath, ".xml"));
                }
            }
            return new MaterializedSbom(requestedPath, components, candidate);
        }

        String detail = errors.isEmpty()
                ? "no candidate files found"
                : String.join("; ", errors);
        throw new IllegalArgumentException(
                "no usable CycloneDX SBOM available (" + detail + ")");
    }

    List<DependencyHygienePolicy.Component> readComponents(Path path) {
        final JsonNode root;
        try {
            root = objectMapper.readTree(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException error) {
            throw new IllegalArgumentException(
                    "cannot read SBOM " + path + ": " + error.getMessage(), error);
        }
        JsonNode components = root.path("components");
        if (!components.isArray() || components.isEmpty()) {
            throw new IllegalArgumentException(
                    "SBOM contains no dependency components: " + path);
        }

        List<DependencyHygienePolicy.Component> result = new ArrayList<>();
        for (JsonNode component : components) {
            result.add(new DependencyHygienePolicy.Component(
                    component.path("group").asText(""),
                    component.path("name").asText(""),
                    component.path("version").asText("")));
        }
        return List.copyOf(result);
    }

    List<DependencyHygienePolicy.ReviewedException> loadExceptions(
            Path path,
            LocalDate today) {
        final JsonNode root;
        try {
            root = objectMapper.readTree(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException error) {
            throw new IllegalArgumentException(
                    "cannot read dependency hygiene exceptions " + path + ": "
                            + error.getMessage(), error);
        }
        if (!root.isArray()) {
            throw new IllegalArgumentException(
                    "dependency hygiene exceptions must be a JSON array");
        }

        List<DependencyHygienePolicy.ReviewedException> result = new ArrayList<>();
        Set<DependencyHygienePolicy.Component> seen = new LinkedHashSet<>();
        for (JsonNode item : root) {
            List<String> missing = REQUIRED_EXCEPTION_FIELDS.stream()
                    .filter(field -> !hasNonBlankText(item, field))
                    .sorted()
                    .toList();
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException(
                        "exception is missing non-blank fields " + missing + ": " + item);
            }
            DependencyHygienePolicy.Component component =
                    new DependencyHygienePolicy.Component(
                            item.path("group").asText(),
                            item.path("name").asText(),
                            item.path("version").asText());
            if (!seen.add(component)) {
                throw new IllegalArgumentException(
                        "duplicate dependency exception: " + component.coordinate());
            }

            final LocalDate expires;
            try {
                expires = LocalDate.parse(item.path("expires").asText());
            } catch (DateTimeParseException error) {
                throw new IllegalArgumentException(
                        "invalid dependency exception expiry for "
                                + component.coordinate(), error);
            }
            if (expires.isBefore(today)) {
                throw new IllegalArgumentException(
                        "expired dependency exception: " + component.coordinate());
            }
            result.add(new DependencyHygienePolicy.ReviewedException(
                    component,
                    item.path("owner").asText(),
                    item.path("rationale").asText(),
                    expires,
                    item.path("removalCondition").asText()));
        }
        return List.copyOf(result);
    }

    private static List<Path> candidateSboms(Path root, Path requested) {
        LinkedHashSet<Path> candidates = new LinkedHashSet<>();
        candidates.add(requested);
        candidates.add(root.resolve("taxonomy-app/target/taxonomy-sbom.json"));
        candidates.add(root.resolve("taxonomy-app/target/bom.json"));
        candidates.add(root.resolve("target/bom.json"));

        try (Stream<Path> stream = Files.list(root)) {
            stream.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(module -> {
                        candidates.add(module.resolve("target/taxonomy-sbom.json"));
                        candidates.add(module.resolve("target/bom.json"));
                    });
        } catch (IOException error) {
            throw new IllegalArgumentException(
                    "cannot inspect SBOM candidates below " + root, error);
        }
        return candidates.stream().map(Path::normalize).toList();
    }

    private static void copy(Path source, Path target) {
        try {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) {
            throw new IllegalArgumentException(
                    "cannot materialize packaged SBOM " + source + " -> " + target,
                    error);
        }
    }

    private static Path replaceExtension(Path path, String extension) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot < 0 ? name : name.substring(0, dot);
        return path.resolveSibling(base + extension);
    }

    private static boolean hasNonBlankText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank();
    }

    private static String relativeOrAbsolute(Path root, Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        return absolute.startsWith(root)
                ? root.relativize(absolute).toString().replace('\\', '/')
                : absolute.toString();
    }

    record MaterializedSbom(
            Path requestedPath,
            List<DependencyHygienePolicy.Component> components,
            Path sourcePath) {
    }
}
