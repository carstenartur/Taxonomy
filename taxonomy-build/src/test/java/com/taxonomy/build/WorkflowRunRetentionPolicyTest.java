package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledOnOs({OS.LINUX, OS.MAC})
class WorkflowRunRetentionPolicyTest {

    @Test
    void dryRunProtectsEvidenceAndSelectsHistoricalWorkflowsFairly(
            @TempDir Path temp) throws Exception {
        TestFixture fixture = fixture(temp, false);

        Result result = runCleanup(fixture, true, 2);

        assertThat(result.exitCode()).isZero();
        assertThat(result.output())
                .contains("[dry-run] delete 202")
                .contains("[dry-run] delete 105")
                .doesNotContain("[dry-run] delete 101")
                .doesNotContain("[dry-run] delete 102")
                .doesNotContain("[dry-run] delete 201");
        assertThat(result.output().indexOf("[dry-run] delete 202"))
                .isLessThan(result.output().indexOf("[dry-run] delete 105"));
        assertThat(Files.readString(fixture.summary()))
                .contains("| current-default-head | 1 |")
                .contains("| current-open-pr-head | 1 |")
                .contains("| release-or-tag | 1 |")
                .contains("| Eligible deletion candidates | 3 |")
                .contains("| Would delete | 2 |")
                .contains("| Eligible candidates remaining | 1 |");
        assertThat(fixture.deleteLog()).doesNotExist();
    }

    @Test
    void destructiveRunDeletesOnlyTheBoundedEligiblePlan(
            @TempDir Path temp) throws Exception {
        TestFixture fixture = fixture(temp, false);

        Result result = runCleanup(fixture, false, 2);

        assertThat(result.exitCode()).isZero();
        assertThat(Files.readAllLines(fixture.deleteLog()))
                .containsExactly("202", "105");
        assertThat(Files.readString(fixture.summary()))
                .contains("| Deleted | 2 |")
                .contains("| Delete failures | 0 |")
                .contains("| Eligible candidates remaining | 1 |");
    }

    @Test
    void protectionInventoryFailureAbortsBeforeAnyDeletion(
            @TempDir Path temp) throws Exception {
        TestFixture fixture = fixture(temp, true);

        Result result = runCleanup(fixture, false, 10);

        assertThat(result.exitCode()).isNotZero();
        assertThat(fixture.deleteLog()).doesNotExist();
    }

    private static Result runCleanup(
            TestFixture fixture,
            boolean dryRun,
            int maximumDeletions) throws Exception {
        Path root = repositoryRoot();
        ProcessBuilder process = new ProcessBuilder(
                "bash",
                root.resolve(".github/scripts/cleanup-workflow-runs.sh").toString());
        process.directory(root.toFile());
        process.redirectErrorStream(true);
        Map<String, String> environment = process.environment();
        environment.put("PATH", fixture.bin() + ":" + environment.get("PATH"));
        environment.put("GH_TOKEN", "test-token");
        environment.put("REPO", "example/taxonomy");
        environment.put("RETAIN_DAYS", "5");
        environment.put("HISTORICAL_RETAIN_DAYS", "2");
        environment.put("KEEP_MINIMUM_RUNS", "3");
        environment.put("MAX_DELETIONS", Integer.toString(maximumDeletions));
        environment.put("DRY_RUN", Boolean.toString(dryRun));
        environment.put("GITHUB_STEP_SUMMARY", fixture.summary().toString());
        environment.put("DELETE_LOG", fixture.deleteLog().toString());
        environment.put("FAIL_OPEN_PRS", Boolean.toString(fixture.failOpenPrs()));

        Process running = process.start();
        String output = new String(running.getInputStream().readAllBytes());
        int exitCode = running.waitFor();
        return new Result(exitCode, output);
    }

