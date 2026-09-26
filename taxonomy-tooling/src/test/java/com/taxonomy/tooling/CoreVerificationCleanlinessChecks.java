package com.taxonomy.tooling;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Executes the real workflow shell with only the external Maven process replaced.
 * The substitute models clean's deletion and verify's output/exit status, not Maven caching.
 * Kept dependency-free so the same regression can also run directly with Java 21.
 */
final class CoreVerificationCleanlinessChecks {
    private static final String CLEAN = "Clean reactor before producing evidence";
    private static final String VERIFY = "Run the canonical Maven verification suite";
    private static final String EVENT = "${{ github.event_name }}";
    private static final List<String> EVIDENCE = List.of(
            "taxonomy-helm-rendered.yaml", "taxonomy-helm-rancher-rke2-rendered.yaml");

    static void assertCleanupOrder(String workflow) {
        String core = core(workflow);
        int cleanup = core.indexOf("      - name: " + CLEAN + "\n");
        int contracts = core.indexOf("      - name: Verify release and delivery contracts\n");
        int helm = core.indexOf("      - name: Validate production and Rancher Helm profiles\n");
        require(cleanup >= 0 && cleanup < contracts && contracts < helm,
                "Non-PR clean must precede contracts and Helm evidence generation");
        String step = step(core, CLEAN);
        require(step.contains("        if: github.event_name != 'pull_request'\n"),
                "Only non-PR invocations should clean the reactor");
        require(script(step).strip().equals("./mvnw -B clean -Dmaven.build.cache.enabled=false"),
                "Cleanup must execute the complete reactor without consulting the build cache");
        require(!script(step(core, VERIFY)).contains(" clean "),
                "Verification must not delete evidence or its open tee output");
    }

