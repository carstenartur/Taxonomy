#!/usr/bin/env python3
"""Prevent new direct REST calls outside the frontend API layer.

Existing legacy files are listed explicitly and must be reduced as they are migrated.
Adding a new file to the allowlist is not an acceptable feature implementation.
"""

from __future__ import annotations

from pathlib import Path
import re
import sys


ROOT = Path.cwd()
STATIC_JS = ROOT / "taxonomy-app" / "src" / "main" / "resources" / "static" / "js"
TEMPLATE = ROOT / "taxonomy-app" / "src" / "main" / "resources" / "templates" / "index.html"
DIRECT_API_FETCH = re.compile(r"\bfetch\s*\(\s*['\"]\/api\/")

# Temporary migration inventory. Every removed entry is an architecture improvement.
LEGACY_ALLOWLIST = {
    "taxonomy-i18n.js",
    "core/taxonomy-analysis.js",
    "core/taxonomy-browse.js",
    "core/taxonomy-scoring.js",
    "relations/taxonomy-coverage.js",
    "relations/taxonomy-quality.js",
    "relations/taxonomy-relations.js",
    "shared/taxonomy-about.js",
    "shared/taxonomy-action-guards.js",
    "shared/taxonomy-dsl-editor.js",
    "shared/taxonomy-export.js",
    "shared/taxonomy-graph.js",
    "shared/taxonomy-search.js",
    "versioning/taxonomy-context-bar.js",
    "versioning/taxonomy-context-compare.js",
    "versioning/taxonomy-context-transfer.js",
    "versioning/taxonomy-history-search.js",
    "versioning/taxonomy-variants.js",
    "versioning/taxonomy-versions.js",
    "versioning/taxonomy-viewcontext.js",
    "workspace/taxonomy-git-status.js",
    "workspace/taxonomy-merge-resolution.js",
    "workspace/taxonomy-workspace-provisioning.js",
    "workspace/taxonomy-workspace-sync.js",
}


def matches(path: Path) -> list[int]:
    lines = path.read_text(encoding="utf-8").splitlines()
    return [number for number, line in enumerate(lines, start=1) if DIRECT_API_FETCH.search(line)]


def main() -> int:
    unexpected: list[str] = []
    legacy_counts: dict[str, int] = {}

    for path in sorted(STATIC_JS.rglob("*.js")):
        relative = path.relative_to(STATIC_JS).as_posix()
        line_numbers = matches(path)
        if not line_numbers or relative.startswith("api/"):
            continue
        if relative in LEGACY_ALLOWLIST:
            legacy_counts[relative] = len(line_numbers)
        else:
            unexpected.append(f"{relative}: direct /api fetch at lines {line_numbers}")

    template_lines = matches(TEMPLATE)
    if template_lines:
        legacy_counts["templates/index.html"] = len(template_lines)

    missing_inventory = sorted(LEGACY_ALLOWLIST - set(legacy_counts))
    if missing_inventory:
        print("Legacy frontend API allowlist can be reduced:")
        for path in missing_inventory:
            print(f"- remove {path}")

    print("Legacy direct-fetch inventory:")
    for path, count in sorted(legacy_counts.items()):
        print(f"- {path}: {count}")

    if unexpected:
        print("Frontend API boundary violations:", file=sys.stderr)
        for violation in unexpected:
            print(f"- {violation}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
