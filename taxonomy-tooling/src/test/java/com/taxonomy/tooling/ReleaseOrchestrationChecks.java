package com.taxonomy.tooling;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Real Bash execution with a deterministic boundary double for the GitHub CLI.
 * The same cases are exposed to JUnit; main supports dependency-free diagnostics.
 * No case can invoke a real gh binary, approve a workflow or contact GitHub.
 */
final class ReleaseOrchestrationChecks {
    static final String SHA = "a".repeat(40);
    static final String OTHER = "b".repeat(40);
    static final String REPO = "example/project";
    static final String PASS = checks("Maven verification", "pass", "SUCCESS");
    static final String PENDING = checks("Maven verification", "pending", "IN_PROGRESS");
    @FunctionalInterface interface Body { void run() throws Exception; }
    record Case(String name, Body body) { }
    record Result(int exit, String output, String calls, String summary, String outputs) { }

    static List<Case> cases(Path root) {
        List<Case> cases = new ArrayList<>();
        add(cases, "preserves authentication error without registration retries", () -> {
            try (Fixture f = new Fixture(root)) {
                f.checks("[]", 4, "GraphQL: Resource not accessible by integration\n");
                Result r = f.gate("verify");
                bad(r, "Resource not accessible by integration");
                require(count(r.calls(), "pr checks") == 1, r.calls());
                require(!r.calls().contains("sleep"), r.calls());
            }
        });
        add(cases, "failed check exit 1 retains the failed check identity", () -> {
            try (Fixture f = new Fixture(root)) {
                f.checks(checks("Maven verification", "fail", "FAILURE"), 1, "");
                Result r = f.gate("verify");
                bad(r, "Maven verification: FAILURE");
                require(count(r.calls(), "pr checks") == 1, r.calls());
            }
        });
        add(cases, "required skipped checks do not pass", () -> {
            try (Fixture f = new Fixture(root)) {
                f.checks(checks("Database Compatibility", "skipping", "SKIPPED"), 0, "");
                bad(f.gate("verify"), "none count as success");
            }
        });
        add(cases, "required cancelled checks do not pass", () -> {
            try (Fixture f = new Fixture(root)) {
                f.checks(checks("Database Compatibility", "cancel", "CANCELLED"), 1, "");
                bad(f.gate("verify"), "CANCELLED");
            }
        });
        add(cases, "all registered successful checks pass final read", () -> {
            try (Fixture f = new Fixture(root)) { good(f.gate("verify")); }
        });
        add(cases, "pending is admission only, not merge approval", () -> {
            try (Fixture f = new Fixture(root)) {
                f.checks(PENDING, 8, "");
                Result r = f.gate("preflight"); good(r);
                require(r.output().contains("NOT a merge approval"), r.output());
            }
        });
        add(cases, "pending cannot pass final verification", () -> {
            try (Fixture f = new Fixture(root)) {
                f.checks(PENDING, 8, ""); bad(f.gate("verify"), "still pending");
            }
        });
        add(cases, "action_required stops before querying checks or dispatching", () -> {
            try (Fixture f = new Fixture(root)) {
                f.put("runs", pages(run(1, 1, "action_required")));
                Result r = f.gate("preflight"); bad(r, "Approve workflows to run");
                require(!r.calls().contains("pr checks"), r.calls());
                require(!r.calls().contains("workflow run"), r.calls());
                require(r.summary().contains("Approve workflows to run"), r.summary());
            }
        });
        add(cases, "superseded approval result does not mask successful later attempt", () -> {
            try (Fixture f = new Fixture(root)) {
                f.put("runs", pages(run(1, 1, "action_required") + "," + run(1, 2, "success")));
                good(f.gate("preflight"));
            }
        });
        add(cases, "newer approval result is not hidden by old success", () -> {
            try (Fixture f = new Fixture(root)) {
                f.put("runs", pages(run(1, 1, "success") + "," + run(1, 2, "action_required")));
                bad(f.gate("preflight"), "Approve workflows to run");
            }
        });
        add(cases, "approval checks include a second API page", () -> {
            try (Fixture f = new Fixture(root)) {
                f.put("runs", "[{\"workflow_runs\":[" + run(1, 1, "success") + "]},"
                        + "{\"workflow_runs\":[" + run(2, 1, "action_required") + "]}]");
                Result r = f.gate("preflight"); bad(r, "Approve workflows to run");
                require(r.calls().contains("--paginate --slurp"), r.calls());
            }
        });
        add(cases, "other PR approval state is not attributed to this PR", () -> {
            try (Fixture f = new Fixture(root)) {
                f.put("runs", pages(run(1, 1, "success") + "," + run(2, 1, "action_required").replace("1185", "1186")));
                good(f.gate("preflight"));
            }
        });
        add(cases, "unknown check buckets fail closed", () -> {
            try (Fixture f = new Fixture(root)) {
                f.checks(checks("Unknown", "future-bucket", "FUTURE"), 0, "");
                bad(f.gate("verify"), "no usable JSON");
            }
        });
        add(cases, "invalid check JSON preserves diagnostic", () -> {
            try (Fixture f = new Fixture(root)) {
                f.checks("not-json", 1, "transport disconnected\n");
                Result r = f.gate("verify"); bad(r, "transport disconnected");
                require(count(r.calls(), "pr checks") == 1, r.calls());
            }
        });
        add(cases, "unexpected error with pass-looking data never becomes success", () -> {
            try (Fixture f = new Fixture(root)) {
                f.checks(PASS, 1, "unexpected failure\n"); bad(f.gate("verify"), "error despite");
            }
        });
        add(cases, "missing required checks are not successful", () -> {
            try (Fixture f = new Fixture(root)) {
                f.checks("[]", 0, ""); bad(f.gate("preflight"), "have not registered");
            }
        });
        add(cases, "malformed workflow-run response fails closed", () -> {
            try (Fixture f = new Fixture(root)) {
                f.put("runs", "[{\"message\":\"Forbidden\"}]");
                bad(f.gate("preflight"), "invalid workflow-run response");
            }
        });
        add(cases, "workflow API errors are not hidden", () -> {
            try (Fixture f = new Fixture(root)) {
                f.env.put("API_EXIT", "1"); f.put("api-error", "HTTP 403: forbidden\n");
                bad(f.gate("preflight"), "HTTP 403: forbidden");
            }
        });
        add(cases, "changed PR head aborts before API gate lookup", () -> {
            try (Fixture f = new Fixture(root)) {
                f.put("pr", pr(OTHER, "OPEN")); Result r = f.gate("verify");
                bad(r, "no longer has head"); require(!r.calls().contains("api "), r.calls());
            }
        });
        add(cases, "closed PR is not reopened", () -> {
            try (Fixture f = new Fixture(root)) {
                f.put("pr", pr(SHA, "CLOSED")); bad(f.gate("preflight"), "is closed");
            }
        });
        add(cases, "changed head during gate reading is detected", () -> {
            try (Fixture f = new Fixture(root)) {
                f.put("pr-after", pr(OTHER, "OPEN")); bad(f.gate("verify"), "no longer has head");
            }
        });
        add(cases, "neutral summary mapped to pass does not replace pending actual gate", () -> {
            try (Fixture f = new Fixture(root)) {
                f.checks("[{\"name\":\"CodeQL\",\"bucket\":\"pass\",\"state\":\"NEUTRAL\"},"
                        + "{\"name\":\"Analyze Java\",\"bucket\":\"pending\",\"state\":\"QUEUED\"}]", 8, "");
                bad(f.gate("verify"), "still pending");
            }
        });
        add(cases, "successful latest exact dispatch is reused without new CI", () -> {
            try (Fixture f = new Fixture(root)) {
                Result r = f.canonical(); good(r); noDispatch(r);
                require(r.outputs().contains("run_id=42"), r.outputs());
                require(r.calls().contains("--interval 60"), r.calls());
            }
        });
        add(cases, "in-progress exact dispatch is joined", () -> {
            try (Fixture f = new Fixture(root)) {
                f.put("canonical-before", ReleaseOrchestrationChecks.canonical(SHA, "in_progress", "null"));
                Result r = f.canonical(); good(r); noDispatch(r);
            }
        });
        add(cases, "newest failed dispatch is not replaced or retried", () -> {
            try (Fixture f = new Fixture(root)) {
                f.put("canonical-before", ReleaseOrchestrationChecks.canonical(SHA, "completed", "\"failure\""));
                Result r = f.canonical(); bad(r, "not retrying"); noDispatch(r);
                require(!r.calls().contains("run watch"), r.calls());
            }
        });
        add(cases, "canonical evidence from another SHA is refused", () -> {
            try (Fixture f = new Fixture(root)) {
                f.put("canonical-before", canonical(OTHER, "completed", "\"success\""));
                Result r = f.canonical(); bad(r, "different source"); noDispatch(r);
            }
        });
        add(cases, "canonical evidence from another workflow is refused", () -> {
            try (Fixture f = new Fixture(root)) {
                f.put("canonical-before", ReleaseOrchestrationChecks.canonical(SHA, "completed", "\"success\"").replace("ci-cd.yml", "other.yml"));
                bad(f.canonical(), "different source");
            }
        });
        add(cases, "a superseding canonical run invalidates selected evidence", () -> {
            try (Fixture f = new Fixture(root)) {
                f.put("run-ids", "42\n43\n"); bad(f.canonical(), "superseded evidence");
            }
        });
        add(cases, "absence of canonical run dispatches exactly once", () -> {
            try (Fixture f = new Fixture(root)) {
                f.put("run-ids", "\n42\n42\n"); Result r = f.canonical(); good(r);
                require(count(r.calls(), "workflow run ci-cd.yml") == 1, r.calls());
            }
        });
        add(cases, "failed canonical watch cannot pass", () -> {
            try (Fixture f = new Fixture(root)) {
                f.env.put("WATCH_EXIT", "1"); Result r = f.canonical();
                require(r.exit() != 0 && !r.outputs().contains("run_id="), r.output());
            }
        });
        add(cases, "a newly started attempt is not an old completed success", () -> {
            try (Fixture f = new Fixture(root)) {
                f.put("canonical-after", ReleaseOrchestrationChecks.canonical(SHA, "in_progress", "null"));
                bad(f.canonical(), "did not finish successfully");
            }
        });
        add(cases, "admission precedes dispatch and protected merge remains unchanged", () -> {
            String w = Files.readString(root.resolve(".github/workflows/protected-release-main-advance.yml"));
            require(w.indexOf("run: bash \"$RUNNER_TEMP/check-release-pr-gates.sh\" preflight")
                    < w.indexOf("gh workflow run ci-cd.yml"), "Late admission check");
            require(w.indexOf("cp .github/scripts/check-release-pr-gates.sh")
                    < w.indexOf("git checkout --detach \"$EXPECTED_SHA\""), "Helper not preserved from authoritative main");
            require(w.contains("gh pr merge \"$PR_NUMBER\" --rebase --match-head-commit \"$EXPECTED_SHA\""), "Missing protected merge");
            require(w.contains("gh pr checks \"$PR_NUMBER\" --required --watch --fail-fast"), "Missing required gate watch");
            require(w.contains("check-release-history") && w.contains("--main-commit \"$merged_sha\""), "Missing history verification");
            require(!w.contains("--admin") && !w.contains("2>/dev/null"), "Unsafe merge or hidden diagnostics");
        });
        add(cases, "contradictory pending exit cannot certify final success", () -> {
            try (Fixture f = new Fixture(root)) {
                f.checks(PASS, 8, ""); bad(f.gate("verify"), "pending exit");
            }
        });
        add(cases, "run records without workflow identity fail closed", () -> {
            try (Fixture f = new Fixture(root)) {
                f.put("runs", pages(run(1, 1, "success").replace("\"workflow_id\":1,", "")));
                bad(f.gate("preflight"), "invalid workflow-run response");
            }
        });
        add(cases, "canonical evidence from another branch is refused", () -> {
            try (Fixture f = new Fixture(root)) {
                f.put("canonical-before", canonical(SHA, "completed", "\"success\"")
                        .replace("release-temp-1.4.1", "other-branch"));
                bad(f.canonical(), "different source");
            }
        });
        return cases;
    }

