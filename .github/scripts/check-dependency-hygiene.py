#!/usr/bin/env python3
"""Validate the packaged CycloneDX SBOM against Taxonomy dependency policy."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import re
from pathlib import Path


def component_key(component: dict) -> tuple[str, str, str]:
    return (
        str(component.get("group", "")),
        str(component.get("name", "")),
        str(component.get("version", "")),
    )


def version_major(version: str) -> int | None:
    match = re.match(r"^(\d+)", version)
    return int(match.group(1)) if match else None


def load_exceptions(path: Path) -> list[dict]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, list):
        raise ValueError("dependency hygiene exceptions must be a JSON array")
    today = dt.date.today()
    valid = []
    for item in data:
        required = {"group", "name", "version", "owner", "rationale", "expires", "removalCondition"}
        missing = sorted(required - set(item))
        if missing:
            raise ValueError(f"exception is missing fields {missing}: {item}")
        expiry = dt.date.fromisoformat(item["expires"])
        if expiry < today:
            raise ValueError(f"expired dependency exception: {item['group']}:{item['name']}:{item['version']}")
        valid.append(item)
    return valid


def is_excepted(component: dict, exceptions: list[dict]) -> bool:
    group, name, version = component_key(component)
    return any(
        item["group"] == group and item["name"] == name and item["version"] == version
        for item in exceptions
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sbom", default="target/taxonomy-sbom.json")
    parser.add_argument("--expected-pdfbox-version")
    parser.add_argument("--exceptions", default=".github/dependency-hygiene-exceptions.json")
    parser.add_argument("--report", default="target/dependency-hygiene-report.txt")
    args = parser.parse_args()

    sbom_path = Path(args.sbom)
    if not sbom_path.is_file():
        raise SystemExit(f"SBOM not found: {sbom_path}")
    exceptions = load_exceptions(Path(args.exceptions))
    components = json.loads(sbom_path.read_text(encoding="utf-8")).get("components", [])

    banned: list[dict] = []
    pdfbox_components: list[dict] = []
    for component in components:
        group, name, version = component_key(component)
        if group == "org.apache.pdfbox":
            pdfbox_components.append(component)
            major = version_major(version)
            if major is None or major < 3 or name == "xmpbox":
                banned.append(component)
        if group == "com.vladsch.flexmark" and name == "flexmark-pdf-converter":
            banned.append(component)
        if group.startswith("com.openhtmltopdf") and "pdfbox" in name.lower():
            banned.append(component)

    banned = [component for component in banned if not is_excepted(component, exceptions)]
    intended_names = {"pdfbox", "pdfbox-io", "fontbox"}
    actual_intended = {
        name: version
        for group, name, version in map(component_key, pdfbox_components)
        if group == "org.apache.pdfbox" and name in intended_names
    }
    missing = sorted(intended_names - set(actual_intended))
    versions = sorted(set(actual_intended.values()))
    expected_mismatch = (
        args.expected_pdfbox_version
        and any(version != args.expected_pdfbox_version for version in actual_intended.values())
    )

    lines = ["Taxonomy packaged dependency hygiene", ""]
    for group, name, version in sorted(map(component_key, pdfbox_components)):
        lines.append(f"PDFBox family: {group}:{name}:{version}")
    lines.append("")
    if exceptions:
        lines.append(f"Active reviewed exceptions: {len(exceptions)}")
    if banned:
        lines.append("BANNED packaged components:")
        lines.extend(f"- {':'.join(component_key(component))}" for component in banned)
    if missing:
        lines.append("Missing intended PDFBox components: " + ", ".join(missing))
    if len(versions) > 1:
        lines.append("PDFBox family versions are not aligned: " + ", ".join(versions))
    if expected_mismatch:
        lines.append(
            f"PDFBox family does not match expected version {args.expected_pdfbox_version}: "
            + ", ".join(versions)
        )

    failed = bool(banned or missing or len(versions) > 1 or expected_mismatch)
    lines.append("")
    lines.append("Result: FAIL" if failed else "Result: PASS")
    report = "\n".join(lines) + "\n"
    Path(args.report).parent.mkdir(parents=True, exist_ok=True)
    Path(args.report).write_text(report, encoding="utf-8")
    print(report, end="")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
