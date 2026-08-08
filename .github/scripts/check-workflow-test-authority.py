#!/usr/bin/env python3
"""Reject GitHub workflow test logic that is not exposed through Maven."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / ".github" / "workflows"
CATALOG = ROOT / ".mvn" / "verification-suites.json"
REMOVED = {
    "accessibility.yml",
    "archimate-import-evidence.yml",
    "core-integration.yml",
    "docs-link-check.yml",
    "document-import-evidence.yml",
    "frontend-architecture.yml",
    "generate-screenshots.yml",
    "hibernate-search-alignment.yml",
    "pipeline-tests.yml",
    "qa-architecture-evidence.yml",
    "ui-acceptance.yml",
    "ui-primary-workflow-acceptance.yml",
    "ui-role-state-acceptance.yml",
    "ui-special-modes-acceptance.yml",
}
DIRECT_TEST_PATTERNS = {
    "direct Maven executable": re.compile(r"(?<![./])\bmvn(?:\.cmd)?\b"),
    "direct browser/a11y script": re.compile(
        r"\bnode\s+\.github/scripts/(?:ui-|accessibility-audit)"
    ),
    "workflow-owned Java test selection": re.compile(r"(?:-Dtest=|-Dit\.test=|failsafe:integration-test)"),
    "workflow-owned local quality test": re.compile(
        r"python3\s+\.github/scripts/(?:test-check-coverage|check-coverage|"
        r"check-doc-links|check-frontend-api-boundaries|check-hibernate-search-alignment|"
        r"check-dependency-hygiene)\.py"
    ),
}


def run_blocks(text: str) -> str:
    """Return only scalar content belonging to run: keys, conservatively."""
    lines = text.splitlines()
    result: list[str] = []
    index = 0
    while index < len(lines):
        line = lines[index]
        match = re.match(r"^(\s*)run:\s*(.*)$", line)
        if not match:
            index += 1
            continue
        indent = len(match.group(1))
        tail = match.group(2)
        if tail not in {"|", ">", "|-", ">-"}:
            result.append(tail)
            index += 1
            continue
        index += 1
        while index < len(lines):
            candidate = lines[index]
            stripped = candidate.lstrip()
            if stripped and len(candidate) - len(stripped) <= indent:
                break
            result.append(stripped)
            index += 1
    return "\n".join(result)


def main() -> int:
    errors: list[str] = []
    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    if catalog.get("canonicalCommand") != "./mvnw -B verify -Pci":
        errors.append("verification catalogue must declare './mvnw -B verify -Pci'")

    responsibilities = catalog.get("workflowResponsibilities")
    if not isinstance(responsibilities, dict) or not responsibilities:
        errors.append("verification catalogue must classify workflow responsibilities")
        classified_workflows: set[str] = set()
    else:
        invalid_responsibilities = [
            name
            for name, purpose in responsibilities.items()
            if not isinstance(name, str)
            or not name.endswith(".yml")
            or not isinstance(purpose, str)
            or not purpose.strip()
        ]
        if invalid_responsibilities:
            errors.append(
                "verification catalogue contains invalid workflow responsibilities: "
                + ", ".join(sorted(str(name) for name in invalid_responsibilities))
            )
        classified_workflows = {
            name for name in responsibilities if isinstance(name, str) and name.endswith(".yml")
        }

    workflow_files = {path.name for path in WORKFLOWS.glob("*.yml")}
    unexpected = workflow_files - classified_workflows
    missing = classified_workflows - workflow_files
    if unexpected:
        errors.append(f"unclassified workflows remain: {', '.join(sorted(unexpected))}")
    if missing:
        errors.append(f"documented workflows missing: {', '.join(sorted(missing))}")
    lingering = workflow_files & REMOVED
    if lingering:
        errors.append(f"redundant workflows were not removed: {', '.join(sorted(lingering))}")

    for path in sorted(WORKFLOWS.glob("*.yml")):
        commands = run_blocks(path.read_text(encoding="utf-8"))
        for description, pattern in DIRECT_TEST_PATTERNS.items():
            if pattern.search(commands):
                errors.append(f"{path.relative_to(ROOT)} contains {description}; invoke a Maven profile instead")

    ci_text = (WORKFLOWS / "ci-cd.yml").read_text(encoding="utf-8")
    if "./mvnw -B verify -Pci" not in ci_text:
        errors.append("ci-cd.yml must invoke the canonical Maven command unchanged")
    db_text = (WORKFLOWS / "database-compatibility.yml").read_text(encoding="utf-8")
    for profile in ("database-postgres", "database-mssql", "database-oracle"):
        if f"-P{profile}" not in db_text:
            errors.append(f"database workflow must invoke Maven-owned profile {profile}")

    if errors:
        print("Maven test-authority policy failed:", file=sys.stderr)
        for error in errors:
            print(f" - {error}", file=sys.stderr)
        return 1
    print(f"Maven test-authority policy passed for {len(workflow_files)} workflows")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
