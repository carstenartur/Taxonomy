#!/usr/bin/env python3
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
