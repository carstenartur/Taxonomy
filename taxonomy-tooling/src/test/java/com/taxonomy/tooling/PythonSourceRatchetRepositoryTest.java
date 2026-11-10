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
      for (String line : Files.readString(path).lines().toList()) {
        String text = line.strip();
        if (text.startsWith("#")
            || text.startsWith("//")
            || text.startsWith("*")
            || text.startsWith("<!--")) continue;
        // Literal negative fixture/guard strings are not executable Java/JavaScript calls.
        if (java && !text.matches(".*(?:ProcessBuilder|Runtime\\.getRuntime|\\.exec\\().*"))
          continue;
        if (js && !text.matches(".*(?:spawn|exec|setup-python).*")) continue;
        if (COMMAND.matcher(text).find()) failures.add(relative + ": " + text);
      }
    }
    assertThat(failures)
        .as("Productive tooling must use Java/Maven and existing shell orchestration")
        .isEmpty();
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
