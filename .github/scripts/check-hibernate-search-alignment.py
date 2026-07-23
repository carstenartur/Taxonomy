#!/usr/bin/env python3
"""Validate the resolved Hibernate Search/ORM/Lucene dependency set."""

from __future__ import annotations

import argparse
import re
from pathlib import Path

COORDINATE = re.compile(
    r"(?P<group>org\.hibernate\.search|org\.hibernate\.orm|org\.apache\.lucene):"
    r"(?P<artifact>[A-Za-z0-9_.-]+):(?:[A-Za-z0-9_.-]+:)*(?P<version>[0-9][^:\s]*)"
)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tree", required=True, type=Path)
    parser.add_argument("--search-version", required=True)
    parser.add_argument("--orm-prefix", default="7.4.")
    parser.add_argument("--lucene-version", default="9.12.3")
    args = parser.parse_args()

    text = args.tree.read_text(encoding="utf-8")
    resolved: dict[tuple[str, str], set[str]] = {}
    for match in COORDINATE.finditer(text):
        key = (match.group("group"), match.group("artifact"))
        resolved.setdefault(key, set()).add(match.group("version"))

    failures: list[str] = []
    search_entries = {
        artifact: versions
        for (group, artifact), versions in resolved.items()
        if group == "org.hibernate.search"
    }
    if not search_entries:
        failures.append("No org.hibernate.search artifacts were found in the dependency tree")
    for artifact, versions in sorted(search_entries.items()):
        if versions != {args.search_version}:
            failures.append(
                f"{artifact} resolved to {sorted(versions)}, expected only {args.search_version}"
            )

    orm_versions = resolved.get(("org.hibernate.orm", "hibernate-core"), set())
    if len(orm_versions) != 1 or not next(iter(orm_versions), "").startswith(args.orm_prefix):
        failures.append(
            f"hibernate-core resolved to {sorted(orm_versions)}, expected one {args.orm_prefix}x version"
        )

    lucene_versions = resolved.get(("org.apache.lucene", "lucene-core"), set())
    if lucene_versions != {args.lucene_version}:
        failures.append(
            f"lucene-core resolved to {sorted(lucene_versions)}, expected {args.lucene_version}"
        )

    report = ["Hibernate Search dependency alignment", ""]
    report.extend(
        f"- org.hibernate.search:{artifact} = {', '.join(sorted(versions))}"
        for artifact, versions in sorted(search_entries.items())
    )
    report.append(f"- org.hibernate.orm:hibernate-core = {', '.join(sorted(orm_versions)) or 'missing'}")
    report.append(f"- org.apache.lucene:lucene-core = {', '.join(sorted(lucene_versions)) or 'missing'}")
    report.append("")
    report.append("Result: " + ("FAIL" if failures else "PASS"))
    if failures:
        report.extend(f"- {failure}" for failure in failures)
    print("\n".join(report))
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