    private static TestFixture fixture(Path temp, boolean failOpenPrs)
            throws Exception {
        Path bin = Files.createDirectories(temp.resolve("bin"));
        Path fakeGh = bin.resolve("gh");
        Files.writeString(fakeGh, fakeGhScript());
        assertThat(fakeGh.toFile().setExecutable(true)).isTrue();
        return new TestFixture(
                bin,
                temp.resolve("summary.md"),
                temp.resolve("deleted-runs.txt"),
                failOpenPrs);
    }

    private static String fakeGhScript() {
        return """
                #!/usr/bin/env bash
                set -euo pipefail
                [[ "$1" == "api" ]]
                shift
                method="GET"
                if [[ "${1:-}" == "--method" ]]; then
                  method="$2"
                  shift 2
                fi
                endpoint="$1"
                if [[ "$method" == "DELETE" ]]; then
                  printf '%s\\n' "${endpoint##*/}" >> "${DELETE_LOG}"
                  exit 0
                fi
                case "$endpoint" in
                  repos/example/taxonomy)
                    echo main
                    ;;
                  repos/example/taxonomy/branches/main)
                    echo MAIN_HEAD
                    ;;
                  'repos/example/taxonomy/contents/.github/workflows?ref=main')
                    echo .github/workflows/ci.yml
                    echo .github/workflows/cleanup-workflow-runs.yml
                    ;;
                  'repos/example/taxonomy/pulls?state=open&per_page=100')
                    [[ "${FAIL_OPEN_PRS}" == "false" ]] || exit 23
                    printf '1\\tPR_HEAD\\tfeature\\n'
                    ;;
                  'repos/example/taxonomy/tags?per_page=100')
                    printf 'v1.0.0\\tTAG_HEAD\\n'
                    ;;
                  'repos/example/taxonomy/releases?per_page=100')
                    echo v1.0.0
                    ;;
                  'repos/example/taxonomy/actions/workflows?per_page=100')
                    printf '1\\tCI\\t.github/workflows/ci.yml\\tactive\\n'
                    printf '2\\tOld helper\\t.github/workflows/old-helper.yml\\tdisabled_manually\\n'
                    ;;
                  'repos/example/taxonomy/actions/workflows/1/runs?status=completed&per_page=100')
                    printf '101\\t2099-08-29T04:00:00Z\\tsuccess\\tMAIN_HEAD\\tmain\\tpush\\t10\\thttps://example/101\\n'
                    printf '102\\t2099-08-29T03:00:00Z\\tfailure\\tPR_HEAD\\tfeature\\tpull_request\\t9\\thttps://example/102\\n'
                    printf '103\\t2099-08-29T02:00:00Z\\tsuccess\\tOTHER\\tfeature\\tpull_request\\t8\\thttps://example/103\\n'
                    printf '104\\t2020-08-01T02:00:00Z\\tfailure\\tOLD_FAILURE\\tfeature\\tpull_request\\t7\\thttps://example/104\\n'
                    printf '105\\t2019-07-01T02:00:00Z\\tcancelled\\tOLD_CANCELLED\\tfeature\\tpull_request\\t6\\thttps://example/105\\n'
                    ;;
                  'repos/example/taxonomy/actions/workflows/2/runs?status=completed&per_page=100')
                    printf '201\\t2020-08-20T02:00:00Z\\tsuccess\\tTAG_HEAD\\tv1.0.0\\tpush\\t2\\thttps://example/201\\n'
                    printf '202\\t2018-07-01T02:00:00Z\\tfailure\\tOLD_HELPER\\told\\tworkflow_dispatch\\t1\\thttps://example/202\\n'
                    ;;
                  rate_limit)
                    echo 5000
                    ;;
                  *)
                    echo "Unexpected endpoint: $endpoint" >&2
                    exit 24
                    ;;
                esac
                """;
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath()
                .normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve(
                    ".github/scripts/cleanup-workflow-runs.sh"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate Taxonomy repository root");
    }

    private record TestFixture(
            Path bin,
            Path summary,
            Path deleteLog,
            boolean failOpenPrs) {
    }

    private record Result(int exitCode, String output) {
    }
}
