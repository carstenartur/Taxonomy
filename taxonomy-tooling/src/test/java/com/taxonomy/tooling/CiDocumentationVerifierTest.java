package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class CiDocumentationVerifierTest {
    @TempDir Path root;

    @Test void verifiesAllPackagedDocumentationAndRecordsItsActualSource() throws Exception {
        fixture();
        Map<String, Object> result = CiDocumentationVerifier.inspectPackage(root, scope(), artifact());
        assertEquals("documentation", result.get("scope"));
        assertEquals(false, result.get("fullVerification"));
        assertEquals(false, result.get("coverageExecuted"));
        assertEquals(2, ((Map<?, ?>) result.get("packagedDocuments")).size());
        assertEquals(scope().commit(), result.get("commit"));
    }

    @Test void rejectsMissingStaleAndUnexpectedPackagedDocsEvenWithAFreshJarDigest() throws Exception {
        fixture();
        for (Map<String, byte[]> entries : java.util.List.of(
                Map.of("BOOT-INF/classes/docs/dev/notes.md", bytes("# Updated\n")),
                Map.of("BOOT-INF/classes/docs/dev/notes.md", bytes("# Old\n"), "BOOT-INF/classes/docs/images/a.svg", bytes("<svg/>")),
                Map.of("BOOT-INF/classes/docs/dev/notes.md", bytes("# Updated\n"), "BOOT-INF/classes/docs/images/a.svg", bytes("<svg/>"),
                        "BOOT-INF/classes/docs/deleted.md", bytes("# Deleted\n")))) {
            jar(entries);
            assertThrows(IllegalArgumentException.class, () -> CiDocumentationVerifier.inspectPackage(root, scope(), artifact()));
        }
    }

    @Test void rejectsCommitTreeDigestAndCheckoutMismatches() throws Exception {
        fixture();
        Path manifest = artifact().resolve("manifest.json");
        String original = Files.readString(manifest);
        for (String field : java.util.List.of("sourceCommit", "sourceTree", "sha256")) {
            var data = FlatJson.parseObject(original);
            data.put(field, "0".repeat(field.equals("sha256") ? 64 : 40));
            Files.writeString(manifest, FlatJson.pretty(data));
            assertThrows(IllegalArgumentException.class, () -> CiDocumentationVerifier.inspectPackage(root, scope(), artifact()), field);
        }
        Files.writeString(manifest, original);
        Files.writeString(root.resolve("docs/dev/notes.md"), "# Changed after checkout\n");
        assertThrows(IllegalArgumentException.class, () -> CiDocumentationVerifier.inspectPackage(root, scope(), artifact()));
    }

    @Test void rejectsUnsafeJarNamesAndUnresolvedProseConflicts() throws Exception {
        fixture();
        Path manifest = artifact().resolve("manifest.json");
        var data = FlatJson.parseObject(Files.readString(manifest));
        data.put("jarName", "../other.jar");
        Files.writeString(manifest, FlatJson.pretty(data));
        assertThrows(IllegalArgumentException.class, () -> CiDocumentationVerifier.inspectPackage(root, scope(), artifact()));
        assertThrows(IllegalArgumentException.class, () -> CiDocumentationVerifier.verifyProse(bytes("# Title\n<<<<<<< branch\ntext\n"), "docs/dev/test.md"));
        assertDoesNotThrow(() -> CiDocumentationVerifier.verifyProse(bytes("# Title\n```text\n<<<<<<< example\n```\n"), "docs/dev/test.md"));
    }

    @Test void onlyPositiveOriginalDocumentationJUnitCanSatisfyTheGate() throws Exception {
        Path report = root.resolve("junit.xml");
        String valid = "<testsuite name=\"com.taxonomy.build.DocumentationPackageIT\" tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\"><testcase name=\"packagedDocumentationMatchesSource\"/></testsuite>";
        Files.writeString(report, valid);
        assertDoesNotThrow(() -> CiDocumentationVerifier.verifyJUnit(report));
        for (String invalid : java.util.List.of(valid.replace("tests=\"1\"", "tests=\"0\""),
                valid.replace("skipped=\"0\"", "skipped=\"1\""), valid.replace("failures=\"0\"", "failures=\"1\""),
                valid.replace("name=\"packagedDocumentationMatchesSource\"/>", "name=\"packagedDocumentationMatchesSource\"><failure/></testcase>"),
                valid.replace("DocumentationPackageIT", "OtherIT"))) {
            Files.writeString(report, invalid);
            assertThrows(IllegalArgumentException.class, () -> CiDocumentationVerifier.verifyJUnit(report));
        }
    }

    @Test void stagedEvidenceRoundTripRejectsChangedReportsAndAnotherBase() throws Exception {
        fixture();
        var env = new LinkedHashMap<String, String>();
        env.put("GITHUB_SHA", scope().commit());
        env.put("GITHUB_EVENT_NAME", "pull_request");
        env.put("PR_BASE_SHA", scope().baseCommit());
        env.put("GITHUB_RUN_ID", "123");
        env.put("GITHUB_RUN_ATTEMPT", "1");
        String name = "TEST-com.taxonomy.build.DocumentationPackageIT.xml";
        Path junit = root.resolve(".github/target/failsafe-reports/" + name);
        Files.createDirectories(junit.getParent());
        Files.writeString(junit, "<testsuite name=\"com.taxonomy.build.DocumentationPackageIT\" tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\"><testcase name=\"packagedDocumentationMatchesSource\"/></testsuite>");
        for (String action : java.util.List.of("package", "stage", "verify"))
            assertEquals(0, TaxonomyTooling.run(new String[]{"check-ci-documentation", "--" + action, "true"}, env, root, System.out, System.err));
        env.put("GITHUB_RUN_ATTEMPT", "2");
        assertEquals(0, TaxonomyTooling.run(new String[]{"check-ci-documentation", "--verify", "true"}, env, root, System.out, System.err));
        env.put("GITHUB_RUN_ID", "124");
        assertEquals(1, TaxonomyTooling.run(new String[]{"check-ci-documentation", "--verify", "true"}, env, root, System.out, System.err));
        env.put("GITHUB_RUN_ID", "123");
        env.put("GITHUB_RUN_ATTEMPT", "0");
        assertEquals(1, TaxonomyTooling.run(new String[]{"check-ci-documentation", "--verify", "true"}, env, root, System.out, System.err));
        env.put("GITHUB_RUN_ATTEMPT", "1");
        env.put("PR_BASE_SHA", "0".repeat(40));
        assertEquals(1, TaxonomyTooling.run(new String[]{"check-ci-documentation", "--verify", "true"}, env, root, System.out, System.err));
        env.put("PR_BASE_SHA", scope().baseCommit());
        Path staged = root.resolve("target/documentation-verification/" + name);
        Files.writeString(staged, Files.readString(staged).replace("skipped=\"0\"", "skipped=\"1\""));
        assertEquals(1, TaxonomyTooling.run(new String[]{"check-ci-documentation", "--verify", "true"}, env, root, System.out, System.err));
    }

    @Test void retryIdentityNeverAcceptsAnotherRunOrFutureProducer() {
        assertTrue(CiDocumentationVerifier.compatibleBuild("123.1.documentation", "123.2.documentation"));
        assertTrue(CiDocumentationVerifier.compatibleBuild("local.1.documentation", "local.1.documentation"));
        for (String producer : java.util.List.of("123.3.documentation", "124.1.documentation",
                "0.1.documentation", "123.0.documentation", "local.1.documentation", "123.1.other"))
            assertFalse(CiDocumentationVerifier.compatibleBuild(producer, "123.2.documentation"), producer);
    }

    private void fixture() throws Exception {
        GitSupport.require(root, "init", "--quiet");
        GitSupport.require(root, "config", "user.name", "CI fixture");
        GitSupport.require(root, "config", "user.email", "ci@example.invalid");
        Files.createDirectories(root.resolve("docs/dev"));
        Files.createDirectories(root.resolve("docs/images"));
        Files.writeString(root.resolve("docs/dev/notes.md"), "# Before\n");
        Files.writeString(root.resolve("docs/images/a.svg"), "<svg/>");
        String base = commit();
        Files.writeString(root.resolve("docs/dev/notes.md"), "# Updated\n");
        String head = commit();
        CiVerificationScope.write(root.resolve("target/ci-change-scope.json"),
                CiVerificationScope.classify(root, "pull_request", false, base, head));
        jar(Map.of("BOOT-INF/classes/docs/dev/notes.md", bytes("# Updated\n"), "BOOT-INF/classes/docs/images/a.svg", bytes("<svg/>")));
    }

    private CiVerificationScope.Decision scope() throws Exception {
        return CiVerificationScope.readAndVerify(root, root.resolve("target/ci-change-scope.json"),
                GitSupport.require(root, "rev-parse", "HEAD").strip());
    }

    private Path artifact() { return root.resolve("target/ui-application"); }

    private void jar(Map<String, byte[]> entries) throws Exception {
        Files.createDirectories(artifact());
        Path jar = artifact().resolve("taxonomy-app-fixture.jar");
        try (var output = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (var entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("schemaVersion", 1);
        manifest.put("sourceCommit", scope().commit());
        manifest.put("sourceTree", scope().sourceTree());
        manifest.put("jarName", jar.getFileName().toString());
        manifest.put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));
        Files.writeString(artifact().resolve("manifest.json"), FlatJson.pretty(manifest));
    }

    private String commit() {
        GitSupport.require(root, "add", "docs");
        GitSupport.require(root, "commit", "--quiet", "-m", "fixture");
        return GitSupport.require(root, "rev-parse", "HEAD").strip();
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
