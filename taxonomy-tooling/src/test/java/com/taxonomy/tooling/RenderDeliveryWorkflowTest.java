package com.taxonomy.tooling;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the checked-in delivery shell with actual Java tooling and a local hook/application
 * boundary.
 */
class RenderDeliveryWorkflowTest {
  @TempDir Path directory;

  @Test
  void disabledDeliveryWritesOnlyDisabledEvidence() throws Exception {
    var result = shell("Record explicitly disabled Render delivery", Map.of("HOOK_URL", ""));
    assertThat(result.exit()).isZero();
    assertThat(QualitySite.read(evidence("disabled")))
        .containsEntry("deploymentState", "disabled")
        .containsEntry("result", "disabled");
    assertThat(Files.exists(evidence("triggered"))).isFalse();
    assertThat(Files.exists(evidence("verification"))).isFalse();
  }

  @Test
  void missingHookAndRejectedHookBothRetainFailureEvidence() throws Exception {
    assertThat(shell("Trigger and verify Render deployment", Map.of("HOOK_URL", "")).exit())
        .isEqualTo(1);
    assertThat(QualitySite.read(evidence("configuration-failure")))
        .containsEntry("deploymentState", "failed")
        .containsEntry("result", "failure");
    assertThat(
            shell("Trigger and verify Render deployment", Map.of("FAKE_HOOK_FAIL", "true")).exit())
        .isEqualTo(1);
    assertThat(QualitySite.read(evidence("hook-failure")))
        .containsEntry("deploymentState", "failed")
        .containsEntry("result", "failure");
    assertThat(Files.exists(evidence("triggered"))).isFalse();
  }

