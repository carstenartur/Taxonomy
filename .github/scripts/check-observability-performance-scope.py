#!/usr/bin/env python3
"""Keep the performance gate scoped to server/runtime-sensitive PR changes."""

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github" / "workflows" / "ci-cd.yml"

STATIC_EXCLUSION = ":(exclude)taxonomy-app/src/main/resources/static/**"
TEMPLATE_EXCLUSION = ":(exclude)taxonomy-app/src/main/resources/templates/**"


def main() -> int:
    workflow = WORKFLOW.read_text(encoding="utf-8")
    failures: list[str] = []

    required = (
        "- name: Detect performance-sensitive changes",
        "PR_BASE_SHA: ${{ github.event.pull_request.base.sha }}",
        "PR_HEAD_SHA: ${{ github.event.pull_request.head.sha }}",
        'git diff --quiet "${PR_BASE_SHA}...${PR_HEAD_SHA}" --',
        "pom.xml",
        "Dockerfile",
        "taxonomy-app/pom.xml",
        "taxonomy-app/src/main",
        STATIC_EXCLUSION,
        TEMPLATE_EXCLUSION,
        ".github/scripts/run-observability-performance.sh",
        "github.event_name != 'pull_request' || steps.observability-performance-scope.outputs.run == 'true'",
        "TAXONOMY_OBSERVABILITY_PERFORMANCE_ENFORCE: 'true'",
        "run: bash .github/scripts/run-observability-performance.sh",
    )
    for needle in required:
        if needle not in workflow:
            failures.append(f"ci-cd.yml is missing {needle!r}")

    try:
        start = workflow.index("- name: Detect performance-sensitive changes")
        end = workflow.index("- name: Measure OpenTelemetry performance budget")
        scope_block = workflow[start:end]
        performance_end = workflow.index("- name: Restore pinned embedding model")
        performance_block = workflow[end:performance_end]
    except ValueError as error:
        failures.append(f"Could not locate performance scope steps: {error}")
        scope_block = ""
        performance_block = ""

    if "...HEAD" in scope_block:
        failures.append(
            "Scope detection must compare base.sha with head.sha, not the synthetic PR merge HEAD"
        )
    if ".github/workflows/ci-cd.yml" in scope_block:
        failures.append(
            "Editing unrelated CI steps must not by itself force the expensive benchmark"
        )
    if "taxonomy-app/src/main" not in scope_block:
        failures.append(
            "Application runtime changes must trigger the performance budget on pull requests"
        )
    if STATIC_EXCLUSION not in scope_block or TEMPLATE_EXCLUSION not in scope_block:
        failures.append(
            "Pure static/template UI changes must not force the server OpenTelemetry benchmark"
        )
    if "taxonomy-app/src/main/java" in scope_block:
        failures.append(
            "Do not replace the inclusive src/main path with Java-only matching; runtime configuration and resources must stay covered"
        )
    if "github.event_name != 'pull_request'" not in performance_block:
        failures.append(
            "Main, tag, release and manual CI runs must not be allowed to skip the performance budget"
        )

    if failures:
        print("Performance scope contract failed:", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1

    print(
        "Performance scope covers server/runtime-sensitive PR changes, excludes pure UI assets, "
        "and always runs on non-PR CI."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
