package com.taxonomy.tooling;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.w3c.dom.Element;

/**
 * Original-report quality publication. No domain data, workflow mutation or fallback to newer
 * sources.
 */
final class QualitySite {
  static final List<String> REQUIRED =
      List.of(
          "index.html",
          "quality-summary.json",
          "tests/badge.json",
          "tests/surefire-report.html",
          "coverage/badge.json",
          "coverage/index.html",
          ".nojekyll");
  static final List<String> PLUGINS =
      List.of(
          "TEST-com.taxonomy.build.ExternalPluginPackagedIT.xml",
          "TEST-com.taxonomy.build.OptionalFeaturePackagedIT.xml");
  static final String JS = "TEST-com.taxonomy.tooling.JavascriptContractTest.xml";

  static int run(String command, String[] raw, Path cwd, PrintStream out, PrintStream err) {
    try {
      List<String> unique = new ArrayList<>();
      Map<String, Object> tools = new TreeMap<>();
      for (int i = 0; i < raw.length; i++) {
        if (raw[i].equals("--tool")) {
          if (++i >= raw.length) throw new IllegalArgumentException("Missing --tool value");
          String[] pair = raw[i].split("=", 2);
          if (pair.length != 2 || pair[0].isBlank() || pair[1].isBlank())
            throw new IllegalArgumentException("tool metadata must use NAME=VALUE");
          tools.put(pair[0].strip(), pair[1].strip());
        } else unique.add(raw[i]);
      }
      var args = TaxonomyTooling.Arguments.parse(unique.toArray(String[]::new));
      if (command.equals("generate-quality-site")) {
        var result =
            generate(
                args.path("root", cwd.resolve("target/quality-reports")),
                args.required("commit").strip(),
                args.optionalOrDefault("source-tree", "unknown").strip(),
                args.optionalOrDefault("build-id", "local").strip(),
                args.optionalOrDefault("generated-at", Instant.now().toString()),
                tools);
        out.println(FlatJson.pretty(result));
        return 0;
      }
      String commit = args.required("expected-commit");
      if (args.optional("root") != null) {
        verifyLocal(args.path("root", cwd), commit);
        out.println("Local quality evidence verified for " + commit + ".");
        return 0;
      }
      String base = args.required("base-url");
      int attempts = Math.max(1, Integer.parseInt(args.optionalOrDefault("attempts", "30")));
      long interval =
          (long)
              (1000
                  * Math.max(
                      0, Double.parseDouble(args.optionalOrDefault("interval-seconds", "10"))));
      String failure = "publication did not answer";
      for (int attempt = 1; attempt <= attempts; attempt++) {
        try {
          verifyRemote(base, commit);
          out.println(
              "Published quality evidence verified for " + commit + " on attempt " + attempt + ".");
          return 0;
        } catch (IOException | IllegalArgumentException e) {
          failure = e.getMessage();
        }
        out.println(
            "Quality publication attempt " + attempt + "/" + attempts + " not ready: " + failure);
        if (attempt < attempts) Thread.sleep(interval);
      }
      throw new IllegalArgumentException(
          "published quality evidence verification failed: " + failure);
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      err.println("::error::" + e.getMessage());
      return 1;
    }
  }

