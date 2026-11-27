package com.taxonomy.tooling;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Small deterministic replacements for workflow payloads; these commands never publish. */
final class ReleaseEvidence {
  private static final Set<String> TRIVY_SEVERITIES =
      Set.of("UNKNOWN", "LOW", "MEDIUM", "HIGH", "CRITICAL");

  static int run(
      String command,
      String[] raw,
      Map<String, String> env,
      Path cwd,
      PrintStream out,
      PrintStream err) {
    try {
      var args = TaxonomyTooling.Arguments.parse(raw);
      if (command.equals("write-release-image-evidence")) {
        String source = required(env, "RELEASE_SOURCE_SHA"),
            digest = required(env, "IMAGE_DIGEST"),
            onnx = required(env, "ONNX_IMAGE_DIGEST"),
            image = required(env, "immutable_image");
        if (!source.matches("[0-9a-f]{40}")
            || !digest.matches("sha256:[0-9a-f]{64}")
            || !onnx.matches("sha256:[0-9a-f]{64}")
            || !image.endsWith("@" + digest))
          throw new IllegalArgumentException("Invalid immutable source/image identity");
        var payload =
            QualitySite.map(
                "schemaVersion",
                1,
                "releaseVersion",
                required(env, "RELEASE_VERSION"),
                "sourceCommit",
                source,
                "image",
                image,
                "digest",
                digest,
                "scan",
                QualitySite.map(
                    "tool",
                    "trivy",
                    "severityGate",
                    List.of("HIGH", "CRITICAL"),
                    "result",
                    "passed"),
                "attestations",
                QualitySite.map("provenance", true, "sbom", true));
        verifyImageReceipt(payload, source, digest, "standard");
        QualitySite.write(Path.of(required(env, "evidence_file")), payload);
        var variant = new LinkedHashMap<>(payload);
        variant.put("imageVariant", "onnx");
        variant.put("image", image.substring(0, image.indexOf('@')) + "@" + onnx);
        variant.put("digest", onnx);
        verifyImageReceipt(variant, source, onnx, "onnx");
        QualitySite.write(cwd.resolve("target/release-onnx-image-evidence.json"), variant);
        return 0;
      }
      if (command.equals("read-evidence-digest")) {
        String digest =
            Objects.toString(
                QualitySite.read(
                        args.path("file", cwd.resolve("target/release-image-evidence.json")))
                    .get("digest"),
                "");
        if (!digest.matches("sha256:[0-9a-f]{64}"))
          throw new IllegalArgumentException("Invalid evidence digest");
        out.println(digest);
        return 0;
      }
      if (command.equals("storage-contract-selectors")) {
        var catalog = QualitySite.read(cwd.resolve(".mvn/verification-suites.json"));
        var profile =
            QualitySite.object(
                QualitySite.object(catalog.get("profiles")).get("jgit-storage-hibernate-contract"));
        for (String key : List.of("test", "itTest", "excludedGroups")) {
          Object value = profile.get(key);
          if (!(value instanceof String text) || text.isBlank())
            throw new IllegalArgumentException("jgit-storage-hibernate-contract is missing " + key);
          out.println(value);
        }
        return 0;
      }
      Path sarif = args.path("file", cwd.resolve("trivy-results.sarif"));
      if (command.equals("check-trivy-coverage") || args.optional("scan-report") != null)
        verifyTrivyCoverage(Path.of(args.required("scan-report")), sarif, out);
      if (command.equals("check-trivy-coverage")) return 0;
      return trivy(sarif, out, err);
    } catch (Exception e) {
      err.println("::error::" + e.getMessage());
      return 1;
    }
  }

  static void verifyImageReceipt(
      Map<String, Object> receipt, String source, String expectedDigest, String variant) {
    if (QualitySite.number(receipt, "schemaVersion") != 1
        || Objects.toString(receipt.get("releaseVersion"), "").isBlank()
        || !expectedDigest.matches("sha256:[0-9a-f]{64}")
        || !source.equals(receipt.get("sourceCommit"))
        || !expectedDigest.equals(receipt.get("digest"))
        || !Objects.toString(receipt.get("image"), "").endsWith("@" + expectedDigest))
      throw new IllegalArgumentException("Immutable image receipt source/digest differs");
    if (variant.equals("onnx")
        ? !"onnx".equals(receipt.get("imageVariant"))
        : receipt.containsKey("imageVariant") && !"standard".equals(receipt.get("imageVariant")))
      throw new IllegalArgumentException("Immutable image receipt variant differs");
    var scan = QualitySite.object(receipt.get("scan"));
    var attestations = QualitySite.object(receipt.get("attestations"));
    if (!"passed".equals(scan.get("result"))
        || !"trivy".equals(scan.get("tool"))
        || !new HashSet<>(QualitySite.list(scan.get("severityGate")))
            .equals(Set.of("HIGH", "CRITICAL"))
        || !Boolean.TRUE.equals(attestations.get("provenance"))
        || !Boolean.TRUE.equals(attestations.get("sbom")))
      throw new IllegalArgumentException("Immutable image receipt scan/attestation differs");
  }

