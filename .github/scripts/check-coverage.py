#!/usr/bin/env python3
"""Validate the authoritative reactor-wide JaCoCo report against a versioned policy."""

from __future__ import annotations

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping

COUNTER_TYPES = ("INSTRUCTION", "LINE", "BRANCH", "METHOD", "CLASS")


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


@dataclass(frozen=True)
class CoveragePolicy:
    required_counters: tuple[str, ...]
    aggregate_minimums: Mapping[str, float]
    expected_groups: tuple[str, ...]


@dataclass(frozen=True)
class CoverageReport:
    aggregate: Mapping[str, Counter]
    groups: Mapping[str, Mapping[str, Counter]]


def normalize_group_name(value: str) -> str:
    """Map human Maven module names and artifact IDs to one stable key."""
    normalized = re.sub(r"[^a-z0-9]+", "-", value.strip().lower()).strip("-")
    return normalized if normalized.startswith("taxonomy-") else f"taxonomy-{normalized}"


def parse_counter_set(
    element: ET.Element,
    required_counters: tuple[str, ...],
) -> dict[str, Counter]:
    counters: dict[str, Counter] = {}
    for node in element.findall("counter"):
        counter_type = node.get("type")
        if counter_type not in required_counters:
            continue
        if counter_type in counters:
            raise ValueError(
                f"Duplicate {counter_type} counter on "
                f"<{element.tag} name={element.get('name')!r}>"
            )
        try:
            covered = int(node.get("covered", "0"))
            missed = int(node.get("missed", "0"))
        except ValueError as error:
            raise ValueError(
                f"Invalid {counter_type} counter on "
                f"<{element.tag} name={element.get('name')!r}>"
            ) from error
        if covered < 0 or missed < 0:
            raise ValueError(f"Negative {counter_type} counter values are not valid")
        counters[counter_type] = Counter(covered=covered, missed=missed)

    missing = [counter for counter in required_counters if counter not in counters]
    if missing:
        raise ValueError(
            f"Missing required counters {', '.join(missing)} on "
            f"<{element.tag} name={element.get('name')!r}>"
        )
    empty = [counter for counter, value in counters.items() if value.total == 0]
    if empty:
        raise ValueError(
            f"Required counters have no measurable total: {', '.join(empty)} on "
            f"<{element.tag} name={element.get('name')!r}>"
        )
    return counters


def parse_report(path: Path, required_counters: tuple[str, ...]) -> CoverageReport:
    root = ET.parse(path).getroot()
    if root.tag != "report":
        raise ValueError(f"Expected JaCoCo <report> root, found <{root.tag}>")

    aggregate = parse_counter_set(root, required_counters)
    groups: dict[str, Mapping[str, Counter]] = {}
    for group in root.findall("group"):
        name = group.get("name")
        if not name:
            raise ValueError("JaCoCo report contains an unnamed group")
        if name in groups:
            raise ValueError(f"JaCoCo report contains duplicate group {name!r}")
        groups[name] = parse_counter_set(group, required_counters)
    return CoverageReport(aggregate=aggregate, groups=groups)


