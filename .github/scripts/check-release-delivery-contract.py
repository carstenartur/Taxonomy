#!/usr/bin/env python3
"""Fail closed when release staging and publication cease to be atomic."""

from __future__ import annotations

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
RELEASE_SCRIPT = ROOT / ".github" / "scripts" / "release.sh"
RELEASE_WORKFLOW = ROOT / ".github" / "workflows" / "deploy-release.yml"
CI_WORKFLOW = ROOT / ".github" / "workflows" / "ci-cd.yml"


def require(text: str, needle: str, source: Path, failures: list[str]) -> None:
    if needle not in text:
        failures.append(f"{source.relative_to(ROOT)} is missing {needle!r}")


def main() -> int:
    script = RELEASE_SCRIPT.read_text(encoding="utf-8")
    workflow = RELEASE_WORKFLOW.read_text(encoding="utf-8")
    ci_workflow = CI_WORKFLOW.read_text(encoding="utf-8")
    failures: list[str] = []

    for needle in (
        "DEFER_RELEASE_PUBLICATION=${DEFER_RELEASE_PUBLICATION:-false}",
        'if [[ "$DEFER_RELEASE_PUBLICATION" == "true" ]]; then',
        "remains a draft until downstream artifacts and final CI succeed",
        'test "$RELEASE_IS_DRAFT" = true',
    ):
        require(script, needle, RELEASE_SCRIPT, failures)

    for needle in (
        "run: python3 .github/scripts/resolve-release-parameters.py",
        "python3 .github/scripts/test-resolve-release-parameters.py",
        "DEFER_RELEASE_PUBLICATION: 'true'",
        "- name: Record exact final main snapshot",
        "- name: Package and stage immutable Helm artifacts",
        "- name: Build and publish immutable release image",
        "- name: Verify exact final main snapshot with canonical CI",
        "- name: Publish complete release and trigger deployment",
        "EXPECTED_MAIN_SHA: ${{ steps.final_main.outputs.sha }}",
        '--commit "$EXPECTED_MAIN_SHA"',
        'run_sha=$(gh run view "$run_id" --json headSha --jq \'.headSha\')',
        'docker buildx imagetools inspect "$image"',
        'gh release edit "$tag" --draft=false --latest',
        "Draft release is missing required Helm asset",
        "Render deployment triggered after complete release publication.",
    ):
        require(workflow, needle, RELEASE_WORKFLOW, failures)

    require(
        ci_workflow,
        "python3 .github/scripts/test-resolve-release-parameters.py",
        CI_WORKFLOW,
        failures,
    )

    if "main_sha=$(git rev-parse origin/main)" in workflow:
        failures.append(
            "deploy-release.yml must not substitute the then-current main SHA "
            "for the release-generated snapshot"
        )

    try:
        stage = workflow.index(
            "- name: Build and stage release, then prepare next development version"
        )
        record_main = workflow.index("- name: Record exact final main snapshot")
        checkout_tag = workflow.index("- name: Checkout immutable release source")
        image = workflow.index("- name: Build and publish immutable release image")
        final_ci = workflow.index(
            "- name: Verify exact final main snapshot with canonical CI"
        )
        publish = workflow.index(
            "- name: Publish complete release and trigger deployment"
        )
        if not stage < record_main < checkout_tag < image < final_ci < publish:
            failures.append(
                "deploy-release.yml must stage first, record the exact main commit, "
                "build the immutable image, verify that exact commit, and publish last"
            )
        stage_block = workflow[stage:checkout_tag]
        if "RENDER_DEPLOY_HOOK_URL" in stage_block:
            failures.append("The staging step must not receive the deployment hook")
        final_ci_block = workflow[final_ci:publish]
        if 'run_sha" != "$EXPECTED_MAIN_SHA' not in final_ci_block:
            failures.append(
                "The final CI step must compare the selected run head to the recorded SHA"
            )
        publish_block = workflow[publish:]
        if "RENDER_DEPLOY_HOOK_URL" not in publish_block:
            failures.append("Only the final publication step may trigger deployment")
    except ValueError as error:
        failures.append(f"Could not determine release step order: {error}")

    if failures:
        print("Release delivery contract failed:", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1

    print(
        "Release delivery contract is atomic: the exact release-generated main "
        "snapshot is verified and publication remains the final gate."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
