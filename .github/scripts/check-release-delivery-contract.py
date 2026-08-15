#!/usr/bin/env python3
"""Fail closed when release verification or publication ceases to be atomic."""

from __future__ import annotations

from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
ROOT_POM = ROOT / "pom.xml"
TOOLING_POM = ROOT / "taxonomy-tooling" / "pom.xml"
RELEASE_PLAN_CHECK = (
    ROOT
    / "taxonomy-tooling"
    / "src"
    / "main"
    / "java"
    / "com"
    / "taxonomy"
    / "tooling"
    / "ReleasePlanValidator.java"
)
RELEASE_PARAMETER_RESOLVER = RELEASE_PLAN_CHECK.with_name(
    "ReleaseParametersResolver.java"
)
VERSION_STATE_CHECK = RELEASE_PLAN_CHECK.with_name("VersionStateVerifier.java")
TOOLING_CLI = RELEASE_PLAN_CHECK.with_name("TaxonomyTooling.java")
RELEASE_SCRIPT = ROOT / ".github" / "scripts" / "release.sh"
RELEASE_IMAGE_GATE = ROOT / ".github" / "scripts" / "check-release-image-gate.py"
RELEASE_GATE_HELPER = ROOT / ".github" / "scripts" / "verify-exact-release-gates.sh"
RELEASE_WORKFLOW = ROOT / ".github" / "workflows" / "deploy-release.yml"
CI_WORKFLOW = ROOT / ".github" / "workflows" / "ci-cd.yml"


def require(text: str, needle: str, source: Path, failures: list[str]) -> None:
    if needle not in text:
        failures.append(f"{source.relative_to(ROOT)} is missing {needle!r}")


def forbid(text: str, needle: str, source: Path, failures: list[str]) -> None:
    if needle in text:
        failures.append(f"{source.relative_to(ROOT)} still contains {needle!r}")


