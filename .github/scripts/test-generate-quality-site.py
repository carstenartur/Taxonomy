#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("generate-quality-site.py").resolve()
SPEC = importlib.util.spec_from_file_location("generate_quality_site", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Cannot load {SCRIPT}")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class GenerateQualitySummaryTest(unittest.TestCase):
    def test_generates_commit_bound_badges_and_report(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            reports = root / "tests" / "module" / "target" / "surefire-reports"
            reports.mkdir(parents=True)
            reports.joinpath("TEST-example.xml").write_text(
                '<testsuite tests="5" failures="0" errors="0" skipped="1"/>',
                encoding="utf-8",
            )
            coverage = root / "coverage"
            coverage.mkdir()
            coverage.joinpath("jacoco.xml").write_text(
                '<report><counter type="INSTRUCTION" missed="20" covered="80"/>'
                '<counter type="LINE" missed="10" covered="90"/>'
                '<counter type="BRANCH" missed="3" covered="7"/>'
                '<counter type="METHOD" missed="2" covered="8"/>'
                '<counter type="CLASS" missed="1" covered="9"/></report>',
                encoding="utf-8",
            )

            summary = MODULE.write_outputs(
                root,
                "abc123",
                "2026-08-06T13:00:00+00:00",
                source_tree="tree456",
                build_id="run-7.1",
                tools={"java": "21", "maven": "3.9.11"},
            )

            self.assertEqual(4, summary["tests"]["passed"])
            self.assertEqual(80.0, summary["coverage"]["instructionPercent"])
            self.assertEqual("tree456", summary["sourceTree"])
            self.assertEqual("run-7.1", summary["buildId"])
            self.assertEqual("21", summary["tools"]["java"])
            test_badge = json.loads((root / "tests" / "badge.json").read_text())
            coverage_badge = json.loads((root / "coverage" / "badge.json").read_text())
            self.assertEqual("4 passed", test_badge["message"])
            self.assertEqual("80.00%", coverage_badge["message"])
            persisted = json.loads((root / "quality-summary.json").read_text())
            self.assertEqual("abc123", persisted["commit"])
            self.assertEqual("tree456", persisted["sourceTree"])
            report_html = (root / "tests" / "surefire-report.html").read_text()
            self.assertIn("Method coverage", report_html)
            self.assertIn("Class coverage", report_html)
            self.assertIn("run-7.1", report_html)
            self.assertTrue((root / ".nojekyll").is_file())
            self.assertTrue((root / "index.html").is_file())

    def test_missing_reports_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(FileNotFoundError):
                MODULE.collect_tests(Path(directory))

    def test_test_failures_make_badge_red(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            reports = root / "tests"
            reports.mkdir()
            reports.joinpath("TEST-failing.xml").write_text(
                '<testsuite tests="2" failures="1" errors="0" skipped="0"/>',
                encoding="utf-8",
            )
            coverage = root / "coverage"
            coverage.mkdir()
            coverage.joinpath("jacoco.xml").write_text(
                '<report><counter type="INSTRUCTION" missed="0" covered="1"/></report>',
                encoding="utf-8",
            )
            MODULE.write_outputs(root, "deadbeef", "now")
            badge = json.loads((reports / "badge.json").read_text())
            self.assertEqual("red", badge["color"])

    def test_tool_metadata_requires_name_and_value(self) -> None:
        self.assertEqual(
            {"java": "21", "maven": "3.9.11"},
            MODULE.parse_tools(["java=21", "maven=3.9.11"]),
        )
        with self.assertRaises(ValueError):
            MODULE.parse_tools(["broken"])


if __name__ == "__main__":
    unittest.main()