  static Map<String, Object> generate(
      Path root, String commit, String tree, String build, String at, Map<String, Object> tools)
      throws Exception {
    List<Path> reports = reports(root.resolve("tests"));
    var tests = totals(reports);
    var coverage = coverage(root.resolve("coverage/jacoco.xml"));
    Map<String, Object> summary =
        map(
            "schemaVersion",
            2,
            "commit",
            commit,
            "verifiedCommit",
            commit,
            "sourceCommit",
            commit,
            "sourceTree",
            tree,
            "buildId",
            build,
            "generatedAt",
            at,
            "tools",
            tools,
            "tests",
            tests,
            "testReports",
            reports.stream().map(p -> relative(root, p)).toList(),
            "coverage",
            coverage);
    write(root.resolve("quality-summary.json"), summary);
    write(
        root.resolve("tests/badge.json"),
        map(
            "schemaVersion",
            1,
            "label",
            "tests",
            "message",
            tests.get("passed") + " passed",
            "color",
            number(tests, "failures") == 0 && number(tests, "errors") == 0
                ? "brightgreen"
                : "red"));
    write(
        root.resolve("coverage/badge.json"),
        map(
            "schemaVersion",
            1,
            "label",
            "reactor coverage",
            "message",
            percent(coverage.get("instructionPercent")) + "%",
            "color",
            color(((Number) coverage.get("instructionPercent")).doubleValue())));
    Files.writeString(root.resolve(".nojekyll"), "");
    String identity =
        "<p>Verified commit: <code>"
            + escape(commit)
            + "</code></p><p>Source tree: <code>"
            + escape(tree)
            + "</code></p><p>Build ID: <code>"
            + escape(build)
            + "</code></p>";
    StringBuilder html =
        new StringBuilder(
            "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\""
                + " content=\"width=device-width, initial-scale=1\"><title>Taxonomy test"
                + " report</title><style>body{font:16px/1.5"
                + " system-ui,sans-serif;max-width:70rem;margin:2rem auto;padding:0"
                + " 1rem}table{border-collapse:collapse}th,td{border:1px solid #bbb;padding:.45rem"
                + " .7rem}code{overflow-wrap:anywhere}</style></head><body><h1>Taxonomy test"
                + " report</h1>"
                + identity
                + "<p>Generated: "
                + escape(at)
                + "</p><table><tr><th>Metric</th><th>Value</th></tr>");
    for (String[] row :
        new String[][] {
          {"Registered tests", "tests"},
          {"Executed", "executed"},
          {"Passed", "passed"},
          {"Skipped", "skipped"},
          {"Failures", "failures"},
          {"Errors", "errors"}
        })
      html.append("<tr><td>")
          .append(row[0])
          .append("</td><td>")
          .append(tests.get(row[1]))
          .append("</td></tr>");
    for (String metric : List.of("instruction", "line", "branch", "method", "class"))
      html.append("<tr><td>")
          .append(Character.toUpperCase(metric.charAt(0)))
          .append(metric.substring(1))
          .append(" coverage</td><td>")
          .append(percent(coverage.getOrDefault(metric + "Percent", 0)))
          .append("%</td></tr>");
    html.append("</table><h2>Toolchain</h2><table>");
    tools.forEach(
        (k, v) ->
            html.append("<tr><td>")
                .append(escape(k))
                .append("</td><td><code>")
                .append(escape(v.toString()))
                .append("</code></td></tr>"));
    html.append(
        "</table><p><a href=\"../quality-summary.json\">Machine-readable quality"
            + " summary</a></p></body></html>");
    Files.writeString(root.resolve("tests/surefire-report.html"), html);
    Files.writeString(
        root.resolve("index.html"),
        "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\""
            + " content=\"width=device-width, initial-scale=1\"><title>Taxonomy verified quality"
            + " reports</title></head><body><h1>Taxonomy verified quality reports</h1>"
            + identity
            + "<ul><li><a href=\"tests/surefire-report.html\">Test report</a></li><li><a"
            + " href=\"coverage/index.html\">JaCoCo coverage report</a></li><li><a"
            + " href=\"quality-summary.json\">Machine-readable provenance and"
            + " metrics</a></li></ul></body></html>");
    return summary;
  }

