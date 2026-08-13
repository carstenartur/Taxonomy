package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** JUnit-owned behavioral contract for the exact final release gate helper. */
class ExactReleaseGateBehaviorContractTest {

    private static final String EXPECTED_SHA = "a".repeat(40);
    private static final String DRIFT_SHA = "b".repeat(40);
    private static final Map<String, String> RUN_IDS = Map.of(
            "ci-cd.yml", "101",
            "database-compatibility.yml", "102",
            "codeql.yml", "103",
            "security-scan.yml", "104");

    private static final String FAKE_GIT = """
            #!/usr/bin/env bash
            set -euo pipefail
            state=${FAKE_RELEASE_GATE_STATE:?}
            printf 'git' >> "$state/calls"
            printf '\\t%s' "$@" >> "$state/calls"
            printf '\\n' >> "$state/calls"

            if [[ ${1:-} == fetch ]]; then
              exit 0
            fi
            if [[ ${1:-} == rev-parse && ${2:-} == origin/main ]]; then
              index=$(cat "$state/main-index")
              line=$((index + 1))
              value=$(sed -n "${line}p" "$state/main-shas")
              if [[ -z "$value" ]]; then
                value=$(tail -n 1 "$state/main-shas")
              fi
              printf '%s\\n' "$value"
              printf '%s\\n' "$((index + 1))" > "$state/main-index"
              exit 0
            fi
            printf 'unsupported fake git invocation:' >&2
            printf ' %q' "$@" >&2
            printf '\\n' >&2
            exit 91
            """;

    private static final String FAKE_GH = """
            #!/usr/bin/env bash
            set -euo pipefail
            state=${FAKE_RELEASE_GATE_STATE:?}
            printf 'gh' >> "$state/calls"
            printf '\\t%s' "$@" >> "$state/calls"
            printf '\\n' >> "$state/calls"

            if [[ ${1:-} == run && ${2:-} == list ]]; then
              workflow=''
              dispatched_only=false
              while (($#)); do
                case "$1" in
                  --workflow)
                    workflow=$2
                    shift 2
                    ;;
                  --event)
                    if [[ $2 == workflow_dispatch ]]; then dispatched_only=true; fi
                    shift 2
                    ;;
                  *) shift ;;
                esac
              done
              source=existing
              if [[ $dispatched_only == true ]]; then source=dispatched; fi
              if [[ -s "$state/$source/$workflow" ]]; then
                cat "$state/$source/$workflow"
              fi
              exit 0
            fi

            if [[ ${1:-} == workflow && ${2:-} == run ]]; then
              workflow=$3
              if [[ -s "$state/dispatch-ids/$workflow" ]]; then
                mkdir -p "$state/dispatched"
                cp "$state/dispatch-ids/$workflow" "$state/dispatched/$workflow"
              fi
              exit 0
            fi

            if [[ ${1:-} == run && ${2:-} == view ]]; then
              run_id=$3
              shift 3
              field=''
              while (($#)); do
                if [[ $1 == --json ]]; then
                  field=$2
                  break
                fi
                shift
              done
              if [[ -s "$state/runs/$run_id/$field" ]]; then
                cat "$state/runs/$run_id/$field"
              fi
              exit 0
            fi

            if [[ ${1:-} == run && ${2:-} == watch ]]; then
              run_id=$3
              exit_code=0
              if [[ -s "$state/runs/$run_id/watch_exit" ]]; then
                exit_code=$(cat "$state/runs/$run_id/watch_exit")
              fi
              exit "$exit_code"
            fi

            printf 'unsupported fake gh invocation:' >&2
            printf ' %q' "$@" >&2
            printf '\\n' >&2
            exit 92
            """;

    private static final String FAKE_SLEEP = """
            #!/usr/bin/env sh
            exit 0
            """;

    @Test
    void reusesSuccessfulExactHeadRunsWithoutDispatch(@TempDir Path root)
            throws Exception {
        RunResult result = runHelper(root, null);

        requireSuccess("reuse exact runs", result);
        requireContains(result.output(),
                "All exact release gates passed for " + EXPECTED_SHA);
        List<String> dispatches = dispatchCalls(result);
        if (!dispatches.isEmpty()) {
            throw new AssertionError(
                    "Existing exact runs must not be dispatched: " + dispatches);
        }
    }

