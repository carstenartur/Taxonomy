package com.taxonomy.tooling;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class EvidenceManifestTest {
  @TempDir Path root;
  static final String SHA = "a".repeat(40), TREE = "b".repeat(40);

  @Test
  void bindsEveryRequiredArtifactAndGateWithoutPublishing() throws Exception {
    Path manifest = fixture();
    var result =
        check(
            "check-evidence-manifest",
            "--root",
            root.toString(),
            "--file",
            manifest.toString(),
            "--expected-commit",
            SHA,
            "--expected-tree",
            TREE);
    assertThat(result.code()).as(result.output()).isZero();
    assertThat(Files.list(root).map(p -> p.getFileName().toString()).toList())
        .doesNotContain("release", "tag", "image");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "source",
        "tree",
        "digest",
        "missing-artifact",
        "cancelled",
        "skipped",
        "metrics",
        "path",
        "missing-gate",
        "duplicate",
        "raw-junit",
        "raw-coverage",
        "negative-metrics",
        "ui-source",
        "ui-negative",
        "image-source",
        "image-digest",
        "onnx-variant",
        "vex-binding",
        "security-high",
        "security-format",
        "sbom-format"
      })
  void rejectsUnboundOrIncompleteEvidence(String mutation) throws Exception {
    Path manifest = fixture();
    var data = FlatJson.parseObject(Files.readString(manifest));
    var artifacts = (List<Map<String, Object>>) (List<?>) data.get("artifacts");
    var gates = (List<Map<String, Object>>) (List<?>) data.get("gates");
    switch (mutation) {
      case "source" -> data.put("sourceCommit", "c".repeat(40));
      case "tree" -> data.put("sourceTree", "c".repeat(40));
      case "digest" -> artifacts.getFirst().put("sha256", "0".repeat(64));
      case "missing-artifact" -> artifacts.removeFirst();
      case "cancelled", "skipped" -> gates.getFirst().put("outcome", mutation);
      case "metrics" -> {
        QualitySite.write(
            root.resolve("quality-summary.json"),
            Map.of(
                "sourceCommit",
                SHA,
                "commit",
                SHA,
                "sourceTree",
                TREE,
                "tests",
                Map.of(
                    "tests",
                    1,
                    "executed",
                    3,
                    "passed",
                    3,
                    "skipped",
                    0,
                    "errors",
                    0,
                    "failures",
                    0)));
        artifacts
            .getFirst()
            .put("sha256", QualitySite.sha256(root.resolve("quality-summary.json")));
      }
      case "path" -> artifacts.getFirst().put("path", "../escape");
      case "missing-gate" -> gates.removeFirst();
      case "duplicate" -> artifacts.add(artifacts.getFirst());
      case "security-format" -> {
        QualitySite.write(
            root.resolve("security.json"),
            Map.of("deploymentState", "succeeded", "expectedCommit", SHA));
        for (var artifact : artifacts)
          if (artifact.get("kind").equals("security"))
            artifact.put("sha256", QualitySite.sha256(root.resolve("security.json")));
      }
      case "sbom-format" -> {
        QualitySite.write(
            root.resolve("sbom.json"),
            Map.of("not", "a CycloneDX document", "serialNumber", "unrelated", "version", 1));
        SbomCompanionGenerator.generate(
            root.resolve("sbom.json"),
            root.resolve("vex.json"),
            java.time.Instant.parse("2026-11-10T00:00:00Z"),
            java.util.UUID.fromString("12345678-1234-1234-1234-123456789abd"));
        for (var artifact : artifacts)
          if (Set.of("sbom", "vex").contains(artifact.get("kind")))
            artifact.put(
                "sha256", QualitySite.sha256(root.resolve(artifact.get("path").toString())));
      }
      case "image-source", "image-digest", "onnx-variant", "vex-binding", "security-high" -> {
        String path =
            mutation.equals("onnx-variant")
                ? "image-onnx.json"
                : mutation.equals("vex-binding")
                    ? "vex.json"
                    : mutation.equals("security-high") ? "security.json" : "image.json";
        var payload = QualitySite.read(root.resolve(path));
        switch (mutation) {
          case "image-source" -> payload.put("sourceCommit", "c".repeat(40));
          case "image-digest" -> {
            payload.put("digest", "sha256:" + "c".repeat(64));
            payload.put("image", "ghcr.io/fixture/taxonomy@sha256:" + "c".repeat(64));
          }
          case "onnx-variant" -> payload.put("imageVariant", "standard");
          case "vex-binding" ->
              QualitySite.object(payload.get("metadata")).put("properties", List.of());
          case "security-high" ->
              payload.put(
                  "runs",
                  List.of(
                      Map.of(
                          "results",
                          List.of(
                              Map.of(
                                  "ruleId",
                                  "fixture",
                                  "message",
                                  Map.of("text", "Severity: HIGH"))))));
        }
        QualitySite.write(root.resolve(path), payload);
        for (var artifact : artifacts)
          if (artifact.get("path").equals(path))
            artifact.put("sha256", QualitySite.sha256(root.resolve(path)));
      }
      case "ui-negative" -> {
        var ui = QualitySite.read(root.resolve("ui.json"));
        ui.put("scenarioCount", 1);
        ui.put("shardCount", 2);
        ui.put(
            "shards",
            List.of(
                Map.of("id", "negative", "scenarioCount", -1, "outcome", "passed"),
                Map.of("id", "positive", "scenarioCount", 2, "outcome", "passed")));
        QualitySite.write(root.resolve("ui.json"), ui);
        for (var artifact : artifacts)
          if (artifact.get("kind").equals("ui"))
            artifact.put("sha256", QualitySite.sha256(root.resolve("ui.json")));
      }
      case "ui-source" -> {
        var ui = QualitySite.read(root.resolve("ui.json"));
        ui.put("sourceCommit", "c".repeat(40));
        QualitySite.write(root.resolve("ui.json"), ui);
        for (var artifact : artifacts)
          if (artifact.get("kind").equals("ui"))
            artifact.put("sha256", QualitySite.sha256(root.resolve("ui.json")));
      }

      case "negative-metrics" -> {
        var quality = QualitySite.read(root.resolve("quality-summary.json"));
        quality.put(
            "tests",
            Map.of(
                "tests",
                1,
                "executed",
                -1,
                "passed",
                -1,
                "skipped",
                2,
                "errors",
                0,
                "failures",
                0,
                "reportFiles",
                1));
        QualitySite.write(root.resolve("quality-summary.json"), quality);
        QualitySite.write(root.resolve("tests/badge.json"), Map.of("message", "-1 passed"));
        artifacts
            .getFirst()
            .put("sha256", QualitySite.sha256(root.resolve("quality-summary.json")));
      }
      case "raw-junit" -> {
        Files.writeString(
            root.resolve("tests/TEST-fixture.xml"),
            "<testsuite tests='1' failures='0' errors='0'"
                + " skipped='0'><testcase><failure/></testcase></testsuite>");
        for (var artifact : artifacts)
          if (artifact.get("kind").equals("junit"))
            artifact.put("sha256", QualitySite.sha256(root.resolve("tests/TEST-fixture.xml")));
      }
      case "raw-coverage" -> {
        Files.writeString(
            root.resolve("coverage/jacoco.xml"),
            "<report><counter type='INSTRUCTION' missed='90' covered='10'/></report>");
        for (var artifact : artifacts)
          if (artifact.get("kind").equals("coverage"))
            artifact.put("sha256", QualitySite.sha256(root.resolve("coverage/jacoco.xml")));
      }
    }
    QualitySite.write(manifest, data);
    var result =
        check(
            "check-evidence-manifest",
            "--root",
            root.toString(),
            "--file",
            manifest.toString(),
            "--expected-commit",
            SHA,
            "--expected-tree",
            TREE);
    assertThat(result.code()).as(mutation + ": " + result.output()).isEqualTo(1);
  }

  @ParameterizedTest
  @CsvSource({"ui,true", "deployment,true", "deployment,false"})
  void acceptsOnlyTheExplicitVerifiedReleaseToMainMapping(
      String mislabeledKind, boolean deploymentFromMain) throws Exception {
    fixture();
    Path repository = Files.createDirectory(root.resolve("repository"));
    GitSupport.require(repository, "init", "-q");
    GitSupport.require(
        repository,
        "-c",
        "user.name=Fixture",
        "-c",
        "user.email=fixture@example.invalid",
        "-c",
        "commit.gpgsign=false",
        "commit",
        "--allow-empty",
        "-qm",
        "release");
    String release = GitSupport.require(repository, "rev-parse", "HEAD").strip();
    String releaseTree = GitSupport.require(repository, "rev-parse", "HEAD^{tree}").strip();
    GitSupport.require(
        repository,
        "-c",
        "user.name=Fixture",
        "-c",
        "user.email=fixture@example.invalid",
        "-c",
        "commit.gpgsign=false",
        "commit",
        "--allow-empty",
        "-qm",
        "next development");
    String main = GitSupport.require(repository, "rev-parse", "HEAD").strip();
    var data = QualitySite.read(root.resolve("manifest.json"));
    data.put("sourceCommit", release);
    data.put("sourceTree", releaseTree);
    data.put("verificationCommit", main);
    data.put("verificationTree", releaseTree);
    var quality = QualitySite.read(root.resolve("quality-summary.json"));
    quality.put("commit", main);
    quality.put("verifiedCommit", main);
    quality.put("sourceCommit", main);
    quality.put("sourceTree", releaseTree);
    QualitySite.write(root.resolve("quality-summary.json"), quality);
    var ui = QualitySite.read(root.resolve("ui.json"));
    ui.put("sourceCommit", main);
    ui.put("sourceTree", releaseTree);
    QualitySite.write(root.resolve("ui.json"), ui);
    for (Object raw : QualitySite.list(data.get("artifacts"))) {
      var a = QualitySite.object(raw);
      a.put(
          "sourceCommit",
          Set.of("quality", "ui").contains(a.get("kind"))
                  || (deploymentFromMain && a.get("kind").equals("deployment"))
              ? main
              : release);
      Path artifact = root.resolve(a.get("path").toString());
      if (artifact.toString().endsWith(".json")) {
        var payload = QualitySite.read(artifact);
        if (payload.containsKey("sourceCommit")) {
          payload.put("sourceCommit", a.get("sourceCommit"));
          QualitySite.write(artifact, payload);
        }
        if (a.get("kind").equals("deployment")) {
          payload.put("expectedCommit", a.get("sourceCommit"));
          QualitySite.write(artifact, payload);
        }
      }
      a.put("sha256", QualitySite.sha256(root.resolve(a.get("path").toString())));
    }
    for (Object raw : QualitySite.list(data.get("gates")))
      QualitySite.object(raw).put("sourceCommit", main);
    QualitySite.write(root.resolve("manifest.json"), data);
    var result =
        check(
            "check-evidence-manifest",
            "--root",
            root.toString(),
            "--expected-commit",
            release,
            "--expected-tree",
            releaseTree,
            "--file",
            root.resolve("manifest.json").toString(),
            "--verification-commit",
            main,
            "--repository-root",
            repository.toString());
    assertThat(result.code()).as(result.output()).isZero();
    for (Object raw : QualitySite.list(data.get("artifacts")))
      if (QualitySite.object(raw).get("kind").equals(mislabeledKind))
        QualitySite.object(raw)
            .put("sourceCommit", deploymentFromMain || mislabeledKind.equals("ui") ? release : main);
    QualitySite.write(root.resolve("manifest.json"), data);
    var mislabeled =
        check(
            "check-evidence-manifest",
            "--root",
            root.toString(),
            "--expected-commit",
            release,
            "--expected-tree",
            releaseTree,
            "--file",
            root.resolve("manifest.json").toString(),
            "--verification-commit",
            main,
            "--repository-root",
            repository.toString());
    assertThat(mislabeled.code()).as(mislabeled.output()).isEqualTo(1);
  }

  static QualitySiteTest.Result check(String... args) {
    var values = new ArrayList<String>(List.of(args));
    values.addAll(
        List.of(
            "--expected-image-digest",
            "sha256:" + "d".repeat(64),
            "--expected-onnx-image-digest",
            "sha256:" + "e".repeat(64)));
    return QualitySiteTest.run(values.toArray(String[]::new));
  }

  Path fixture() throws Exception {
    Files.createDirectories(root.resolve("tests"));
    Files.createDirectories(root.resolve("coverage"));
    Files.writeString(
        root.resolve("tests/TEST-fixture.xml"),
        "<testsuite tests='1' failures='0' errors='0' skipped='0'><testcase/></testsuite>");
    Files.writeString(
        root.resolve("coverage/jacoco.xml"),
        "<report><counter type='INSTRUCTION' missed='20' covered='80'/></report>");
    Files.writeString(root.resolve("coverage/index.html"), "coverage");
    QualitySite.generate(root, SHA, TREE, "123.1", "now", Map.of("java", "25"));
    QualitySite.write(
        root.resolve("sbom.json"),
        Map.of(
            "bomFormat",
            "CycloneDX",
            "specVersion",
            "1.6",
            "serialNumber",
            "urn:uuid:12345678-1234-1234-1234-123456789abc",
            "version",
            1,
            "components",
            List.of()));
    SbomCompanionGenerator.generate(
        root.resolve("sbom.json"),
        root.resolve("vex.json"),
        java.time.Instant.parse("2026-11-10T00:00:00Z"),
        java.util.UUID.fromString("12345678-1234-1234-1234-123456789abd"));
    var image =
        QualitySite.map(
            "schemaVersion",
            1,
            "releaseVersion",
            "1.5.3",
            "sourceCommit",
            SHA,
            "digest",
            "sha256:" + "d".repeat(64),
            "image",
            "ghcr.io/fixture/taxonomy@sha256:" + "d".repeat(64),
            "scan",
            Map.of(
                "tool", "trivy", "result", "passed", "severityGate", List.of("HIGH", "CRITICAL")),
            "attestations",
            Map.of("provenance", true, "sbom", true));
    QualitySite.write(root.resolve("image.json"), image);
    var onnx = new LinkedHashMap<>(image);
    onnx.put("imageVariant", "onnx");
    onnx.put("digest", "sha256:" + "e".repeat(64));
    onnx.put("image", "ghcr.io/fixture/taxonomy@sha256:" + "e".repeat(64));
    QualitySite.write(root.resolve("image-onnx.json"), onnx);
    QualitySite.write(root.resolve("security.json"), Map.of("version", "2.1.0", "runs", List.of()));
    var output = new java.io.ByteArrayOutputStream();
    assertThat(
            DeploymentVerifier.renderCommand(
                "render-delivery-state",
                new String[] {"--state", "disabled"},
                Map.of("BASE_URL", "https://example.invalid", "EXPECTED_SHA", SHA),
                root,
                new java.io.PrintStream(output),
                new java.io.PrintStream(output)))
        .as(output.toString())
        .isZero();
    Files.copy(
        root.resolve("target/delivery-evidence/render-disabled.json"),
        root.resolve("deployment.json"));
    List<Map<String, Object>> artifacts = new ArrayList<>();
    for (String kind :
        List.of(
            "quality",
            "junit",
            "coverage",
            "sbom",
            "vex",
            "image",
            "image-onnx",
            "security",
            "ui",
            "database",
            "distribution",
            "helm",
            "deployment")) {
      String path =
          switch (kind) {
            case "quality" -> "quality-summary.json";
            case "junit" -> "tests/TEST-fixture.xml";
            case "coverage" -> "coverage/jacoco.xml";
            default -> kind + ".json";
          };
      if (!Files.exists(root.resolve(path)))
        QualitySite.write(
            root.resolve(path),
            kind.equals("ui")
                ? Map.of(
                    "schemaVersion",
                    1,
                    "sourceCommit",
                    SHA,
                    "sourceTree",
                    TREE,
                    "outcome",
                    "passed",
                    "scenarioCount",
                    1,
                    "shardCount",
                    1,
                    "applicationArtifactSha256",
                    "d".repeat(64),
                    "shards",
                    List.of(Map.of("id", "fixture", "scenarioCount", 1, "outcome", "passed")))
                : Map.of("sourceCommit", SHA));
      artifacts.add(
          new LinkedHashMap<>(
              Map.of(
                  "kind",
                  kind,
                  "path",
                  path,
                  "sourceCommit",
                  SHA,
                  "sha256",
                  QualitySite.sha256(root.resolve(path)))));
    }
    List<Map<String, Object>> gates = new ArrayList<>();
    for (String name :
        List.of(
            "ci",
            "database-postgresql",
            "database-sqlserver",
            "database-oracle",
            "codeql",
            "security",
            "ui",
            "packaged-plugins"))
      gates.add(
          new LinkedHashMap<>(Map.of("name", name, "sourceCommit", SHA, "outcome", "success")));
    Path manifest = root.resolve("manifest.json");
    QualitySite.write(
        manifest,
        Map.of(
            "schemaVersion",
            1,
            "profile",
            "release",
            "sourceCommit",
            SHA,
            "sourceTree",
            TREE,
            "publication",
            false,
            "artifacts",
            artifacts,
            "gates",
            gates));
    return manifest;
  }
}