  static Map<String, Object> coverage(Path path) throws IOException {
    Map<String, Object> result = new LinkedHashMap<>();
    for (Element counter :
        XmlSupport.children(XmlSupport.parse(path).getDocumentElement(), "counter")) {
      String metric = counter.getAttribute("type").toLowerCase(Locale.ROOT);
      if (metric.isEmpty()) continue;
      long missed = Long.parseLong(counter.getAttribute("missed")),
          covered = Long.parseLong(counter.getAttribute("covered"));
      if (missed < 0 || covered < 0)
        throw new IllegalArgumentException("Negative coverage counter");
      result.put(metric + "Missed", missed);
      result.put(metric + "Covered", covered);
      result.put(
          metric + "Percent",
          missed + covered == 0
              ? 100.0
              : new java.math.BigDecimal(100.0 * covered / (missed + covered))
                  .setScale(2, java.math.RoundingMode.HALF_EVEN)
                  .doubleValue());
    }
    if (!result.containsKey("instructionPercent"))
      throw new IllegalArgumentException("aggregate instruction counter missing in " + path);
    return result;
  }

  static Map<String, Object> totals(List<Path> reports) throws IOException {
    if (reports.isEmpty()) throw new IllegalArgumentException("no JUnit XML reports found");
    Map<String, Object> result = map("tests", 0L, "failures", 0L, "errors", 0L, "skipped", 0L);
    for (Path p : reports) {
      var counts = JunitOutcomeVerifier.validate(XmlSupport.parse(p).getDocumentElement());
      counts.forEach((k, v) -> result.put(k, number(result, k) + v));
    }
    if (number(result, "tests") <= 0)
      throw new IllegalArgumentException("JUnit reports contained zero tests");
    result.put("executed", number(result, "tests") - number(result, "skipped"));
    result.put(
        "passed",
        number(result, "executed") - number(result, "failures") - number(result, "errors"));
    result.put("reportFiles", (long) reports.size());
    return result;
  }

  static void verifyLocal(Path root, String commit) throws Exception {
    for (String file : REQUIRED)
      if (!Files.isRegularFile(root.resolve(file)))
        throw new IllegalArgumentException("quality publication is missing: " + file);
    var summary = read(root.resolve("quality-summary.json"));
    verifyPayloads(
        summary,
        read(root.resolve("tests/badge.json")),
        read(root.resolve("coverage/badge.json")),
        commit);
    List<Path> files = reports(root.resolve("tests"));
    verifyInventory(summary, files.stream().map(p -> relative(root, p)).toList());
    requireSameCounts(
        object(summary.get("tests")), totals(files), "raw JUnit outcomes differ from summary");
    verifyApplicationShards(root, summary);
    if (Files.exists(root.resolve("evidence/plugin-profile"))
        || Files.exists(root.resolve("evidence/application-test-shards")))
      verifyDedicated(root, summary);
  }

  static void verifyPayloads(
      Map<String, Object> summary,
      Map<String, Object> test,
      Map<String, Object> badge,
      String expected) {
    String actual =
        Objects.toString(summary.getOrDefault("commit", summary.get("verifiedCommit")), "")
            .strip()
            .toLowerCase(Locale.ROOT);
    if (expected.isBlank() || !actual.equals(expected.strip().toLowerCase(Locale.ROOT)))
      throw new IllegalArgumentException("quality report commit does not match " + expected);
    for (String key : List.of("generatedAt", "sourceTree", "buildId", "tests", "coverage", "tools"))
      if (!summary.containsKey(key))
        throw new IllegalArgumentException(
            "quality summary is missing required metadata field " + key);
    var tests = object(summary.get("tests"));
    long registered = number(tests, "tests"),
        executed = number(tests, "executed"),
        passed = number(tests, "passed"),
        skipped = number(tests, "skipped"),
        failures = number(tests, "failures"),
        errors = number(tests, "errors");
    if (Math.min(
            Math.min(registered, executed),
            Math.min(passed, Math.min(skipped, Math.min(failures, errors))))
        < 0) throw new IllegalArgumentException("negative test totals");
    if (executed != registered - skipped)
      throw new IllegalArgumentException("executed-test total is inconsistent");
    if (passed != executed - failures - errors)
      throw new IllegalArgumentException("passed-test total is inconsistent");
    if (!Objects.equals(test.get("message"), passed + " passed"))
      throw new IllegalArgumentException("test badge does not match summary");
    if (!Objects.equals(
        badge.get("message"),
        percent(object(summary.get("coverage")).get("instructionPercent")) + "%"))
      throw new IllegalArgumentException("coverage badge does not match summary");
  }

