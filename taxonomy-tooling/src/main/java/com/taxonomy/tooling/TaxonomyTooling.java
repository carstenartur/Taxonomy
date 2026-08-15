package com.taxonomy.tooling;

import org.w3c.dom.Element;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Entry point for dependency-free release and repository tooling. */
public final class TaxonomyTooling {

    private TaxonomyTooling() {
    }

    public static void main(String[] arguments) {
        int exitCode = run(
                arguments,
                System.getenv(),
                Path.of("."),
                System.out,
                System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(
            String[] arguments,
            Path workingDirectory,
            PrintStream output,
            PrintStream error) {
        return run(arguments, Map.of(), workingDirectory, output, error);
    }

    public static int run(
            String[] arguments,
            Map<String, String> environment,
            Path workingDirectory,
            PrintStream output,
            PrintStream error) {
        if (arguments.length == 0) {
            error.println("Usage: taxonomy-tooling <command> [options]");
            return 2;
        }
        String command = arguments[0];
        String[] commandArguments = java.util.Arrays.copyOfRange(
                arguments, 1, arguments.length);
        return switch (command) {
            case "resolve-release-parameters" -> resolveReleaseParameters(
                    commandArguments, environment, workingDirectory, error);
            case "check-version-state" -> checkVersionState(
                    commandArguments, workingDirectory, output, error);
            case "check-release-plan" -> checkReleasePlan(
                    commandArguments, workingDirectory, output, error);
            case "compare-versions" -> compareVersions(commandArguments, error);
            case "read-pom-version" -> readPomVersion(
                    commandArguments, workingDirectory, output, error);
            case "update-release-metadata" -> updateReleaseMetadata(
                    commandArguments, workingDirectory, output, error);
            default -> {
                error.println("Unknown taxonomy-tooling command: " + command);
                yield 2;
            }
        };
    }

    private static int resolveReleaseParameters(
            String[] rawArguments,
            Map<String, String> environment,
            Path workingDirectory,
            PrintStream error) {
        try {
            Arguments arguments = Arguments.parse(rawArguments);
            Path root = arguments.path("root", workingDirectory)
                    .toAbsolutePath().normalize();
            ReleaseParametersResolver.resolveFromEnvironment(root, environment);
            return 0;
        } catch (IOException | IllegalArgumentException failure) {
            error.println("::error::" + failure.getMessage());
            return 1;
        }
    }

    private static int checkVersionState(
            String[] rawArguments,
            Path workingDirectory,
            PrintStream output,
            PrintStream error) {
        try {
            Arguments arguments = Arguments.parse(rawArguments);
            Path root = arguments.path("root", workingDirectory)
                    .toAbsolutePath().normalize();
            VersionStateVerifier.Verification verification =
                    VersionStateVerifier.verify(
                            root,
                            arguments.required("mode"),
                            arguments.optional("expected-version"),
                            arguments.optional("tag"));
            if (!verification.successful()) {
                error.println("Inconsistent repository version state:");
                for (String failure : verification.failures()) {
                    error.println("- " + failure);
                }
                return 1;
            }
            output.println("Repository version state is consistent: "
                    + verification.version() + " (" + verification.mode() + ").");
            return 0;
        } catch (IOException | IllegalArgumentException failure) {
            error.println("Version-state check failed: " + failure.getMessage());
            return 1;
        }
    }

    private static int checkReleasePlan(
            String[] rawArguments,
            Path workingDirectory,
            PrintStream output,
            PrintStream error) {
        try {
            Arguments arguments = Arguments.parse(rawArguments);
            Path root = arguments.path("root", workingDirectory)
                    .toAbsolutePath().normalize();
            ReleasePlanValidator.Result result = ReleasePlanValidator.validate(
                    root,
                    arguments.required("current-version"),
                    arguments.required("release-version"),
                    arguments.required("next-development-version"),
                    arguments.optionalOrDefault("state", "development"),
                    arguments.booleanValue("require-clean", true));
            output.println("Maven release check passed: "
                    + result.currentVersion() + " -> " + result.releaseVersion()
                    + " -> " + result.nextDevelopmentVersion() + " ("
                    + result.state() + ", " + result.pomCount()
                    + " reactor POMs).");
            return 0;
        } catch (IOException | IllegalArgumentException failure) {
            error.println("Release check failed: " + failure.getMessage());
            return 1;
        }
    }

    private static int compareVersions(
            String[] rawArguments,
            PrintStream error) {
        try {
            Arguments arguments = Arguments.parse(rawArguments);
            String release = VersionNumbers.normalizeRelease(
                    arguments.required("release-version"), "release_version");
            String next = VersionNumbers.normalizeDevelopment(
                    arguments.required("next-development-version"),
                    "next_development_version",
                    false);
            VersionNumbers.requireNewer(release, next);
            return 0;
        } catch (IllegalArgumentException failure) {
            error.println("::error::" + failure.getMessage());
            return 1;
        }
    }

    private static int readPomVersion(
            String[] rawArguments,
            Path workingDirectory,
            PrintStream output,
            PrintStream error) {
        try {
            Arguments arguments = Arguments.parse(rawArguments);
            String version;
            if (arguments.flag("stdin")) {
                Element project = XmlSupport.parse(System.in).getDocumentElement();
                version = XmlSupport.childText(project, "version");
                if (version.isBlank()) {
                    throw new IllegalArgumentException(
                            "pom.xml has no project version");
                }
            } else {
                version = XmlSupport.rootProjectVersion(
                        arguments.path(
                                "file",
                                workingDirectory.resolve("pom.xml")));
            }
            output.println(version);
            return 0;
        } catch (IOException | IllegalArgumentException failure) {
            error.println("::error::" + failure.getMessage());
            return 1;
        }
    }

    private static int updateReleaseMetadata(
            String[] rawArguments,
            Path workingDirectory,
            PrintStream output,
            PrintStream error) {
        try {
            Arguments arguments = Arguments.parse(rawArguments);
            boolean release = arguments.flag("release");
            String dateText = arguments.optional("date");
            if (!release && dateText != null) {
                throw new IllegalArgumentException(
                        "--date is valid only together with --release");
            }
            LocalDate releaseDate = release
                    ? dateText == null
                            ? LocalDate.now(ZoneOffset.UTC)
                            : LocalDate.parse(dateText)
                    : null;
            ReleaseMetadataUpdater.Result result = ReleaseMetadataUpdater.update(
                    arguments.path("root", workingDirectory),
                    arguments.required("version"),
                    release,
                    releaseDate);
            output.println("Release metadata updated: "
                    + result.version() + " ("
                    + (result.release() ? "release " + result.releaseDate()
                            : "development")
                    + ", " + result.updatedFiles().size() + " files).");
            return 0;
        } catch (IOException | IllegalArgumentException | DateTimeException failure) {
            error.println("::error::" + failure.getMessage());
            return 1;
        }
    }

    private static final class Arguments {
        private static final Set<String> FLAGS = Set.of("stdin", "release");

        private final Map<String, String> values;
        private final Map<String, Boolean> flags;

        private Arguments(
                Map<String, String> values,
                Map<String, Boolean> flags) {
            this.values = values;
            this.flags = flags;
        }

        static Arguments parse(String[] raw) {
            LinkedHashMap<String, String> values = new LinkedHashMap<>();
            LinkedHashMap<String, Boolean> flags = new LinkedHashMap<>();
            for (int index = 0; index < raw.length; index++) {
                String token = raw[index];
                if (!token.startsWith("--") || token.length() == 2) {
                    throw new IllegalArgumentException(
                            "Expected --option, got " + token);
                }
                String name = token.substring(2);
                if (FLAGS.contains(name)) {
                    if (flags.putIfAbsent(name, Boolean.TRUE) != null) {
                        throw new IllegalArgumentException(
                                "Duplicate flag --" + name);
                    }
                    continue;
                }
                if (index + 1 >= raw.length || raw[index + 1].startsWith("--")) {
                    throw new IllegalArgumentException(
                            "Missing value for --" + name);
                }
                if (values.putIfAbsent(name, raw[++index]) != null) {
                    throw new IllegalArgumentException(
                            "Duplicate option --" + name);
                }
            }
            return new Arguments(values, flags);
        }

        String required(String name) {
            String value = values.get(name);
            if (value == null) {
                throw new IllegalArgumentException(
                        "--" + name + " is required");
            }
            return value;
        }

        String optional(String name) {
            return values.get(name);
        }

        String optionalOrDefault(String name, String fallback) {
            String value = values.get(name);
            return value == null ? fallback : value;
        }

        Path path(String name, Path fallback) {
            String value = values.get(name);
            return value == null ? fallback : Path.of(value);
        }

        boolean booleanValue(String name, boolean fallback) {
            String value = values.get(name);
            if (value == null) {
                return fallback;
            }
            if ("true".equalsIgnoreCase(value)) {
                return true;
            }
            if ("false".equalsIgnoreCase(value)) {
                return false;
            }
            throw new IllegalArgumentException(
                    "--" + name + " must be true or false");
        }

        boolean flag(String name) {
            return flags.getOrDefault(name, Boolean.FALSE);
        }
    }
}