    private static void add(List<Case> cases, String name, Body body) { cases.add(new Case(name, body)); }
    private static String checks(String name, String bucket, String state) {
        return "[{\"name\":\"" + name + "\",\"bucket\":\"" + bucket + "\",\"state\":\"" + state + "\",\"link\":\"https://github.com/example/project/actions/runs/1\"}]";
    }
    private static String pr(String sha, String state) {
        return "{\"headRefOid\":\"" + sha + "\",\"state\":\"" + state + "\",\"baseRefName\":\"main\"}";
    }
    private static String run(int workflow, int attempt, String conclusion) {
        return "{\"head_sha\":\"" + SHA + "\",\"event\":\"pull_request\",\"pull_requests\":[{\"number\":1185}],"
                + "\"workflow_id\":" + workflow + ",\"run_number\":1,\"run_attempt\":" + attempt + ",\"conclusion\":\"" + conclusion
                + "\",\"name\":\"Database Compatibility\",\"html_url\":\"https://github.com/example/project/actions/runs/1\"}";
    }
    private static String pages(String runs) { return "[{\"workflow_runs\":[" + runs + "]}]"; }
    private static String canonical(String sha, String status, String conclusion) {
        return "{\"id\":42,\"head_branch\":\"release-temp-1.4.1\",\"head_sha\":\"" + sha + "\",\"event\":\"workflow_dispatch\",\"path\":\".github/workflows/ci-cd.yml\",\"status\":\"" + status + "\",\"conclusion\":" + conclusion + "}";
    }
    private static long count(String value, String needle) { return value.lines().filter(s -> s.contains(needle)).count(); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void good(Result r) { require(r.exit() == 0, "Expected success: " + r.output()); }
    private static void bad(Result r, String diagnostic) {
        require(r.exit() != 0, "Expected refusal: " + r.output());
        require(r.output().contains(diagnostic), "Missing diagnostic '" + diagnostic + "': " + r.output());
    }
    private static void noDispatch(Result r) { require(!r.calls().contains("workflow run"), r.calls()); }

    static Path repositoryRoot() {
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve(".github")) && Files.isRegularFile(p.resolve("pom.xml"))) return p;
        }
        throw new IllegalStateException("Repository root not found");
    }

    /** Executes precisely the cases JUnit exposes, without supplying fake JUnit classes. */
    public static void main(String[] args) throws Exception {
        Path root = args.length == 0 ? repositoryRoot() : Path.of(args[0]).toAbsolutePath();
        int passed = 0, failed = 0;
        for (Case c : cases(root)) {
            try { c.body().run(); System.out.println("PASS " + c.name()); passed++; }
            catch (Exception | AssertionError e) { System.err.println("FAIL " + c.name() + ": " + e); failed++; }
        }
        System.out.printf("Cases: %d; passed: %d; failed: %d; skipped: 0%n", passed + failed, passed, failed);
        if (failed != 0) throw new AssertionError("Release orchestration regression cases failed: " + failed);
    }

    private static final class Fixture implements AutoCloseable {
        final Path root;
        final Path dir = Files.createTempDirectory("release-gate-boundary-");
        final Map<String, String> env = new java.util.HashMap<>();
        Fixture(Path root) throws IOException {
            this.root = root;
            put("pr", pr(SHA, "OPEN")); put("pr-after", pr(SHA, "OPEN"));
            checks(PASS, 0, ""); put("api-error", "");
            put("runs", pages(run(1, 1, "success")));
            put("canonical-before", ReleaseOrchestrationChecks.canonical(SHA, "completed", "\"success\""));
            put("canonical-after", ReleaseOrchestrationChecks.canonical(SHA, "completed", "\"success\""));
            put("run-ids", "42\n42\n42\n");
            put("summary", ""); put("outputs", ""); put("calls", "");
            put("gh", """
                    #!/usr/bin/env bash
                    set -eu
                    printf '%s\\n' "$*" >> "$FIXTURE/calls"
                    step() {
                      local file="$FIXTURE/$1.count" n=0
                      if [[ -f "$file" ]]; then read -r n < "$file"; fi
                      n=$((n + 1)); echo "$n" > "$file"; echo "$n"
                    }
                    if [[ "$1 $2" == 'pr view' ]]; then
                      n=$(step pr)
                      if [[ "$n" == 1 ]]; then cat "$FIXTURE/pr"; else cat "$FIXTURE/pr-after"; fi
                    elif [[ "$1 $2" == 'pr checks' ]]; then
                      cat "$FIXTURE/checks"; cat "$FIXTURE/check-error" >&2; exit "$CHECK_EXIT"
                    elif [[ "$1" == api && "$*" == *'actions/runs?'* ]]; then
                      cat "$FIXTURE/api-error" >&2
                      [[ "${API_EXIT:-0}" == 0 ]] || exit "$API_EXIT"
                      cat "$FIXTURE/runs"
                    elif [[ "$1" == api && "$*" == *'actions/runs/'* ]]; then
                      n=$(step canonical)
                      if [[ "$n" == 1 ]]; then cat "$FIXTURE/canonical-before"; else cat "$FIXTURE/canonical-after"; fi
                    elif [[ "$1 $2" == 'run list' ]]; then
                      n=$(step lists); sed -n "${n}p" "$FIXTURE/run-ids"
                    elif [[ "$1 $2" == 'run watch' ]]; then
                      exit "${WATCH_EXIT:-0}"
                    elif [[ "$1 $2" == 'workflow run' ]]; then
                      exit 0
                    else
                      echo "Unexpected GitHub boundary command: $*" >&2; exit 99
                    fi
                    """);
            put("sleep", "#!/usr/bin/env bash\nprintf 'sleep %s\\n' \"$*\" >> \"$FIXTURE/calls\"\n");
            for (String binary : List.of("gh", "sleep")) {
                require(dir.resolve(binary).toFile().setExecutable(true), "Cannot prepare boundary double");
            }
            env.put("FIXTURE", dir.toString()); env.put("EXPECTED_SHA", SHA);
            env.put("PR_NUMBER", "1185"); env.put("GITHUB_REPOSITORY", REPO);
            env.put("TEMP_BRANCH", "release-temp-1.4.1");
            env.put("GITHUB_OUTPUT", dir.resolve("outputs").toString());
            env.put("GITHUB_STEP_SUMMARY", dir.resolve("summary").toString());
        }
        void put(String name, String content) throws IOException { Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8); }
        void checks(String data, int status, String error) throws IOException {
            put("checks", data); put("check-error", error); env.put("CHECK_EXIT", Integer.toString(status));
        }
        Result gate(String mode) throws Exception {
            return execute(List.of("bash", root.resolve(".github/scripts/check-release-pr-gates.sh").toString(), mode));
        }
        Result canonical() throws Exception {
            String w = Files.readString(root.resolve(".github/workflows/protected-release-main-advance.yml"));
            String step = w.split("      - name: Run canonical verification on exact snapshot\\n", 2)[1]
                    .split("\\n      - name:", 2)[0];
            String body = step.split("        run: \\|\\n", 2)[1];
            StringBuilder shell = new StringBuilder();
            for (String line : body.split("\\n")) shell.append(line.startsWith("          ") ? line.substring(10) : line).append('\n');
            put("canonical.sh", shell.toString());
            return execute(List.of("bash", dir.resolve("canonical.sh").toString()));
        }
        Result execute(List<String> command) throws Exception {
            ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile());
            builder.environment().remove("GH_TOKEN"); builder.environment().remove("GITHUB_TOKEN");
            builder.environment().putAll(env);
            builder.environment().put("PATH", dir + java.io.File.pathSeparator + System.getenv("PATH"));
            builder.redirectErrorStream(true).redirectOutput(dir.resolve("log").toFile());
            Process process = builder.start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("Boundary test timeout"); }
            return new Result(process.exitValue(), Files.readString(dir.resolve("log")), Files.readString(dir.resolve("calls")),
                    Files.readString(dir.resolve("summary")), Files.readString(dir.resolve("outputs")));
        }
        @Override public void close() throws IOException {
            try (var paths = Files.walk(dir)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }
}
