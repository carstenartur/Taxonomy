#!/usr/bin/env python3
"""Apply the bounded reviewed release-notes authority patch to a checkout."""

from pathlib import Path
import sys


def require_once(text: str, needle: str, label: str) -> None:
    count = text.count(needle)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")


def main() -> int:
    if len(sys.argv) != 2:
        raise SystemExit("usage: apply-reviewed-release-notes.py <checkout>")
    root = Path(sys.argv[1]).resolve()

    release_path = root / ".github/scripts/release.sh"
    release = release_path.read_text(encoding="utf-8")

    requirement = ': "${VEX_HELPER:?VEX_HELPER is required}"\n'
    require_once(release, requirement, "VEX helper requirement")
    if "RELEASE_NOTES_VALIDATOR is required" in release:
        raise SystemExit("release-notes validator requirement already present")
    release = release.replace(
        requirement,
        requirement
        + ': "${RELEASE_NOTES_VALIDATOR:?RELEASE_NOTES_VALIDATOR is required}"\n',
    )

    start = release.index("generate_release_notes() {")
    end = release.index("\n\ncollect_release_artifacts() {", start)
    validator_function = """validate_release_notes() {
  local notes_file=release_notes.md
  if ! git ls-files --error-unmatch "$notes_file" >/dev/null 2>&1; then
    fail "Reviewed release notes are not tracked in the release commit: $notes_file"
  fi
  if ! git diff --quiet HEAD -- "$notes_file"; then
    fail "Reviewed release notes differ from the immutable release commit"
  fi
  RELEASE_VERSION="$RELEASE_VERSION" RELEASE_NOTES_FILE="$notes_file" \\
    "$RELEASE_NOTES_VALIDATOR"
}"""
    release = release[:start] + validator_function + release[end:]

    require_once(
        release, "\ngenerate_release_notes\n", "release-notes invocation"
    )
    release = release.replace(
        "\ngenerate_release_notes\n", "\nvalidate_release_notes\n"
    )

    generated_notes = """    --title "Release $RELEASE_VERSION" \\
    --notes-file release_notes.md \\
    --generate-notes"""
    reviewed_notes = """    --title "Release $RELEASE_VERSION" \\
    --notes-file release_notes.md"""
    require_once(release, generated_notes, "generated GitHub release notes")
    release = release.replace(generated_notes, reviewed_notes)

    for forbidden in (
        "generate_release_notes()",
        "gh issue list",
        "--generate-notes",
        "No closed issues found since",
        'echo "Initial release"',
    ):
        if forbidden in release:
            raise SystemExit(f"release script still contains {forbidden!r}")
    release_path.write_text(release, encoding="utf-8")

    workflow_path = root / ".github/workflows/deploy-release.yml"
    workflow = workflow_path.read_text(encoding="utf-8")

    copy_anchor = (
        '          cp .github/scripts/generate-vex.py '
        '"$RUNNER_TEMP/generate-vex.py"\n'
    )
    require_once(workflow, copy_anchor, "generate-vex copy")
    workflow = workflow.replace(
        copy_anchor,
        copy_anchor
        + "          cp .github/scripts/validate-reviewed-release-notes.sh \\\n"
        + '            "$RUNNER_TEMP/validate-reviewed-release-notes.sh"\n',
    )

    chmod_anchor = """            "$RUNNER_TEMP/generate-vex.py" \\
            "$RUNNER_TEMP/verify-exact-release-gates.sh"""
    require_once(workflow, chmod_anchor, "release-helper chmod")
    workflow = workflow.replace(
        chmod_anchor,
        """            "$RUNNER_TEMP/generate-vex.py" \\
            "$RUNNER_TEMP/validate-reviewed-release-notes.sh" \\
            "$RUNNER_TEMP/verify-exact-release-gates.sh""",
    )

    syntax_anchor = (
        "          bash -n .github/scripts/verify-exact-release-gates.sh\n"
    )
    require_once(workflow, syntax_anchor, "exact-gate syntax check")
    workflow = workflow.replace(
        syntax_anchor,
        syntax_anchor
        + "          bash -n .github/scripts/validate-reviewed-release-notes.sh\n",
    )

    env_anchor = "          VEX_HELPER: ${{ runner.temp }}/generate-vex.py\n"
    require_once(workflow, env_anchor, "VEX helper environment")
    workflow = workflow.replace(
        env_anchor,
        env_anchor
        + "          RELEASE_NOTES_VALIDATOR: "
        + "$"
        + "{{ runner.temp }}/validate-reviewed-release-notes.sh\n",
    )
    workflow_path.write_text(workflow, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
