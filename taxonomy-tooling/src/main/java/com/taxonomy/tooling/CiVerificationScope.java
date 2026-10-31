package com.taxonomy.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Conservative, source-bound selection of the required CI verification scope. */
final class CiVerificationScope {
    static final List<String> FULL_LANES = List.of("application", "production-image", "app-test-shards",
            "core", "coverage-aggregate", "observability", "ui-contracts", "ui-shards",
            "interoperability-products", "plugin-profile");

    private CiVerificationScope() { }

    record Decision(String scope, String reason, String event, boolean draft,
                    String baseCommit, String commit, String sourceTree, List<String> paths) { }

    static Decision classify(Path root, String event, boolean draft, String base, String commit) {
        requireSha(commit);
        String actual = GitSupport.require(root, "rev-parse", "HEAD").strip();
        require(commit.equals(actual), "CI commit differs from checkout HEAD");
        String tree = GitSupport.require(root, "rev-parse", commit + "^{tree}").strip();
        if (!"pull_request".equals(event))
            return decision("full", "Only pull requests may use a reduced scope", event, draft, base, commit, tree, List.of());
        if (draft)
            return decision("draft", "Draft heads do not satisfy required verification", event, true, base, commit, tree, List.of());
        if (base == null || !base.matches("[0-9a-f]{40}"))
            return decision("full", "Missing or invalid base commit", event, false, base, commit, tree, List.of());
        GitSupport.Result diff = GitSupport.run(root, "diff", "--no-renames", "--name-status", "-z", base, commit, "--");
        if (diff.exitCode() != 0)
            return decision("full", "Changed paths could not be determined", event, false, base, commit, tree, List.of());
        if (diff.stdout().isEmpty())
            return decision("full", "Empty change inventory", event, false, base, commit, tree, List.of());
        String[] tokens = diff.stdout().split("\u0000", -1);
        List<String> paths = new ArrayList<>();
        boolean prose = tokens.length % 2 == 1 && tokens[tokens.length - 1].isEmpty();
        for (int i = 0; i + 1 < tokens.length; i += 2) {
            String status = tokens[i];
            String path = tokens[i + 1];
            paths.add(path);
            if (!(status.equals("A") || status.equals("M")) || !allowedProse(path)) {
                prose = false;
                continue;
            }
            String entry = GitSupport.require(root, "ls-tree", commit, "--", path);
            if (!entry.startsWith("100644 blob ")) prose = false;
        }
        paths.sort(String::compareTo);
        return decision(prose ? "documentation" : "full",
                prose ? "Only allowlisted internal/developer prose changed" : "Change requires complete product verification",
                event, false, base, commit, tree, paths);
    }

    private static Decision decision(String scope, String reason, String event, boolean draft,
                                     String base, String commit, String tree, List<String> paths) {
        return new Decision(scope, reason, event, draft, base == null ? "" : base, commit, tree, List.copyOf(paths));
    }

    private static boolean allowedProse(String path) {
        if (!path.matches("[A-Za-z0-9_./-]+") || path.contains("..") || path.startsWith("/")) return false;
        // These documents carry executable architecture/performance contracts.
        if (path.equals("README.md") || path.equals("docs/dev/OBSERVABILITY_PERFORMANCE.md")) return false;
        return path.matches("(?:README_DE|release_notes)\\.md")
                || path.matches("docs/(?:internal|dev|superpowers)/.+\\.md")
                || path.matches("plugins/[A-Za-z0-9_-]+/README(?:_DE)?\\.md");
    }

