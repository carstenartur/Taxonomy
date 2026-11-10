package com.taxonomy.tooling;

import static org.assertj.core.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReleaseEvidenceTest {
  @TempDir Path root;

  @Test
  void createsTwoImmutableVariantReceiptsWithoutPublishing() throws Exception {
    var env =
        Map.of(
            "RELEASE_VERSION",
            "1.5.3",
            "RELEASE_SOURCE_SHA",
            "a".repeat(40),
            "IMAGE_DIGEST",
            "sha256:" + "b".repeat(64),
            "ONNX_IMAGE_DIGEST",
            "sha256:" + "c".repeat(64),
            "immutable_image",
            "ghcr.io/fixture/taxonomy@sha256:" + "b".repeat(64),
            "evidence_file",
            root.resolve("standard.json").toString());
    var bytes = new ByteArrayOutputStream();
    var print = new PrintStream(bytes);
    assertThat(
            TaxonomyTooling.run(
                new String[] {"write-release-image-evidence"}, env, root, print, print))
        .as(bytes.toString())
        .isZero();
    assertThat(FlatJson.parseObject(Files.readString(root.resolve("standard.json"))))
        .containsEntry("sourceCommit", "a".repeat(40));
    assertThat(
            FlatJson.parseObject(
                Files.readString(root.resolve("target/release-onnx-image-evidence.json"))))
        .containsEntry("imageVariant", "onnx")
        .containsEntry("digest", "sha256:" + "c".repeat(64));
  }

  @Test
  void trivySeverityHonorsRuleEvidenceAndRejectsMalformedReport() throws Exception {
    Path report = root.resolve("sarif.json");
    Files.writeString(
        report,
        "{\"runs\":[{\"tool\":{\"driver\":{\"rules\":[{\"id\":\"fixture\",\"help\":{\"text\":\"Severity:"
            + " CRITICAL\"}}]}},\"results\":[{\"ruleId\":\"fixture\"}]}]}");
    var r = QualitySiteTest.run("check-trivy-sarif", "--file", report.toString());
    assertThat(r.code()).as(r.output()).isEqualTo(1);
    assertThat(r.output()).contains("CRITICAL");
  }

  @Test
  void splitSeverityTagsRemainBlockingAndMalformedSarifFailsClosed() throws Exception {
    Path report = root.resolve("tags.json");
    Files.writeString(
        report,
        "{\"runs\":[{\"tool\":{\"driver\":{\"rules\":[{\"id\":\"fixture\",\"properties\":{\"tags\":[\"Severity:\",\"CRITICAL\"]}}]}},\"results\":[{\"ruleId\":\"fixture\"}]}]}");
    var result = QualitySiteTest.run("check-trivy-sarif", "--file", report.toString());
    assertThat(result.code()).as(result.output()).isEqualTo(1);
    assertThat(result.output()).contains("CRITICAL");
    Files.writeString(report, "{broken");
    assertThat(QualitySiteTest.run("check-trivy-sarif", "--file", report.toString()).code())
        .isEqualTo(1);
  }
}
