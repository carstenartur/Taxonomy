#!/usr/bin/env python3
"""Regression tests for check-coverage.py without third-party dependencies."""

from __future__ import annotations

import importlib.util
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


def report_xml(groups: dict[str, tuple[int, int]], aggregate: tuple[int, int]) -> str:
    group_xml = "".join(
        f'<group name="{name}"><counter type="INSTRUCTION" missed="{missed}" covered="{covered}"/></group>'
        for name, (covered, missed) in groups.items()
    )
    return (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<report name="Taxonomy Aggregate Coverage">'
        f'{group_xml}'
        f'<counter type="INSTRUCTION" missed="{aggregate[1]}" covered="{aggregate[0]}"/>'
        '</report>'
    )


class CoverageGateTest(unittest.TestCase):

    def write_report(self, xml: str) -> Path:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = Path(directory.name) / "jacoco.xml"
        path.write_text(xml, encoding="utf-8")
        return path

    def test_passes_only_with_all_expected_groups_and_threshold(self) -> None:
        path = self.write_report(report_xml({
            "taxonomy-domain": (90, 10),
            "taxonomy-dsl": (80, 20),
        }, (170, 30)))

        passed, text = MODULE.build_report(
            path, 0.81, ["Taxonomy Domain", "Taxonomy DSL"]
        )

        self.assertTrue(passed)
        self.assertIn("Aggregate: 85.00%", text)
        self.assertIn("Result:    PASS", text)

    def test_fails_when_a_shipped_module_group_is_missing(self) -> None:
        path = self.write_report(report_xml({
            "taxonomy-domain": (90, 10),
        }, (90, 10)))

        passed, text = MODULE.build_report(
            path, 0.81, ["Taxonomy Domain", "Taxonomy DSL"]
        )

        self.assertFalse(passed)
        self.assertIn("Missing required module groups: Taxonomy DSL", text)

    def test_fails_below_threshold_even_when_all_modules_are_present(self) -> None:
        path = self.write_report(report_xml({
            "Taxonomy Domain": (70, 30),
            "taxonomy-dsl": (70, 30),
        }, (140, 60)))

        passed, text = MODULE.build_report(
            path, 0.81, ["taxonomy-domain", "Taxonomy DSL"]
        )

        self.assertFalse(passed)
        self.assertIn("Aggregate: 70.00%", text)
        self.assertIn("Result:    FAIL", text)

    def test_rejects_duplicate_group_names(self) -> None:
        path = self.write_report(
            '<report name="duplicate">'
            '<group name="Taxonomy Domain"><counter type="INSTRUCTION" missed="1" covered="9"/></group>'
            '<group name="Taxonomy Domain"><counter type="INSTRUCTION" missed="1" covered="9"/></group>'
            '<counter type="INSTRUCTION" missed="2" covered="18"/>'
            '</report>'
        )

        with self.assertRaisesRegex(ValueError, "duplicate group"):
            MODULE.parse_report(path)


if __name__ == "__main__":
    unittest.main()
