package com.taxonomy.tooling;

import static org.assertj.core.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

class ReleaseEvidenceTest {
  @TempDir Path root;
  private static final String EMPTY_SARIF = """
      {"version":"2.1.0","runs":[{"tool":{"driver":{"name":"Trivy","rules":[]}},"results":[]}]}
      """;
  private static final String HELM_PASS = """
      {"Target":"deploy/helm/taxonomy/templates/resources.yaml","Class":"config","Type":"helm",
       "MisconfSummary":{"Successes":1,"Failures":0},
       "Misconfigurations":[{"ID":"KSV001","Status":"PASS","Severity":"MEDIUM"}]}
      """;

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
        "{\"version\":\"2.1.0\",\"runs\":[{\"tool\":{\"driver\":{\"name\":\"Trivy\",\"rules\":[{\"id\":\"fixture\",\"help\":{\"text\":\"Severity:"
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
        "{\"version\":\"2.1.0\",\"runs\":[{\"tool\":{\"driver\":{\"name\":\"Trivy\",\"rules\":[{\"id\":\"fixture\",\"properties\":{\"tags\":[\"Severity:\",\"CRITICAL\"]}}]}},\"results\":[{\"ruleId\":\"fixture\"}]}]}");
    var result = QualitySiteTest.run("check-trivy-sarif", "--file", report.toString());
    assertThat(result.code()).as(result.output()).isEqualTo(1);
    assertThat(result.output()).contains("CRITICAL");
    Files.writeString(report, "{broken");
    assertThat(QualitySiteTest.run("check-trivy-sarif", "--file", report.toString()).code())
        .isEqualTo(1);
  }

  @Test
  void rejectsACompleteSarifWhenTrivySkippedRenderingTheHelmChart() throws Exception {
    var result = checkScanCoverage("""
        {"SchemaVersion":2,"ArtifactType":"repository","Results":[
          {"Target":"Dockerfile","Class":"config","Type":"dockerfile",
           "MisconfSummary":{"Successes":1,"Failures":0},
           "Misconfigurations":[{"ID":"DS002","Status":"PASS"}]}
        ]}
        """);
    assertThat(result.code()).as(result.output()).isEqualTo(1);
    assertThat(result.output()).contains("Helm", "deploy/helm/taxonomy/templates/resources.yaml");
  }

  @Test
  void rejectsIncompleteOrUnscannedHelmCoverageEvidence() throws Exception {
    for (String report : List.of(
        "{}",
        "{\"SchemaVersion\":2,\"Results\":[]}",
        """
        {"SchemaVersion":2,"Results":[
          {"Target":"deploy/helm/taxonomy/templates/resources.yaml","Class":"config","Type":"helm"}
        ]}
        """,
        """
        {"SchemaVersion":2,"Results":[
          {"Target":"deploy/helm/taxonomy/templates/resources.yaml","Class":"config","Type":"helm",
           "MisconfSummary":{"Successes":2,"Failures":0},
           "Misconfigurations":[{"ID":"KSV001","Status":"PASS"}]}
        ]}
        """)) {
      var result = checkScanCoverage(report);
      assertThat(result.code()).as(report + "\n" + result.output()).isEqualTo(1);
    }
  }

  @Test
  void acceptsHelmCoverageWithActualPassingAndFailingCheckResults() throws Exception {
    var result = checkScanCoverage("""
        {"SchemaVersion":2,"Trivy":{"Version":"0.76.2"},"ArtifactType":"repository","Results":[
          {"Target":"deploy/helm/taxonomy/templates/resources.yaml","Class":"config","Type":"helm",
           "MisconfSummary":{"Successes":1,"Failures":1},
           "Misconfigurations":[
             {"ID":"KSV001","Status":"PASS","Severity":"MEDIUM"},
             {"ID":"KSV039","Status":"FAIL","Severity":"LOW"}
           ]}
        ]}
        """, sarif("KSV039", "LOW"));
    assertThat(result.code()).as(result.output()).isZero();
    assertThat(result.output()).contains("Helm chart scan coverage verified");
  }

  @Test
  void blocksRawHighMisconfigurationWhenSarifConversionLosesIt() throws Exception {
    String scan = scanReport(helmFailure("HIGH"));
    var result = checkScanCoverage(scan, EMPTY_SARIF);
    assertThat(result.code()).as(result.output()).isEqualTo(1);
    assertThat(result.output()).contains("SARIF", "KSV039");
  }

  @Test
  void checksVulnerabilityAndSecretFindingsAgainstTheOriginalScan() throws Exception {
    for (String[] scenario : List.of(
        new String[] {"""
        {"Target":"app.jar","Class":"lang-pkgs","Type":"jar",
         "Vulnerabilities":[{"VulnerabilityID":"CVE-fixture","Severity":"CRITICAL"}]}
        """, "CVE-fixture", "CRITICAL"},
        new String[] {"""
        {"Target":"credentials.txt","Class":"secret","Type":"secret",
         "Secrets":[{"RuleID":"fixture-private-key","Severity":"HIGH"}]}
        """, "fixture-private-key", "HIGH"})) {
      String report = scanReport(HELM_PASS, scenario[0]);
      var gate = checkScanCoverage(report, EMPTY_SARIF);
      assertThat(gate.code()).as(gate.output()).isEqualTo(1);
      assertThat(gate.output()).contains("SARIF");
      Path scan = root.resolve("domain.json");
      Path converted = root.resolve("domain.sarif");
      Files.writeString(scan, report);
      Files.writeString(converted, sarif(scenario[1], scenario[2]));
      var coverage = QualitySiteTest.run("check-trivy-coverage", "--scan-report", scan.toString(),
          "--file", converted.toString());
      assertThat(coverage.code()).as(coverage.output()).isZero();
      var severity = QualitySiteTest.run("check-trivy-sarif", "--file", converted.toString());
      assertThat(severity.code()).as(severity.output()).isEqualTo(1);
      assertThat(severity.output()).contains(scenario[1], scenario[2]);
    }
  }

  @Test
  void rejectsLostDuplicateFindingsAndSeverityChangesDuringConversion() throws Exception {
    String duplicate = """
        {"Target":"app.jar","Class":"lang-pkgs","Type":"jar","Vulnerabilities":[
          {"VulnerabilityID":"CVE-fixture","Severity":"LOW"},
          {"VulnerabilityID":"CVE-fixture","Severity":"LOW"}
        ]}
        """;
    assertThat(checkScanCoverage(scanReport(HELM_PASS, duplicate), sarif("CVE-fixture", "LOW")).code())
        .isEqualTo(1);
    var changed = checkScanCoverage(scanReport(helmFailure("HIGH")), sarif("KSV039", "LOW"));
    assertThat(changed.code()).as(changed.output()).isEqualTo(1);
  }

  @Test
  void rejectsMalformedTrivySarifAndUnparseableOrConflictingSeverities() throws Exception {
    for (String malformed : List.of(
        "{}",
        "{\"version\":\"2.1.0\",\"runs\":[]}",
        "{\"version\":\"2.1.0\",\"runs\":[{}]}",
        "{\"version\":\"2.1.0\",\"runs\":[{\"tool\":{\"driver\":{\"name\":\"Trivy\",\"rules\":[]}}}]}",
        EMPTY_SARIF.replace("Trivy", "Other scanner"),
        sarif("fixture", "UNRATED"),
        sarif("fixture", "LOW").replace("\"ruleId\":\"fixture\"",
            "\"ruleId\":\"fixture\",\"message\":{\"text\":\"Severity: HIGH\"}"))) {
      Path file = root.resolve("malformed.sarif");
      Files.writeString(file, malformed);
      var result = QualitySiteTest.run("check-trivy-sarif", "--file", file.toString());
      assertThat(result.code()).as(malformed + "\n" + result.output()).isEqualTo(1);
    }
  }

  @Test
  void completeHighReportsPassCoverageButStillFailTheSeverityGate() throws Exception {
    Path scan = root.resolve("high.json");
    Path sarif = root.resolve("high.sarif");
    Files.writeString(scan, scanReport(helmFailure("HIGH")));
    Files.writeString(sarif, sarif("KSV039", "HIGH"));
    var coverage = QualitySiteTest.run("check-trivy-coverage", "--scan-report", scan.toString(),
        "--file", sarif.toString());
    assertThat(coverage.code()).as(coverage.output()).isZero();
    var severity = QualitySiteTest.run("check-trivy-sarif", "--file", sarif.toString());
    assertThat(severity.code()).as(severity.output()).isEqualTo(1);
    assertThat(severity.output()).contains("HIGH", "KSV039");
  }

  @Test
  void partialScansCannotUploadButCompleteHighFindingsCan() throws Exception {
    Path repository = ReleaseOrchestrationChecks.repositoryRoot();
    Map<?, ?> workflow = new Yaml(new SafeConstructor(new LoaderOptions())).load(
        Files.readString(repository.resolve(".github/workflows/security-scan.yml")));
    Map<?, ?> jobs = (Map<?, ?>) workflow.get("jobs");
    List<?> steps = (List<?>) ((Map<?, ?>) jobs.get("trivy")).get("steps");
    Map<?, ?> upload = steps.stream().map(Map.class::cast)
        .filter(step -> Objects.toString(step.get("uses"), "").startsWith("github/codeql-action/upload-sarif@"))
        .findFirst().orElseThrow();
    String condition = Objects.toString(upload.get("if"));
    var partial = checkScanCoverage(scanReport(), EMPTY_SARIF);
    assertThat(partial.code()).isEqualTo(1);
    assertThat(uploadAllowed(condition, "failure", true)).as("incomplete scan must not resolve old alerts").isFalse();
    assertThat(uploadAllowed(condition, "skipped", true)).isFalse();
    assertThat(uploadAllowed(condition, "success", false)).isFalse();
    assertThat(uploadAllowed(condition, "success", true)).as("complete HIGH findings remain uploadable").isTrue();
    Map<?, ?> coverage = steps.stream().map(Map.class::cast)
        .filter(step -> "scan_coverage".equals(step.get("id"))).findFirst().orElseThrow();
    assertThat(Objects.toString(coverage.get("run"))).contains("check-trivy-coverage");
    Map<?, ?> archive = steps.stream().map(Map.class::cast)
        .filter(step -> "Archive scanner evidence".equals(step.get("name"))).findFirst().orElseThrow();
    assertThat(uploadAllowed(Objects.toString(archive.get("if")), "failure", true))
        .as("raw incomplete reports remain available for diagnosis").isTrue();
  }

  private static boolean uploadAllowed(String condition, String coverageOutcome, boolean sarifExists) {
    boolean allowed = true;
    for (String term : condition.replace("${{", "").replace("}}", "").trim().split("\\s*&&\\s*")) {
      allowed &= switch (term) {
        case "always()" -> true;
        case "steps.scan_coverage.outcome == 'success'" -> coverageOutcome.equals("success");
        case "hashFiles('trivy-results.sarif') != ''" -> sarifExists;
        default -> throw new AssertionError("Unsupported workflow condition: " + term);
      };
    }
    return allowed;
  }

  private static String scanReport(String... results) {
    return "{\"SchemaVersion\":2,\"ArtifactType\":\"repository\",\"Results\":["
        + String.join(",", results) + "]}";
  }

  private static String helmFailure(String severity) {
    return """
        {"Target":"deploy/helm/taxonomy/templates/resources.yaml","Class":"config","Type":"helm",
         "MisconfSummary":{"Successes":0,"Failures":1},
         "Misconfigurations":[{"ID":"KSV039","Status":"FAIL","Severity":"%s"}]}
        """.formatted(severity);
  }

  private static String sarif(String id, String severity) {
    return """
        {"version":"2.1.0","runs":[{"tool":{"driver":{"name":"Trivy","rules":[
          {"id":"%s","help":{"text":"Severity: %s"}}
        ]}},"results":[{"ruleId":"%s"}]}]}
        """.formatted(id, severity, id);
  }

  private QualitySiteTest.Result checkScanCoverage(String coverage) throws Exception {
    return checkScanCoverage(coverage, EMPTY_SARIF);
  }

  private QualitySiteTest.Result checkScanCoverage(String coverage, String converted) throws Exception {
    Path sarif = root.resolve("coverage.sarif");
    Path scan = root.resolve("scan.json");
    Files.writeString(sarif, converted);
    Files.writeString(scan, coverage);
    return QualitySiteTest.run("check-trivy-sarif", "--file", sarif.toString(), "--scan-report", scan.toString());
  }
}