def main() -> int:
    pom = ROOT_POM.read_text(encoding="utf-8")
    tooling_pom = TOOLING_POM.read_text(encoding="utf-8")
    plan_check = RELEASE_PLAN_CHECK.read_text(encoding="utf-8")
    parameter_resolver = RELEASE_PARAMETER_RESOLVER.read_text(encoding="utf-8")
    version_state = VERSION_STATE_CHECK.read_text(encoding="utf-8")
    tooling_cli = TOOLING_CLI.read_text(encoding="utf-8")
    script = RELEASE_SCRIPT.read_text(encoding="utf-8")
    gate_helper = RELEASE_GATE_HELPER.read_text(encoding="utf-8")
    workflow = RELEASE_WORKFLOW.read_text(encoding="utf-8")
    ci_workflow = CI_WORKFLOW.read_text(encoding="utf-8")
    failures: list[str] = []

    image_gate = subprocess.run(
        [sys.executable, str(RELEASE_IMAGE_GATE)],
        cwd=ROOT,
        text=True,
        capture_output=True,
        check=False,
    )
    if image_gate.stdout:
        print(image_gate.stdout, end="")
    if image_gate.returncode != 0:
        if image_gate.stderr:
            print(image_gate.stderr, end="", file=sys.stderr)
        failures.append(
            "Immutable OCI image verification contract failed; see "
            "check-release-image-gate.py output"
        )

    for needle in (
        "<module>taxonomy-tooling</module>",
        "<id>release-check</id>",
        "<taxonomy.release.check>true</taxonomy.release.check>",
    ):
        require(pom, needle, ROOT_POM, failures)
    for needle in (
        ".github/scripts/check-release-plan.py",
        "<executable>python3</executable>",
    ):
        forbid(pom, needle, ROOT_POM, failures)

    for needle in (
        "<artifactId>taxonomy-tooling</artifactId>",
        "<mainClass>com.taxonomy.tooling.TaxonomyTooling</mainClass>",
    ):
        require(tooling_pom, needle, TOOLING_POM, failures)

    if "<artifactId>maven-release-plugin</artifactId>" in pom:
        failures.append(
            "pom.xml must not introduce maven-release-plugin as a competing SCM authority"
        )

    for needle in (
        "release verification requires a clean checkout",
        "maven-release-plugin would create a second SCM release authority",
        "reactorPomPaths",
        "modelsByCoordinate",
        "effectiveProperties",
        "unresolved version property",
        "declares module",
        "external ",
        "requireNewer",
    ):
        require(plan_check, needle, RELEASE_PLAN_CHECK, failures)

    for needle in (
        "validateReleaseRequestAnchor",
        "request_revision must advance from",
        "release request commit must change only",
        "validateStagedReleaseAncestry",
        "next_version_increment must be patch, minor or major",
        "appendOutputs",
    ):
        require(parameter_resolver, needle, RELEASE_PARAMETER_RESOLVER, failures)

    for needle in (
        "CITATION.cff",
        "codemeta.json",
        "deploy/helm/taxonomy/Chart.yaml",
        "rootProjectVersion",
    ):
        require(version_state, needle, VERSION_STATE_CHECK, failures)

    for needle in (
        'case "resolve-release-parameters"',
        'case "check-version-state"',
        'case "check-release-plan"',
        'case "compare-versions"',
        'case "read-pom-version"',
    ):
        require(tooling_cli, needle, TOOLING_CLI, failures)

    for needle in (
        "DEFER_RELEASE_PUBLICATION=${DEFER_RELEASE_PUBLICATION:-false}",
        '"${TOOLING_JAR:?TOOLING_JAR is required}"',
        "run_maven_release_check()",
        "run_release_plan_check()",
        "check_version_state()",
        "stage_version_metadata()",
        "git ls-files -z -- 'pom.xml' ':(glob)**/pom.xml'",
        'java -jar "$TOOLING_JAR" compare-versions',
        'java -jar "$TOOLING_JAR" check-release-plan',
        'java -jar "$TOOLING_JAR" check-version-state',
        'java -jar "$TOOLING_JAR" read-pom-version --stdin',
        'run_release_plan_check "$RELEASE_CHECK_STATE" true',
        "run_release_plan_check release false",
        "run_maven_release_check release release-check,ci clean verify",
        "! -name 'taxonomy-tooling-*.jar'",
        'if [[ "$DEFER_RELEASE_PUBLICATION" == "true" ]]; then',
        "remains a draft until downstream artifacts and final CI succeed",
        'test "$RELEASE_IS_DRAFT" = true',
    ):
        require(script, needle, RELEASE_SCRIPT, failures)

    for forbidden in (
        "VERSION_STATE_HELPER",
        "resolve-release-parameters.py",
        "check-version-state.py",
        "check-release-plan.py",
        "python3 - <<'PY'\nimport os\nrelease =",
    ):
        forbid(script, forbidden, RELEASE_SCRIPT, failures)

    if script.count("\n  stage_version_metadata\n") != 2:
        failures.append(
            "release.sh must stage all tracked Maven POMs for both the release "
            "commit and the next-development commit"
        )
    if "git add pom.xml */pom.xml" in script:
        failures.append(
            "release.sh must not use a one-directory POM glob that omits nested modules"
        )
    if 'grep -R "SNAPSHOT" --include="pom.xml"' in script:
        failures.append(
            "release.sh must not scan unrelated POMs instead of validating the declared reactor"
        )
    if "./mvnw -B clean verify -Pci" in script:
        failures.append(
            "release.sh bypasses the Maven release-check profile during verification"
        )

    for needle in (
        "next_development_version:",
        "Exact next development version",
        "next_version_increment:",
        "type: choice",
        "INPUT_NEXT_DEVELOPMENT_VERSION:",
        "INPUT_NEXT_VERSION_INCREMENT:",
        "- name: Build Java release tooling",
        "./mvnw -B -pl taxonomy-tooling -am package -DskipTests",
        'run: java -jar "$RUNNER_TEMP/taxonomy-tooling.jar" resolve-release-parameters --root .',
        "./mvnw -B -pl taxonomy-tooling test",
        "TOOLING_JAR: ${{ runner.temp }}/taxonomy-tooling.jar",
        "group: release-main",
        "ref: ${{ github.event_name == 'push' && github.sha || 'main' }}",
        "SOURCE_BRANCH: ${{ github.ref_name }}",
        "if: steps.release_parameters.outputs.resume_staged_release != 'true'",
        "- name: Validate staged release for resume",
        "if: steps.release_parameters.outputs.resume_staged_release == 'true'",
        'git merge-base --is-ancestor "$tag" origin/main',
        "Release $tag must still be a draft before publication resumes",
        'java -jar "$RUNNER_TEMP/taxonomy-tooling.jar" check-version-state',
        "DEFER_RELEASE_PUBLICATION: 'true'",
        "cp .github/scripts/verify-exact-release-gates.sh",
        "bash -n .github/scripts/verify-exact-release-gates.sh",
        "timeout-minutes: 300",
        "- name: Record exact final main snapshot",
        "- name: Checkout immutable release source",
        "- name: Package and stage immutable Helm artifacts",
        "- name: Build and publish immutable release image",
        "- name: Scan immutable release image digest",
        "- name: Bind release evidence and Helm deployment to immutable image digest",
        "- name: Archive immutable release image evidence",
        "- name: Verify exact final main release gate matrix",
        "- name: Publish complete release and trigger deployment",
        "EXPECTED_MAIN_SHA: ${{ steps.final_main.outputs.sha }}",
        'run: bash "$RUNNER_TEMP/verify-exact-release-gates.sh"',
        'docker buildx imagetools inspect "$image"',
        "Draft release is missing required Helm asset",
        "Render deployment triggered after complete release publication.",
    ):
        require(workflow, needle, RELEASE_WORKFLOW, failures)

    for forbidden in (
        "run: python3 .github/scripts/resolve-release-parameters.py",
        "python3 .github/scripts/test-resolve-release-parameters.py",
        "check-version-state.py",
        "VERSION_STATE_HELPER:",
    ):
        forbid(workflow, forbidden, RELEASE_WORKFLOW, failures)

    for needle in (
        "EXPECTED_MAIN_SHA=${EXPECTED_MAIN_SHA:-}",
        "readonly -a REQUIRED_WORKFLOWS=(",
        "ci-cd.yml",
        "database-compatibility.yml",
        "codeql.yml",
        "security-scan.yml",
        "assert_main_unchanged",
        '--branch main',
        '--commit "$EXPECTED_MAIN_SHA"',
        'gh workflow run "$workflow" --ref main',
        "run_sha=$(gh run view \"$run_id\" --json headSha --jq '.headSha')",
        "watch_exit=0",
        'gh run watch "$run_id" --exit-status || watch_exit=$?',
        'if [[ "$status" != "completed" || "$conclusion" != "success" ]]',
        "if (( watch_exit != 0 ))",
        'echo "All exact release gates passed',
    ):
        require(gate_helper, needle, RELEASE_GATE_HELPER, failures)

    if gate_helper.count("assert_main_unchanged") < 4:
        failures.append(
            "verify-exact-release-gates.sh must bind discovery, dispatch "
            "and final readiness to unchanged main"
        )

    for forbidden in (
        "      release_version:",
        "      resume_staged_release:",
        "INPUT_RELEASE_VERSION:",
        "INPUT_RESUME_STAGED_RELEASE:",
        "group: release-${{ github.ref_name }}",
        "\n          ref: main\n",
        "SOURCE_BRANCH: main",
    ):
        if forbidden in workflow:
            failures.append(
                f"{RELEASE_WORKFLOW.relative_to(ROOT)} still exposes or forwards "
                f"unsafe or unsupported release configuration {forbidden!r}"
            )

    for needle in (
        "./mvnw -B -pl taxonomy-tooling -am package -DskipTests",
        'java -jar "$tooling_jar" check-release-plan',
        '--state development',
        '--require-clean true',
    ):
        require(ci_workflow, needle, CI_WORKFLOW, failures)
    for forbidden in (
        "python3 .github/scripts/test-resolve-release-parameters.py",
        "python3 .github/scripts/test-check-release-plan.py",
        "./mvnw -B -Prelease-check validate",
    ):
        forbid(ci_workflow, forbidden, CI_WORKFLOW, failures)

    if "main_sha=$(git rev-parse origin/main)" in workflow:
        failures.append(
            "deploy-release.yml must not substitute the then-current main SHA "
            "for the recorded publication candidate"
        )

    try:
        checkout = workflow.index("- name: Checkout authoritative release source")
        setup_java = workflow.index("- name: Set up JDK 21")
        build_tooling = workflow.index("- name: Build Java release tooling")
        resolve = workflow.index("- name: Resolve release parameters")
        stage = workflow.index(
            "- name: Build and stage release, then prepare next development version"
        )
        resume = workflow.index("- name: Validate staged release for resume")
        record_main = workflow.index("- name: Record exact final main snapshot")
        checkout_tag = workflow.index("- name: Checkout immutable release source")
        package = workflow.index("- name: Package and stage immutable Helm artifacts")
        image = workflow.index("- name: Build and publish immutable release image")
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
        if not (
            checkout
            < setup_java
            < build_tooling
            < resolve
            < stage
            < resume
            < record_main
            < final_gates
            < checkout_tag
            < package
            < image
            < scan
            < bind
            < archive
            < publish
        ):
            failures.append(
                "deploy-release.yml must bind checkout to the triggering source, "
                "build immutable Java tooling, then either stage or validate a "
                "resumable draft, record the exact main commit, verify its full "
                "gate matrix, then fetch and build the immutable tag, scan and "
                "bind the pushed image digest, and publish last"
            )

        checkout_block = workflow[checkout:setup_java]
        exact_push_source = "ref: ${{ github.event_name == 'push' && github.sha || 'main' }}"
        if exact_push_source not in checkout_block:
            failures.append(
                "Release checkout must use the exact triggering SHA for push events "
                "and authoritative main for manual dispatch"
            )

        stage_block = workflow[stage:resume]
        if "resume_staged_release != 'true'" not in stage_block:
            failures.append("Normal release staging must be skipped in resume mode")
        if "SOURCE_BRANCH: ${{ github.ref_name }}" not in stage_block:
            failures.append(
                "Release staging must preserve the actual dispatch branch for the "
                "release script's main-only publication guard"
            )
        if "RENDER_DEPLOY_HOOK_URL" in stage_block:
            failures.append("The staging step must not receive the deployment hook")

        resume_block = workflow[resume:record_main]
        for needle in (
            "resume_staged_release == 'true'",
            'git fetch origin "refs/heads/main:refs/remotes/origin/main" --force',
            'git fetch origin "refs/tags/${tag}:refs/tags/${tag}"',
            'git merge-base --is-ancestor "$tag" origin/main',
            '--mode release',
            '--expected-version "$RELEASE_VERSION" --tag "$tag"',
            '--mode development',
            '--expected-version "$NEXT_VERSION_INPUT"',
            'if [[ "$is_draft" != "true" ]]; then',
        ):
            if needle not in resume_block:
                failures.append(f"Resume validation is missing {needle!r}")
        if "RENDER_DEPLOY_HOOK_URL" in resume_block:
            failures.append("Resume validation must not receive the deployment hook")

        immutable_checkout_block = workflow[checkout_tag:package]
        tag_fetch = 'git fetch origin "refs/tags/${tag}:refs/tags/${tag}"'
        tag_checkout = 'git checkout --detach "$tag"'
        if tag_fetch not in immutable_checkout_block:
            failures.append("Immutable checkout must explicitly fetch the remote tag")
        if tag_checkout not in immutable_checkout_block:
            failures.append("Immutable checkout must detach at the fetched tag")
        if (
            tag_fetch in immutable_checkout_block
            and tag_checkout in immutable_checkout_block
            and immutable_checkout_block.index(tag_fetch)
            > immutable_checkout_block.index(tag_checkout)
        ):
            failures.append("Immutable tag must be fetched before it is checked out")

        final_gate_block = workflow[final_gates:checkout_tag]
        for needle in (
            "EXPECTED_MAIN_SHA: ${{ steps.final_main.outputs.sha }}",
            'run: bash "$RUNNER_TEMP/verify-exact-release-gates.sh"',
        ):
            if needle not in final_gate_block:
                failures.append(f"Exact final gate step is missing {needle!r}")

        publish_block = workflow[publish:]
        for needle in (
            'gh release edit "$tag"',
            "--notes-file release_notes.md",
            "--draft=false",
            "--latest",
        ):
            if needle not in publish_block:
                failures.append(f"Final publication step is missing {needle!r}")
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
        "Release delivery contract is Maven/JUnit-verifiable, atomic, freely "
        "versionable, resumable and digest-bound: dependency-free Java tooling "
        "validates the release plan and version state without SCM mutation, "
        "immutable sources and images are verified explicitly, the exact main "
        "snapshot passes CI, database, CodeQL and security gates before immutable "
        "artifact work, and publication remains the final operation."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
