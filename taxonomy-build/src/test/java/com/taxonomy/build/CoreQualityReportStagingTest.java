package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Executes the real staging helper; preserving diagnostics must not publish a failed build. */
class CoreQualityReportStagingTest {
    private static final String COVERAGE = "taxonomy-coverage/target/site/jacoco-aggregate/jacoco.xml";
    private static final String SOURCE = "taxonomy-app/target/portfolio-context-evidence/";
    private static final String STAGED = "target/quality-reports/";
    private static final String DIAGNOSTICS = STAGED + "evidence/portfolio-context/";

    @TempDir Path checkout;

    @Test
    void failedCoverageStillPreservesTheActualOwnerDiagnostics() throws Exception {
        byte[] log = ("[ERROR] Portfolio context assertion failed — retained diagnostic\n".repeat(8_192))
                .getBytes(StandardCharsets.UTF_8);
        byte[] rootImage = png(0xff0088cc);
        byte[] contextImage = png(0xffcc4400);
        String report = "taxonomy-app/target/failsafe-reports/TEST-context.xml";
        put(report, "<testsuite tests=\"2\" failures=\"2\" errors=\"0\"/>".getBytes(StandardCharsets.UTF_8));
        put("target/maven-verification.log", log);
        put(SOURCE + "root/failure.png", rootImage);
        put(SOURCE + "taxonomy/failure.png", contextImage);
        put(SOURCE + "downloads/session-private/report.docx", new byte[]{1, 2, 3});

        Result result = stage("unresolved-without-coverage");

        assertThat(result.exit()).as(result.output()).isEqualTo(1);
        assertThat(result.output()).contains("Aggregate JaCoCo XML is missing; refusing stale coverage evidence.");
        assertBytes(STAGED + "tests/" + report, Files.readAllBytes(checkout.resolve(report)));
        assertThat(checkout.resolve(STAGED + "README.txt")).doesNotExist();
        assertThat(checkout.resolve(STAGED + "coverage/jacoco.xml")).doesNotExist();
        assertBytes(STAGED + "evidence/maven-verification.log", log);
        assertBytes(DIAGNOSTICS + "root/failure.png", rootImage);
        assertBytes(DIAGNOSTICS + "taxonomy/failure.png", contextImage);
        assertThat(checkout.resolve(DIAGNOSTICS + "downloads")).doesNotExist();
    }

    @Test
    void successfulRepeatedStagingKeepsProvenanceAndReplacesOldDiagnostics() throws Exception {
        String commit = initializeGit();
        String tree = git("rev-parse", commit + "^{tree}").output().strip();
        byte[] coverage = "<report name=\"current coverage\"/>".getBytes(StandardCharsets.UTF_8);
        byte[] image = png(0xff55aa22);
        put(COVERAGE, coverage);
        put("target/maven-verification.log", "first build\n".getBytes(StandardCharsets.UTF_8));
        put(SOURCE + "root/failure.png", image);
        put(SOURCE + "taxonomy/failure.png", image);

        Result first = stage(commit);
        assertThat(first.exit()).as(first.output()).isZero();
        assertBytes(DIAGNOSTICS + "root/failure.png", image);
        assertBytes(DIAGNOSTICS + "taxonomy/failure.png", image);

        byte[] currentLog = "second build\n".getBytes(StandardCharsets.UTF_8);
        byte[] currentImage = png(0xff663399);
        put("target/maven-verification.log", currentLog);
        put(SOURCE + "root/tools-1366.png", currentImage);
        Files.delete(checkout.resolve(SOURCE + "root/failure.png"));
        Files.delete(checkout.resolve(SOURCE + "taxonomy/failure.png"));
        Result second = stage(commit);

        assertThat(second.exit()).as(second.output()).isZero();
        assertBytes(STAGED + "evidence/maven-verification.log", currentLog);
        assertBytes(DIAGNOSTICS + "root/tools-1366.png", currentImage);
        assertBytes(STAGED + "coverage/jacoco.xml", coverage);
        assertThat(checkout.resolve(DIAGNOSTICS + "root/failure.png")).doesNotExist();
        assertThat(checkout.resolve(DIAGNOSTICS + "taxonomy/failure.png")).doesNotExist();
        assertThat(Files.readString(checkout.resolve(STAGED + "README.txt"))).isEqualTo(
                "Staged core evidence\nCommit: " + commit + "\nSource tree: " + tree
                        + "\nCore build ID: 123.2.core\n");
    }

    @Test
    void absentOptionalDiagnosticsDoNotChangeEitherCoverageOutcome() throws Exception {
        String commit = initializeGit();
        put(COVERAGE, "<report/>".getBytes(StandardCharsets.UTF_8));

        Result complete = stage(commit);
        assertThat(complete.exit()).as(complete.output()).isZero();
        assertThat(checkout.resolve(STAGED + "README.txt")).isRegularFile();
        assertThat(checkout.resolve(STAGED + "evidence/maven-verification.log")).doesNotExist();
        assertThat(checkout.resolve(DIAGNOSTICS)).doesNotExist();

        Files.delete(checkout.resolve(COVERAGE));
        Result incomplete = stage(commit);
        assertThat(incomplete.exit()).as(incomplete.output()).isEqualTo(1);
        assertThat(incomplete.output()).contains("Aggregate JaCoCo XML is missing");
        assertThat(checkout.resolve(STAGED + "README.txt")).doesNotExist();
    }

    private Result stage(String commit) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("bash", repositoryRoot().resolve(
                ".github/scripts/stage-core-quality-reports.sh").toString());
        builder.environment().put("GITHUB_SHA", commit);
        builder.environment().put("GITHUB_RUN_ID", "123");
        builder.environment().put("GITHUB_RUN_ATTEMPT", "2");
        return run(builder);
    }

    private String initializeGit() throws Exception {
        git("init", "-q");
        git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                "-c", "commit.gpgsign=false", "commit", "-q", "--allow-empty", "-m", "fixture");
        return git("rev-parse", "HEAD").output().strip();
    }

    private Result git(String... arguments) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add("git");
        command.addAll(java.util.List.of(arguments));
        Result result = run(new ProcessBuilder(command));
        assertThat(result.exit()).as(result.output()).isZero();
        return result;
    }

    private Result run(ProcessBuilder builder) throws Exception {
        Path output = checkout.resolve("process-output.txt");
        Process process = builder.directory(checkout.toFile()).redirectErrorStream(true)
                .redirectOutput(output.toFile()).start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("bounded staging fixture process").isTrue();
            return new Result(process.exitValue(), Files.readString(output));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private void put(String relative, byte[] bytes) throws Exception {
        Path target = checkout.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    private void assertBytes(String relative, byte[] expected) throws Exception {
        Path actual = checkout.resolve(relative);
        assertThat(actual).isRegularFile();
        assertThat(Files.readAllBytes(actual)).containsExactly(expected);
    }

    private static byte[] png(int argb) throws Exception {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, argb);
        try (var output = new ByteArrayOutputStream()) {
            assertThat(ImageIO.write(image, "png", output)).isTrue();
            return output.toByteArray();
        }
    }

    private static Path repositoryRoot() {
        Path root = Path.of("").toAbsolutePath().normalize();
        while (root != null && !Files.isRegularFile(root.resolve(".github/scripts/stage-core-quality-reports.sh"))) {
            root = root.getParent();
        }
        if (root == null) throw new IllegalStateException("Taxonomy checkout not found");
        return root;
    }

    private record Result(int exit, String output) { }
}
