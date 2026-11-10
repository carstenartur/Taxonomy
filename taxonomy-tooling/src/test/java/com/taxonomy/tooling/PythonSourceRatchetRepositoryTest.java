package com.taxonomy.tooling;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Absolute source/runtime policy after #629. No baseline or permanent exceptions. */
class PythonSourceRatchetRepositoryTest {
  private static final Pattern COMMAND =
      Pattern.compile(
          "(^|[\\s\\\"'`=:/<>])(python(?:[0-9]+(?:\\.[0-9]+)*)?|pytest|pip(?:[0-9]+(?:\\.[0-9]+)*)?|unittest)(?=$|[\\s\\\"'`;<>])|actions/setup-python@");

  // Literals and comments consume a whole token so example command strings outside a
  // process call never become invocations themselves.
  private static final Pattern CODE_TOKEN =
      Pattern.compile(
          "(?s)//[^\\r\\n]*|/\\*.*?\\*/|\"\"\".*?\"\"\"|"
              + "\"(?:\\\\.|[^\"\\\\])*+\"|'(?:\\\\.|[^'\\\\])*+'|`(?:\\\\.|[^`\\\\])*+`|"
              + "[\\w$]+|[^\\s]");
  private static final Set<String> JAVA_CALLS = Set.of("ProcessBuilder", "exec");
  private static final Set<String> JS_CALLS =
      Set.of("spawn", "spawnSync", "exec", "execSync", "execFile", "execFileSync");

  @Test
  void repositoryContainsNoPythonSources() throws Exception {
    var failures = new ArrayList<String>();
    for (Path p : sourceFiles())
      if (p.getFileName().toString().endsWith(".py")) failures.add(p.toString());
    assertThat(failures).as("No Python source, including untracked source, is permitted").isEmpty();
  }

  @Test
  void productiveSourcesCannotExecuteOrSetupPython() throws Exception {
    var failures = new ArrayList<String>();
    Path root = JavascriptJUnitBridge.root();
    for (Path path : sourceFiles()) {
      String name = path.getFileName().toString(),
          relative = root.relativize(path).toString().replace('\\', '/');
      if (relative.contains("/src/test/") || relative.endsWith(".md")) continue;
      boolean java = name.endsWith(".java"),
          js = name.endsWith(".js") || name.endsWith(".mjs") || name.endsWith(".cjs");
      if (!(java
          || js
          || name.endsWith(".sh")
          || name.endsWith(".yml")
          || name.endsWith(".yaml")
          || name.endsWith(".xml")
          || name.endsWith(".toml")
          || name.endsWith(".properties")
          || name.equals("Makefile")
          || name.startsWith("Dockerfile")
          || name.equals("package.json"))) continue;
      String source = Files.readString(path);
      if (java || js) {
        if (invokesPython(source, java ? JAVA_CALLS : JS_CALLS)) failures.add(relative);
      } else {
        source
            .lines()
            .map(String::strip)
            .filter(
                line ->
                    !line.startsWith("#")
                        && !line.startsWith("//")
                        && !line.startsWith("*")
                        && !line.startsWith("<!--"))
            .filter(line -> COMMAND.matcher(line).find())
            .forEach(line -> failures.add(relative + ": " + line));
      }
    }
    assertThat(failures)
        .as("Productive tooling must use Java/Maven and existing shell orchestration")
        .isEmpty();
  }

  private static boolean invokesPython(String source, Set<String> calls) {
    if (!COMMAND.matcher(source).find()) return false;
    var tokens = CODE_TOKEN.matcher(source);
    boolean callName = false;
    int depth = 0;
    while (tokens.find()) {
      String token = tokens.group();
      if (token.startsWith("//") || token.startsWith("/*")) continue;
      if (token.equals("(") && (callName || depth > 0)) depth++;
      else if (token.equals(")") && depth > 0) depth--;
      else if (depth > 0 && COMMAND.matcher(token).find()) return true;
      callName = calls.contains(token);
    }
    return false;
  }

  @Test
  void checksCompleteProcessInvocationsAndSkipsLiteralExamples() {
    assertThat(invokesPython("new ProcessBuilder(\n  List.of(\"python3\", script));", JAVA_CALLS))
        .isTrue();
    assertThat(
            invokesPython(
                "Runtime.getRuntime()\n.exec( /* tool */ \"pip install a\");", JAVA_CALLS))
        .isTrue();
    assertThat(invokesPython("child.spawnSync(\n 'python3.12', args);", JS_CALLS)).isTrue();
    assertThat(invokesPython("exec(\n `pytest -q`);", JS_CALLS)).isTrue();
    assertThat(invokesPython("String example = \"new ProcessBuilder('python3')\";", JAVA_CALLS))
        .isFalse();
    assertThat(
            invokesPython(
                "// spawn('python')\nspawn('node'); const example = `exec('pytest')`;", JS_CALLS))
        .isFalse();
  }

  @Test
  void executableTokensIncludeVersionedInterpretersAndExcludeSubstringNoise() {
    for (String source :
        List.of(
            "python script.py",
            "python2 script.py",
            "python3.12 script.py",
            "pytest -q",
            "pip install package",
            "pip3.12 install package",
            "unittest discover",
            "uses: actions/setup-python@v6",
            "<executable>python3</executable>"))
      assertThat(COMMAND.matcher(source).find()).as(source).isTrue();
    for (String source : List.of("set -euo pipefail", "pipeline", "pythonSourceRatchet"))
      assertThat(COMMAND.matcher(source).find()).as(source).isFalse();
  }

  private static List<Path> sourceFiles() throws Exception {
    Path root = JavascriptJUnitBridge.root();
    try (var files = Files.walk(root)) {
      return files
          .filter(Files::isRegularFile)
          .filter(
              p -> {
                var parts = root.relativize(p);
                for (Path part : parts)
                  if (Set.of(".git", "target", "node_modules").contains(part.toString()))
                    return false;
                return true;
              })
          .toList();
    }
  }
}