    @Test
    void dispatchesOnlyTheMissingWorkflow(@TempDir Path root) throws Exception {
        RunResult result = runHelper(root, state -> {
            Files.delete(state.resolve("existing/database-compatibility.yml"));
            writeLine(state.resolve("dispatch-ids/database-compatibility.yml"), "202");
            writeRun(state, "202", "workflow_dispatch", EXPECTED_SHA,
                    "main", "completed", "success", 0);
            write(state.resolve("main-shas"),
                    EXPECTED_SHA + "\n" + EXPECTED_SHA + "\n" + EXPECTED_SHA + "\n");
        });

        requireSuccess("dispatch missing workflow", result);
        List<String> expected = List.of(
                "gh\tworkflow\trun\tdatabase-compatibility.yml\t--ref\tmain");
        List<String> actual = dispatchCalls(result);
        if (!actual.equals(expected)) {
            throw new AssertionError("Unexpected dispatch set: " + actual);
        }
    }

    @Test
    void failedWorkflowStillReportsFinalStatusAndConclusion(@TempDir Path root)
            throws Exception {
        RunResult result = runHelper(root, state -> {
            Path run = state.resolve("runs/" + RUN_IDS.get("codeql.yml"));
            writeLine(run.resolve("watch_exit"), "1");
            writeLine(run.resolve("conclusion"), "failure");
        });

        requireFailure("failed workflow diagnostics", result,
                "codeql.yml run 103 ended with status=completed "
                        + "conclusion=failure (gh run watch exit=1)");
    }

    @Test
    void unreliableWatchFailsClosedEvenWhenApiStatusIsSuccessful(
            @TempDir Path root) throws Exception {
        RunResult result = runHelper(root, state -> writeLine(
                state.resolve("runs/" + RUN_IDS.get("security-scan.yml")
                        + "/watch_exit"), "2"));

        requireFailure("unreliable watch", result,
                "security-scan.yml run 104 could not be watched reliably "
                        + "(exit=2) despite status=completed conclusion=success");
    }

    @Test
    void finalMainMovementBlocksPublication(@TempDir Path root) throws Exception {
        RunResult result = runHelper(root, state -> write(
                state.resolve("main-shas"), EXPECTED_SHA + "\n" + DRIFT_SHA + "\n"));

        requireFailure("main drift", result,
                "Release gate candidate is " + EXPECTED_SHA
                        + ", but origin/main is " + DRIFT_SHA);
    }

    @Test
    void mismatchedRunBranchIsRejected(@TempDir Path root) throws Exception {
        RunResult result = runHelper(root, state -> writeLine(
                state.resolve("runs/"
                        + RUN_IDS.get("database-compatibility.yml")
                        + "/headBranch"), "feature/x"));

        requireFailure("run identity", result,
                "database-compatibility.yml run 102 verifies branch "
                        + "'feature/x', not main");
    }

    @Test
    void unregisteredDispatchFailsClosed(@TempDir Path root) throws Exception {
        RunResult result = runHelper(root, state -> {
            Files.delete(state.resolve("existing/database-compatibility.yml"));
            write(state.resolve("main-shas"),
                    EXPECTED_SHA + "\n" + EXPECTED_SHA + "\n");
        });

        requireFailure("unregistered dispatch", result,
                "Could not locate or dispatch database-compatibility.yml "
                        + "for exact commit " + EXPECTED_SHA);
    }

    @Test
    void behaviorContractRemainsJUnitOwned() {
        Path repositoryRoot = findRepositoryRoot();
        Path forbiddenPythonRunner = repositoryRoot.resolve(
                ".github/scripts/test-verify-exact-release-gates.py");
        if (Files.exists(forbiddenPythonRunner)) {
            throw new AssertionError(
                    "Exact release gate behavior must remain JUnit-owned; remove "
                            + forbiddenPythonRunner);
        }
    }