  @Test
  void acceptedHookPinsCommitAndVerifiesTheLiveApplication() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String path = exchange.getRequestURI().getPath();
          String body =
              path.endsWith("readiness")
                  ? "{\"status\":\"UP\"}"
                  : path.endsWith("info")
                      ? "{\"git\":{\"commit\":{\"id\":\"0123456789abcdef\"}}}"
                      : "application";
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try {
      var result =
          shell(
              "Trigger and verify Render deployment",
              Map.of("BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort()));
      assertThat(result.exit()).as(result.log()).isZero();
      assertThat(Files.readString(directory.resolve("hook-target.txt")))
          .isEqualTo(
              "https://api.render.com/deploy/srv-fixture?key=fixture-secret&ref=0123456789abcdef");
      assertThat(QualitySite.read(evidence("triggered")))
          .containsEntry("deploymentState", "triggered")
          .containsEntry("result", "in_progress");
      assertThat(QualitySite.read(evidence("verification")))
          .containsEntry("deploymentState", "succeeded")
          .containsEntry("result", "success")
          .containsEntry("expectedCommit", "0123456789abcdef");
      assertThat(result.log()).doesNotContain("fixture-secret");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void javaToolingJobsUsePinnedJavaAndVerifiedSourceWithoutPythonBlocks() throws Exception {
    String workflow = Files.readString(root().resolve(".github/workflows/delivery.yml"));
    assertThat(workflow)
        .doesNotContain("python3", "verify-deployment.py", "verify-quality-publication.py")
        .contains(
            "bash .github/scripts/taxonomy-tooling.sh verify-deployment",
            "render-delivery-state --state disabled");
    for (String job : List.of("publish-reports", "render-deployment-disabled", "deploy-render")) {
      int start = workflow.indexOf("  " + job + ":");
      int end = workflow.indexOf("\n  ", start + 3);
      // Find the next job, rather than a nested step with additional indentation.
      var next = java.util.regex.Pattern.compile("(?m)^  [a-z][a-z-]+:").matcher(workflow);
      next.region(start + 3, workflow.length());
      end = next.find() ? next.start() : workflow.length();
      assertThat(workflow.substring(start, end))
          .contains(
              "actions/setup-java@77dd5b2ce225ea11469928ac317850245a74ae78",
              "java-version: '25'",
              "ref: ${{ github.event.workflow_run.head_sha }}");
    }
  }

  private ShellResult shell(String step, Map<String, String> overrides) throws Exception {
    Path root = root();
    Files.createDirectories(directory.resolve(".github/scripts"));
    Files.copy(
        root.resolve(".github/scripts/taxonomy-tooling.sh"),
        directory.resolve(".github/scripts/taxonomy-tooling.sh"),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    Path bin = Files.createDirectories(directory.resolve("bin"));
    Path curl = bin.resolve("curl");
    Files.writeString(
        curl,
        """
        #!/usr/bin/env bash
        set -euo pipefail
        if [[ "${FAKE_HOOK_FAIL:-}" == true ]]; then exit 22; fi
        destination=''
        previous=''
        for value in "$@"; do
          if [[ "$previous" == --output ]]; then destination="$value"; fi
          previous="$value"
        done
        mkdir -p "$(dirname "$destination")"
        printf '%s' '{"message":"queued"}' > "$destination"
        printf '%s' "${@: -1}" > "$HOOK_TARGET_FILE"
        """);
    assertThat(curl.toFile().setExecutable(true)).isTrue();
    Path tooling = packageTooling();
    Path log = directory.resolve("workflow-" + System.nanoTime() + ".log");
    var builder =
        new ProcessBuilder(
                "bash",
                "-c",
                block(Files.readString(root.resolve(".github/workflows/delivery.yml")), step))
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile());
    builder
        .environment()
        .putAll(
            Map.of(
                "BASE_URL",
                "https://example.invalid/",
                "EXPECTED_SHA",
                "0123456789abcdef",
                "HOOK_URL",
                "https://api.render.com/deploy/srv-fixture?key=fixture-secret&ref=stale",
                "CONFIGURED_RENDER_SERVICE_ID",
                "",
                "RENDER_API_KEY",
                "",
                "TOOLING_JAR",
                tooling.toString(),
                "HOOK_TARGET_FILE",
                directory.resolve("hook-target.txt").toString(),
                "PATH",
                bin
                    + ":"
                    + Path.of(System.getProperty("java.home"), "bin")
                    + ":"
                    + System.getenv("PATH")));
    builder.environment().putAll(overrides);
    var process = builder.start();
    boolean ended = process.waitFor(45, TimeUnit.SECONDS);
    if (!ended) process.destroyForcibly();
    assertThat(ended).as("bounded delivery fixture process; log %s", log).isTrue();
    return new ShellResult(process.exitValue(), Files.readString(log));
  }

  private Path packageTooling() throws Exception {
    Path jar = directory.resolve("tooling.jar");
    var manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, TaxonomyTooling.class.getName());
    Path classes =
        Path.of(TaxonomyTooling.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    try (var output = new JarOutputStream(Files.newOutputStream(jar), manifest);
        var files = Files.walk(classes)) {
      for (Path file :
          files
              .filter(Files::isRegularFile)
              .filter(p -> p.toString().endsWith(".class"))
              .toList()) {
        output.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
        Files.copy(file, output);
        output.closeEntry();
      }
    }
    return jar;
  }

  private Path evidence(String state) {
    return directory.resolve("target/delivery-evidence/render-" + state + ".json");
  }

  private static String block(String workflow, String step) {
    int named = workflow.indexOf("      - name: " + step + "\n");
    assertThat(named).isNotNegative();
    int run = workflow.indexOf("        run: |\n", named) + "        run: |\n".length();
    var result = new StringBuilder();
    for (String line : workflow.substring(run).split("\n", -1)) {
      if (line.isBlank()) result.append('\n');
      else if (line.startsWith("          ")) result.append(line.substring(10)).append('\n');
      else break;
    }
    return result.toString();
  }

  private static Path root() {
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !Files.isRegularFile(root.resolve("taxonomy-tooling/pom.xml")))
      root = root.getParent();
    if (root == null) throw new IllegalStateException("Repository root not found");
    return root;
  }

  record ShellResult(int exit, String log) {}
}
