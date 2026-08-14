package com.taxonomy.tooling;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Resolves and validates the exact parameters consumed by the release workflow. */
public final class ReleaseParametersResolver {

    private static final Pattern COMMIT_ID = Pattern.compile("^[0-9a-f]{40}$");
    private static final Set<String> INCREMENTS = Set.of("patch", "minor", "major");
    private static final List<String> OUTPUT_KEYS = List.of(
            "release_version",
            "next_development_version",
            "skip_tests",
            "dry_run",
            "resume_staged_release");

    private ReleaseParametersResolver() {
    }

    public static Parameters resolve(
            String eventName,
            Map<String, String> environment,
            Map<String, Object> request,
            String currentVersion) {
        String releaseVersion;
        String nextVersion;
        Object skipValue;
        Object dryRunValue;
        Object resumeValue;

        if ("push".equals(eventName)) {
            if (request == null) {
                throw new IllegalArgumentException(
                        "release request is required for a push event");
            }
            releaseVersion = VersionNumbers.normalizeRelease(
                    request.get("release_version"), "release_version");
            nextVersion = VersionNumbers.normalizeDevelopment(
                    request.getOrDefault("next_development_version", ""),
                    "next_development_version",
                    true);
            skipValue = request.getOrDefault("skip_tests", Boolean.FALSE);
            dryRunValue = request.getOrDefault("dry_run", Boolean.FALSE);
            resumeValue = request.getOrDefault(
                    "resume_staged_release", Boolean.FALSE);
        } else if ("workflow_dispatch".equals(eventName)) {
            if (currentVersion == null) {
                throw new IllegalArgumentException(
                        "current project version is required for workflow_dispatch");
            }
            DerivedVersions derived = deriveReleaseVersions(
                    currentVersion,
                    environment.getOrDefault(
                            "INPUT_NEXT_VERSION_INCREMENT", "patch"),
                    environment.getOrDefault(
                            "INPUT_NEXT_DEVELOPMENT_VERSION", ""));
            releaseVersion = derived.releaseVersion();
            nextVersion = derived.nextDevelopmentVersion();
            skipValue = defaultBoolean(environment.get("INPUT_SKIP_TESTS"));
            dryRunValue = defaultBoolean(environment.get("INPUT_DRY_RUN"));
            resumeValue = Boolean.FALSE;
        } else {
            throw new IllegalArgumentException(
                    "unsupported release event: " + eventName);
        }

        Parameters parameters = new Parameters(
                releaseVersion,
                nextVersion,
                normalizeBoolean(skipValue, "skip_tests"),
                normalizeBoolean(dryRunValue, "dry_run"),
                normalizeBoolean(resumeValue, "resume_staged_release"));
        requireNewerNextVersion(releaseVersion, nextVersion, null);

        if (parameters.resumeStagedRelease()) {
            if (parameters.dryRun()) {
                throw new IllegalArgumentException(
                        "resume_staged_release cannot be combined with dry_run");
            }
            if (parameters.nextDevelopmentVersion().isBlank()) {
                throw new IllegalArgumentException(
                        "next_development_version is required when "
                                + "resume_staged_release is true");
            }
        }
        return parameters;
    }

    public static Parameters resolveFromEnvironment(
            Path root,
            Map<String, String> environment) throws IOException {
        String eventName = requireEnvironment(environment, "EVENT_NAME");
        String currentVersion = XmlSupport.rootProjectVersion(root.resolve("pom.xml"));
        Map<String, Object> request = null;
        if ("push".equals(eventName)) {
            Path configured = Path.of(environment.getOrDefault(
                    "RELEASE_REQUEST_PATH", ".github/release-request.json"));
            Path requestPath = configured.isAbsolute()
                    ? configured
                    : root.resolve(configured);
            request = FlatJson.parseObject(Files.readString(
                    requestPath, StandardCharsets.UTF_8));
            validateReleaseRequestAnchor(root, requestPath, request);
        }

        Parameters parameters = resolve(
                eventName, environment, request, currentVersion);
        validateStagedReleaseAncestry(root, parameters, currentVersion);
        appendOutputs(
                Path.of(requireEnvironment(environment, "GITHUB_OUTPUT")),
                parameters);
        return parameters;
    }

