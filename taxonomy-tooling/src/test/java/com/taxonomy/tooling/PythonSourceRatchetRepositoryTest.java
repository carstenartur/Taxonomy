package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class PythonSourceRatchetRepositoryTest {

    /**
     * Green commit that atomically removed the five release-core adapters and
     * relocated every dependent JUnit fixture into taxonomy-tooling.
     */
    private static final String RELEASE_CORE_REMOVAL_BASELINE =
            "5661bff2ba75288e650b7f4a6cb1cb5364d787c2";

    private static final Set<String> ALLOWED_REMAINING_PYTHON_PATHS = Set.of(
            ".github/scripts/check-codeql-sarif.py",
            ".github/scripts/check-delivery-hardening.py",
            ".github/scripts/check-observability-performance-scope.py",
            ".github/scripts/check-release-delivery-contract.py",
            ".github/scripts/check-release-image-gate.py",
            ".github/scripts/generate-quality-site.py",
            ".github/scripts/generate-vex.py",
            ".github/scripts/test-generate-quality-site.py",
            ".github/scripts/test-verify-deployment.py",
            ".github/scripts/test-verify-quality-publication.py",
            ".github/scripts/update-release-metadata.py",
            ".github/scripts/verify-deployment.py",
            ".github/scripts/verify-quality-publication.py");

    private static final Pattern EXECUTABLE_PYTHON_REFERENCE = Pattern.compile(
            "(^|[\\s\\\"'`=:/])"
                    + "(python(?:3(?:\\.[0-9]+)?)?|pytest|pip)"
                    + "(?=$|[\\s\\\"'`;])"
                    + "|actions/setup-python@");

    @Test
    void remainingPythonInventoryCanOnlyShrink() throws Exception {
        Path root = findRepositoryRoot();
        Set<String> current = trackedPythonPaths(root);

        assertThat(ALLOWED_REMAINING_PYTHON_PATHS)
                .as("#673 remaining Python inventory; additions are prohibited")
                .containsAll(current);
    }

    @Test
    void productiveRuntimeReferencesCanOnlyBeRemoved() throws Exception {
        Path root = findRepositoryRoot();
        GitSupport.Result worktree = GitSupport.run(
                root, "rev-parse", "--is-inside-work-tree");
        if (worktree.exitCode() != 0) {
            return;
        }

        GitSupport.Result ancestry = GitSupport.run(
                root,
                "merge-base",
                "--is-ancestor",
                RELEASE_CORE_REMOVAL_BASELINE,
                "HEAD");
        assertThat(ancestry.exitCode())
                .as("release-core Python-removal baseline %s must remain in history: %s",
                        RELEASE_CORE_REMOVAL_BASELINE,
                        ancestry.stderr().strip())
                .isZero();

        String diff = GitSupport.require(
                root,
                "diff",
                "--unified=0",
                "--no-renames",
                RELEASE_CORE_REMOVAL_BASELINE + "...HEAD",
                "--",
                "*.xml",
                ":(glob)**/*.xml",
                "*.yml",
                ":(glob)**/*.yml",
                "*.yaml",
                ":(glob)**/*.yaml",
                "*.sh",
                ":(glob)**/*.sh",
                ":(glob)**/src/main/**/*.java",
                ":(glob).github/scripts/**/*.js",
                ":(glob).github/scripts/**/*.mjs",
                "*.properties",
                ":(glob)**/*.properties",
                "*.toml",
                ":(glob)**/*.toml",
                "Makefile",
                ":(glob)**/Makefile",
                ".env",
                ".env.example",
                ":(glob)**/.env",
                ":(glob)**/.env.example",
                "Dockerfile",
                ":(glob)**/Dockerfile",
                "package.json",
                ":(glob)**/package.json");

        Set<String> additions = new LinkedHashSet<>();
        String currentFile = "<unknown>";
        for (String line : diff.lines().toList()) {
            if (line.startsWith("+++ b/")) {
                currentFile = line.substring("+++ b/".length());
                continue;
            }
            if (!line.startsWith("+") || line.startsWith("+++")) {
                continue;
            }
            String added = line.substring(1).stripLeading();
            if (added.startsWith("#")
                    || added.startsWith("<!--")
                    || added.startsWith("//")
                    || added.startsWith("*")) {
                continue;
            }
            if (EXECUTABLE_PYTHON_REFERENCE.matcher(added).find()) {
                additions.add(currentFile + ": " + added);
            }
        }

        assertThat(additions)
                .as("new executable Python references after %s",
                        RELEASE_CORE_REMOVAL_BASELINE)
                .isEmpty();
    }

    private static Set<String> trackedPythonPaths(Path root) throws IOException {
        GitSupport.Result tracked = GitSupport.run(
                root,
                "ls-files",
                "-z",
                "--",
                "*.py",
                ":(glob)**/*.py");
        if (tracked.exitCode() == 0) {
            LinkedHashSet<String> result = new LinkedHashSet<>();
            String separator = String.valueOf((char) 0);
            for (String path : tracked.stdout().split(
                    Pattern.quote(separator), -1)) {
                if (!path.isBlank()) {
                    result.add(path.replace('\\', '/'));
                }
            }
            return result;
        }
        return sourceTreePythonPaths(root);
    }

    private static Set<String> sourceTreePythonPaths(Path root)
            throws IOException {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(
                    Path directory,
                    BasicFileAttributes attributes) {
                Path name = directory.getFileName();
                if (!directory.equals(root)
                        && name != null
                        && Set.of(".git", "target", "node_modules")
                                .contains(name.toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(
                    Path file,
                    BasicFileAttributes attributes) {
                if (attributes.isRegularFile()
                        && file.getFileName().toString().endsWith(".py")) {
                    result.add(root.relativize(file).toString()
                            .replace('\\', '/'));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return result;
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isRegularFile(current.resolve(
                            "taxonomy-tooling/pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate Taxonomy repository root");
    }
}