    private static RunResult runHelper(
            Path root,
            FixtureCustomizer customizer) throws Exception {
        Path bin = root.resolve("bin");
        Path state = root.resolve("state");
        Files.createDirectories(bin);
        Files.createDirectories(state);
        executable(bin.resolve("git"), FAKE_GIT);
        executable(bin.resolve("gh"), FAKE_GH);
        executable(bin.resolve("sleep"), FAKE_SLEEP);
        baseline(state);
        if (customizer != null) {
            customizer.customize(state);
        }

        Path repositoryRoot = findRepositoryRoot();
        Path helper = repositoryRoot.resolve(
                ".github/scripts/verify-exact-release-gates.sh");
        if (!Files.isRegularFile(helper)) {
            throw new AssertionError("Missing release gate helper: " + helper);
        }

        ProcessBuilder builder = new ProcessBuilder("bash", helper.toString())
                .directory(repositoryRoot.toFile())
                .redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        environment.put("PATH", bin + File.pathSeparator
                + environment.getOrDefault("PATH", ""));
        environment.put("FAKE_RELEASE_GATE_STATE", state.toString());
        environment.put("EXPECTED_MAIN_SHA", EXPECTED_SHA);
        environment.put("RELEASE_GATE_DISCOVERY_ATTEMPTS", "1");
        environment.put("RELEASE_GATE_REGISTRATION_ATTEMPTS", "1");
        environment.put("RELEASE_GATE_POLL_SECONDS", "0");

        Process process = builder.start();
        boolean finished = process.waitFor(20, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError("Release gate helper exceeded 20 seconds");
        }
        String output = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        List<String> calls = Files.readAllLines(
                state.resolve("calls"), StandardCharsets.UTF_8);
        return new RunResult(process.exitValue(), output, calls);
    }

    private static void baseline(Path state) throws Exception {
        write(state.resolve("main-shas"),
                EXPECTED_SHA + "\n" + EXPECTED_SHA + "\n");
        writeLine(state.resolve("main-index"), "0");
        write(state.resolve("calls"), "");
        for (Map.Entry<String, String> entry : RUN_IDS.entrySet()) {
            writeLine(state.resolve("existing/" + entry.getKey()), entry.getValue());
            String event = "database-compatibility.yml".equals(entry.getKey())
                    ? "workflow_dispatch" : "push";
            writeRun(state, entry.getValue(), event, EXPECTED_SHA,
                    "main", "completed", "success", 0);
        }
    }

    private static void writeRun(
            Path state,
            String runId,
            String event,
            String headSha,
            String headBranch,
            String status,
            String conclusion,
            int watchExit) throws Exception {
        Path run = state.resolve("runs/" + runId);
        writeLine(run.resolve("headSha"), headSha);
        writeLine(run.resolve("headBranch"), headBranch);
        writeLine(run.resolve("event"), event);
        writeLine(run.resolve("status"), status);
        writeLine(run.resolve("conclusion"), conclusion);
        writeLine(run.resolve("watch_exit"), Integer.toString(watchExit));
    }

    private static List<String> dispatchCalls(RunResult result) {
        return result.calls().stream()
                .filter(call -> call.startsWith("gh\tworkflow\trun\t"))
                .toList();
    }

    private static void requireSuccess(String name, RunResult result) {
        if (result.exitCode() != 0) {
            throw new AssertionError(name + ": expected success, got "
                    + result.exitCode() + "\n" + result.output());
        }
    }

    private static void requireFailure(
            String name,
            RunResult result,
            String expectedMessage) {
        if (result.exitCode() == 0) {
            throw new AssertionError(name + ": expected failure\n"
                    + result.output());
        }
        requireContains(result.output(), expectedMessage);
    }

    private static void requireContains(String text, String expected) {
        if (!text.contains(expected)) {
            throw new AssertionError("Expected diagnostic " + expected
                    + "\nActual output:\n" + text);
        }
    }

    private static void executable(Path path, String content) throws Exception {
        write(path, content);
        if (!path.toFile().setExecutable(true, false)) {
            throw new AssertionError("Unable to make executable: " + path);
        }
    }

    private static void writeLine(Path path, String value) throws Exception {
        write(path, value + "\n");
    }

    private static void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isRegularFile(current.resolve(
                    ".github/scripts/verify-exact-release-gates.sh"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate Taxonomy repository root");
    }

    @FunctionalInterface
    private interface FixtureCustomizer {
        void customize(Path state) throws Exception;
    }

    private record RunResult(
            int exitCode,
            String output,
            List<String> calls) {
    }
}
