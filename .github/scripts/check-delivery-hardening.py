#!/usr/bin/env python3
"""Fail closed when report publication or Render deployment loses provenance."""

from __future__ import annotations

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
CI = ROOT / ".github" / "workflows" / "ci-cd.yml"
CORE_CONTRACT = ROOT / ".github" / "scripts" / "ci-core-contracts.sh"
STAGE_CORE_REPORTS = ROOT / ".github" / "scripts" / "stage-core-quality-reports.sh"
FINALIZE_QUALITY_EVIDENCE = (
    ROOT / ".github" / "scripts" / "finalize-quality-evidence.sh"
)
DELIVERY = ROOT / ".github" / "workflows" / "delivery.yml"
QUALITY_VERIFY = ROOT / ".github" / "scripts" / "verify-quality-publication.py"
DEPLOY_VERIFY = ROOT / ".github" / "scripts" / "verify-deployment.py"
CSS = (
    ROOT
    / "taxonomy-app"
    / "src"
    / "main"
    / "resources"
    / "static"
    / "css"
    / "taxonomy-ergonomics.css"
)
I18N = (
    ROOT
    / "taxonomy-app"
    / "src"
    / "main"
    / "resources"
    / "static"
    / "js"
    / "taxonomy-i18n.js"
)
UI_EVIDENCE = ROOT / ".github" / "scripts" / "ui-role-state-evidence.mjs"
RANCHER = ROOT / "deploy" / "helm" / "taxonomy" / "values-rancher-rke2.yaml"
SMALL = ROOT / "deploy" / "helm" / "taxonomy" / "values-small.yaml"
MODEL_DOWNLOAD = ROOT / ".github" / "scripts" / "download-embedding-model.sh"
DOCKERFILE = ROOT / "Dockerfile"


def require(text: str, needle: str, source: Path, failures: list[str]) -> None:
    if needle not in text:
        failures.append(f"{source.relative_to(ROOT)} is missing {needle!r}")


