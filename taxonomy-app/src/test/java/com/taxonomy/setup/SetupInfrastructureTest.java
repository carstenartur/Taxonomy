package com.taxonomy.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Keep setup/package verification under Maven's existing verification authority. */
class SetupInfrastructureTest {
    @TempDir Path directory;

    @Test void reviewConfigurationContracts() throws Exception {
        SetupReviewRegressionCases.main(new String[0]);
    }

    @Test void nativePackageCommandContracts() throws Exception {
        Path source = repository().resolve("deploy/native");
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "A JDK is required to verify native packaging commands");
        assertEquals(0, compiler.run(null, null, null, "-d", directory.toString(),
                source.resolve("PackageTaxonomy.java").toString(), source.resolve("PackageTaxonomyCases.java").toString()));
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        run(List.of(Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString(),
                "-cp", directory.toString(), "PackageTaxonomyCases"), directory, 60);
    }

    @Test void guidedHelmContracts() throws Exception {
        boolean available;
        try {
            Process helm = new ProcessBuilder("helm", "version", "--short").redirectErrorStream(true)
                    .redirectOutput(directory.resolve("helm-version.log").toFile()).start();
            available = helm.waitFor(10, TimeUnit.SECONDS) && helm.exitValue() == 0;
            if (helm.isAlive()) { helm.destroyForcibly(); }
        } catch (java.io.IOException missing) { available = false; }
        if ("true".equalsIgnoreCase(System.getenv("CI"))) {
            assertTrue(available, "Canonical CI must install Helm before Maven setup verification");
        }
        assumeTrue(available, "Helm not installed locally; canonical CI requires it");
        Path root = repository();
        run(List.of("bash", root.resolve("deploy/helm/taxonomy/verify-setup.sh").toString()), root, 120);
        run(List.of("bash", root.resolve("deploy/helm/taxonomy/verify-setup-security.sh").toString()), root, 120);
    }

    private void run(List<String> command, Path workingDirectory, int seconds) throws Exception {
        Path log = Files.createTempFile(directory, "verification-", ".log");
        Process process = new ProcessBuilder(command).directory(workingDirectory.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(seconds, TimeUnit.SECONDS), "Setup verification timed out");
            assertEquals(0, process.exitValue(), () -> {
                try { return Files.readString(log); } catch (java.io.IOException failure) { return "Cannot read verification log"; }
            });
        } finally { if (process.isAlive()) { process.destroyForcibly(); } }
    }

    private static Path repository() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve(".mvn/verification-suites.json"))) { current = current.getParent(); }
        if (current == null) { throw new IllegalStateException("Repository verification catalogue not found"); }
        return current;
    }
}
