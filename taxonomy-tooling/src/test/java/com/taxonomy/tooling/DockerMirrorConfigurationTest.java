package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class DockerMirrorConfigurationTest {
    private static final String MIRROR = "https://mirror.gcr.io";

    @Test
    void preservesRunnerSettingsAndExistingMirrors(@TempDir Path directory) throws Exception {
        Fixture fixture = new Fixture(directory);
        Files.writeString(fixture.config, """
                {"storage-driver":"overlay2","features":{"containerd-snapshotter":true},
                 "registry-mirrors":["https://existing.example"]}
                """);

        fixture.run(0);

        var config = FlatJson.parseObject(Files.readString(fixture.config));
        assertThat(config.get("storage-driver")).isEqualTo("overlay2");
        assertThat(config.get("features")).isEqualTo(java.util.Map.of("containerd-snapshotter", true));
        assertThat(config.get("registry-mirrors"))
                .isEqualTo(List.of(MIRROR, "https://existing.example"));
        assertThat(Files.readAllLines(fixture.calls)).containsExactly("validate", "restart", "info");
    }

    @Test
    void initializesMissingConfigurationAndDoesNotRestartAgain(@TempDir Path directory) throws Exception {
        Fixture fixture = new Fixture(directory);
        fixture.run(0);
        fixture.run(0);

        assertThat(FlatJson.parseObject(Files.readString(fixture.config)).get("registry-mirrors"))
                .isEqualTo(List.of(MIRROR));
        assertThat(Files.readAllLines(fixture.calls))
                .containsExactly("validate", "restart", "info", "validate", "info");
    }

    @Test
    void rejectsMalformedConfigurationBeforeChangingTheDaemon(@TempDir Path directory) throws Exception {
        Fixture fixture = new Fixture(directory);
        String original = "{\"registry-mirrors\":\"not-an-array\"}";
        Files.writeString(fixture.config, original);
        fixture.run(1);

        assertThat(Files.readString(fixture.config)).isEqualTo(original);
        assertThat(Files.exists(fixture.calls)).isFalse();
    }

    @Test
    void retainsOriginalConfigurationWhenDaemonValidationFails(@TempDir Path directory) throws Exception {
        Fixture fixture = new Fixture(directory);
        String original = "{\"unknown-dockerd-option\":true}";
        Files.writeString(fixture.config, original);
        Files.writeString(directory.resolve("reject-validation"), "");
        fixture.run(1);

        assertThat(Files.readString(fixture.config)).isEqualTo(original);
        assertThat(Files.readAllLines(fixture.calls)).containsExactly("validate");
    }

    @Test
    void requiresTheRunningDaemonToAdvertiseTheMirror(@TempDir Path directory) throws Exception {
        Fixture fixture = new Fixture(directory);
        Files.writeString(directory.resolve("inactive-mirror"), "");
        fixture.run(1);
        assertThat(Files.readAllLines(fixture.calls)).containsExactly("validate", "restart", "info");
    }

    private static final class Fixture {
        private final Path directory;
        private final Path config;
        private final Path calls;

        Fixture(Path directory) throws Exception {
            this.directory = directory;
            config = directory.resolve("daemon.json");
            calls = directory.resolve("calls");
            Files.createDirectory(directory.resolve("bin"));
            command("sudo", "exec \"$@\"\n");
            command("dockerd", """
                    echo validate >> "$FIXTURE/calls"
                    test ! -f "$FIXTURE/reject-validation" || exit 1
                    test "$1" = --validate
                    test "$2" = --config-file
                    jq -e 'type == "object"' "$3" >/dev/null
                    """);
            command("systemctl", """
                    test "$1" = restart && test "$2" = docker
                    echo restart >> "$FIXTURE/calls"
                    """);
            command("docker", """
                    test "$1" = info
                    echo info >> "$FIXTURE/calls"
                    if test -f "$FIXTURE/inactive-mirror"; then
                      echo '[]'
                    else
                      jq '."registry-mirrors" | map(. + "/")' "$FIXTURE/daemon.json"
                    fi
                    """);
        }

        private void command(String name, String body) throws Exception {
            Path path = directory.resolve("bin").resolve(name);
            Files.writeString(path, "#!/bin/bash\nset -euo pipefail\n" + body);
            assertThat(path.toFile().setExecutable(true)).isTrue();
        }

        void run(int expected) throws Exception {
            Path script = ReleaseOrchestrationChecks.repositoryRoot()
                    .resolve(".github/scripts/configure-docker-mirror.sh");
            ProcessBuilder builder = new ProcessBuilder("bash", script.toString(), config.toString());
            builder.environment().put("PATH", directory.resolve("bin") + ":" + System.getenv("PATH"));
            builder.environment().put("FIXTURE", directory.toString());
            Path output = directory.resolve("output");
            Process process = builder.redirectErrorStream(true).redirectOutput(output.toFile()).start();
            try {
                assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
                int exit = process.exitValue();
                if (expected == 0) assertThat(exit).withFailMessage(Files.readString(output)).isZero();
                else assertThat(exit).withFailMessage(Files.readString(output)).isNotZero();
            } finally {
                process.destroyForcibly();
            }
        }
    }
}
