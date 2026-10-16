package com.taxonomy.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

/** Exercises the real smoke-script functions with a local, signalable kubectl stand-in. */
final class HelmPortForwardChecks {
    private HelmPortForwardChecks() { }

    static void check(String kind) throws Exception {
        if (!kind.equals("broker") && !kind.equals("web")) throw new IllegalArgumentException(kind);
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("deploy/helm/taxonomy/artemis-constrained-smoke.sh")))
            root = root.getParent();
        if (root == null) throw new IllegalStateException("Repository not found");
        String source = Files.readString(root.resolve("deploy/helm/taxonomy/artemis-constrained-smoke.sh"));
        String wrapper = source.lines().filter(line -> line.startsWith("kubectl() {")).findFirst().orElseThrow();
        String name = "start_" + kind + "_forward";
        int from = source.indexOf(name + "() {");
        int end = source.indexOf("\n}", from);
        if (from < 0 || end < 0) throw new AssertionError("Missing smoke function " + name);
        Path directory = Files.createTempDirectory("taxonomy-forward-test-");
        Process process = null;
        try {
            Path executable = directory.resolve("kubectl");
            Files.writeString(executable, """
                    #!/usr/bin/env bash
                    set -euo pipefail
                    [[ "$1" == --context && "$2" == fixture-context ]] || exit 91
                    shift 2
                    case "$1" in
                      get) printf 'fixture-coordinator\n' ;;
                      port-forward)
                        printf '%s\n' "$$" >> "$TEST_FORWARD_PIDS"
                        exec sleep 30 ;;
                      *) exit 92 ;;
                    esac
                    """);
            if (!executable.toFile().setExecutable(true)) throw new IllegalStateException("Cannot create fixture executable");
            String variable = kind.toUpperCase(java.util.Locale.ROOT) + "_FORWARD_PID";
            Path script = directory.resolve("check.sh");
            Files.writeString(script, """
                    #!/usr/bin/env bash
                    set -euo pipefail
                    KUBE_CONTEXT=fixture-context
                    NAMESPACE=fixture-namespace
                    WEB_SELECTOR=fixture-selector
                    BROKER_FORWARD_PID= WEB_FORWARD_PID=
                    BACKGROUND_PIDS=()
                    fail() { printf '%s\n' "$*" >&2; exit 1; }
                    cleanup() {
                      if [[ -f "$TEST_FORWARD_PIDS" ]]; then
                        while read -r owned; do kill "$owned" 2>/dev/null || true; done < "$TEST_FORWARD_PIDS"
                      fi
                      for owned in "${BACKGROUND_PIDS[@]}"; do kill "$owned" 2>/dev/null || true; wait "$owned" 2>/dev/null || true; done
                    }
                    trap cleanup EXIT
                    await_count() {
                      for ((i=0;i<100;i++)); do
                        if [[ -f "$TEST_FORWARD_PIDS" ]] && [[ $(wc -l < "$TEST_FORWARD_PIDS") -ge "$1" ]]; then return; fi
                        sleep .01
                      done
                      fail 'Fixture process did not start'
                    }
                    """ + wrapper + "\n" + source.substring(from, end + 2) + "\n"
                    + name + "\nawait_count 1\nfirst=$(head -n 1 \"$TEST_FORWARD_PIDS\")\n"
                    + "[[ ${" + variable + "} == \"$first\" ]] || fail 'Tracked PID is a wrapper, not the port-forward process'\n"
                    + name + "\nawait_count 2\nsecond=$(tail -n 1 \"$TEST_FORWARD_PIDS\")\n"
                    + "[[ ${" + variable + "} == \"$second\" ]] || fail 'Replacement process is not owned'\n"
                    + "if kill -0 \"$first\" 2>/dev/null; then fail 'Previous forward survived replacement'; fi\n"
                    + "kill -0 \"$second\" || fail 'Replacement forward not alive'\n"
                    + "printf 'FORWARD_OWNERSHIP_OK " + kind + "\\n'\n");
            Path log = directory.resolve("result.log");
            var builder = new ProcessBuilder("bash", script.toString()).redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().put("PATH", directory + java.io.File.pathSeparator + System.getenv("PATH"));
            builder.environment().put("EVIDENCE_DIR", directory.toString());
            builder.environment().put("TEST_FORWARD_PIDS", directory.resolve("pids").toString());
            process = builder.start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) throw new AssertionError("Port-forward ownership test timed out: " + kind);
            String output = Files.readString(log);
            if (process.exitValue() != 0 || !output.contains("FORWARD_OWNERSHIP_OK " + kind))
                throw new AssertionError(kind + ": " + output);
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            try (var paths = Files.walk(directory)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        check(args[0]);
        System.out.println("FORWARD_OWNERSHIP_OK " + args[0]);
    }
}