  static boolean compatibleBuild(String producer, Object publication) {
    if (!(publication instanceof String target) || producer.isBlank() || target.isBlank())
      return false;
    if (producer.equals(target) && !producer.matches("[0-9]+\\..*")) return true;
    return producer.matches("[1-9][0-9]*\\.[1-9][0-9]*")
        && target.matches("[1-9][0-9]*\\.[1-9][0-9]*")
        && producer.split("\\.")[0].equals(target.split("\\.")[0])
        && new java.math.BigInteger(producer.split("\\.")[1])
                .compareTo(new java.math.BigInteger(target.split("\\.")[1]))
            <= 0;
  }

  static void verifyApplicationShards(Path root, Map<String, Object> summary) throws Exception {
    Path evidence = root.resolve("evidence/application-test-shards");
    if (!Files.isDirectory(evidence)) return;
    for (String shard : List.of("core", "browser", "recovery", "scenario")) {
      Path manifestPath = evidence.resolve(shard + "/manifest.json");
      if (!Files.isRegularFile(manifestPath))
        throw new IllegalArgumentException("application shard manifest is missing: " + shard);
      var manifest = read(manifestPath);
      String build = Objects.toString(manifest.get("buildId"), "");
      String suffix = "." + shard;
      if (number(manifest, "schemaVersion") != 1
          || !shard.equals(manifest.get("shard"))
          || !Objects.equals(manifest.get("commit"), summary.get("commit"))
          || !Objects.equals(manifest.get("sourceTree"), summary.get("sourceTree"))
          || !build.endsWith(suffix)
          || !compatibleBuild(
              build.substring(0, build.length() - suffix.length()), summary.get("buildId")))
        throw new IllegalArgumentException(
            "application shard source/build metadata differs: " + shard);
      var coverage = object(manifest.get("coverage"));
      if (!"coverage.exec".equals(coverage.get("path")))
        throw new IllegalArgumentException("application shard coverage metadata is invalid");
      digest(
          evidence.resolve(shard + "/coverage.exec"), coverage.get("sha256"), "application shard");
      var entries = list(manifest.get("reports"));
      if (entries.isEmpty())
        throw new IllegalArgumentException("application shard report inventory is empty");
      Path destination =
          root.resolve(
              shard.equals("core")
                  ? "tests/taxonomy-app/target"
                  : "tests/application-test-shards/" + shard);
      Set<Path> originals = new HashSet<>();
      for (Object entry : entries) {
        var report = object(entry);
        String relative = reportPath(report.get("path"));
        Path path = destination.resolve(relative.substring(6));
        if (!originals.add(path))
          throw new IllegalArgumentException("application shard repeats an original report");
        digest(path, report.get("sha256"), "application shard");
        var counts = JunitOutcomeVerifier.validate(XmlSupport.parse(path).getDocumentElement());
        for (var c : counts.entrySet())
          if (number(report, c.getKey()) != c.getValue())
            throw new IllegalArgumentException("application shard original outcomes differ");
      }
      if (!originals.equals(new HashSet<>(reports(destination))))
        throw new IllegalArgumentException("application shard original report inventory differs");
    }
  }