def main() -> int:
    ci = CI.read_text(encoding="utf-8")
    core_contract = CORE_CONTRACT.read_text(encoding="utf-8")
    stage_core_reports = STAGE_CORE_REPORTS.read_text(encoding="utf-8")
    finalizer = FINALIZE_QUALITY_EVIDENCE.read_text(encoding="utf-8")
    delivery = DELIVERY.read_text(encoding="utf-8")
    quality_verify = QUALITY_VERIFY.read_text(encoding="utf-8")
    deploy_verify = DEPLOY_VERIFY.read_text(encoding="utf-8")
    css = CSS.read_text(encoding="utf-8")
    i18n = I18N.read_text(encoding="utf-8")
    ui_evidence = UI_EVIDENCE.read_text(encoding="utf-8")
    rancher = RANCHER.read_text(encoding="utf-8")
    small = SMALL.read_text(encoding="utf-8")
    model_download = MODEL_DOWNLOAD.read_text(encoding="utf-8")
    dockerfile = DOCKERFILE.read_text(encoding="utf-8")
    failures: list[str] = []

    # The workflow owns orchestration. Repository scripts own the delegated
    # verification and evidence-building details, so validate both sides of
    # every delegation rather than duplicating implementation strings in YAML.
    for needle in (
        "core_contract=.github/scripts/ci-core-contracts.sh",
        'bash "$core_contract"',
        "run: bash .github/scripts/stage-core-quality-reports.sh",
        "run: bash .github/scripts/finalize-quality-evidence.sh",
        "Restore pinned embedding model",
        "actions/cache@55cc8345863c7cc4c66a329aec7e433d2d1c52a9",
    ):
        require(ci, needle, CI, failures)

    for needle in (
        "set -euo pipefail",
        "python3 .github/scripts/test-generate-quality-site.py",
        "python3 .github/scripts/test-verify-quality-publication.py",
        "python3 .github/scripts/test-verify-deployment.py",
        "node .github/scripts/test-taxonomy-base-path.mjs",
        "python3 .github/scripts/check-delivery-hardening.py",
    ):
        require(core_contract, needle, CORE_CONTRACT, failures)

    for needle in (
        "set -euo pipefail",
        "taxonomy-coverage/target/site/jacoco-aggregate",
        "Aggregate JaCoCo XML is missing",
        'source_tree=$(git rev-parse "${GITHUB_SHA}^{tree}")',
        'build_id="${GITHUB_RUN_ID}.${GITHUB_RUN_ATTEMPT}.core"',
        "Staged core evidence",
        "Core build ID",
    ):
        require(stage_core_reports, needle, STAGE_CORE_REPORTS, failures)
    for final_only in (
        "generate-quality-site.py",
        "verify-quality-publication.py",
    ):
        if final_only in stage_core_reports:
            failures.append(
                f"{STAGE_CORE_REPORTS.relative_to(ROOT)} must leave {final_only} "
                "to the final evidence gate"
            )

    for needle in (
        "set -euo pipefail",
        "target/observability-performance",
        "target/ui-verification/summary.json",
        'source_tree=$(git rev-parse "${GITHUB_SHA}^{tree}")',
        'build_id="${GITHUB_RUN_ID}.${GITHUB_RUN_ATTEMPT}"',
        "python3 .github/scripts/generate-quality-site.py",
        "python3 .github/scripts/verify-quality-publication.py",
        '--root target/quality-reports --commit "$GITHUB_SHA"',
        '--source-tree "$source_tree" --build-id "$build_id"',
        '--tool "java=$(java -version 2>&1 | sed -n \'1p\')"',
        '--tool "maven=$(./mvnw -version 2>&1 | sed -n \'1p\')"',
        '--root target/quality-reports --expected-commit "$GITHUB_SHA"',
        "Verified commit:",
        "Build ID:",
    ):
        require(finalizer, needle, FINALIZE_QUALITY_EVIDENCE, failures)

    for needle in (
        "Checkout verified quality tooling",
        "Verify report provenance and badge consistency",
        "Verify published GitHub Pages evidence",
        "QUALITY_REPORT_BASE_URL",
        "verify-quality-publication.py",
        '--base-url "$base_url"',
        "EXPECTED_SHA: ${{ github.event.workflow_run.head_sha }}",
        "keep_files: false",
        "force_orphan: true",
        'query["ref"] = expected',
        "render-deploy-hook.json",
        "render-hook-failure.json",
        "if ! curl --fail --silent --show-error --retry 3",
        "--connect-timeout 15 --max-time 90",
        "Render deploy hook request failed after bounded retries.",
        "render-verification.json",
        "RENDER_API_KEY",
        "RENDER_SERVICE_ID",
        "RENDER_DEPLOY_ENABLED",
        "name: Render deployment disabled",
        "vars.RENDER_DEPLOY_ENABLED != 'true'",
        "vars.RENDER_DEPLOY_ENABLED == 'true'",
        '"deploymentState": "disabled"',
        '"deploymentState": "triggered"',
        "RENDER_DEPLOY_ENABLED is true but RENDER_DEPLOY_HOOK_URL is missing",
        "BASE_URL: ${{ vars.RENDER_BASE_URL || 'https://taxonomy-analyzer.onrender.com' }}",
        "python3 .github/scripts/verify-deployment.py",
        "render-deployment-evidence-${{ github.event.workflow_run.head_sha }}",
    ):
        require(delivery, needle, DELIVERY, failures)
    if "keep_files: true" in delivery:
        failures.append(
            "delivery.yml must replace the report tree atomically, not retain stale files"
        )
    if (
        "RENDER_DEPLOY_HOOK_URL is not configured; Render deployment is disabled."
        in delivery
    ):
        failures.append(
            "enabled Render delivery must fail closed when its hook secret is missing"
        )
    if "if-no-files-found: warn" in delivery.split("  deploy-render:", 1)[-1]:
        failures.append("Render delivery evidence must never be optional")

    for needle in (
        '"sourceTree"',
        '"buildId"',
        '"tools"',
        "expected_test_message",
        "expected_coverage_message",
        "verify_remote_once",
        '"Cache-Control": "no-cache"',
    ):
        require(quality_verify, needle, QUALITY_VERIFY, failures)

    for needle in (
        "RENDER_FAILURE_STATES",
        "fetch_render_deploy",
        "renderDeployId",
        "renderDeployStatus",
        '"deploymentState": "verifying"',
        '"deploymentState": "succeeded"',
        '"deploymentState": "failed"',
        "root smoke test",
        "write_evidence(args.evidence_file, evidence)",
    ):
        require(deploy_verify, needle, DEPLOY_VERIFY, failures)

    for needle in (
        '.card-body[style*="max-height"]:not(:has(> #taxonomyTree))',
        ".card-body:has(> #taxonomyTree)",
        "max-height: min(65vh, 42rem) !important;",
    ):
        require(css, needle, CSS, failures)
    for needle in (
        "detectApplicationBasePath",
        "resolveApplicationUrl",
        "installBasePathAwareFetch",
        "window.fetch = wrappedFetch",
    ):
        require(i18n, needle, I18N, failures)

    for needle in (
        "measureTaxonomyTreeViewport",
        "maxAllowedHeight",
        "Taxonomy tree viewport is unbounded",
    ):
        require(ui_evidence, needle, UI_EVIDENCE, failures)

    for needle in (
        "cpu: 100m",
        'cpu: "500m"',
        "memory: 768Mi",
        "memory: 1536Mi",
        'TAXONOMY_EMBEDDING_ENABLED: "false"',
        'TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD: "false"',
        "TAXONOMY_SEARCH_DIRECTORY_TYPE: local-heap",
        "MaxRAMPercentage=65.0",
    ):
        require(small, needle, SMALL, failures)
    if 'cpu: "2"' in small:
        failures.append("values-small.yaml must not use the universal two-CPU limit")

    for needle in (
        'nginx.ingress.kubernetes.io/rewrite-target: "/$2"',
        'nginx.ingress.kubernetes.io/x-forwarded-prefix: "/taxonomy"',
        "path: /taxonomy(/|$)(.*)",
        'cpu: "500m"',
    ):
        require(rancher, needle, RANCHER, failures)

    for needle in (
        "model_is_valid",
        "--retry 12",
        "--retry-max-time 600",
        "mktemp -d",
        "MODEL_PROVENANCE.txt",
    ):
        require(model_download, needle, MODEL_DOWNLOAD, failures)

    for needle in (
        "ARG VCS_REF=unknown",
        "git.commit.id=%s",
        "taxonomy-app/src/main/resources/git.properties",
    ):
        require(dockerfile, needle, DOCKERFILE, failures)

    if failures:
        print("Delivery hardening contract failed:", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1

    print(
        "Delivery hardening contract passed: CI delegates to fail-closed repository "
        "scripts whose report tests, aggregate coverage staging, commit/tree/build/tool "
        "provenance and final-only UI/observability consolidation, publication generation "
        "and verification are all checked; reports are atomically published and remotely "
        "re-verified; Render is pinned to the verified commit, records explicit "
        "disabled/triggered/verifying/succeeded/failed evidence, archives hook transport "
        "failures and can poll platform status; responsive tree height remains bounded, "
        "the container exposes its build commit, and the Rancher prefix profile is explicit."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