    static void write(Path file, Decision decision) throws IOException {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", 1);
        value.put("scope", decision.scope());
        value.put("reason", decision.reason());
        value.put("event", decision.event());
        value.put("draft", decision.draft());
        value.put("baseCommit", decision.baseCommit());
        value.put("commit", decision.commit());
        value.put("sourceTree", decision.sourceTree());
        value.put("paths", decision.paths());
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, FlatJson.pretty(value));
    }

    static Decision readAndVerify(Path root, Path file, String expectedCommit) throws IOException {
        Map<String, Object> saved = FlatJson.parseObject(Files.readString(file));
        require(saved.get("schemaVersion") instanceof Number version && version.intValue() == 1,
                "Unsupported CI scope schema");
        require(expectedCommit.equals(saved.get("commit")), "CI scope commit mismatch");
        require(saved.get("draft") instanceof Boolean, "Missing draft identity");
        Decision expected = classify(root, text(saved, "event"), (Boolean) saved.get("draft"),
                text(saved, "baseCommit"), expectedCommit);
        require(expected.scope().equals(saved.get("scope")) && expected.reason().equals(saved.get("reason"))
                && expected.sourceTree().equals(saved.get("sourceTree")) && expected.paths().equals(saved.get("paths")),
                "CI scope does not match the current source change");
        return expected;
    }

    static void requireLanes(String scope, Map<String, String> results) {
        require(!"draft".equals(scope), "Draft pull-request heads intentionally do not satisfy required Maven verification");
        require(scope.equals("full") || scope.equals("documentation"), "Unknown CI scope");
        require("success".equals(results.get("change-scope")), "CI scope planner did not succeed");
        for (String lane : FULL_LANES) {
            String expected = scope.equals("full") || lane.equals("application") ? "success" : "skipped";
            require(expected.equals(results.get(lane)), lane + " ended with " + results.get(lane) + "; expected " + expected);
        }
        String expected = scope.equals("documentation") ? "success" : "skipped";
        require(expected.equals(results.get("documentation")), "Documentation lane did not have the required outcome");
    }

    static int run(String[] raw, Map<String, String> env, Path cwd, PrintStream out, PrintStream err, boolean gate) {
        try {
            var args = TaxonomyTooling.Arguments.parse(raw);
            Path root = args.path("root", cwd).toAbsolutePath().normalize();
            String commit = args.optionalOrDefault("commit", env.getOrDefault("GITHUB_SHA", GitSupport.require(root, "rev-parse", "HEAD").strip()));
            Path file = args.path("scope-file", root.resolve("target/ci-change-scope.json"));
            if (gate) {
                Decision scope = readAndVerify(root, file, commit);
                require(Objects.equals(scope.event(), env.get("GITHUB_EVENT_NAME")), "CI event mismatch");
                require(scope.draft() == Boolean.parseBoolean(env.getOrDefault("PR_DRAFT", "false")), "CI draft identity mismatch");
                require(scope.baseCommit().equals(env.getOrDefault("PR_BASE_SHA", "")), "CI base commit mismatch");
                Map<String, Object> needs = FlatJson.parseObject(env.getOrDefault("CI_NEEDS_JSON", "{}"));
                Map<String, String> results = new LinkedHashMap<>();
                for (var entry : needs.entrySet()) {
                    require(entry.getValue() instanceof Map<?, ?>, "Invalid CI lane result");
                    Object result = ((Map<?, ?>) entry.getValue()).get("result");
                    require(result instanceof String, "Missing CI lane result");
                    results.put(entry.getKey(), (String) result);
                }
                requireLanes(scope.scope(), results);
                out.println("Required lanes verified for scope: " + scope.scope());
                return 0;
            }
            Decision scope = classify(root, env.getOrDefault("GITHUB_EVENT_NAME", "local"),
                    Boolean.parseBoolean(env.getOrDefault("PR_DRAFT", "false")),
                    env.getOrDefault("PR_BASE_SHA", ""), commit);
            write(file, scope);
            if (env.containsKey("GITHUB_OUTPUT")) Files.writeString(Path.of(env.get("GITHUB_OUTPUT")),
                    "scope=" + scope.scope() + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            out.println("CI verification scope: " + scope.scope() + " — " + scope.reason());
            return 0;
        } catch (IOException | IllegalArgumentException error) {
            err.println("::error::" + error.getMessage());
            return 1;
        }
    }

    private static String text(Map<String, Object> map, String key) {
        require(map.get(key) instanceof String, "Missing CI scope field: " + key);
        return (String) map.get(key);
    }

    private static void requireSha(String value) {
        require(value != null && value.matches("[0-9a-f]{40}"), "Expected a full source commit SHA");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
