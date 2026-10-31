package com.taxonomy.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.math.BigInteger;
import java.util.regex.Pattern;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipFile;

/** Actual package checks and explicitly scoped evidence for prose-only pull requests. */
final class CiDocumentationVerifier {
    private static final String SUITE = "com.taxonomy.build.DocumentationPackageIT";
    private static final String REPORT = "TEST-" + SUITE + ".xml";
    private static final String PREFIX = "BOOT-INF/classes/docs/";

    private CiDocumentationVerifier() { }

    static Map<String, Object> inspectPackage(Path root, CiVerificationScope.Decision scope, Path artifact) throws IOException {
        require(scope.scope().equals("documentation"), "Documentation verification requires documentation scope");
        require(GitSupport.run(root, "diff", "--quiet", scope.commit(), "--").exitCode() == 0,
                "Checkout differs from the declared source commit");
        Map<String, Object> application = FlatJson.parseObject(Files.readString(artifact.resolve("manifest.json")));
        require(scope.commit().equals(application.get("sourceCommit")), "Application commit mismatch");
        require(scope.sourceTree().equals(application.get("sourceTree")), "Application source tree mismatch");
        Object filename = application.get("jarName");
        require(filename instanceof String name && name.matches("taxonomy-app-[A-Za-z0-9._+-]+\\.jar")
                && !name.contains(".."), "Unsafe application JAR name");
        Path jar = artifact.resolve((String) filename);
        require(sha256(jar).equals(application.get("sha256")), "Application JAR digest mismatch");
        Map<String, String> expected = new TreeMap<>();
        String inventory = GitSupport.require(root, "ls-tree", "-r", "-z", "--name-only", scope.commit(), "--", "docs");
        for (String path : inventory.split("\u0000")) {
            if (path.isBlank() || !(path.endsWith(".md") || path.startsWith("docs/images/"))) continue;
            Path source = root.resolve(path).normalize();
            require(source.startsWith(root) && !Files.isSymbolicLink(source) && Files.isRegularFile(source),
                    "Unsafe documentation source: " + path);
            expected.put(PREFIX + path.substring("docs/".length()), sha256(source));
        }
        require(!expected.isEmpty(), "Packaged documentation inventory is empty");
        Map<String, String> actual = new TreeMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith(PREFIX)) continue;
                try (var input = zip.getInputStream(entry)) {
                    require(actual.put(entry.getName(), digest(input)) == null,
                            "Duplicate packaged document: " + entry.getName());
                }
            }
        }
        require(expected.equals(actual), "Packaged documentation differs from the complete source inventory");
        Map<String, String> changed = new TreeMap<>();
        for (String path : scope.paths()) {
            byte[] bytes = Files.readAllBytes(root.resolve(path));
            verifyProse(bytes, path);
            changed.put(path, sha256(root.resolve(path)));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 1);
        result.put("scope", "documentation");
        result.put("fullVerification", false);
        result.put("coverageExecuted", false);
        result.put("commit", scope.commit());
        result.put("sourceTree", scope.sourceTree());
        result.put("baseCommit", scope.baseCommit());
        result.put("applicationSha256", application.get("sha256"));
        result.put("applicationManifestSha256", sha256(artifact.resolve("manifest.json")));
        result.put("changedDocuments", changed);
        result.put("packagedDocuments", actual);
        return result;
    }

    static void verifyProse(byte[] bytes, String path) {
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("Documentation is not valid UTF-8: " + path, error);
        }
        require(!text.isBlank() && !text.contains("\u0000"), "Empty or binary documentation: " + path);
        String fence = null;
        for (String line : text.lines().toList()) {
            String stripped = line.stripLeading();
            if (stripped.startsWith("```") || stripped.startsWith("~~~")) {
                String marker = stripped.substring(0, 3);
                if (fence == null) fence = marker;
                else if (fence.equals(marker)) fence = null;
            } else if (fence == null) {
                require(!stripped.startsWith("<<<<<<< ") && !stripped.startsWith(">>>>>>> "),
                        "Unresolved documentation conflict: " + path);
            }
        }
    }

    static void verifyJUnit(Path report) throws IOException {
        var suite = XmlSupport.parse(report).getDocumentElement();
        require("testsuite".equals(suite.getTagName()) && SUITE.equals(suite.getAttribute("name")),
                "Unexpected documentation JUnit suite");
        require("1".equals(suite.getAttribute("tests")) && "0".equals(suite.getAttribute("failures"))
                && "0".equals(suite.getAttribute("errors")) && "0".equals(suite.getAttribute("skipped")),
                "Documentation package test must execute and pass");
        var cases = XmlSupport.children(suite, "testcase");
        require(cases.size() == 1, "Documentation JUnit case is missing");
        for (String failure : java.util.List.of("failure", "error", "skipped", "flakyFailure", "flakyError", "rerunFailure", "rerunError"))
            require(suite.getElementsByTagName(failure).getLength() == 0, "Documentation test did not pass originally");
    }

    static int run(String[] raw, Map<String, String> env, Path cwd, PrintStream out, PrintStream err) {
        try {
            var args = TaxonomyTooling.Arguments.parse(raw);
            Path root = args.path("root", cwd).toAbsolutePath().normalize();
            String commit = args.optionalOrDefault("commit", env.getOrDefault("GITHUB_SHA", GitSupport.require(root, "rev-parse", "HEAD").strip()));
            Path scopeFile = args.path("scope-file", root.resolve("target/ci-change-scope.json"));
            var scope = CiVerificationScope.readAndVerify(root, scopeFile, commit);
            if (env.containsKey("GITHUB_EVENT_NAME")) {
                require(scope.event().equals(env.get("GITHUB_EVENT_NAME")), "Documentation CI event mismatch");
                require(scope.baseCommit().equals(env.getOrDefault("PR_BASE_SHA", "")), "Documentation base commit mismatch");
                require(!Boolean.parseBoolean(env.getOrDefault("PR_DRAFT", "false")), "Draft documentation cannot satisfy verification");
            }
            Path artifact = args.path("application", root.resolve("target/ui-application"));
            Path destination = root.resolve("target/documentation-verification");
            Map<String, Object> inspected = inspectPackage(root, scope, artifact);
            Files.createDirectories(destination);
            if (args.booleanValue("package", false)) {
                Files.writeString(destination.resolve("package.json"), FlatJson.pretty(inspected));
                out.println("Verified " + ((Map<?, ?>) inspected.get("packagedDocuments")).size() + " packaged documentation resources against " + scope.sourceTree());
                return 0;
            }
            Map<String, Object> original = FlatJson.parseObject(Files.readString(destination.resolve("package.json")));
            require(original.equals(FlatJson.parseObject(FlatJson.pretty(inspected))), "Documentation package evidence changed");
            String build = env.getOrDefault("GITHUB_RUN_ID", "local") + "." + env.getOrDefault("GITHUB_RUN_ATTEMPT", "1") + ".documentation";
            if (args.booleanValue("stage", false)) {
                Path report = args.path("junit", root.resolve(".github/target/failsafe-reports/" + REPORT));
                verifyJUnit(report);
                Files.copy(report, destination.resolve(REPORT), StandardCopyOption.REPLACE_EXISTING);
                var manifest = new LinkedHashMap<String, Object>();
                manifest.put("schemaVersion", 1);
                manifest.put("scope", "documentation");
                manifest.put("commit", commit);
                manifest.put("sourceTree", scope.sourceTree());
                manifest.put("buildId", build);
                manifest.put("scopeSha256", sha256(scopeFile));
                manifest.put("packageSha256", sha256(destination.resolve("package.json")));
                manifest.put("junitSha256", sha256(destination.resolve(REPORT)));
                Files.writeString(destination.resolve("manifest.json"), FlatJson.pretty(manifest));
                Files.writeString(destination.resolve("README.md"), "# Documentation verification\n\nScope: documentation only.\n\nCommit: " + commit
                        + "\n\nSource tree: " + scope.sourceTree() + "\n\nBuild: " + build
                        + "\n\nThe packaged documentation and changed prose were verified. Full product tests and coverage were not executed in this scope.\n");
            } else if (args.booleanValue("verify", false)) {
                var manifest = FlatJson.parseObject(Files.readString(destination.resolve("manifest.json")));
                require("documentation".equals(manifest.get("scope")) && commit.equals(manifest.get("commit"))
                        && scope.sourceTree().equals(manifest.get("sourceTree")) && compatibleBuild(manifest.get("buildId"), build),
                        "Documentation evidence identity mismatch");
                require(sha256(scopeFile).equals(manifest.get("scopeSha256"))
                        && sha256(destination.resolve("package.json")).equals(manifest.get("packageSha256"))
                        && sha256(destination.resolve(REPORT)).equals(manifest.get("junitSha256")), "Documentation evidence digest mismatch");
                verifyJUnit(destination.resolve(REPORT));
            } else throw new IllegalArgumentException("Expected --package true, --stage true or --verify true");
            out.println("Verified source-bound documentation evidence; full product coverage is a separate scope.");
            return 0;
        } catch (IOException | IllegalArgumentException error) {
            err.println("::error::" + error.getMessage());
            return 1;
        }
    }

    static boolean compatibleBuild(Object producer, String verifier) {
        if (!(producer instanceof String original)) return false;
        // Local identities remain exact; CI may reuse an earlier successful producer
        // from this workflow run without restamping its original evidence.
        if (original.equals(verifier) && !original.matches("[0-9]+\\..*")) return true;
        Pattern identity = Pattern.compile("([1-9][0-9]*)\\.([1-9][0-9]*)\\.documentation");
        var first = identity.matcher(original);
        var current = identity.matcher(verifier);
        return first.matches() && current.matches() && first.group(1).equals(current.group(1))
                && new BigInteger(first.group(2)).compareTo(new BigInteger(current.group(2))) <= 0;
    }

    private static String sha256(Path file) throws IOException {
        try (var input = Files.newInputStream(file)) { return digest(input); }
    }

    private static String digest(java.io.InputStream input) throws IOException {
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            for (int read; (read = input.read(buffer)) != -1;) hash.update(buffer, 0, read);
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
