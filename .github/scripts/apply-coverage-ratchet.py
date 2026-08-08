#!/usr/bin/env python3
"""Install the policy-backed multi-counter JaCoCo coverage gate for issue #641."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

CHECK = r'''#!/usr/bin/env python3
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
'''

TEST = r'''#!/usr/bin/env python3
"""Regression tests for the policy-backed multi-counter coverage gate."""

from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("check-coverage.py")
SPEC = importlib.util.spec_from_file_location("taxonomy_check_coverage", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)

COUNTERS = ("INSTRUCTION", "LINE", "BRANCH", "METHOD", "CLASS")


def counters_xml(values: dict[str, tuple[int, int]]) -> str:
    return "".join(
        f'<counter type="{name}" missed="{missed}" covered="{covered}"/>'
        for name, (covered, missed) in values.items()
    )


def report_xml(
    groups: dict[str, dict[str, tuple[int, int]]],
    aggregate: dict[str, tuple[int, int]],
) -> str:
    group_xml = "".join(
        f'<group name="{name}">{counters_xml(values)}</group>'
        for name, values in groups.items()
    )
    return (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<report name="Taxonomy Aggregate Coverage">'
        f'{group_xml}{counters_xml(aggregate)}'
        '</report>'
    )


def values(covered: int = 90, missed: int = 10) -> dict[str, tuple[int, int]]:
    return {counter: (covered, missed) for counter in COUNTERS}


class CoverageGateTest(unittest.TestCase):

    def temporary_path(self, name: str, content: str) -> Path:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = Path(directory.name) / name
        path.write_text(content, encoding="utf-8")
        return path

    def policy(self, **minimum_overrides: float) -> MODULE.CoveragePolicy:
        minimums = {
            "INSTRUCTION": 0.81,
            "LINE": 0.80,
            "BRANCH": 0.64,
            "METHOD": 0.80,
            "CLASS": 0.80,
        }
        minimums.update(minimum_overrides)
        return MODULE.CoveragePolicy(
            required_counters=COUNTERS,
            aggregate_minimums=minimums,
            expected_groups=("Taxonomy Domain", "Taxonomy DSL"),
        )

    def test_passes_and_publishes_all_counters_for_every_group(self) -> None:
        path = self.temporary_path("jacoco.xml", report_xml({
            "taxonomy-domain": values(90, 10),
            "taxonomy-dsl": values(85, 15),
        }, values(175, 25)))

        passed, text = MODULE.build_report(path, self.policy())

        self.assertTrue(passed)
        for counter in COUNTERS:
            self.assertIn(f"- {counter}:", text)
        self.assertIn("taxonomy-domain", text)
        self.assertIn("Aggregate coverage:", text)
        self.assertIn("Result: PASS", text)

    def test_branch_ratchet_fails_when_instructions_still_pass(self) -> None:
        aggregate = values(90, 10)
        aggregate["BRANCH"] = (63, 37)
        path = self.temporary_path("jacoco.xml", report_xml({
            "taxonomy-domain": values(),
            "taxonomy-dsl": values(),
        }, aggregate))

        passed, text = MODULE.build_report(path, self.policy())

        self.assertFalse(passed)
        self.assertIn("INSTRUCTION: 90.00%", text)
        self.assertIn("BRANCH: 63.00%", text)
        self.assertIn("Counters below minimum: BRANCH", text)

    def test_fails_closed_when_required_branch_counter_is_missing(self) -> None:
        incomplete = values()
        del incomplete["BRANCH"]
        path = self.temporary_path("jacoco.xml", report_xml({
            "taxonomy-domain": incomplete,
            "taxonomy-dsl": values(),
        }, values()))

        with self.assertRaisesRegex(ValueError, "Missing required counters BRANCH"):
            MODULE.build_report(path, self.policy())

    def test_fails_closed_when_required_counter_has_no_total(self) -> None:
        empty = values()
        empty["CLASS"] = (0, 0)
        path = self.temporary_path("jacoco.xml", report_xml({
            "taxonomy-domain": values(),
            "taxonomy-dsl": values(),
        }, empty))

        with self.assertRaisesRegex(ValueError, "no measurable total: CLASS"):
            MODULE.build_report(path, self.policy())

    def test_fails_when_a_shipped_module_group_is_missing(self) -> None:
        path = self.temporary_path("jacoco.xml", report_xml({
            "taxonomy-domain": values(),
        }, values()))

        passed, text = MODULE.build_report(path, self.policy())

        self.assertFalse(passed)
        self.assertIn("Missing required module groups: Taxonomy DSL", text)

    def test_rejects_duplicate_group_names(self) -> None:
        xml = (
            '<report name="duplicate">'
            f'<group name="Taxonomy Domain">{counters_xml(values())}</group>'
            f'<group name="Taxonomy Domain">{counters_xml(values())}</group>'
            f'{counters_xml(values())}'
            '</report>'
        )
        path = self.temporary_path("jacoco.xml", xml)

        with self.assertRaisesRegex(ValueError, "duplicate group"):
            MODULE.parse_report(path, COUNTERS)

    def test_policy_requires_an_explicit_positive_branch_minimum(self) -> None:
        raw = {
            "schemaVersion": 1,
            "requiredCounters": list(COUNTERS),
            "aggregateMinimums": {
                "INSTRUCTION": 0.81,
                "LINE": 0.80,
                "BRANCH": 0.0,
                "METHOD": 0.80,
                "CLASS": 0.80,
            },
            "expectedGroups": ["taxonomy-domain"],
        }
        path = self.temporary_path("policy.json", json.dumps(raw))

        with self.assertRaisesRegex(ValueError, "BRANCH minimum must be greater"):
            MODULE.load_policy(path)

    def test_policy_rejects_missing_counter_minimums(self) -> None:
        raw = {
            "schemaVersion": 1,
            "requiredCounters": list(COUNTERS),
            "aggregateMinimums": {
                "INSTRUCTION": 0.81,
                "LINE": 0.80,
                "BRANCH": 0.64,
                "METHOD": 0.80,
            },
            "expectedGroups": ["taxonomy-domain"],
        }
        path = self.temporary_path("policy.json", json.dumps(raw))

        with self.assertRaisesRegex(ValueError, "define exactly every required counter"):
            MODULE.load_policy(path)


if __name__ == "__main__":
    unittest.main()
'''

POLICY = '''{
  "schemaVersion": 1,
  "description": "Aggregate JaCoCo ratchet based on verified reactor evidence; thresholds may only move upward unless a reviewed exception is introduced.",
  "requiredCounters": [
    "INSTRUCTION",
    "LINE",
    "BRANCH",
    "METHOD",
    "CLASS"
  ],
  "aggregateMinimums": {
    "INSTRUCTION": 0.81,
    "LINE": 0.82,
    "BRANCH": 0.64,
    "METHOD": 0.85,
    "CLASS": 0.92
  },
  "expectedGroups": [
    "taxonomy-domain",
    "taxonomy-dsl",
    "taxonomy-export",
    "taxonomy-extension-api",
    "taxonomy-app"
  ]
}
'''

DOC = '''# Reactor-wide test coverage

The authoritative coverage evidence for Taxonomy is produced by the final coverage module, `taxonomy-coverage`, using JaCoCo `report-aggregate`.

## Included production modules

The report must contain all shipped modules as separate JaCoCo groups:

1. `taxonomy-domain`
2. `taxonomy-dsl`
3. `taxonomy-export`
4. `taxonomy-extension-api`
5. `taxonomy-app`

The gate normalizes Maven display names and artifact IDs, but it still fails when any required module is missing. This prevents a highly covered application module from hiding an uninstrumented or untested library module.

## Multi-counter ratchet

`.github/coverage-policy.json` is the single versioned policy for the aggregate gate. It requires and publishes all of these JaCoCo counters:

- instructions;
- lines;
- branches;
- methods;
- classes.

Every required counter must exist and have a measurable total both at aggregate level and for every module group. The build fails closed when a counter is missing, empty or below its configured aggregate minimum.

The branch threshold is an explicit ratchet based on verified reactor evidence. It must not be removed or silently replaced by instruction-only coverage. Thresholds should move upward as tests improve. Critical-package budgets, diff coverage and time-limited exceptions are tracked separately under issue #624.

## Single source of truth

The following outputs all consume the same XML file and policy:

```text
taxonomy-coverage/target/site/jacoco-aggregate/jacoco.xml
.github/coverage-policy.json
```

- CI coverage gate;
- published and archived coverage evidence;
- release coverage claims;
- the human-readable `target/coverage-gate.txt` summary.

Module-local reports may still exist for diagnosis, but they are not added together and are not authoritative.

## Local verification

```bash
./mvnw install -DexcludedGroups=real-llm
python3 .github/scripts/check-coverage.py \\
  --xml taxonomy-coverage/target/site/jacoco-aggregate/jacoco.xml \\
  --policy .github/coverage-policy.json \\
  --report target/coverage-gate.txt
```

The gate's deterministic regression tests run with:

```bash
python3 .github/scripts/test-check-coverage.py
```

## Adding or removing a module

A shipped module change is incomplete until all of the following are updated:

- root reactor `<modules>` list;
- direct dependencies in `taxonomy-coverage/pom.xml`;
- `expectedGroups` in `.github/coverage-policy.json`;
- this document.

CI deliberately fails when those views drift apart.
'''

(ROOT / ".github/scripts/check-coverage.py").write_text(CHECK, encoding="utf-8")
(ROOT / ".github/scripts/test-check-coverage.py").write_text(TEST, encoding="utf-8")
(ROOT / ".github/coverage-policy.json").write_text(POLICY, encoding="utf-8")
(ROOT / "docs/dev/REACTOR_COVERAGE.md").write_text(DOC, encoding="utf-8")

pom_path = ROOT / "taxonomy-build/pom.xml"
pom = pom_path.read_text(encoding="utf-8")
old = '''                                <argument>--minimum</argument><argument>0.81</argument>
                                <argument>--expected-group</argument><argument>taxonomy-domain</argument>
                                <argument>--expected-group</argument><argument>taxonomy-dsl</argument>
                                <argument>--expected-group</argument><argument>taxonomy-export</argument>
                                <argument>--expected-group</argument><argument>taxonomy-extension-api</argument>
                                <argument>--expected-group</argument><argument>taxonomy-app</argument>
'''
new = '''                                <argument>--policy</argument><argument>.github/coverage-policy.json</argument>
'''
if pom.count(old) != 1:
    raise SystemExit(f"Expected exactly one legacy coverage argument block, found {pom.count(old)}")
pom_path.write_text(pom.replace(old, new, 1), encoding="utf-8")

print("Installed the versioned multi-counter aggregate coverage ratchet.")
