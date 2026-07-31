#!/usr/bin/env python3
"""Fail closed when Maven, release metadata and Helm disagree about the version state."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
import xml.etree.ElementTree as ET

MAVEN_NS = {"m": "http://maven.apache.org/POM/4.0.0"}
RELEASE_VERSION = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")
DEVELOPMENT_VERSION = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+-SNAPSHOT$")


def text(element: ET.Element, path: str) -> str | None:
    value = element.findtext(path, namespaces=MAVEN_NS)
    return value.strip() if value and value.strip() else None


def root_version(root: Path) -> str:
    project = ET.parse(root / "pom.xml").getroot()
    version = text(project, "m:version")
    if not version:
        raise ValueError("Root pom.xml has no project version")
    return version


def pom_failures(root: Path, expected: str) -> list[str]:
    failures: list[str] = []
    for pom in sorted(root.rglob("pom.xml")):
        if "target" in pom.parts or ".git" in pom.parts:
            continue
        project = ET.parse(pom).getroot()
        parent_group = text(project, "m:parent/m:groupId")
        parent_artifact = text(project, "m:parent/m:artifactId")
        parent_version = text(project, "m:parent/m:version")
        if parent_group == "com.taxonomy" and parent_artifact == "taxonomy":
            if parent_version != expected:
                failures.append(
                    f"{pom.relative_to(root)} parent version {parent_version!r} != {expected!r}"
                )
        group = text(project, "m:groupId") or parent_group
        own_version = text(project, "m:version")
        if group == "com.taxonomy" and own_version and own_version != expected:
            failures.append(
                f"{pom.relative_to(root)} project version {own_version!r} != {expected!r}"
            )
    return failures


def metadata_failures(root: Path, expected: str, release_mode: bool) -> list[str]:
    failures: list[str] = []

    citation = (root / "CITATION.cff").read_text(encoding="utf-8")
    cff_version = re.search(r'^version: "([^"]+)"$', citation, flags=re.MULTILINE)
    if not cff_version or cff_version.group(1) != expected:
        failures.append("CITATION.cff version does not match the Maven version")
    cff_has_date = bool(re.search(r"^date-released: ", citation, flags=re.MULTILINE))
    if cff_has_date != release_mode:
        failures.append("CITATION.cff release-date state does not match the requested mode")

    citation_md = (root / "CITATION.md").read_text(encoding="utf-8")
    preferred = re.search(
        r"Taxonomy Architecture Analyzer\*\*\. Version ([0-9A-Za-z.-]+)\.", citation_md
    )
    bibtex = re.search(r"^\s*version\s+= \{([^}]+)\},$", citation_md, flags=re.MULTILINE)
    if not preferred or preferred.group(1) != expected:
        failures.append("CITATION.md preferred citation version does not match")
    if not bibtex or bibtex.group(1) != expected:
        failures.append("CITATION.md BibTeX version does not match")
    md_has_date = bool(re.search(r"^\s*date\s+= \{[^}]+\},$", citation_md, flags=re.MULTILINE))
    if md_has_date != release_mode:
        failures.append("CITATION.md release-date state does not match the requested mode")

    for name, version_key, date_key in (
        (".zenodo.json", "version", "publication_date"),
        ("codemeta.json", "version", "datePublished"),
    ):
        data = json.loads((root / name).read_text(encoding="utf-8"))
        if data.get(version_key) != expected:
            failures.append(f"{name} version does not match")
        if (date_key in data) != release_mode:
            failures.append(f"{name} release-date state does not match the requested mode")

    chart = root / "deploy" / "helm" / "taxonomy" / "Chart.yaml"
    if chart.is_file():
        chart_text = chart.read_text(encoding="utf-8")
        match = re.search(r'^appVersion:\s*["\']?([^"\'\s]+)["\']?\s*$', chart_text, re.MULTILINE)
        if not match or match.group(1) != expected:
            failures.append("Helm Chart.yaml appVersion does not match the Maven version")

    return failures


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--mode", choices=("development", "release"), required=True)
    parser.add_argument("--expected-version")
    parser.add_argument("--tag", help="Optional release tag, for example v1.2.3")
    args = parser.parse_args()

    root = args.root.resolve()
    try:
        actual = root_version(root)
    except (OSError, ET.ParseError, ValueError) as error:
        print(f"Version-state check failed: {error}", file=sys.stderr)
        return 1

    expected = args.expected_version or actual
    failures: list[str] = []
    if actual != expected:
        failures.append(f"Root Maven version {actual!r} != expected {expected!r}")

    release_mode = args.mode == "release"
    pattern = RELEASE_VERSION if release_mode else DEVELOPMENT_VERSION
    if not pattern.fullmatch(actual):
        failures.append(f"Version {actual!r} is not a valid {args.mode} version")

    if args.tag:
        expected_tag = f"v{actual}"
        if not release_mode:
            failures.append("A release tag is only valid in release mode")
        elif args.tag != expected_tag:
            failures.append(f"Tag {args.tag!r} != expected {expected_tag!r}")

    try:
        failures.extend(pom_failures(root, actual))
        failures.extend(metadata_failures(root, actual, release_mode))
    except (OSError, ET.ParseError, json.JSONDecodeError) as error:
        failures.append(f"Could not inspect version metadata: {error}")

    if failures:
        print("Inconsistent repository version state:", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1

    print(f"Repository version state is consistent: {actual} ({args.mode}).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
