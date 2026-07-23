#!/usr/bin/env python3
"""Validate the authoritative reactor-wide JaCoCo instruction coverage report."""

from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Counter:
    covered: int
    missed: int

    @property
    def total(self) -> int:
        return self.covered + self.missed

    @property
    def ratio(self) -> float:
        return self.covered / self.total if self.total else 0.0


def direct_instruction_counter(element: ET.Element) -> Counter:
    for counter in element.findall("counter"):
        if counter.get("type") == "INSTRUCTION":
            return Counter(
                covered=int(counter.get("covered", "0")),
                missed=int(counter.get("missed", "0")),
            )
    raise ValueError(
        f"No direct INSTRUCTION counter on <{element.tag} name={element.get('name')!r}>"
    )


def parse_report(path: Path) -> tuple[Counter, dict[str, Counter]]:
    root = ET.parse(path).getroot()
    if root.tag != "report":
        raise ValueError(f"Expected JaCoCo <report> root, found <{root.tag}>")

    aggregate = direct_instruction_counter(root)
    groups: dict[str, Counter] = {}
    for group in root.findall("group"):
        name = group.get("name")
        if not name:
            raise ValueError("JaCoCo report contains an unnamed group")
        if name in groups:
            raise ValueError(f"JaCoCo report contains duplicate group {name!r}")
        groups[name] = direct_instruction_counter(group)
    return aggregate, groups


def normalize_group_name(value: str) -> str:
    """Map human Maven module names and artifact IDs to one stable key."""
    normalized = re.sub(r"[^a-z0-9]+", "-", value.strip().lower()).strip("-")
    return normalized if normalized.startswith("taxonomy-") else f"taxonomy-{normalized}"


def build_report(
    xml_path: Path,
    minimum: float,
    expected_groups: list[str],
) -> tuple[bool, str]:
    aggregate, groups = parse_report(xml_path)
    actual_by_key = {normalize_group_name(name): name for name in groups}
    expected_by_key = {normalize_group_name(name): name for name in expected_groups}
    missing_keys = sorted(set(expected_by_key) - set(actual_by_key))
    unexpected_keys = (
        sorted(set(actual_by_key) - set(expected_by_key)) if expected_groups else []
    )
    missing = [expected_by_key[key] for key in missing_keys]
    unexpected = [actual_by_key[key] for key in unexpected_keys]

    passed = aggregate.total > 0 and aggregate.ratio >= minimum and not missing
    lines = [
        "Taxonomy reactor-wide JaCoCo instruction coverage",
        "",
        f"Source: {xml_path}",
        "",
    ]
    for name in sorted(groups):
        counter = groups[name]
        lines.append(
            f"- {name}: {counter.ratio:.2%} "
            f"({counter.covered}/{counter.total} instructions)"
        )
    lines.extend([
        "",
        f"Aggregate: {aggregate.ratio:.2%} "
        f"({aggregate.covered}/{aggregate.total} instructions)",
        f"Required:  {minimum:.2%}",
    ])
    if missing:
        lines.append("Missing required module groups: " + ", ".join(missing))
    if unexpected:
        lines.append("Additional report groups: " + ", ".join(unexpected))
    lines.extend([
        f"Result:    {'PASS' if passed else 'FAIL'}",
        "",
    ])
    return passed, "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--xml",
        type=Path,
        default=Path("taxonomy-coverage/target/site/jacoco-aggregate/jacoco.xml"),
        help="Authoritative report-aggregate XML file",
    )
    parser.add_argument(
        "--minimum",
        type=float,
        default=0.81,
        help="Minimum reactor-wide instruction ratio, e.g. 0.81 for 81%%",
    )
    parser.add_argument(
        "--expected-group",
        action="append",
        default=[],
        help="Required Maven module name or JaCoCo artifact group; repeat for every shipped module",
    )
    parser.add_argument("--report", type=Path, default=Path("target/coverage-gate.txt"))
    args = parser.parse_args(argv)

    if not args.xml.is_file():
        print(f"Coverage gate failed: report not found: {args.xml}", file=sys.stderr)
        return 1

    try:
        passed, text = build_report(args.xml, args.minimum, args.expected_group)
    except (OSError, ET.ParseError, ValueError) as error:
        print(f"Coverage gate failed: {error}", file=sys.stderr)
        return 1

    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(text, encoding="utf-8")
    print(text, end="")
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
