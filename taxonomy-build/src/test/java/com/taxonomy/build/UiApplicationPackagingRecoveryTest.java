package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the actual workflow command with controlled Maven outcomes, without remote services. */
@EnabledOnOs(OS.LINUX)
class UiApplicationPackagingRecoveryTest {
    @TempDir Path checkout;

    @Test void retriesCachedBadGatewayWithForcedMissingReleaseCheck() throws Exception {
        Result result = run("cached-502");
        assertThat(result.exit()).as(result.output()).isZero();
        assertThat(result.calls()).hasSize(2);
        assertThat(result.calls().get(1)).contains("-U");
        assertThat(result.output()).contains("recovered dependency download");
    }

    @Test void persistentBadGatewayStopsAfterOneRetryAndKeepsFailure() throws Exception {
        Result result = run("persistent-502");
        assertThat(result.exit()).as(result.output()).isEqualTo(7);
        assertThat(result.calls()).hasSize(2);
    }

    @Test void mixedResolutionErrorsMayRetryButKeepThePermanentFailure() throws Exception {
        Result result = run("mixed-502-404");
        assertThat(result.exit()).as(result.output()).isEqualTo(7);
        assertThat(result.calls()).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"success", "cancelled"})
    void logFailureCannotHideMavenFailureOrProduceSuccess(String scenario) throws Exception {
        Result result = run(scenario, true);
        assertThat(result.exit()).as(result.output()).isEqualTo(scenario.equals("cancelled") ? 130 : 23);
        assertThat(result.calls()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"compile-error", "test-error"})
    void verificationFailureIsNotRetriedEvenAfterAnUnrelatedDownloadWarning(String scenario) throws Exception {
        Result result = run(scenario);
        assertThat(result.exit()).as(result.output()).isEqualTo(17);
        assertThat(result.calls()).hasSize(1);
    }

    @Test void cancelledBuildIsNotRetriedAfterADownloadError() throws Exception {
        Result result = run("cancelled");
        assertThat(result.exit()).as(result.output()).isEqualTo(130);
        assertThat(result.calls()).hasSize(1);
    }

    @Test void missingArtifactIsNotTreatedAsTransientBadGateway() throws Exception {
        Result result = run("missing-artifact");
        assertThat(result.exit()).as(result.output()).isEqualTo(7);
        assertThat(result.calls()).hasSize(1);
    }

    @Test void successfulPackagingRunsOnce() throws Exception {
        Result result = run("success");
        assertThat(result.exit()).as(result.output()).isZero();
        assertThat(result.calls()).containsExactly("-B -ntp -DskipTests package");
    }

    private Result run(String scenario) throws Exception {
        return run(scenario, false);
    }

    private Result run(String scenario, boolean failLog) throws Exception {
        Path root = repositoryRoot();
        String workflow = Files.readString(root.resolve(".github/workflows/ci-cd.yml"));
        var command = Pattern.compile("- name: Package commit-bound UI application\\R\\s+run: ([^\\r\\n]+)").matcher(workflow);
        assertThat(command.find()).as("actual application packaging command").isTrue();
        Path helper = Path.of(".github/scripts/package-ui-application.sh");
        if (Files.exists(root.resolve(helper))) {
            Files.createDirectories(checkout.resolve(helper).getParent());
            Files.copy(root.resolve(helper), checkout.resolve(helper));
        }
        Path maven = checkout.resolve("mvnw");
        Files.writeString(maven, """
                #!/usr/bin/env bash
                set -euo pipefail
                printf '%s\\n' "$*" >> calls.txt
                case "$PACKAGING_SCENARIO" in
                  success) exit 0 ;;
                  cached-502)
                    if [[ " $* " == *" -U "* ]]; then
                      echo 'recovered dependency download'
                      exit 0
                    fi ;;
                  compile-error)
                    echo '[WARNING] Previous optional metadata request: status code: 502'
                    echo '[ERROR] COMPILATION ERROR: cannot find symbol'
                    exit 17 ;;
                  test-error)
                    echo '[WARNING] Previous optional metadata request: status code: 502'
                    echo '[ERROR] Failed to execute goal maven-surefire-plugin:test: There are test failures.'
                    exit 17 ;;
                esac
                echo '[ERROR] Failed to execute goal on project taxonomy-app: Could not resolve dependencies for project com.taxonomy:taxonomy-app'
                if [[ "$PACKAGING_SCENARIO" == missing-artifact ]]; then
                  echo '[ERROR] Could not transfer artifact: status code: 404'
                else
                  echo '[ERROR] org.webjars.npm:d3:jar:7.9.0 was not found in https://raw.githubusercontent.com/carstenartur/jgit-storage-hibernate/maven-repository/ during a previous attempt.'
                  echo '[ERROR] Failure was cached in the local repository. Original error: Could not transfer artifact org.webjars.npm:d3:jar:7.9.0 from/to central: status code: 502, reason phrase: Bad Gateway (502)'
                fi
                if [[ "$PACKAGING_SCENARIO" == mixed-502-404 ]]; then
                  echo '[ERROR] Could not transfer artifact example:missing:jar:1.0 from/to central: status code: 404'
                fi
                if [[ "$PACKAGING_SCENARIO" == cancelled ]]; then exit 130; fi
                exit 7
                """);
        assertThat(maven.toFile().setExecutable(true)).isTrue();
        Path output = checkout.resolve("output.txt");
        ProcessBuilder builder = new ProcessBuilder("bash", "-c", command.group(1))
                .directory(checkout.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().put("PACKAGING_SCENARIO", scenario);
        if (failLog) {
            Path bin = Files.createDirectories(checkout.resolve("bin"));
            Path tee = bin.resolve("tee");
            Files.writeString(tee, "#!/usr/bin/env bash\ncat > /dev/null\nexit 23\n");
            assertThat(tee.toFile().setExecutable(true)).isTrue();
            builder.environment().put("PATH", bin + ":" + builder.environment().get("PATH"));
        }
        Process process = builder.start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("bounded packaging retry").isTrue();
            return new Result(process.exitValue(), Files.readString(output), Files.readAllLines(checkout.resolve("calls.txt")));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private Path repositoryRoot() {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve(".github/workflows/ci-cd.yml"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Taxonomy checkout not found");
        return root;
    }

    private record Result(int exit, String output, List<String> calls) { }
}
