#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("verify-quality-publication.py").resolve()
SPEC = importlib.util.spec_from_file_location("verify_quality_publication", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Cannot load {SCRIPT}")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class VerifyQualityPublicationTest(unittest.TestCase):
    COMMIT = "0123456789abcdef0123456789abcdef01234567"

    def _payloads(self):
        summary = {
            "commit": self.COMMIT,
            "verifiedCommit": self.COMMIT,
            "generatedAt": "2026-08-07T03:00:00+00:00",
            "sourceTree": "tree123",
            "buildId": "run-1",
            "tools": {"java": "21", "maven": "3.9.11"},
            "tests": {
                "tests": 5,
                "executed": 4,
                "passed": 4,
                "skipped": 1,
                "failures": 0,
                "errors": 0,
            },
            "coverage": {"instructionPercent": 82.74},
        }
        return (
            summary,
            {"message": "4 passed"},
            {"message": "82.74%"},
        )

    def _write_local(self, root: Path) -> None:
        summary, tests, coverage = self._payloads()
        for relative in MODULE.REQUIRED_FILES:
            path = root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            if relative.endswith(".json"):
                payload = summary if relative == "quality-summary.json" else (
                    tests if relative == "tests/badge.json" else coverage
                )
                path.write_text(json.dumps(payload), encoding="utf-8")
            elif relative == ".nojekyll":
                path.touch()
            else:
                path.write_text(f"verified {self.COMMIT}", encoding="utf-8")

    def test_local_publication_matches_summary_and_commit(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_local(root)
            MODULE.verify_local(root, self.COMMIT)

    def test_missing_badge_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_local(root)
            (root / "tests" / "badge.json").unlink()
            with self.assertRaisesRegex(FileNotFoundError, "tests/badge.json"):
                MODULE.verify_local(root, self.COMMIT)

    def test_badge_mismatch_fails_closed(self) -> None:
        summary, tests, coverage = self._payloads()
        tests["message"] = "2153 passed"
        with self.assertRaisesRegex(ValueError, "does not match summary"):
            MODULE.verify_payloads(summary, tests, coverage, self.COMMIT)

    def test_inconsistent_executed_total_fails_closed(self) -> None:
        summary, tests, coverage = self._payloads()
        summary["tests"]["executed"] = 5
        with self.assertRaisesRegex(ValueError, "executed-test total is inconsistent"):
            MODULE.verify_payloads(summary, tests, coverage, self.COMMIT)

    def test_wrong_commit_fails_closed(self) -> None:
        summary, tests, coverage = self._payloads()
        with self.assertRaisesRegex(ValueError, "does not match"):
            MODULE.verify_payloads(summary, tests, coverage, "deadbeef")

    def test_remote_verification_checks_html_and_json(self) -> None:
        summary, tests, coverage = self._payloads()

        def json_fetcher(url: str):
            if "quality-summary.json" in url:
                return summary
            if "tests/badge.json" in url:
                return tests
            if "coverage/badge.json" in url:
                return coverage
            raise AssertionError(url)

        def text_fetcher(url: str) -> str:
            self.assertIn("verified=", url)
            return f"quality report for {self.COMMIT}"

        MODULE.verify_remote_once(
            "https://example.invalid/Taxonomy",
            self.COMMIT,
            json_fetcher=json_fetcher,
            text_fetcher=text_fetcher,
        )


if __name__ == "__main__":
    unittest.main()