    public static DerivedVersions deriveReleaseVersions(
            String currentVersion,
            String increment,
            Object exactNextVersion) {
        if (!VersionNumbers.DEVELOPMENT.matcher(currentVersion).matches()) {
            throw new IllegalArgumentException(
                    "current project version '" + currentVersion
                            + "' must use X.Y.Z-SNAPSHOT");
        }
        String releaseVersion = currentVersion.substring(
                0, currentVersion.length() - "-SNAPSHOT".length());
        String nextVersion = VersionNumbers.normalizeDevelopment(
                exactNextVersion,
                "next_development_version",
                true);
        if (nextVersion.isBlank()) {
            nextVersion = defaultNextVersion(releaseVersion, increment);
        }
        requireNewerNextVersion(releaseVersion, nextVersion, currentVersion);
        return new DerivedVersions(releaseVersion, nextVersion);
    }

    public static String defaultNextVersion(
            String releaseVersion,
            String increment) {
        String normalized = increment == null || increment.isBlank()
                ? "patch"
                : increment.strip().toLowerCase(Locale.ROOT);
        if (!INCREMENTS.contains(normalized)) {
            throw new IllegalArgumentException(
                    "next_version_increment must be patch, minor or major");
        }
        VersionNumbers.SemanticVersion current = VersionNumbers.semantic(
                releaseVersion);
        int major = current.major();
        int minor = current.minor();
        int patch = current.patch();
        switch (normalized) {
            case "patch" -> patch++;
            case "minor" -> {
                minor++;
                patch = 0;
            }
            case "major" -> {
                major++;
                minor = 0;
                patch = 0;
            }
            default -> throw new IllegalStateException(normalized);
        }
        return major + "." + minor + "." + patch + "-SNAPSHOT";
    }

    public static void requireNewerNextVersion(
            String releaseVersion,
            String nextVersion,
            String currentVersion) {
        if (nextVersion == null || nextVersion.isBlank()
                || VersionNumbers.semantic(nextVersion).compareTo(
                        VersionNumbers.semantic(releaseVersion)) > 0) {
            return;
        }
        String context;
        String guidance;
        if (currentVersion != null) {
            context = "current project version " + currentVersion
                    + " means this run releases " + releaseVersion;
            guidance = "Leave next_development_version empty to use the selected "
                    + "patch, minor or major increment, or enter a higher "
                    + "X.Y.Z-SNAPSHOT version.";
        } else {
            context = "release request publishes " + releaseVersion;
            guidance = "Set next_development_version to a higher "
                    + "X.Y.Z-SNAPSHOT version, or leave it empty when no "
                    + "post-release version advance is required.";
        }
        throw new IllegalArgumentException(
                context + "; next development version " + nextVersion
                        + " must be newer. " + guidance);
    }

    public static void validateReleaseRequestAnchor(
            Path root,
            Path requestPath,
            Map<String, Object> request) {
        Path repository = root.toAbsolutePath().normalize();
        Path absoluteRequest = requestPath.isAbsolute()
                ? requestPath.toAbsolutePath().normalize()
                : repository.resolve(requestPath).normalize();
        if (!absoluteRequest.startsWith(repository)) {
            throw new IllegalArgumentException(
                    "release request path must stay inside the repository");
        }
        String relativeRequest = repository.relativize(absoluteRequest)
                .toString().replace('\\', '/');

        Object anchorValue = request.get("requested_after_commit");
        if (!(anchorValue instanceof String anchor)
                || !COMMIT_ID.matcher(anchor.strip()).matches()) {
            throw new IllegalArgumentException(
                    "requested_after_commit must be a full Git commit ID");
        }
        anchor = anchor.strip();
        long requestRevision = normalizeRequestRevision(
                request.get("request_revision"));

        String parent = GitSupport.require(
                repository, "rev-parse", "HEAD^1").strip();
        if (!parent.equals(anchor)) {
            throw new IllegalArgumentException(
                    "requested_after_commit must equal the release-request "
                            + "commit's first parent " + parent + ", got " + anchor);
        }

        List<String> changedPaths = GitSupport.require(
                repository,
                "diff",
                "--name-only",
                "--diff-filter=ACDMRTUXB",
                anchor,
                "HEAD",
                "--").lines().filter(line -> !line.isBlank()).toList();
        if (!changedPaths.equals(List.of(relativeRequest))) {
            String changed = changedPaths.isEmpty()
                    ? "<none>"
                    : String.join(", ", changedPaths);
            throw new IllegalArgumentException(
                    "release request commit must change only " + relativeRequest
                            + "; changed paths: " + changed);
        }

        GitSupport.Result previousExists = GitSupport.run(
                repository, "cat-file", "-e", anchor + ":" + relativeRequest);
        long previousRevision;
        if (previousExists.exitCode() == 0) {
            Map<String, Object> previous = FlatJson.parseObject(
                    GitSupport.require(repository, "show",
                            anchor + ":" + relativeRequest));
            previousRevision = normalizeRequestRevision(
                    previous.get("request_revision"));
        } else if (previousExists.exitCode() == 1
                || previousExists.exitCode() == 128) {
            previousRevision = 0;
        } else {
            String detail = previousExists.stderr().isBlank()
                    ? "unknown Git error"
                    : previousExists.stderr().strip();
            throw new IllegalArgumentException(
                    "cannot inspect previous release request: " + detail);
        }

        long expected = previousRevision + 1;
        if (requestRevision != expected) {
            throw new IllegalArgumentException(
                    "request_revision must advance from " + previousRevision
                            + " to " + expected + ", got " + requestRevision);
        }
    }

