package com.taxonomy.tooling;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Verifies an archived source/artifact inventory. Validation has no publishing side effects. */
final class EvidenceManifest {
  static final Set<String> RELEASE_KINDS =
      Set.of(
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
          "deployment");
  static final Set<String> RELEASE_GATES =
      Set.of(
          "ci",
          "database-postgresql",
          "database-sqlserver",
          "database-oracle",
          "codeql",
          "security",
          "ui",
          "packaged-plugins");

  static int run(String command, String[] raw, Path cwd, PrintStream out, PrintStream err) {
    try {
      var args = TaxonomyTooling.Arguments.parse(raw);
      Path root = args.path("root", cwd).toAbsolutePath().normalize();
      Path file = args.path("file", root.resolve("evidence-manifest.json"));
      String commit = args.required("expected-commit"), tree = args.required("expected-tree");
      if (command.equals("write-quality-manifest")) {
        QualitySite.verifyLocal(root, commit);
        var artifacts = new ArrayList<Map<String, Object>>();
        for (String[] entry :
            new String[][] {
              {"quality", "quality-summary.json"},
              {"coverage", "coverage/jacoco.xml"},
              {"ui", "evidence/ui-verification-summary.json"}
            }) artifacts.add(artifact(root, entry[0], entry[1], commit));
        for (Path report : QualitySite.reports(root.resolve("tests")))
          artifacts.add(artifact(root, "junit", QualitySite.relative(root, report), commit));
        QualitySite.write(
            file,
            QualitySite.map(
                "schemaVersion",
                1,
                "profile",
                "quality",
                "sourceCommit",
                commit,
                "sourceTree",
                tree,
                "publication",
                false,
                "artifacts",
                artifacts,
                "gates",
                List.of()));
      }
      var manifest = QualitySite.read(file);
      String verification = args.optionalOrDefault("verification-commit", commit),
          verificationTree = tree;
      if (!verification.matches("[0-9a-f]{40}")
          || !verification.equals(manifest.getOrDefault("verificationCommit", commit)))
        throw new IllegalArgumentException("verification commit differs");
      if (!verification.equals(commit)) {
        Path repository = args.path("repository-root", cwd).toAbsolutePath().normalize();
        ReleaseHistoryVerifier.verifyRelease(repository, commit, verification);
        if (!GitSupport.require(repository, "rev-parse", commit + "^{tree}").strip().equals(tree))
          throw new IllegalArgumentException("release source tree differs");
        verificationTree =
            GitSupport.require(repository, "rev-parse", verification + "^{tree}").strip();
      }
      if (!verificationTree.equals(manifest.getOrDefault("verificationTree", tree)))
        throw new IllegalArgumentException("verification tree differs");
      boolean releaseProfile = "release".equals(manifest.get("profile"));
      verify(
          root,
          manifest,
          commit,
          tree,
          verification,
          verificationTree,
          releaseProfile ? args.required("expected-image-digest") : null,
          releaseProfile ? args.required("expected-onnx-image-digest") : null);
      out.println("Source/artifact evidence manifest verified for " + commit + ".");
      return 0;
    } catch (Exception e) {
      err.println("::error::Evidence manifest: " + e.getMessage());
      return 1;
    }
  }

  static Map<String, Object> artifact(Path root, String kind, String relative, String commit)
      throws Exception {
    return QualitySite.map(
        "kind",
        kind,
        "path",
        relative,
        "sourceCommit",
        commit,
        "sha256",
        QualitySite.sha256(safe(root, relative)));
  }

