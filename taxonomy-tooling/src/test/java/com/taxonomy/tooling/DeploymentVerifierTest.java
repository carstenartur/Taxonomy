package com.taxonomy.tooling;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentVerifierTest {
  static final String COMMIT = "0123456789abcdef";
  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-10T12:00:00Z"), ZoneOffset.UTC);
  @TempDir Path directory;

  @Test
  void acceptsNestedAndAbbreviatedCommitsWithoutAcceptingShortOrStaleValues() {
    assertThat(
            DeploymentVerifier.containsCommit(
                Map.of("git", Map.of("commit", Map.of("id", COMMIT))), COMMIT))
        .isTrue();
    assertThat(DeploymentVerifier.containsCommit(List.of(Map.of("id", "0123456")), COMMIT))
        .isTrue();
    assertThat(DeploymentVerifier.containsCommit(Map.of("id", COMMIT), "0123456")).isTrue();
    assertThat(DeploymentVerifier.containsCommit(Map.of("id", "012345"), COMMIT)).isFalse();
    assertThat(DeploymentVerifier.containsCommit(Map.of("id", "oldcommit"), COMMIT)).isFalse();
    assertThat(DeploymentVerifier.containsCommit(Map.of("id", " ABCDEF1 "), "abcdef1234567890"))
        .isTrue();
  }

  @Test
  void extractsRenderDeployIdAndNestedStatus() {
    var payload = Map.of("deploy", Map.of("id", "dep-xyz789", "status", " BUILD_IN_PROGRESS "));
    assertThat(DeploymentVerifier.deployId(payload)).isEqualTo("dep-xyz789");
    assertThat(DeploymentVerifier.deployStatus(payload)).isEqualTo("build_in_progress");
    assertThat(DeploymentVerifier.deployId(List.of(Map.of("id", "srv-ignored"), payload)))
        .isEqualTo("dep-xyz789");
    assertThat(DeploymentVerifier.deployStatus(Map.of("data", Map.of("status", "live"))))
        .isEqualTo("live");
  }

  @Test
  void renderMustBeLiveBeforeApplicationVerification() {
    assertThat(DeploymentVerifier.renderStatusDecision("dep-xyz789", "build_in_progress").state())
        .isEqualTo("pending");
    assertThat(DeploymentVerifier.renderStatusDecision("dep-xyz789", null).detail())
        .contains("unknown", "waiting for live");
    assertThat(DeploymentVerifier.renderStatusDecision("dep-xyz789", "live").state())
        .isEqualTo("live");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "build_failed",
        "update_failed",
        "pre_deploy_failed",
        "canceled",
        "cancelled",
        "deactivated",
        "failed"
      })
  void terminalRenderStatesFail(String state) {
    assertThat(DeploymentVerifier.renderStatusDecision("dep-xyz789", state).state())
        .isEqualTo("failure");
    assertThat(DeploymentVerifier.renderStatusDecision("dep-xyz789", state).detail())
        .contains("ended with " + state);
  }

  @Test
  void verificationRequiresReadinessCommitAndSuccessfulRootSmoke() throws Exception {
    var transport = new FixtureTransport();
    assertThat(DeploymentVerifier.verifyOnce("https://example.invalid/", COMMIT, transport).ok())
        .isTrue();
    assertThat(transport.applicationRequests)
        .containsExactly("/actuator/health/readiness", "/actuator/info", "/");
    transport.readiness = "DOWN";
    assertThat(DeploymentVerifier.verifyOnce("https://example.invalid", COMMIT, transport).detail())
        .contains("readiness is not UP");
    transport.readiness = "UP";
    transport.commit = "oldcommit";
    assertThat(DeploymentVerifier.verifyOnce("https://example.invalid", COMMIT, transport).detail())
        .contains("expected commit");
  }

  @ParameterizedTest
  @ValueSource(ints = {401, 404, 503})
  void rejectsHttpErrorSmokeResponses(int status) throws Exception {
    var transport = new FixtureTransport();
    transport.smokeStatus = status;
    assertThat(DeploymentVerifier.verifyOnce("https://example.invalid", COMMIT, transport).detail())
        .contains("HTTP " + status);
  }

  @Test
  void toleratesQueuedMissingAndMalformedHookResponses() throws Exception {
    Path response = directory.resolve("hook.json");
    assertThat(DeploymentVerifier.loadDeployId(response)).isNull();
    for (String content :
        List.of("{\"message\":\"queued\"}", "not json", "{\"id\":\"dep-xyz789\"},\"extra\":null")) {
      Files.writeString(response, content);
      assertThat(DeploymentVerifier.loadDeployId(response)).isNull();
    }
    Files.writeString(response, "{\"deploy\":{\"id\":\"dep-xyz789\"}}");
    assertThat(DeploymentVerifier.loadDeployId(response)).isEqualTo("dep-xyz789");
  }

  @Test
  void writesMachineReadableEvidenceAndRetainsAllDeliveryStates() throws Exception {
    Path evidence = directory.resolve("nested/evidence.json");
    DeploymentVerifier.writeEvidence(evidence, Map.of("result", "success", "attempts", 2));
    assertThat(QualitySite.read(evidence))
        .containsEntry("result", "success")
        .containsEntry("attempts", 2L);
    Map<String, String> env =
        Map.of("BASE_URL", "https://example.invalid///", "EXPECTED_SHA", COMMIT);
    for (var item :
        Map.of(
                "disabled",
                "disabled",
                "triggered",
                "triggered",
                "configuration-failure",
                "failed",
                "hook-failure",
                "failed")
            .entrySet()) {
      assertThat(
              DeploymentVerifier.renderCommand(
                  "render-delivery-state",
                  new String[] {"--state", item.getKey()},
                  env,
                  directory,
                  new PrintStream(new ByteArrayOutputStream()),
                  new PrintStream(new ByteArrayOutputStream())))
          .isZero();
      var payload =
          QualitySite.read(
              directory.resolve("target/delivery-evidence/render-" + item.getKey() + ".json"));
      assertThat(payload)
          .containsEntry("deploymentState", item.getValue())
          .containsEntry("expectedCommit", COMMIT)
          .containsEntry("targetUrl", "https://example.invalid");
    }
  }

  @Test
  void pinsHookCommitWithoutDroppingQueryParametersOrFragment() {
    assertThat(
            DeploymentVerifier.pinnedHookUrl(
                "https://api.render.com/deploy/srv-abc?key=secret&ref=old&blank=#fragment", COMMIT))
        .isEqualTo(
            "https://api.render.com/deploy/srv-abc?key=secret&ref=" + COMMIT + "&blank=#fragment");
    assertThat(DeploymentVerifier.serviceId("https://api.render.com/deploy/srv-abc123?key=secret"))
        .isEqualTo("srv-abc123");
    assertThat(DeploymentVerifier.serviceId("https://api.render.com/deploy/no-service?key=secret"))
        .isEmpty();
    assertThat(
            DeploymentVerifier.pinnedHookUrl(
                "https://api.render.com/deploy/srv-abc?key=a%2Ab~c&&ref=old&ref=older", COMMIT))
        .isEqualTo("https://api.render.com/deploy/srv-abc?key=a%2Ab~c&ref=" + COMMIT);
  }

  @Test
  void waitsForRenderLiveAndThenWritesSucceededEvidence() throws Exception {
    var transport = new FixtureTransport();
    transport.renderStates.addAll(List.of("build_in_progress", "live"));
    Files.writeString(directory.resolve("hook.json"), "{\"id\":\"dep-xyz789\"}");
    var sleeps = new ArrayList<Long>();
    int result =
        run(
            transport,
            sleeps,
            "--deploy-response-file",
            "hook.json",
            "--render-service-id",
            "srv-abc",
            "--render-api-key",
            "secret",
            "--attempts",
            "3");
    assertThat(result).isZero();
    assertThat(sleeps).containsExactly(10000L);
    assertThat(transport.applicationRequests).hasSize(3);
    assertThat(transport.applicationRequestsAtFirstRender).isZero();
    assertThat(QualitySite.read(directory.resolve("result.json")))
        .containsEntry("deploymentState", "succeeded")
        .containsEntry("result", "success")
        .containsEntry("attempts", 2L)
        .containsEntry("renderDeployStatus", "live")
        .containsEntry("renderApiStatusVerification", true);
  }

  @Test
  void terminalRenderFailureNeverVerifiesTheApplicationOrSleeps() throws Exception {
    var transport = new FixtureTransport();
    transport.renderStates.add("update_failed");
    Files.writeString(directory.resolve("hook.json"), "{\"id\":\"dep-xyz789\"}");
    var sleeps = new ArrayList<Long>();
    assertThat(
            run(
                transport,
                sleeps,
                "--deploy-response-file",
                "hook.json",
                "--render-service-id",
                "srv-abc",
                "--render-api-key",
                "secret"))
        .isEqualTo(1);
    assertThat(sleeps).isEmpty();
    assertThat(transport.applicationRequests).isEmpty();
    assertThat(QualitySite.read(directory.resolve("result.json")))
        .containsEntry("deploymentState", "failed")
        .containsEntry("attempts", 1L)
        .containsEntry("renderDeployStatus", "update_failed");
  }

  @Test
  void pendingRenderCannotAcceptAnOlderHealthyApplication() throws Exception {
    var transport = new FixtureTransport();
    transport.renderStates.add("build_in_progress");
    Files.writeString(directory.resolve("hook.json"), "{\"id\":\"dep-xyz789\"}");
    var sleeps = new ArrayList<Long>();
    assertThat(
            run(
                transport,
                sleeps,
                "--deploy-response-file",
                "hook.json",
                "--render-service-id",
                "srv-abc",
                "--render-api-key",
                "secret",
                "--attempts",
                "2",
                "--interval-seconds",
                "0"))
        .isEqualTo(1);
    assertThat(transport.applicationRequests).isEmpty();
    assertThat(sleeps).containsExactly(0L);
    assertThat(QualitySite.read(directory.resolve("result.json")))
        .containsEntry("deploymentState", "failed")
        .containsEntry("attempts", 2L);
  }

  @Test
  void loopbackHttpPreservesErrorsAndRetriesMalformedJsonBeforeSuccess() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var readinessCalls = new AtomicInteger();
    server.createContext(
        "/actuator/health/readiness",
        exchange -> {
          assertThat(exchange.getRequestHeaders().getFirst("Cache-Control")).isEqualTo("no-cache");
          byte[] bytes =
              (readinessCalls.incrementAndGet() == 1 ? "malformed" : "{\"status\":\"UP\"}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.createContext(
        "/actuator/info",
        exchange -> {
          byte[] bytes =
              ("{\"git\":{\"commit\":{\"id\":\"" + COMMIT + "\"}}}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.createContext(
        "/missing",
        exchange -> {
          exchange.sendResponseHeaders(404, -1);
          exchange.close();
        });
    server.createContext(
        "/",
        exchange -> {
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();
    try {
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      assertThat(DeploymentVerifier.httpTransport().status(URI.create(base + "/missing")))
          .isEqualTo(404);
      var logs = new ByteArrayOutputStream();
      assertThat(
              DeploymentVerifier.run(
                  new String[] {
                    "--base-url",
                    base,
                    "--expected-commit",
                    COMMIT,
                    "--attempts",
                    "2",
                    "--interval-seconds",
                    "0",
                    "--evidence-file",
                    "loopback.json"
                  },
                  Map.of(),
                  directory,
                  new PrintStream(logs),
                  new PrintStream(logs)))
          .isZero();
      assertThat(readinessCalls).hasValue(2);
      assertThat(QualitySite.read(directory.resolve("loopback.json")))
          .containsEntry("deploymentState", "succeeded")
          .containsEntry("attempts", 2L);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void transportFailureEvidenceNeverIncludesApiKeyOrSecretPayload() throws Exception {
    var transport = new FixtureTransport();
    transport.failure = new IOException("network error secret API_KEY=secret private-payload");
    var logs = new ByteArrayOutputStream();
    int result =
        DeploymentVerifier.run(
            new String[] {
              "--base-url",
              "https://example.invalid",
              "--expected-commit",
              COMMIT,
              "--attempts",
              "1",
              "--render-api-key",
              "secret",
              "--evidence-file",
              "result.json"
            },
            Map.of(),
            directory,
            new PrintStream(logs),
            new PrintStream(logs),
            new DeploymentVerifier.Runtime(transport, CLOCK, millis -> {}));
    assertThat(result).isEqualTo(1);
    assertThat(logs.toString(StandardCharsets.UTF_8)).doesNotContain("secret", "private-payload");
    assertThat(Files.readString(directory.resolve("result.json")))
        .doesNotContain("secret", "private-payload");
  }

  int run(FixtureTransport transport, List<Long> sleeps, String... additional) {
    var args =
        new ArrayList<>(
            List.of(
                "--base-url",
                "https://example.invalid",
                "--expected-commit",
                COMMIT,
                "--evidence-file",
                "result.json"));
    args.addAll(List.of(additional));
    return DeploymentVerifier.run(
        args.toArray(String[]::new),
        Map.of(),
        directory,
        new PrintStream(new ByteArrayOutputStream()),
        new PrintStream(new ByteArrayOutputStream()),
        new DeploymentVerifier.Runtime(transport, CLOCK, sleeps::add));
  }

  static class FixtureTransport implements DeploymentVerifier.Transport {
    String readiness = "UP", commit = COMMIT;
    int smokeStatus = 200, renderCalls, applicationRequestsAtFirstRender = -1;
    IOException failure;
    final List<String> applicationRequests = new ArrayList<>(), renderStates = new ArrayList<>();

    public Object json(URI uri, Map<String, String> headers) throws IOException {
      if (failure != null) throw failure;
      if (uri.getHost().equals("api.render.com")) {
        if (applicationRequestsAtFirstRender < 0)
          applicationRequestsAtFirstRender = applicationRequests.size();
        assertThat(headers.get("Authorization")).isEqualTo("Bearer secret");
        return Map.of("status", renderStates.get(Math.min(renderCalls++, renderStates.size() - 1)));
      }
      applicationRequests.add(uri.getPath());
      return uri.getPath().endsWith("readiness")
          ? Map.of("status", readiness)
          : Map.of("git", Map.of("commit", Map.of("id", commit)));
    }

    public int status(URI uri) {
      applicationRequests.add(uri.getPath());
      return smokeStatus;
    }
  }
}