  static void verifyDedicated(Path root, Map<String, Object> summary) throws Exception {
    var manifest = read(root.resolve("evidence/plugin-profile/manifest.json"));
    if (!Objects.equals(manifest.get("commit"), summary.get("commit"))
        || !Objects.equals(manifest.get("sourceTree"), summary.get("sourceTree"))
        || !compatibleBuild(Objects.toString(manifest.get("buildId"), ""), summary.get("buildId")))
      throw new IllegalArgumentException("dedicated plugin source/build metadata differs");
    var hashes = object(manifest.get("reports"));
    if (!hashes.keySet().equals(new HashSet<>(PLUGINS)))
      throw new IllegalArgumentException("dedicated plugin report inventory differs");
    List<Path> all = reports(root.resolve("tests"));
    for (String name : PLUGINS) {
      Path path = root.resolve("tests/plugin-profile/target/failsafe-reports/" + name);
      executed(path);
      digest(path, hashes.get(name), "dedicated plugin");
      if (all.stream().filter(p -> p.getFileName().toString().equals(name)).count() != 1)
        throw new IllegalArgumentException("dedicated plugin report has more than one owner");
    }
    Path javascript = root.resolve("tests/ui-contracts/target/surefire-reports/" + JS);
    executed(javascript);
    for (Path p : all)
      if (p.getFileName().toString().equals(JS)) {
        var doc = XmlSupport.parse(p).getDocumentElement();
        boolean browser =
            XmlSupport.descendants(doc, "property").stream()
                .anyMatch(
                    prop ->
                        prop.getAttribute("name").equals("taxonomy.javascript.mode")
                            && prop.getAttribute("value").equals("browser"));
        if (p.equals(javascript) ? browser : (!p.startsWith(root.resolve("tests/ui")) || !browser))
          throw new IllegalArgumentException(
              "JavaScript contracts have more than one owner or browser mode");
      }
  }

  static void executed(Path path) throws IOException {
    var doc = XmlSupport.parse(path).getDocumentElement();
    var counts = JunitOutcomeVerifier.validate(doc);
    if (counts.get("tests") <= 0
        || counts.get("skipped") + counts.get("failures") + counts.get("errors") != 0)
      throw new IllegalArgumentException("dedicated report must execute every case successfully");
    String expected =
        path.getFileName().toString().replaceFirst("^TEST-", "").replaceFirst("\\.xml$", "");
    if (XmlSupport.descendants(doc, "testcase").stream()
        .anyMatch(c -> !expected.equals(c.getAttribute("classname"))))
      throw new IllegalArgumentException("dedicated report class differs");
  }

  static void verifyRemote(String base, String commit) throws Exception {
    var summary = FlatJson.parseObject(fetch(remote(base, "quality-summary.json", commit)));
    verifyPayloads(
        summary,
        FlatJson.parseObject(fetch(remote(base, "tests/badge.json", commit))),
        FlatJson.parseObject(fetch(remote(base, "coverage/badge.json", commit))),
        commit);
    var inventory = list(summary.get("testReports"));
    List<String> paths = new ArrayList<>();
    Map<String, Object> total = map("tests", 0L, "failures", 0L, "errors", 0L, "skipped", 0L);
    for (Object entry : inventory) {
      String p = reportPath(entry);
      paths.add(p);
      var counts =
          JunitOutcomeVerifier.validate(
              XmlSupport.parse(
                      new ByteArrayInputStream(
                          fetch(remote(base, p, commit)).getBytes(StandardCharsets.UTF_8)))
                  .getDocumentElement());
      counts.forEach((k, v) -> total.put(k, number(total, k) + v));
    }
    verifyInventory(summary, paths);
    total.put("executed", number(total, "tests") - number(total, "skipped"));
    total.put(
        "passed", number(total, "executed") - number(total, "failures") - number(total, "errors"));
    total.put("reportFiles", (long) paths.size());
    requireSameCounts(
        object(summary.get("tests")), total, "raw JUnit outcomes differ from summary");
    for (String p : List.of("index.html", "tests/surefire-report.html"))
      if (!fetch(remote(base, p, commit))
          .toLowerCase(Locale.ROOT)
          .contains(commit.toLowerCase(Locale.ROOT)))
        throw new IllegalArgumentException("published HTML does not identify expected commit");
  }

