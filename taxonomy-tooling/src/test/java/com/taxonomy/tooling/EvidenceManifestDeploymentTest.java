package com.taxonomy.tooling;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EvidenceManifestDeploymentTest {
  @TempDir Path root;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void acceptsTheExistingExplicitlyDisabledReceiptWithoutClaimingDeployment(boolean publication)
      throws Exception {
    fixture(publication);
    var result = check();
    assertThat(result.code()).as(result.output()).isZero();
    assertThat(QualitySite.read(root.resolve("deployment.json")))
        .containsEntry("deploymentState", "disabled")
        .containsEntry("result", "disabled")
        .doesNotContainKey("attempts");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void joinsExistingSuccessfulReceiptsWithAndWithoutRenderStatusVerification(boolean renderApi)
      throws Exception {
    fixture(true);
    successfulReceipt(renderApi);
    var result = check();
    assertThat(result.code()).as(result.output()).isZero();
    assertThat(QualitySite.read(root.resolve("deployment.json")))
        .containsEntry("expectedCommit", EvidenceManifestTest.SHA)
        .containsEntry("deploymentState", "succeeded")
        .containsEntry("result", "success");
  }

  @Test
  void rejectsAReleaseArchiveWithoutItsDeploymentReceipt() throws Exception {
    fixture(false);
    var manifest = QualitySite.read(root.resolve("manifest.json"));
    QualitySite.list(manifest.get("artifacts"))
        .removeIf(value -> "deployment".equals(QualitySite.object(value).get("kind")));
    QualitySite.write(root.resolve("manifest.json"), manifest);
    var result = check();
    assertThat(result.code()).as(result.output()).isEqualTo(1);
    assertThat(result.output()).contains("Missing required artifact kinds", "deployment");
  }

  @Test
  void rejectsLiveDeploymentClaimsFromANonPublishingFixture() throws Exception {
    fixture(false);
    successfulReceipt(false);
    var result = check();
    assertThat(result.code()).as(result.output()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "schema",
        "schema-type",
        "source",
        "missing-target",
        "relative-target",
        "unsupported-target",
        "missing-start",
        "missing-end",
        "invalid-time",
        "reversed-time",
        "missing-detail",
        "failed",
        "triggered",
        "verifying",
        "cancelled",
        "unknown",
        "wrong-result",
        "disabled-result",
        "missing-attempts",
        "zero-attempts",
        "fractional-attempts",
        "attempts-type",
        "missing-api-mode",
        "api-mode-type",
        "missing-deploy-id",
        "not-live",
        "malformed-json"
      })
  void rejectsIncompleteOrInconsistentReceiptsEvenWithRecomputedDigests(String mutation)
      throws Exception {
    fixture(true);
    successfulReceipt(true);
    var receipt = QualitySite.read(root.resolve("deployment.json"));
    switch (mutation) {
      case "schema" -> receipt.put("schemaVersion", 3);
      case "schema-type" -> receipt.put("schemaVersion", "2");
      case "source" -> receipt.put("expectedCommit", "c".repeat(40));
      case "missing-target" -> receipt.remove("targetUrl");
      case "relative-target" -> receipt.put("targetUrl", "/application");
      case "unsupported-target" -> receipt.put("targetUrl", "file:///application");
      case "missing-start" -> receipt.remove("startedAt");
      case "missing-end" -> receipt.remove("endedAt");
      case "invalid-time" -> receipt.put("startedAt", "yesterday");
      case "reversed-time" -> receipt.put("endedAt", "2026-11-09T12:00:00Z");
      case "missing-detail" -> receipt.remove("detail");
      case "failed" -> {
        receipt.put("deploymentState", "failed");
        receipt.put("result", "failure");
      }
      case "triggered", "verifying" -> {
        receipt.put("deploymentState", mutation);
        receipt.put("result", "in_progress");
      }
      case "cancelled", "unknown" -> receipt.put("deploymentState", mutation);
      case "wrong-result" -> receipt.put("result", "failure");
      case "disabled-result" -> receipt.put("deploymentState", "disabled");
      case "missing-attempts" -> receipt.remove("attempts");
      case "zero-attempts" -> receipt.put("attempts", 0);
      case "fractional-attempts" -> receipt.put("attempts", 1.5);
      case "attempts-type" -> receipt.put("attempts", "1");
      case "missing-api-mode" -> receipt.remove("renderApiStatusVerification");
      case "api-mode-type" -> receipt.put("renderApiStatusVerification", "true");
      case "missing-deploy-id" -> receipt.remove("renderDeployId");
      case "not-live" -> receipt.put("renderDeployStatus", "build_in_progress");
      case "malformed-json" -> {}
      default -> throw new AssertionError(mutation);
    }
    QualitySite.write(root.resolve("deployment.json"), receipt);
    if (mutation.equals("malformed-json")) Files.writeString(root.resolve("deployment.json"), "{");
    refreshDigest();
    var result = check();
    assertThat(result.code()).as(mutation + ": " + result.output()).isEqualTo(1);
  }

  void fixture(boolean publication) throws Exception {
    var fixture = new EvidenceManifestTest();
    fixture.root = root;
    fixture.fixture();
    var manifest = QualitySite.read(root.resolve("manifest.json"));
    manifest.put("publication", publication);
    QualitySite.write(root.resolve("manifest.json"), manifest);
  }

  void successfulReceipt(boolean renderApi) throws Exception {
    var transport = new DeploymentVerifierTest.FixtureTransport();
    transport.commit = EvidenceManifestTest.SHA;
    transport.renderStates.add("live");
    var args =
        new ArrayList<>(
            List.of(
                "--base-url",
                "https://example.invalid",
                "--expected-commit",
                EvidenceManifestTest.SHA,
                "--attempts",
                "1",
                "--evidence-file",
                "deployment.json"));
    if (renderApi) {
      Files.writeString(root.resolve("hook.json"), "{\"id\":\"dep-xyz789\"}");
      args.addAll(
          List.of(
              "--deploy-response-file", "hook.json",
              "--render-service-id", "srv-abc",
              "--render-api-key", "secret"));
    }
    var output = new ByteArrayOutputStream();
    assertThat(
            DeploymentVerifier.run(
                args.toArray(String[]::new),
                Map.of(),
                root,
                new PrintStream(output),
                new PrintStream(output),
                new DeploymentVerifier.Runtime(
                    transport,
                    DeploymentVerifierTest.CLOCK,
                    millis -> {
                      throw new AssertionError("A ready fixture must not retry");
                    })))
        .as(output.toString())
        .isZero();
    refreshDigest();
  }

  void refreshDigest() throws Exception {
    var manifest = QualitySite.read(root.resolve("manifest.json"));
    for (Object value : QualitySite.list(manifest.get("artifacts"))) {
      var artifact = QualitySite.object(value);
      if (artifact.get("kind").equals("deployment"))
        artifact.put("sha256", QualitySite.sha256(root.resolve("deployment.json")));
    }
    QualitySite.write(root.resolve("manifest.json"), manifest);
  }

  QualitySiteTest.Result check() {
    return EvidenceManifestTest.check(
        "check-evidence-manifest",
        "--root",
        root.toString(),
        "--file",
        root.resolve("manifest.json").toString(),
        "--expected-commit",
        EvidenceManifestTest.SHA,
        "--expected-tree",
        EvidenceManifestTest.TREE);
  }
}
