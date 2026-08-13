#!/usr/bin/env python3
"""Fail closed if release publication stops verifying the exact OCI digest."""

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github" / "workflows" / "deploy-release.yml"


def require(text: str, needle: str, failures: list[str]) -> None:
    if needle not in text:
        failures.append(f"deploy-release.yml is missing {needle!r}")


def main() -> int:
    workflow = WORKFLOW.read_text(encoding="utf-8")
    failures: list[str] = []

    for needle in (
        "id: release_image",
        "provenance: mode=max",
        "sbom: true",
        "- name: Scan immutable release image digest",
        "aquasecurity/trivy-action@ed142fd0673e97e23eac54620cfb913e5ce36c25",
        "image-ref: ghcr.io/${{ github.repository_owner }}/taxonomy@${{ steps.release_image.outputs.digest }}",
        "severity: HIGH,CRITICAL",
        "exit-code: '1'",
        "- name: Bind release evidence and Helm deployment to immutable image digest",
        "IMAGE_DIGEST: ${{ steps.release_image.outputs.digest }}",
        "^sha256:[0-9a-f]{64}$",
        'immutable_image="${repository}@${IMAGE_DIGEST}"',
        '"attestations"',
        '"provenance": True',
        '"sbom": True',
        '"result": "passed"',
        "--set-string \"image.digest=${IMAGE_DIGEST}\"",
        "taxonomy-${RELEASE_VERSION}-kubernetes-digest.yaml",
        "taxonomy-${RELEASE_VERSION}-image-evidence.json",
        "taxonomy-${RELEASE_VERSION}-image-trivy.sarif",
        "taxonomy-${RELEASE_VERSION}-release.sha256",
        "retention-days: 90",
        "docker buildx imagetools inspect \"$image\"",
        'if [[ "$evidence_digest" != "$IMAGE_DIGEST" ]]; then',
    ):
        require(workflow, needle, failures)

    try:
        build = workflow.index("- name: Build and publish immutable release image")
        scan = workflow.index("- name: Scan immutable release image digest")
        bind = workflow.index(
            "- name: Bind release evidence and Helm deployment to immutable image digest"
        )
        archive = workflow.index("- name: Archive immutable release image evidence")
        final_gates = workflow.index(
            "- name: Verify exact final main release gate matrix"
        )
        publish = workflow.index(
            "- name: Publish complete release and trigger deployment"
        )
        if not final_gates < build < scan < bind < archive < publish:
            failures.append(
                "the exact final main gate matrix must pass before the release image is "
                "built, digest-scanned, evidence-bound, archived and published"
            )

        scan_block = workflow[scan:bind]
        if "exit-code: '1'" not in scan_block:
            failures.append("immutable digest scan must fail the release on blocking findings")
        if "steps.release_image.outputs.digest" not in scan_block:
            failures.append("image scan must consume the build-push digest output")

        bind_block = workflow[bind:archive]
        if "gh release upload" not in bind_block:
            failures.append("digest evidence must be attached to the draft release")
        if "image.digest=${IMAGE_DIGEST}" not in bind_block:
            failures.append("Helm release evidence must include a digest-bound manifest")

        publish_block = workflow[publish:]
        evidence_check = publish_block.find("evidence_digest")
        publish_release = publish_block.find("gh release edit")
        if evidence_check < 0 or publish_release < 0 or evidence_check > publish_release:
            failures.append(
                "release image evidence must be verified before the draft becomes public"
            )
        if 'image="${repository}@${IMAGE_DIGEST}"' not in publish_block:
            failures.append("final publication must inspect the immutable digest, not only a tag")
    except ValueError as error:
        failures.append(f"Could not determine immutable image gate order: {error}")

    if failures:
        print("Immutable release image gate failed:", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1

    print(
        "Immutable release image gate passed: provenance/SBOM attestations are enabled, "
        "the pushed digest is vulnerability-scanned, release evidence is digest-bound, "
        "and publication occurs only after those checks."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
