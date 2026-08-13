#!/usr/bin/env python3
"""Behavioral regression tests for the exact final release gate helper."""

from __future__ import annotations

import os
from pathlib import Path
import subprocess
import sys
import tempfile
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
HELPER = ROOT / ".github" / "scripts" / "verify-exact-release-gates.sh"

EXPECTED_SHA = "a" * 40
DRIFT_SHA = "b" * 40
RUN_IDS = {
    "ci-cd.yml": "101",
    "database-compatibility.yml": "102",
    "codeql.yml": "103",
    "security-scan.yml": "104",
}


def executable(path: Path, content: str) -> None:
    path.write_text(content, encoding="utf-8")
    path.chmod(0o755)


FAKE_GIT = r'''#!/usr/bin/env bash
set -euo pipefail
state=${FAKE_RELEASE_GATE_STATE:?}
printf 'git' >> "$state/calls"
printf '\t%s' "$@" >> "$state/calls"
printf '\n' >> "$state/calls"

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
  printf '%s\n' "$value"
  printf '%s\n' "$((index + 1))" > "$state/main-index"
  exit 0
fi
printf 'unsupported fake git invocation:' >&2
printf ' %q' "$@" >&2
printf '\n' >&2
exit 91
'''


FAKE_GH = r'''#!/usr/bin/env bash
set -euo pipefail
state=${FAKE_RELEASE_GATE_STATE:?}
printf 'gh' >> "$state/calls"
printf '\t%s' "$@" >> "$state/calls"
printf '\n' >> "$state/calls"

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
printf '\n' >&2
exit 92
'''


FAKE_SLEEP = """#!/usr/bin/env sh
exit 0
"""


