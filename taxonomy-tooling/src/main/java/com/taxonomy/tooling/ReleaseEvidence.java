package com.taxonomy.tooling;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Small deterministic replacements for workflow payloads; these commands never publish. */
final class ReleaseEvidence {
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
      return trivy(args.path("file", cwd.resolve("trivy-results.sarif")), out, err);
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

  static int trivy(Path file, PrintStream out, PrintStream err) throws IOException {
    var report = QualitySite.read(file);
    List<String> blocking = new ArrayList<>();
    for (Object rawRun : QualitySite.list(report.getOrDefault("runs", List.of()))) {
      var run = QualitySite.object(rawRun);
      Map<String, Map<String, Object>> rules = new HashMap<>();
      var driver =
          QualitySite.object(
              QualitySite.object(run.getOrDefault("tool", Map.of()))
                  .getOrDefault("driver", Map.of()));
      for (Object rule : QualitySite.list(driver.getOrDefault("rules", List.of()))) {
        var r = QualitySite.object(rule);
        rules.put(Objects.toString(r.get("id"), ""), r);
      }
      for (Object rawResult : QualitySite.list(run.getOrDefault("results", List.of()))) {
        var result = QualitySite.object(rawResult);
        String id = Objects.toString(result.getOrDefault("ruleId", "unknown"));
        var rule = rules.getOrDefault(id, Map.of());
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
        if (matcher.find()
            && Set.of("HIGH", "CRITICAL").contains(matcher.group(1).toUpperCase(Locale.ROOT)))
          blocking.add(
              "- [" + matcher.group(1).toUpperCase(Locale.ROOT) + "] " + id + ": " + message);
      }
    }
    if (!blocking.isEmpty()) {
      err.println("Blocking Trivy findings:");
      blocking.forEach(err::println);
      return 1;
    }
    out.println("No HIGH or CRITICAL Trivy findings detected.");
    return 0;
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