  static void verifyHelmScan(Path file, PrintStream out) throws IOException {
    String target = "deploy/helm/taxonomy/templates/resources.yaml";
    var report = QualitySite.read(file);
    if (QualitySite.number(report, "SchemaVersion") != 2)
      throw new IllegalArgumentException("Unsupported Trivy scan report schema");
    for (Object raw : QualitySite.list(report.get("Results"))) {
      var result = QualitySite.object(raw);
      if (!target.equals(result.get("Target"))) continue;
      if (!"config".equals(result.get("Class")) || !"helm".equals(result.get("Type")))
        throw new IllegalArgumentException("Expected Helm scan evidence for " + target);
      var summary = QualitySite.object(result.get("MisconfSummary"));
      long successes = QualitySite.number(summary, "Successes"),
          failures = QualitySite.number(summary, "Failures"),
          exceptions = summary.containsKey("Exceptions") ? QualitySite.number(summary, "Exceptions") : 0;
      if (successes < 0 || failures < 0 || exceptions < 0 || successes + failures == 0)
        throw new IllegalArgumentException("No completed Helm checks for " + target);
      Map<String, Long> counts = new HashMap<>();
      for (Object rawCheck : QualitySite.list(result.get("Misconfigurations"))) {
        var check = QualitySite.object(rawCheck);
        String status = Objects.toString(check.get("Status"), "");
        if (!Set.of("PASS", "FAIL", "EXCEPTION").contains(status))
          throw new IllegalArgumentException("Invalid Helm check status for " + target);
        counts.merge(status, 1L, Long::sum);
      }
      if (counts.getOrDefault("PASS", 0L) != successes
          || counts.getOrDefault("FAIL", 0L) != failures
          || counts.getOrDefault("EXCEPTION", 0L) != exceptions)
        throw new IllegalArgumentException("Incomplete Helm check results for " + target);
      out.println("Helm chart scan coverage verified: " + target);
      return;
    }
    throw new IllegalArgumentException("Missing Helm chart scan coverage: " + target);
  }

  static int trivy(Path file, PrintStream out, PrintStream err) throws IOException {
    List<String> blocking = new ArrayList<>();
    for (TrivyFinding finding : sarifFindings(file)) {
      if (Set.of("HIGH", "CRITICAL").contains(finding.severity()))
        blocking.add("- [" + finding.severity() + "] " + finding.id() + ": " + finding.message());
    }
    if (!blocking.isEmpty()) {
      err.println("Blocking Trivy findings:");
      blocking.forEach(err::println);
      return 1;
    }
    out.println("No HIGH or CRITICAL Trivy findings detected.");
    return 0;
  }

  static void verifyTrivyCoverage(Path scan, Path sarif, PrintStream out) throws IOException {
    verifyHelmScan(scan, out);
    var expected = findingCounts(scanFindings(scan));
    var converted = findingCounts(sarifFindings(sarif));
    if (!expected.equals(converted))
      throw new IllegalArgumentException(
          "Trivy SARIF findings differ from the original scan: expected " + expected + ", got " + converted);
    out.println("Trivy SARIF preserves all original finding IDs, severities and occurrence counts.");
  }

  private static Map<List<String>, Long> findingCounts(List<TrivyFinding> findings) {
    Map<List<String>, Long> counts = new LinkedHashMap<>();
    for (TrivyFinding finding : findings)
      counts.merge(List.of(finding.id(), finding.severity()), 1L, Long::sum);
    return counts;
  }

