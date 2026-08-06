#!/usr/bin/env python3
"""Fail closed when report publication or Render deployment loses provenance."""

from __future__ import annotations

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
CI = ROOT / ".github" / "workflows" / "ci-cd.yml"
DELIVERY = ROOT / ".github" / "workflows" / "delivery.yml"
CSS = ROOT / "taxonomy-app" / "src" / "main" / "resources" / "static" / "css" / "taxonomy-ergonomics.css"
I18N = ROOT / "taxonomy-app" / "src" / "main" / "resources" / "static" / "js" / "taxonomy-i18n.js"
UI_EVIDENCE = ROOT / ".github" / "scripts" / "ui-role-state-evidence.mjs"
RANCHER = ROOT / "deploy" / "helm" / "taxonomy" / "values-rancher-rke2.yaml"
DOCKERFILE = ROOT / "Dockerfile"


def require(text: str, needle: str, source: Path, failures: list[str]) -> None:
    if needle not in text:
        failures.append(f"{source.relative_to(ROOT)} is missing {needle!r}")


def main() -> int:
    ci = CI.read_text(encoding="utf-8")
    delivery = DELIVERY.read_text(encoding="utf-8")
    css = CSS.read_text(encoding="utf-8")
    i18n = I18N.read_text(encoding="utf-8")
    ui_evidence = UI_EVIDENCE.read_text(encoding="utf-8")
    rancher = RANCHER.read_text(encoding="utf-8")
    dockerfile = DOCKERFILE.read_text(encoding="utf-8")
    failures: list[str] = []

    for needle in (
        "python3 .github/scripts/test-generate-quality-site.py",
        "python3 .github/scripts/test-verify-deployment.py",
        "node .github/scripts/test-taxonomy-base-path.mjs",
        "python3 .github/scripts/check-delivery-hardening.py",
        "python3 .github/scripts/generate-quality-site.py",
        '--commit "$GITHUB_SHA"',
        "taxonomy-coverage/target/site/jacoco-aggregate/jacoco.xml",
    ):
        require(ci, needle, CI, failures)

    for needle in (
        "quality-summary.json",
        "EXPECTED_SHA: ${{ github.event.workflow_run.head_sha }}",
        "keep_files: false",
        "force_orphan: true",
        "BASE_URL: ${{ vars.RENDER_BASE_URL || 'https://taxonomy-analyzer.onrender.com' }}",
        "python3 .github/scripts/verify-deployment.py",
        '--expected-commit "$EXPECTED_SHA"',
    ):
        require(delivery, needle, DELIVERY, failures)
    if "keep_files: true" in delivery:
        failures.append("delivery.yml must replace the report tree atomically, not retain stale files")

    for needle in (
        '.card-body[style*="max-height"]:not(:has(> #taxonomyTree))',
        '.card-body:has(> #taxonomyTree)',
        'max-height: min(65vh, 42rem) !important;',
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
        'nginx.ingress.kubernetes.io/rewrite-target: "/$2"',
        'nginx.ingress.kubernetes.io/x-forwarded-prefix: "/taxonomy"',
        "path: /taxonomy(/|$)(.*)",
        'cpu: "500m"',
    ):
        require(rancher, needle, RANCHER, failures)

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
        "Delivery hardening contract passed: reports are commit-bound and atomic, "
        "Render verification proves the deployed SHA, responsive tree height remains "
        "bounded, the container exposes its build commit, and the Rancher prefix profile is explicit."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