def load_policy(path: Path) -> CoveragePolicy:
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"Cannot read coverage policy {path}: {error}") from error
    if not isinstance(raw, dict) or raw.get("schemaVersion") != 1:
        raise ValueError("Coverage policy schemaVersion must be 1")

    counters = raw.get("requiredCounters")
    if not isinstance(counters, list) or not counters:
        raise ValueError("Coverage policy requiredCounters must be a non-empty list")
    if any(not isinstance(counter, str) or counter not in COUNTER_TYPES for counter in counters):
        raise ValueError(
            "Coverage policy requiredCounters may only contain " + ", ".join(COUNTER_TYPES)
        )
    if len(counters) != len(set(counters)):
        raise ValueError("Coverage policy requiredCounters contains duplicates")
    if "BRANCH" not in counters:
        raise ValueError("Coverage policy must explicitly require BRANCH coverage")

    minimums = raw.get("aggregateMinimums")
    if not isinstance(minimums, dict) or set(minimums) != set(counters):
        raise ValueError(
            "Coverage policy aggregateMinimums must define exactly every required counter"
        )
    parsed_minimums: dict[str, float] = {}
    for counter in counters:
        value = minimums[counter]
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise ValueError(f"Coverage minimum for {counter} must be numeric")
        ratio = float(value)
        if ratio < 0.0 or ratio > 1.0:
            raise ValueError(f"Coverage minimum for {counter} must be between 0 and 1")
        parsed_minimums[counter] = ratio
    if parsed_minimums["BRANCH"] <= 0.0:
        raise ValueError("Coverage policy BRANCH minimum must be greater than zero")

    groups = raw.get("expectedGroups")
    if not isinstance(groups, list) or not groups or any(
        not isinstance(group, str) or not group.strip() for group in groups
    ):
        raise ValueError("Coverage policy expectedGroups must be a non-empty string list")
    normalized = [normalize_group_name(group) for group in groups]
    if len(normalized) != len(set(normalized)):
        raise ValueError("Coverage policy expectedGroups contains duplicate normalized names")

    return CoveragePolicy(
        required_counters=tuple(counters),
        aggregate_minimums=parsed_minimums,
        expected_groups=tuple(groups),
    )


def format_counter(counter: Counter) -> str:
    return f"{counter.ratio:.2%} ({counter.covered}/{counter.total})"


def build_report(xml_path: Path, policy: CoveragePolicy) -> tuple[bool, str]:
    report = parse_report(xml_path, policy.required_counters)
    actual_by_key = {normalize_group_name(name): name for name in report.groups}
    expected_by_key = {
        normalize_group_name(name): name for name in policy.expected_groups
    }
    missing_keys = sorted(set(expected_by_key) - set(actual_by_key))
    unexpected_keys = sorted(set(actual_by_key) - set(expected_by_key))
    missing = [expected_by_key[key] for key in missing_keys]
    unexpected = [actual_by_key[key] for key in unexpected_keys]

    violations = [
        counter
        for counter in policy.required_counters
        if report.aggregate[counter].ratio < policy.aggregate_minimums[counter]
    ]
    passed = not missing and not violations

    lines = [
        "Taxonomy reactor-wide JaCoCo coverage",
        "",
        f"Source: {xml_path}",
        "Policy: versioned multi-counter aggregate ratchet",
        "",
        "Per-module coverage:",
    ]
    for name in sorted(report.groups):
        lines.append(f"- {name}")
        for counter_type in policy.required_counters:
            lines.append(
                f"  - {counter_type}: {format_counter(report.groups[name][counter_type])}"
            )

    lines.extend(["", "Aggregate coverage:"])
    for counter_type in policy.required_counters:
        counter = report.aggregate[counter_type]
        minimum = policy.aggregate_minimums[counter_type]
        result = "PASS" if counter.ratio >= minimum else "FAIL"
        lines.append(
            f"- {counter_type}: {format_counter(counter)}; "
            f"required {minimum:.2%}; {result}"
        )
    if missing:
        lines.append("Missing required module groups: " + ", ".join(missing))
    if unexpected:
        lines.append("Additional report groups: " + ", ".join(unexpected))
    if violations:
        lines.append("Counters below minimum: " + ", ".join(violations))
    lines.extend([f"Result: {'PASS' if passed else 'FAIL'}", ""])
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
        "--policy",
        type=Path,
        default=Path(".github/coverage-policy.json"),
        help="Versioned aggregate coverage policy",
    )
    parser.add_argument("--report", type=Path, default=Path("target/coverage-gate.txt"))
    args = parser.parse_args(argv)

    if not args.xml.is_file():
        print(f"Coverage gate failed: report not found: {args.xml}", file=sys.stderr)
        return 1
    if not args.policy.is_file():
        print(f"Coverage gate failed: policy not found: {args.policy}", file=sys.stderr)
        return 1

    try:
        policy = load_policy(args.policy)
        passed, text = build_report(args.xml, policy)
    except (OSError, ET.ParseError, ValueError) as error:
        print(f"Coverage gate failed: {error}", file=sys.stderr)
        return 1

    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(text, encoding="utf-8")
    print(text, end="")
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