  private static List<TrivyFinding> scanFindings(Path file) throws IOException {
    List<TrivyFinding> findings = new ArrayList<>();
    for (Object rawResult : QualitySite.list(QualitySite.read(file).get("Results"))) {
      var result = QualitySite.object(rawResult);
      for (String domain : List.of("Vulnerabilities", "Secrets", "Misconfigurations")) {
        for (Object rawFinding : QualitySite.list(result.getOrDefault(domain, List.of()))) {
          var finding = QualitySite.object(rawFinding);
          if (domain.equals("Misconfigurations")) {
            String status = nonBlankString(finding, "Status");
            if (!Set.of("PASS", "FAIL", "EXCEPTION").contains(status))
              throw new IllegalArgumentException("Invalid Trivy misconfiguration status: " + status);
            if (!status.equals("FAIL")) continue;
          }
          String id = nonBlankString(finding, switch (domain) {
            case "Vulnerabilities" -> "VulnerabilityID";
            case "Secrets" -> "RuleID";
            default -> "ID";
          });
          String severity = nonBlankString(finding, "Severity").toUpperCase(Locale.ROOT);
          if (!TRIVY_SEVERITIES.contains(severity))
            throw new IllegalArgumentException("Invalid Trivy severity for " + id + ": " + severity);
          findings.add(new TrivyFinding(id, severity, ""));
        }
      }
    }
    return findings;
  }

  private static List<TrivyFinding> sarifFindings(Path file) throws IOException {
    var report = QualitySite.read(file);
    if (!"2.1.0".equals(report.get("version")))
      throw new IllegalArgumentException("Expected SARIF version 2.1.0");
    var runs = QualitySite.list(report.get("runs"));
    if (runs.isEmpty()) throw new IllegalArgumentException("Trivy SARIF has no scan runs");
    List<TrivyFinding> findings = new ArrayList<>();
    for (Object rawRun : runs) {
      var run = QualitySite.object(rawRun);
      Map<String, Map<String, Object>> rules = new HashMap<>();
      var driver = QualitySite.object(QualitySite.object(run.get("tool")).get("driver"));
      if (!"Trivy".equals(driver.get("name")))
        throw new IllegalArgumentException("Expected a Trivy SARIF run");
      for (Object rule : QualitySite.list(driver.get("rules"))) {
        var r = QualitySite.object(rule);
        String id = nonBlankString(r, "id");
        if (rules.putIfAbsent(id, r) != null)
          throw new IllegalArgumentException("Duplicate Trivy SARIF rule: " + id);
      }
      for (Object rawResult : QualitySite.list(run.get("results"))) {
        var result = QualitySite.object(rawResult);
        String id = nonBlankString(result, "ruleId");
        var rule = rules.get(id);
        if (rule == null) throw new IllegalArgumentException("Missing Trivy SARIF rule: " + id);
        String message = text(result, "message"),
            evidence =
                message
                    + "\n"
                    + text(rule, "help")
                    + "\n"
                    + text(rule, "fullDescription")
                    + "\n"
                    + String.join(
                        " ",
                        QualitySite.list(
                                QualitySite.object(rule.getOrDefault("properties", Map.of()))
                                    .getOrDefault("tags", List.of()))
                            .stream()
                            .map(
                                value -> {
                                  if (!(value instanceof String text))
                                    throw new IllegalArgumentException("Invalid severity tag");
                                  return text;
                                })
                            .toList());
        var matcher =
            Pattern.compile(
                    "\\bSeverity:\\s*(CRITICAL|HIGH|MEDIUM|LOW|UNKNOWN)\\b",
                    Pattern.CASE_INSENSITIVE)
                .matcher(evidence);
        Set<String> severities = new HashSet<>();
        while (matcher.find()) severities.add(matcher.group(1).toUpperCase(Locale.ROOT));
        if (severities.size() != 1)
          throw new IllegalArgumentException("Missing or conflicting Trivy SARIF severity for " + id);
        findings.add(new TrivyFinding(id, severities.iterator().next(), message));
      }
    }
    return findings;
  }

  private record TrivyFinding(String id, String severity, String message) {}

  private static String nonBlankString(Map<String, Object> object, String key) {
    if (!(object.get(key) instanceof String value) || value.isBlank())
      throw new IllegalArgumentException("Missing or invalid Trivy field " + key);
    return value;
  }

  static String text(Map<String, Object> object, String field) {
    return Objects.toString(
        QualitySite.object(object.getOrDefault(field, Map.of())).getOrDefault("text", ""));
  }

  static String required(Map<String, String> env, String key) {
    String value = env.get(key);
    if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
    return value;
  }
}