  static void verify(
      Path root,
      Map<String, Object> manifest,
      String commit,
      String tree,
      String verification,
      String verificationTree,
      String imageDigest,
      String onnxDigest)
      throws Exception {
    if (!commit.matches("[0-9a-f]{40}") || !tree.matches("[0-9a-f]{40}"))
      throw new IllegalArgumentException("Expected canonical full commit/tree identities");
    if (QualitySite.number(manifest, "schemaVersion") != 1
        || !commit.equals(manifest.get("sourceCommit"))
        || !tree.equals(manifest.get("sourceTree"))
        || !(manifest.get("publication") instanceof Boolean))
      throw new IllegalArgumentException("source/tree/schema/publication identity differs");
    String profile = Objects.toString(manifest.get("profile"), "");
    Set<String> required =
        profile.equals("release")
            ? RELEASE_KINDS
            : profile.equals("quality") ? Set.of("quality", "junit", "coverage", "ui") : null;
    if (required == null) throw new IllegalArgumentException("Unknown evidence profile");
    Set<String> kinds = new HashSet<>(), paths = new HashSet<>();
    Set<Path> reports = new HashSet<>(), expectedReports = new HashSet<>();
    Set<Path> coverageFiles = new HashSet<>(), expectedCoverage = new HashSet<>();
    Path sbom = null, vex = null;
    for (Object raw : QualitySite.list(manifest.get("artifacts"))) {
      var entry = QualitySite.object(raw);
      String kind = Objects.toString(entry.get("kind"), ""),
          path = Objects.toString(entry.get("path"), "");
      if (!paths.add(path)
          || !RELEASE_KINDS.contains(kind)
          || !(commit.equals(entry.get("sourceCommit"))
              || verification.equals(entry.get("sourceCommit"))))
        throw new IllegalArgumentException("duplicate/unbound artifact: " + path);
      Path actual = safe(root, path);
      QualitySite.digest(actual, entry.get("sha256"), "evidence artifact");
      kinds.add(kind);
      if (kind.equals("sbom")) sbom = actual;
      if (kind.equals("vex")) vex = actual;
      if (kind.equals("deployment"))
        DeploymentVerifier.verifyReceipt(
            QualitySite.read(actual),
            entry.get("sourceCommit").toString(),
            Boolean.TRUE.equals(manifest.get("publication")));
      if (kind.equals("image") || kind.equals("image-onnx")) {
        if (!commit.equals(entry.get("sourceCommit")))
          throw new IllegalArgumentException("Image receipt row source differs");
        ReleaseEvidence.verifyImageReceipt(
            QualitySite.read(actual),
            commit,
            kind.equals("image") ? imageDigest : onnxDigest,
            kind.equals("image") ? "standard" : "onnx");
      }
      if (kind.equals("security")) {
        var sarif = QualitySite.read(actual);
        if (!"2.1.0".equals(sarif.get("version")) || !sarif.containsKey("runs"))
          throw new IllegalArgumentException("Security artifact is not recorded SARIF evidence");
        QualitySite.list(sarif.get("runs"));
        var diagnostics = new java.io.ByteArrayOutputStream();
        var output = new PrintStream(diagnostics);
        if (ReleaseEvidence.trivy(actual, output, output) != 0)
          throw new IllegalArgumentException(
              "Archived security evidence contains blocking findings");
      }

      if (Set.of("quality", "ui").contains(kind) && !verification.equals(entry.get("sourceCommit")))
        throw new IllegalArgumentException(
            "Artifact row source differs from its verification payload: " + kind);
      if (kind.equals("junit") && !reports.add(actual))
        throw new IllegalArgumentException("Repeated JUnit file: " + path);
      if (kind.equals("coverage") && !coverageFiles.add(actual))
        throw new IllegalArgumentException("Repeated coverage file: " + path);
      if (kind.equals("ui")) {
        var ui = QualitySite.read(actual);
        if (!verification.equals(ui.get("sourceCommit"))
            || !verificationTree.equals(ui.get("sourceTree"))
            || !"passed".equals(ui.get("outcome"))
            || QualitySite.number(ui, "scenarioCount") <= 0
            || QualitySite.number(ui, "shardCount") <= 0)
          throw new IllegalArgumentException("UI evidence source/outcome differs");
        var shards = QualitySite.list(ui.get("shards"));
        long scenarios = 0;
        Set<String> ids = new HashSet<>();
        for (Object rawShard : shards) {
          var shard = QualitySite.object(rawShard);
          if (Objects.toString(shard.get("id"), "").isBlank()
              || !ids.add(Objects.toString(shard.get("id"), ""))
              || QualitySite.number(shard, "scenarioCount") < 0
              || !"passed".equals(shard.get("outcome")))
            throw new IllegalArgumentException("UI shard evidence differs");
          scenarios += QualitySite.number(shard, "scenarioCount");
        }
        if (shards.size() != QualitySite.number(ui, "shardCount")
            || scenarios != QualitySite.number(ui, "scenarioCount"))
          throw new IllegalArgumentException("UI evidence metrics differ");
      }

      if (kind.equals("quality")) {
        var quality = QualitySite.read(actual);
        if (!verification.equals(quality.get("sourceCommit"))
            || !verification.equals(quality.get("commit"))
            || !verificationTree.equals(quality.get("sourceTree")))
          throw new IllegalArgumentException("quality summary source differs");
        var tests = QualitySite.object(quality.get("tests"));
        long registered = QualitySite.number(tests, "tests"),
            skipped = QualitySite.number(tests, "skipped"),
            failures = QualitySite.number(tests, "failures"),
            errors = QualitySite.number(tests, "errors"),
            executed = QualitySite.number(tests, "executed"),
            passed = QualitySite.number(tests, "passed");
        if (registered <= 0
            || skipped < 0
            || skipped > registered
            || executed <= 0
            || passed <= 0
            || failures != 0
            || errors != 0
            || executed != registered - skipped
            || passed != executed)
          throw new IllegalArgumentException("quality summary metrics differ");
        Path qualityRoot = actual.getParent();
        if (!actual.equals(qualityRoot.resolve("quality-summary.json")))
          throw new IllegalArgumentException("Unexpected quality summary path: " + path);
        QualitySite.verifyLocal(qualityRoot, verification);
        for (Object report : QualitySite.list(quality.get("testReports")))
          expectedReports.add(qualityRoot.resolve(QualitySite.reportPath(report)));
        expectedCoverage.add(qualityRoot.resolve("coverage/jacoco.xml"));
        var recordedCoverage = QualitySite.object(quality.get("coverage"));
        var measuredCoverage = QualitySite.coverage(qualityRoot.resolve("coverage/jacoco.xml"));
        if (!recordedCoverage.keySet().equals(measuredCoverage.keySet()))
          throw new IllegalArgumentException("coverage metric inventory differs");
        for (String key : measuredCoverage.keySet())
          if (new java.math.BigDecimal(recordedCoverage.get(key).toString())
                  .compareTo(new java.math.BigDecimal(measuredCoverage.get(key).toString()))
              != 0) throw new IllegalArgumentException("raw coverage metrics differ: " + key);
      }
    }
    if (sbom != null && vex != null) SbomCompanionGenerator.verifyBinding(sbom, vex);
    if (!kinds.containsAll(required))
      throw new IllegalArgumentException(
          "Missing required artifact kinds: " + difference(required, kinds));
    if (!reports.equals(expectedReports) || !coverageFiles.equals(expectedCoverage))
      throw new IllegalArgumentException(
          "Manifest JUnit or coverage inventory differs from validated files");
    Set<String> gates = new HashSet<>();
    for (Object raw : QualitySite.list(manifest.get("gates"))) {
      var gate = QualitySite.object(raw);
      String name = Objects.toString(gate.get("name"), "");
      if (!gates.add(name)
          || !RELEASE_GATES.contains(name)
          || !verification.equals(gate.get("sourceCommit"))
          || !"success".equals(gate.get("outcome")))
        throw new IllegalArgumentException(
            "Missing, duplicate, skipped/cancelled or foreign gate: " + name);
    }
    if (profile.equals("release") && !gates.equals(RELEASE_GATES))
      throw new IllegalArgumentException(
          "Missing required gates: " + difference(RELEASE_GATES, gates));
  }

  static Path safe(Path root, String relative) throws IOException {
    if (relative.isBlank()
        || relative.contains("\\")
        || relative.contains(":")
        || relative.contains("%")
        || Path.of(relative).isAbsolute()
        || Arrays.asList(relative.split("/", -1)).contains(".."))
      throw new IllegalArgumentException("Invalid artifact path");
    Path actual = root.resolve(relative).normalize();
    if (!actual.startsWith(root)
        || !Files.isRegularFile(actual)
        || !actual.toRealPath().startsWith(root.toRealPath()))
      throw new IllegalArgumentException("Missing or escaping artifact: " + relative);
    return actual;
  }

  static Set<String> difference(Set<String> a, Set<String> b) {
    var result = new TreeSet<>(a);
    result.removeAll(b);
    return result;
  }
}
