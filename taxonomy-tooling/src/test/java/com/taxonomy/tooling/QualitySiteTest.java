package com.taxonomy.tooling;

import static org.assertj.core.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QualitySiteTest {
  @TempDir Path root;
  static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
  static final String XML =
      "<testsuite tests=\"2\" failures=\"0\" errors=\"0\""
          + " skipped=\"1\"><testcase/><testcase><skipped/></testcase></testsuite>";

  @Test
  void generatesAndVerifiesOriginalOutcomesWithEscapedProvenance() throws Exception {
    fixture();
    var result =
        run(
            "generate-quality-site",
            "--root",
            root.toString(),
            "--commit",
            COMMIT,
            "--source-tree",
            "tree123",
            "--build-id",
            "123.2",
            "--tool",
            "java=25<safe>",
            "--generated-at",
            "2026-11-10T00:00:00Z");
    assertThat(result.code).as(result.output).isZero();
    var summary = FlatJson.parseObject(Files.readString(root.resolve("quality-summary.json")));
    assertThat(summary).containsEntry("sourceTree", "tree123").containsEntry("buildId", "123.2");
    assertThat(((Map<?, ?>) summary.get("tests")).get("passed")).isEqualTo(1L);
    assertThat(Files.readString(root.resolve("tests/surefire-report.html")))
        .contains("25&lt;safe&gt;");
    assertThat(
            run(
                    "verify-quality-publication",
                    "--root",
                    root.toString(),
                    "--expected-commit",
                    COMMIT)
                .code)
        .isZero();
    Files.writeString(
        root.resolve("tests/TEST-fixture.xml"),
        XML.replace("<testcase/>", "<testcase name=\"altered\"/>"));
    // Raw outcomes, rather than testcase labels, remain the historical publication contract.
    assertThat(
            run(
                    "verify-quality-publication",
                    "--root",
                    root.toString(),
                    "--expected-commit",
                    "wrong")
                .code)
        .isEqualTo(1);
  }

  @Test
  void rejectsMissingMalformedAndForgedOutcomes() throws Exception {
    assertThat(run("generate-quality-site", "--root", root.toString(), "--commit", COMMIT).code)
        .isEqualTo(1);
    fixture();
    for (String malformed :
        List.of(
            XML.replace("tests=\"2\"", "tests=\"3\""),
            XML.replace("<testcase/>", "<testcase><flakyFailure/></testcase>"),
            "<!DOCTYPE x [<!ENTITY leak SYSTEM 'file:///etc/passwd'>]>" + XML)) {
      Files.writeString(root.resolve("tests/TEST-fixture.xml"), malformed);
      var result = run("generate-quality-site", "--root", root.toString(), "--commit", COMMIT);
      assertThat(result.code).as(result.output).isEqualTo(1);
    }
  }

  @Test
  void preservesCoverageColorThresholdsAndRejectsBadToolMetadata() throws Exception {
    for (Object[] row :
        new Object[][] {
          {90.0, "brightgreen"},
          {80.0, "green"},
          {70.0, "yellowgreen"},
          {60.0, "yellow"},
          {59.9, "red"}
        }) assertThat(QualitySite.color((double) row[0])).isEqualTo(row[1]);
    fixture();
    assertThat(
            run(
                    "generate-quality-site",
                    "--root",
                    root.toString(),
                    "--commit",
                    COMMIT,
                    "--tool",
                    "broken")
                .code)
        .isEqualTo(1);
  }

  @Test
  void detectsBadgeAndExecutedTotalDriftAndMissingFiles() throws Exception {
    fixture();
    QualitySite.generate(root, COMMIT, "tree", "run-1", "now", Map.of("java", "25"));
    Path badge = root.resolve("tests/badge.json");
    String original = Files.readString(badge);
    Files.writeString(badge, original.replace("1 passed", "900 passed"));
    assertThat(
            run(
                    "verify-quality-publication",
                    "--root",
                    root.toString(),
                    "--expected-commit",
                    COMMIT)
                .output)
        .contains("does not match summary");
    Files.writeString(badge, original);
    Path summary = root.resolve("quality-summary.json");
    var data = FlatJson.parseObject(Files.readString(summary));
    ((Map<String, Object>) data.get("tests")).put("executed", 2L);
    QualitySite.write(summary, data);
    assertThat(
            run(
                    "verify-quality-publication",
                    "--root",
                    root.toString(),
                    "--expected-commit",
                    COMMIT)
                .output)
        .contains("executed-test total");
    Files.delete(badge);
    assertThat(
            run(
                    "verify-quality-publication",
                    "--root",
                    root.toString(),
                    "--expected-commit",
                    COMMIT)
                .output)
        .contains("tests/badge.json");
  }

  @Test
  void remoteVerificationUsesBoundedRetriesCacheHeadersAndRawOutcomes() throws Exception {
    fixture();
    QualitySite.generate(root, COMMIT, "tree", "run-1", "now", Map.of("java", "25"));
    var server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    var requests = new java.util.concurrent.atomic.AtomicInteger();
    server.createContext(
        "/quality/",
        exchange -> {
          try {
            assertThat(exchange.getRequestHeaders().getFirst("Cache-Control"))
                .isEqualTo("no-cache");
            assertThat(exchange.getRequestURI().getRawQuery()).contains("verified=" + COMMIT);
            String relative = exchange.getRequestURI().getPath().substring("/quality/".length());
            byte[] body = Files.readAllBytes(root.resolve(relative));
            int code = requests.incrementAndGet() == 1 ? 503 : 200;
            exchange.sendResponseHeaders(code, body.length);
            exchange.getResponseBody().write(body);
          } finally {
            exchange.close();
          }
        });
    server.start();
    try {
      var result =
          run(
              "verify-quality-publication",
              "--base-url",
              "http://127.0.0.1:" + server.getAddress().getPort() + "/quality",
              "--expected-commit",
              COMMIT,
              "--attempts",
              "2",
              "--interval-seconds",
              "0");
      assertThat(result.code).as(result.output).isZero();
      assertThat(requests.get()).isGreaterThan(5);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void safeRawPathsAndBuildCohortsRetainHistoricalBounds() {
    for (Object bad :
        List.of(
            "tests/../escape.xml",
            "other/file.xml",
            "tests/file.xml?x=1",
            "tests/a\\b.xml",
            "tests/%2e%2e/file.xml"))
      assertThatThrownBy(() -> QualitySite.reportPath(bad))
          .isInstanceOf(IllegalArgumentException.class);
    assertThat(QualitySite.compatibleBuild("123.1", "123.2")).isTrue();
    assertThat(QualitySite.compatibleBuild("local", "local")).isTrue();
    for (String bad : List.of("124.1", "123.3", "123.0", "123.invalid", "0123.1", "123.01"))
      assertThat(QualitySite.compatibleBuild(bad, "123.2")).as(bad).isFalse();
  }

  @Test
  void roundsCoverageAndBadgeNumbersLikeTheRetiredGenerator() throws Exception {
    fixture();
    Files.writeString(
        root.resolve("coverage/jacoco.xml"),
        "<report><counter type='INSTRUCTION' missed='31' covered='1'/></report>");
    var summary = QualitySite.generate(root, COMMIT, "tree", "build", "now", Map.of());
    assertThat(QualitySite.object(summary.get("coverage")).get("instructionPercent"))
        .isEqualTo(3.12);
    assertThat(QualitySite.percent(2.675)).isEqualTo("2.67");
    assertThat(QualitySite.percent(3.125)).isEqualTo("3.12");
  }

  void fixture() throws Exception {
    Files.createDirectories(root.resolve("tests"));
    Files.createDirectories(root.resolve("coverage"));
    Files.writeString(root.resolve("tests/TEST-fixture.xml"), XML);
    Files.writeString(
        root.resolve("coverage/jacoco.xml"),
        "<report><counter type=\"INSTRUCTION\" missed=\"20\" covered=\"80\"/></report>");
    Files.writeString(root.resolve("coverage/index.html"), "coverage");
  }

  record Result(int code, String output) {}

  static Result run(String... args) {
    var bytes = new ByteArrayOutputStream();
    var print = new PrintStream(bytes);
    int code = TaxonomyTooling.run(args, Path.of("."), print, print);
    return new Result(code, bytes.toString());
  }
}
