package com.taxonomy.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Dependency-free authority for immutable Helm OCI publication evidence. */
public final class HelmOciEvidenceTool {

    private static final Pattern VERSION = Pattern.compile(
            "^[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z][0-9A-Za-z.-]*)?$");
    private static final Pattern SOURCE_COMMIT = Pattern.compile("^[0-9a-f]{40}$");
    private static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern OCI_CHART = Pattern.compile(
            "^oci://ghcr\\.io/[a-z0-9._-]+(?:/[a-z0-9._-]+)+$");
    private static final Set<String> PUBLICATION_STATUSES =
            Set.of("published", "reused");

    private HelmOciEvidenceTool() {
    }

    public static void main(String[] arguments) {
        int exitCode = run(arguments, Path.of("."), System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(
            String[] arguments,
            Path workingDirectory,
            PrintStream output,
            PrintStream error) {
        try {
            Map<String, String> options = parse(arguments);
            Result result = generate(
                    path(options, "output", workingDirectory),
                    required(options, "chart"),
                    required(options, "version"),
                    required(options, "release-tag"),
                    required(options, "source-commit"),
                    required(options, "publication-status"),
                    required(options, "archive-sha256"),
                    required(options, "manifest-sha256"));
            output.println("Helm OCI evidence generated: " + result.output());
            output.println("  Version: " + result.version());
            output.println("  Release tag: " + result.releaseTag());
            return 0;
        } catch (IOException | IllegalArgumentException failure) {
            error.println("::error::" + failure.getMessage());
            return 1;
        }
    }

    static Result generate(
            Path output,
            String chart,
            String version,
            String releaseTag,
            String sourceCommit,
            String publicationStatus,
            String archiveSha256,
            String renderedManifestSha256) throws IOException {
        Path destination = output.toAbsolutePath().normalize();
        Files.deleteIfExists(destination);
        try {
            String exactChart = requirePattern(chart, "chart", OCI_CHART);
            String exactVersion = requirePattern(version, "version", VERSION);
            if (exactVersion.toUpperCase(Locale.ROOT).contains("SNAPSHOT")) {
                throw new IllegalArgumentException(
                        "version must identify an immutable non-SNAPSHOT release");
            }
            String exactTag = require(releaseTag, "releaseTag");
            if (!exactTag.equals("v" + exactVersion)) {
                throw new IllegalArgumentException(
                        "releaseTag must equal v<version>, got '" + exactTag + "'");
            }
            String exactSourceCommit = requirePattern(
                    sourceCommit, "sourceCommit", SOURCE_COMMIT);
            String exactStatus = require(publicationStatus, "publicationStatus");
            if (!PUBLICATION_STATUSES.contains(exactStatus)) {
                throw new IllegalArgumentException(
                        "publicationStatus must be published or reused");
            }
            String exactArchiveSha = requirePattern(
                    archiveSha256, "archiveSha256", SHA_256);
            String exactManifestSha = requirePattern(
                    renderedManifestSha256,
                    "renderedManifestSha256",
                    SHA_256);

            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("appVersion", exactVersion);
            evidence.put("archiveSha256", exactArchiveSha);
            evidence.put("chart", exactChart);
            evidence.put(
                    "image",
                    "ghcr.io/carstenartur/taxonomy:" + exactTag);
            evidence.put("publicationStatus", exactStatus);
            evidence.put("renderedManifestSha256", exactManifestSha);
            evidence.put("schemaVersion", 1);
            evidence.put("sourceCommit", exactSourceCommit);
            evidence.put("upgradeStrategy", "Recreate");
            evidence.put(
                    "verification",
                    "pulled-back-and-rendered-identically");
            evidence.put("version", exactVersion);

            Files.createDirectories(destination.getParent());
            Files.writeString(
                    destination,
                    FlatJson.pretty(evidence) + "\n",
                    StandardCharsets.UTF_8);
            return new Result(destination, exactVersion, exactTag);
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(destination);
            throw failure;
        }
    }

    private static Map<String, String> parse(String[] arguments) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (int index = 0; index < arguments.length; index++) {
            String token = arguments[index];
            if (!token.startsWith("--") || token.length() == 2) {
                throw new IllegalArgumentException(
                        "Expected --option, got " + token);
            }
            String name = token.substring(2);
            if (index + 1 >= arguments.length
                    || arguments[index + 1].startsWith("--")) {
                throw new IllegalArgumentException(
                        "Missing value for --" + name);
            }
            if (result.putIfAbsent(name, arguments[++index]) != null) {
                throw new IllegalArgumentException(
                        "Duplicate option --" + name);
            }
        }
        return result;
    }

    private static Path path(
            Map<String, String> options,
            String name,
            Path workingDirectory) {
        String value = required(options, name);
        Path path = Path.of(value);
        return path.isAbsolute() ? path : workingDirectory.resolve(path);
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null) {
            throw new IllegalArgumentException("--" + name + " is required");
        }
        return value;
    }

    private static String requirePattern(
            String value, String field, Pattern pattern) {
        String normalized = require(value, field);
        if (!pattern.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " has an invalid immutable evidence value: '"
                            + normalized + "'");
        }
        return normalized;
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }

    record Result(Path output, String version, String releaseTag) {
    }
}