    public static void validateStagedReleaseAncestry(
            Path root,
            Parameters parameters,
            String currentVersion) {
        if (parameters.nextDevelopmentVersion().isBlank()
                || !currentVersion.equals(parameters.nextDevelopmentVersion())) {
            return;
        }
        Path repository = root.toAbsolutePath().normalize();
        if (GitSupport.run(repository, "rev-parse", "--git-dir").exitCode() != 0) {
            return;
        }
        String tag = "v" + parameters.releaseVersion();
        GitSupport.Result tagCommit = GitSupport.run(
                repository,
                "rev-parse",
                "--verify",
                "--quiet",
                tag + "^{commit}");
        if (tagCommit.exitCode() != 0) {
            return;
        }
        GitSupport.Result ancestry = GitSupport.run(
                repository,
                "merge-base",
                "--is-ancestor",
                tagCommit.stdout().strip(),
                "HEAD");
        if (ancestry.exitCode() == 0) {
            return;
        }
        if (ancestry.exitCode() == 1) {
            throw new IllegalArgumentException(
                    "staged release tag " + tag
                            + " is not an ancestor of the current "
                            + currentVersion
                            + " checkout; repair release ancestry before publication");
        }
        String detail = ancestry.stderr().isBlank()
                ? "unknown git merge-base error"
                : ancestry.stderr().strip();
        throw new IllegalArgumentException(
                "cannot verify staged release ancestry for " + tag + ": " + detail);
    }

    public static void appendOutputs(Path output, Parameters parameters)
            throws IOException {
        Map<String, String> values = parameters.outputs();
        StringBuilder text = new StringBuilder();
        for (String key : OUTPUT_KEYS) {
            text.append(key).append('=').append(values.get(key)).append('\n');
        }
        Path parent = output.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(
                output,
                text,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    static Map<String, Object> readRequest(Path path) throws IOException {
        return FlatJson.parseObject(Files.readString(path, StandardCharsets.UTF_8));
    }

    private static String normalizeBoolean(Object value, String field) {
        if (value instanceof Boolean bool) {
            return Boolean.toString(bool);
        }
        if (value instanceof String text) {
            String normalized = text.strip().toLowerCase(Locale.ROOT);
            if ("true".equals(normalized) || "false".equals(normalized)) {
                return normalized;
            }
        }
        throw new IllegalArgumentException(field + " must be true or false");
    }

    private static Object defaultBoolean(String value) {
        return value == null || value.isBlank() ? "false" : value;
    }

    private static long normalizeRequestRevision(Object value) {
        if (!(value instanceof Long revision) || revision < 1) {
            throw new IllegalArgumentException(
                    "request_revision must be a positive integer");
        }
        return revision;
    }

    private static String requireEnvironment(
            Map<String, String> environment,
            String name) {
        String value = environment.get(name);
        if (value == null) {
            throw new IllegalArgumentException(
                    name + " environment variable is required");
        }
        return value;
    }

    public record DerivedVersions(
            String releaseVersion,
            String nextDevelopmentVersion) {
    }

    public record Parameters(
            String releaseVersion,
            String nextDevelopmentVersion,
            String skipTestsValue,
            String dryRunValue,
            String resumeStagedReleaseValue) {

        public boolean skipTests() {
            return Boolean.parseBoolean(skipTestsValue);
        }

        public boolean dryRun() {
            return Boolean.parseBoolean(dryRunValue);
        }

        public boolean resumeStagedRelease() {
            return Boolean.parseBoolean(resumeStagedReleaseValue);
        }

        public Map<String, String> outputs() {
            LinkedHashMap<String, String> result = new LinkedHashMap<>();
            result.put("release_version", releaseVersion);
            result.put("next_development_version", nextDevelopmentVersion);
            result.put("skip_tests", skipTestsValue);
            result.put("dry_run", dryRunValue);
            result.put("resume_staged_release", resumeStagedReleaseValue);
            return result;
        }
    }
}
