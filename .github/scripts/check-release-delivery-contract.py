#!/usr/bin/env python3
"""Fail closed when release staging, recovery and publication cease to be atomic."""

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
        "next_version_increment:",
        "type: choice",
        "INPUT_NEXT_VERSION_INCREMENT:",
        "run: python3 .github/scripts/resolve-release-parameters.py",
        "python3 .github/scripts/test-resolve-release-parameters.py",
        "ref: main",
        "SOURCE_BRANCH: main",
        "if: steps.release_parameters.outputs.resume_staged_release != 'true'",
        "- name: Validate staged release for resume",
        "if: steps.release_parameters.outputs.resume_staged_release == 'true'",
        'git merge-base --is-ancestor "$tag" origin/main',
        "Release $tag must still be a draft before publication resumes",
        "DEFER_RELEASE_PUBLICATION: 'true'",
        "- name: Record exact final main snapshot",
        "- name: Checkout immutable release source",
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

    for forbidden in (
        "      release_version:",
        "      next_development_version:",
        "      resume_staged_release:",
        "INPUT_RELEASE_VERSION:",
        "INPUT_NEXT_DEVELOPMENT_VERSION:",
        "INPUT_RESUME_STAGED_RELEASE:",
    ):
        if forbidden in workflow:
            failures.append(
                f"{RELEASE_WORKFLOW.relative_to(ROOT)} still exposes or forwards "
                f"free-form release input {forbidden!r}"
            )

    require(
        ci_workflow,
        "python3 .github/scripts/test-resolve-release-parameters.py",
        CI_WORKFLOW,
        failures,
    )

    if "main_sha=$(git rev-parse origin/main)" in workflow:
        failures.append(
            "deploy-release.yml must not substitute the then-current main SHA "
            "for the recorded publication candidate"
        )

    try:
        stage = workflow.index(
            "- name: Build and stage release, then prepare next development version"
        )
        resume = workflow.index("- name: Validate staged release for resume")
        record_main = workflow.index("- name: Record exact final main snapshot")
        checkout_tag = workflow.index("- name: Checkout immutable release source")
        package = workflow.index("- name: Package and stage immutable Helm artifacts")
        image = workflow.index("- name: Build and publish immutable release image")
        final_ci = workflow.index(
            "- name: Verify exact final main snapshot with canonical CI"
        )
        publish = workflow.index(
            "- name: Publish complete release and trigger deployment"
        )
        if not stage < resume < record_main < checkout_tag < package < image < final_ci < publish:
            failures.append(
                "deploy-release.yml must either stage or validate a resumable draft, "
                "record the exact main commit, fetch and build the immutable tag, "
                "verify that exact main commit, and publish last"
            )

        stage_block = workflow[stage:resume]
        if "resume_staged_release != 'true'" not in stage_block:
            failures.append("Normal release staging must be skipped in resume mode")
        if "RENDER_DEPLOY_HOOK_URL" in stage_block:
            failures.append("The staging step must not receive the deployment hook")

        resume_block = workflow[resume:record_main]
        for needle in (
            "resume_staged_release == 'true'",
            'git fetch origin "refs/heads/main:refs/remotes/origin/main" --force',
            'git fetch origin "refs/tags/${tag}:refs/tags/${tag}"',
            'git merge-base --is-ancestor "$tag" origin/main',
            '--mode release --expected-version "$RELEASE_VERSION" --tag "$tag"',
            "--mode development --expected-version \"$NEXT_VERSION_INPUT\"",
            'if [[ "$is_draft" != "true" ]]; then',
        ):
            if needle not in resume_block:
                failures.append(f"Resume validation is missing {needle!r}")
        if "RENDER_DEPLOY_HOOK_URL" in resume_block:
            failures.append("Resume validation must not receive the deployment hook")

        checkout_block = workflow[checkout_tag:package]
        tag_fetch = 'git fetch origin "refs/tags/${tag}:refs/tags/${tag}"'
        tag_checkout = 'git checkout --detach "$tag"'
        if tag_fetch not in checkout_block:
            failures.append("Immutable checkout must explicitly fetch the remote tag")
        if tag_checkout not in checkout_block:
            failures.append("Immutable checkout must detach at the fetched tag")
        if (
            tag_fetch in checkout_block
            and tag_checkout in checkout_block
            and checkout_block.index(tag_fetch) > checkout_block.index(tag_checkout)
        ):
            failures.append("Immutable tag must be fetched before it is checked out")

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
        "Release delivery contract is atomic, repository-derived and resumable: "
        "immutable sources are fetched explicitly, the exact main snapshot is "
        "verified, and publication remains the final gate."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