def write(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(f"{value}\n", encoding="utf-8")


def write_run(
    state: Path,
    run_id: str,
    *,
    event: str = "push",
    head_sha: str = EXPECTED_SHA,
    head_branch: str = "main",
    status: str = "completed",
    conclusion: str = "success",
    watch_exit: int = 0,
) -> None:
    run = state / "runs" / run_id
    write(run / "headSha", head_sha)
    write(run / "headBranch", head_branch)
    write(run / "event", event)
    write(run / "status", status)
    write(run / "conclusion", conclusion)
    write(run / "watch_exit", watch_exit)


def baseline(state: Path) -> None:
    write(state / "main-shas", f"{EXPECTED_SHA}\n{EXPECTED_SHA}")
    write(state / "main-index", 0)
    write(state / "calls", "")
    for workflow, run_id in RUN_IDS.items():
        write(state / "existing" / workflow, run_id)
        event = (
            "workflow_dispatch"
            if workflow == "database-compatibility.yml"
            else "push"
        )
        write_run(state, run_id, event=event)


def run_helper(
    configure=None,
) -> tuple[subprocess.CompletedProcess[str], list[str]]:
    with tempfile.TemporaryDirectory(prefix="taxonomy-release-gates-") as temp:
        temp_path = Path(temp)
        bin_path = temp_path / "bin"
        state = temp_path / "state"
        bin_path.mkdir()
        state.mkdir()
        executable(bin_path / "git", FAKE_GIT)
        executable(bin_path / "gh", FAKE_GH)
        executable(bin_path / "sleep", FAKE_SLEEP)
        baseline(state)
        if configure is not None:
            configure(state)

        env = os.environ.copy()
        env.update(
            {
                "PATH": f"{bin_path}{os.pathsep}{env['PATH']}",
                "FAKE_RELEASE_GATE_STATE": str(state),
                "EXPECTED_MAIN_SHA": EXPECTED_SHA,
                "RELEASE_GATE_DISCOVERY_ATTEMPTS": "1",
                "RELEASE_GATE_REGISTRATION_ATTEMPTS": "1",
                "RELEASE_GATE_POLL_SECONDS": "0",
            }
        )
        result = subprocess.run(
            ["bash", str(HELPER)],
            cwd=ROOT,
            env=env,
            text=True,
            capture_output=True,
            check=False,
            timeout=20,
        )
        calls = (state / "calls").read_text(encoding="utf-8").splitlines()
        return result, calls


def output(result: subprocess.CompletedProcess[str]) -> str:
    return result.stdout + result.stderr


def require_success(name: str, result: subprocess.CompletedProcess[str]) -> None:
    if result.returncode != 0:
        raise AssertionError(
            f"{name}: expected success, got {result.returncode}\n{output(result)}"
        )


def require_failure(
    name: str,
    result: subprocess.CompletedProcess[str],
    expected_message: str,
) -> None:
    if result.returncode == 0:
        raise AssertionError(f"{name}: expected failure\n{output(result)}")
    if expected_message not in output(result):
        raise AssertionError(
            f"{name}: expected diagnostic {expected_message!r}\n{output(result)}"
        )


def test_reuses_successful_exact_head_runs() -> None:
    result, calls = run_helper()
    require_success("reuse exact runs", result)
    if f"All exact release gates passed for {EXPECTED_SHA}" not in result.stdout:
        raise AssertionError(f"missing success summary\n{output(result)}")
    dispatches = [call for call in calls if call.startswith("gh\tworkflow\trun\t")]
    if dispatches:
        raise AssertionError(
            f"existing exact runs must not be dispatched: {dispatches}"
        )


def test_dispatches_only_missing_workflow() -> None:
    def configure(state: Path) -> None:
        (state / "existing" / "database-compatibility.yml").unlink()
        write(state / "dispatch-ids" / "database-compatibility.yml", "202")
        write_run(state, "202", event="workflow_dispatch")
        write(
            state / "main-shas",
            f"{EXPECTED_SHA}\n{EXPECTED_SHA}\n{EXPECTED_SHA}",
        )

    result, calls = run_helper(configure)
    require_success("dispatch missing workflow", result)
    dispatches = [call for call in calls if call.startswith("gh\tworkflow\trun\t")]
    expected = [
        "gh\tworkflow\trun\tdatabase-compatibility.yml\t--ref\tmain"
    ]
    if dispatches != expected:
        raise AssertionError(f"unexpected dispatch set: {dispatches}")


def test_failed_workflow_keeps_final_diagnostics() -> None:
    def configure(state: Path) -> None:
        write(state / "runs" / RUN_IDS["codeql.yml"] / "watch_exit", 1)
        write(state / "runs" / RUN_IDS["codeql.yml"] / "conclusion", "failure")

    result, _ = run_helper(configure)
    require_failure(
        "failed workflow diagnostics",
        result,
        (
            "codeql.yml run 103 ended with status=completed conclusion=failure "
            "(gh run watch exit=1)"
        ),
    )


def test_unreliable_watch_fails_even_after_successful_status() -> None:
    def configure(state: Path) -> None:
        write(state / "runs" / RUN_IDS["security-scan.yml"] / "watch_exit", 2)

    result, _ = run_helper(configure)
    require_failure(
        "unreliable watch",
        result,
        (
            "security-scan.yml run 104 could not be watched reliably (exit=2) "
            "despite status=completed conclusion=success"
        ),
    )


def test_final_main_drift_blocks_publication() -> None:
    def configure(state: Path) -> None:
        write(state / "main-shas", f"{EXPECTED_SHA}\n{DRIFT_SHA}")

    result, _ = run_helper(configure)
    require_failure(
        "main drift",
        result,
        f"Release gate candidate is {EXPECTED_SHA}, but origin/main is {DRIFT_SHA}",
    )


def test_mismatched_run_identity_is_rejected() -> None:
    def configure(state: Path) -> None:
        write(
            state
            / "runs"
            / RUN_IDS["database-compatibility.yml"]
            / "headBranch",
            "feature/x",
        )

    result, _ = run_helper(configure)
    require_failure(
        "run identity",
        result,
        "database-compatibility.yml run 102 verifies branch 'feature/x', not main",
    )


def test_unregistered_dispatch_fails_closed() -> None:
    def configure(state: Path) -> None:
        (state / "existing" / "database-compatibility.yml").unlink()
        write(state / "main-shas", f"{EXPECTED_SHA}\n{EXPECTED_SHA}")

    result, _ = run_helper(configure)
    require_failure(
        "unregistered dispatch",
        result,
        (
            "Could not locate or dispatch database-compatibility.yml "
            f"for exact commit {EXPECTED_SHA}"
        ),
    )


def main() -> int:
    if not HELPER.is_file():
        print(f"missing helper: {HELPER}", file=sys.stderr)
        return 1

    tests = (
        test_reuses_successful_exact_head_runs,
        test_dispatches_only_missing_workflow,
        test_failed_workflow_keeps_final_diagnostics,
        test_unreliable_watch_fails_even_after_successful_status,
        test_final_main_drift_blocks_publication,
        test_mismatched_run_identity_is_rejected,
        test_unregistered_dispatch_fails_closed,
    )
    failures: list[str] = []
    for test in tests:
        try:
            test()
            print(f"PASS {test.__name__}")
        except Exception as error:  # aggregate all case diagnostics
            failures.append(f"{test.__name__}: {error}")

    if failures:
        print("Exact release gate behavioral tests failed:", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1

    print(f"Exact release gate behavioral tests passed: {len(tests)} scenarios.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