  static String fetch(URI uri) throws Exception {
    var request =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/json,text/html;q=0.9,*/*;q=0.8")
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .header("User-Agent", "taxonomy-quality-publication-check")
            .GET()
            .build();
    var response =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build()
            .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() < 200 || response.statusCode() >= 300)
      throw new IOException("Quality publication HTTP " + response.statusCode());
    return response.body();
  }

  static URI remote(String base, String path, String commit) {
    URI target = URI.create(base.replaceAll("/+$", "") + "/").resolve(path);
    return URI.create(
        target.toString() + "?verified=" + URLEncoder.encode(commit, StandardCharsets.UTF_8));
  }

  static void verifyInventory(Map<String, Object> summary, List<String> paths) {
    var expected = list(summary.get("testReports"));
    if (expected.isEmpty()
        || new HashSet<>(expected).size() != expected.size()
        || !new HashSet<>(expected).equals(new HashSet<>(paths)))
      throw new IllegalArgumentException("raw JUnit report inventory differs from summary");
    expected.forEach(QualitySite::reportPath);
  }

  static String reportPath(Object value) {
    if (!(value instanceof String p)
        || !p.startsWith("tests/")
        || !p.endsWith(".xml")
        || p.contains("\\")
        || p.contains("?")
        || p.contains("#")
        || Arrays.asList(p.split("/", -1)).contains("..")
        || p.contains("%")
        || p.contains(":"))
      throw new IllegalArgumentException("invalid raw JUnit report path: " + value);
    return p;
  }

  static void requireSameCounts(Map<String, Object> a, Map<String, Object> b, String message) {
    if (!a.keySet().equals(b.keySet())) throw new IllegalArgumentException(message);
    for (String key : a.keySet())
      if (number(a, key) != number(b, key)) throw new IllegalArgumentException(message);
  }

  static String sha256(Path path) throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    try (var input = Files.newInputStream(path)) {
      byte[] buffer = new byte[65536];
      for (int n; (n = input.read(buffer)) != -1; ) digest.update(buffer, 0, n);
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  static void digest(Path path, Object expected, String label) throws Exception {
    if (!Files.isRegularFile(path) || !sha256(path).equals(expected))
      throw new IllegalArgumentException(label + " original digest differs: " + path);
  }

  static List<Path> reports(Path root) throws IOException {
    if (!Files.isDirectory(root)) return List.of();
    try (var files = Files.walk(root)) {
      return files
          .filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().matches("TEST-.*\\.xml"))
          .sorted()
          .toList();
    }
  }

  static String relative(Path root, Path path) {
    return root.relativize(path).toString().replace('\\', '/');
  }

  static Map<String, Object> read(Path p) throws IOException {
    return FlatJson.parseObject(Files.readString(p));
  }

  static void write(Path p, Object value) throws IOException {
    Files.createDirectories(p.toAbsolutePath().getParent());
    Files.writeString(p, FlatJson.pretty(value) + "\n");
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> object(Object v) {
    if (!(v instanceof Map<?, ?>)) throw new IllegalArgumentException("Expected JSON object");
    return (Map<String, Object>) v;
  }

  @SuppressWarnings("unchecked")
  static List<Object> list(Object v) {
    if (!(v instanceof List<?>)) throw new IllegalArgumentException("Expected JSON array");
    return (List<Object>) v;
  }

  static long number(Map<String, Object> m, String key) {
    Object v = m.get(key);
    try {
      return new java.math.BigDecimal(Objects.toString(v)).longValueExact();
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid integer " + key);
    }
  }

  static Map<String, Object> map(Object... args) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < args.length; i += 2) m.put((String) args[i], args[i + 1]);
    return m;
  }

  static String percent(Object value) {
    double n = ((Number) value).doubleValue();
    if (!Double.isFinite(n)) throw new IllegalArgumentException("Invalid coverage percentage");
    return new java.math.BigDecimal(n)
        .setScale(2, java.math.RoundingMode.HALF_EVEN)
        .toPlainString();
  }

  static String color(double v) {
    return v >= 90
        ? "brightgreen"
        : v >= 80 ? "green" : v >= 70 ? "yellowgreen" : v >= 60 ? "yellow" : "red";
  }

  static String escape(String text) {
    return text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#x27;");
  }
}
