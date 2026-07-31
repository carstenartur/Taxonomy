#!/usr/bin/env python3
"""Keep the expensive observability performance gate scoped to actual PR changes."""

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github" / "workflows" / "ci-cd.yml"


def main() -> int:
    workflow = WORKFLOW.read_text(encoding="utf-8")
    failures: list[str] = []

    required = (
        "PR_BASE_SHA: ${{ github.event.pull_request.base.sha }}",
        "PR_HEAD_SHA: ${{ github.event.pull_request.head.sha }}",
        'git diff --quiet "${PR_BASE_SHA}...${PR_HEAD_SHA}" --',
        ".github/scripts/run-observability-performance.sh",
        "TAXONOMY_OBSERVABILITY_PERFORMANCE_ENFORCE: 'true'",
        "run: bash .github/scripts/run-observability-performance.sh",
    )
    for needle in required:
        if needle not in workflow:
            failures.append(f"ci-cd.yml is missing {needle!r}")

    try:
        start = workflow.index("- name: Detect observability performance changes")
        end = workflow.index("- name: Measure OpenTelemetry performance budget")
        scope_block = workflow[start:end]
    except ValueError as error:
        failures.append(f"Could not locate observability scope steps: {error}")
        scope_block = ""

    if "...HEAD" in scope_block:
        failures.append(
            "Scope detection must compare base.sha with head.sha, not the synthetic PR merge HEAD"
        )
    if ".github/workflows/ci-cd.yml" in scope_block:
        failures.append(
            "Editing unrelated CI steps must not force the expensive observability benchmark"
        )

    if failures:
        print("Observability performance scope contract failed:", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1

    print("Observability performance scope is limited to actual PR observability changes.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