    static void assertEvidenceSurvives(String workflow, String event, int mavenStatus) throws Exception {
        require(List.of("push", "pull_request", "workflow_dispatch").contains(event), "Unknown fixture event");
        String core = core(workflow);
        boolean pullRequest = event.equals("pull_request");
        Path directory = Files.createTempDirectory("core-verification-cleanliness-");
        try {
            Files.writeString(directory.resolve("mvnw"), """
                    #!/usr/bin/env bash
                    set -eu
                    printf '%s\\n' "$*" >> calls.txt
                    case " $* " in
                      *' clean '*) rm -rf -- target; echo 'fixture clean complete' ;;
                    esac
                    case " $* " in
                      *' verify '*)
                        mkdir -p target
                        echo 'fixture Maven stdout'
                        echo 'fixture Maven stderr' >&2
                        exit "$TEST_MAVEN_STATUS"
                        ;;
                    esac
                    """);
            require(directory.resolve("mvnw").toFile().setExecutable(true), "Cannot execute Maven fixture");
            Files.createDirectories(directory.resolve("target"));
            Files.writeString(directory.resolve("target/stale-before-build"), "stale");
            // This represents a reusable cache outside target; cleaning must not delete it.
            Files.createDirectories(directory.resolve("build-cache"));
            Files.writeString(directory.resolve("build-cache/entry"), "reusable");
            if (!pullRequest && core.contains("      - name: " + CLEAN + "\n")) {
                require(run(directory, script(step(core, CLEAN)), event, 0, "cleanup") == 0,
                        "Cleanup command failed");
            }
            Files.createDirectories(directory.resolve("target"));
            for (String filename : EVIDENCE) {
                Files.writeString(directory.resolve("target/" + filename), "current Helm evidence");
            }
            int result = run(directory, script(step(core, VERIFY)), event, mavenStatus, "verify");
            require(result == mavenStatus, "Maven exit status was masked by tee: " + result);
            for (String filename : EVIDENCE) {
                Path evidence = directory.resolve("target/" + filename);
                require(Files.isRegularFile(evidence), event + " lost " + filename + " during verification");
                require(Files.readString(evidence).equals("current Helm evidence"), "Helm evidence was replaced");
            }
            Path log = directory.resolve("target/maven-verification.log");
            require(Files.isRegularFile(log), event + " lost the Maven log");
            String output = Files.readString(log);
            require(output.contains("fixture Maven stdout") && output.contains("fixture Maven stderr"),
                    "Both stdout and stderr must survive, including a failed verification");
            require(Files.exists(directory.resolve("target/stale-before-build")) == pullRequest,
                    "Only non-PR builds must remove pre-existing target outputs");
            require(Files.readString(directory.resolve("build-cache/entry")).equals("reusable"),
                    "Clean must preserve the build cache");
            List<String> calls = Files.readAllLines(directory.resolve("calls.txt"));
            long cleans = calls.stream().filter(call -> (" " + call + " ").contains(" clean ")).count();
            require(cleans == (pullRequest ? 0 : 1), "Unexpected number of clean invocations: " + cleans);
            String verify = calls.getLast();
            for (String flag : List.of("verify", "-Pci", "-DrunOnnxTests=true", "-Dtaxonomy.ui.skip=true")) {
                require(List.of(verify.split(" ")).contains(flag), "Missing verification flag " + flag);
            }
            require(verify.contains("-Dmaven.build.cache.skipCache=true") != pullRequest,
                    "Non-PR verification must bypass cache reads; PRs must retain caching");
            require(!verify.contains("skipTests") && !verify.contains("skipITs") && !verify.contains(" -pl "),
                    "The canonical suite must not be weakened");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static int run(Path directory, String script, String event, int status, String name)
            throws Exception {
        Path output = directory.resolve(name + "-process.log");
        var builder = new ProcessBuilder("bash", "--noprofile", "--norc", "-e", "-c",
                script.replace(EVENT, event)).directory(directory.toFile())
                .redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().put("TEST_MAVEN_STATUS", Integer.toString(status));
        builder.environment().remove("BASH_ENV");
        Process process = builder.start();
        try {
            require(process.waitFor(30, TimeUnit.SECONDS), "Workflow shell did not terminate: " + name);
            return process.exitValue();
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor();
            }
        }
    }

    private static String core(String workflow) {
        String normalized = workflow.replace("\r\n", "\n");
        int start = normalized.indexOf("\n  core:\n");
        int end = normalized.indexOf("\n  observability:\n", start);
        require(start >= 0 && end > start, "Cannot locate the core job");
        return normalized.substring(start, end);
    }

    private static String step(String core, String name) {
        String marker = "      - name: " + name + "\n";
        int start = core.indexOf(marker);
        require(start >= 0 && core.indexOf(marker, start + marker.length()) < 0,
                "Expected one workflow step: " + name);
        int end = core.indexOf("\n      - ", start);
        return core.substring(start, end < 0 ? core.length() : end) + "\n";
    }

    private static String script(String step) {
        List<String> lines = step.lines().toList();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (!line.startsWith("        run: ")) continue;
            String value = line.substring("        run: ".length());
            if (!value.equals("|")) return value + "\n";
            StringBuilder result = new StringBuilder();
            for (int next = index + 1; next < lines.size(); next++) {
                String body = lines.get(next);
                if (body.isBlank()) result.append('\n');
                else if (body.startsWith("          ")) result.append(body.substring(10)).append('\n');
                else break;
            }
            return result.toString();
        }
        throw new AssertionError("Missing literal workflow run command");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] arguments) throws Exception {
        require(arguments.length == 1, "Pass the ci-cd.yml path");
        String workflow = Files.readString(Path.of(arguments[0]));
        int failures = 0;
        for (String event : List.of("pull_request", "push", "workflow_dispatch")) {
            for (int status : List.of(0, 23)) {
                try {
                    assertEvidenceSurvives(workflow, event, status);
                    System.out.println("PASS " + event + " Maven exit=" + status);
                } catch (AssertionError failure) {
                    failures++;
                    System.err.println("FAIL " + event + " Maven exit=" + status + ": " + failure.getMessage());
                }
            }
        }
        try { assertCleanupOrder(workflow); System.out.println("PASS cleanup order"); }
        catch (AssertionError failure) { failures++; System.err.println("FAIL " + failure.getMessage()); }
        require(failures == 0, failures + " cleanliness regressions failed");
    }
}
