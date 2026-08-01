#!/usr/bin/env python3
"""Regression tests for release workflow parameter normalization."""

from __future__ import annotations

from collections import OrderedDict
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("resolve-release-parameters.py")
SPEC = importlib.util.spec_from_file_location("resolve_release_parameters", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Cannot load {SCRIPT}")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ReleaseParameterTest(unittest.TestCase):
    def test_dispatch_trims_surrounding_version_whitespace(self) -> None:
        parameters = MODULE.resolve_parameters(
            "workflow_dispatch",
            {
                "INPUT_RELEASE_VERSION": " 1.2.9 ",
                "INPUT_NEXT_DEVELOPMENT_VERSION": "1.3.0-SNAPSHOT ",
                "INPUT_SKIP_TESTS": "false",
                "INPUT_DRY_RUN": " false ",
                "INPUT_RESUME_STAGED_RELEASE": " false ",
            },
        )

        self.assertEqual("1.2.9", parameters["release_version"])
        self.assertEqual(
            "1.3.0-SNAPSHOT", parameters["next_development_version"]
        )
        self.assertEqual("false", parameters["skip_tests"])
        self.assertEqual("false", parameters["dry_run"])
        self.assertEqual("false", parameters["resume_staged_release"])

    def test_push_request_uses_the_same_normalization(self) -> None:
        parameters = MODULE.resolve_parameters(
            "push",
            {},
            {
                "release_version": "1.2.9 ",
                "next_development_version": " 1.3.0-SNAPSHOT",
                "skip_tests": False,
                "dry_run": False,
                "resume_staged_release": True,
            },
        )

        self.assertEqual(
            {
                "release_version": "1.2.9",
                "next_development_version": "1.3.0-SNAPSHOT",
                "skip_tests": "false",
                "dry_run": "false",
                "resume_staged_release": "true",
            },
            parameters,
        )

    def test_empty_next_version_remains_supported_for_normal_release(self) -> None:
        parameters = MODULE.resolve_parameters(
            "workflow_dispatch",
            {
                "INPUT_RELEASE_VERSION": "1.2.9",
                "INPUT_NEXT_DEVELOPMENT_VERSION": "   ",
                "INPUT_SKIP_TESTS": "false",
                "INPUT_DRY_RUN": "false",
                "INPUT_RESUME_STAGED_RELEASE": "false",
            },
        )

        self.assertEqual("", parameters["next_development_version"])

    def test_resume_requires_explicit_next_development_version(self) -> None:
        with self.assertRaisesRegex(ValueError, "next_development_version is required"):
            MODULE.resolve_parameters(
                "workflow_dispatch",
                {
                    "INPUT_RELEASE_VERSION": "1.2.9",
                    "INPUT_NEXT_DEVELOPMENT_VERSION": "",
                    "INPUT_SKIP_TESTS": "false",
                    "INPUT_DRY_RUN": "false",
                    "INPUT_RESUME_STAGED_RELEASE": "true",
                },
            )

    def test_resume_cannot_be_combined_with_dry_run(self) -> None:
        with self.assertRaisesRegex(ValueError, "cannot be combined with dry_run"):
            MODULE.resolve_parameters(
                "workflow_dispatch",
                {
                    "INPUT_RELEASE_VERSION": "1.2.9",
                    "INPUT_NEXT_DEVELOPMENT_VERSION": "1.3.0-SNAPSHOT",
                    "INPUT_SKIP_TESTS": "false",
                    "INPUT_DRY_RUN": "true",
                    "INPUT_RESUME_STAGED_RELEASE": "true",
                },
            )

    def test_invalid_version_is_rejected_before_writing_outputs(self) -> None:
        with self.assertRaisesRegex(ValueError, "next_development_version"):
            MODULE.resolve_parameters(
                "workflow_dispatch",
                {
                    "INPUT_RELEASE_VERSION": "1.2.9",
                    "INPUT_NEXT_DEVELOPMENT_VERSION": "1.3-SNAPSHOT",
                    "INPUT_SKIP_TESTS": "false",
                    "INPUT_DRY_RUN": "false",
                    "INPUT_RESUME_STAGED_RELEASE": "false",
                },
            )

    def test_unsupported_event_is_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "unsupported release event"):
            MODULE.resolve_parameters("schedule", {})

    def test_cli_writes_only_normalized_single_line_outputs(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output_path = Path(directory, "github-output")
            environment = os.environ | {
                "EVENT_NAME": "workflow_dispatch",
                "INPUT_RELEASE_VERSION": "1.2.9 ",
                "INPUT_NEXT_DEVELOPMENT_VERSION": " 1.3.0-SNAPSHOT ",
                "INPUT_SKIP_TESTS": "false",
                "INPUT_DRY_RUN": "false",
                "INPUT_RESUME_STAGED_RELEASE": "false",
                "GITHUB_OUTPUT": str(output_path),
            }
            result = subprocess.run(
                [sys.executable, str(SCRIPT)],
                env=environment,
                text=True,
                capture_output=True,
                check=False,
            )

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(
                [
                    "release_version=1.2.9",
                    "next_development_version=1.3.0-SNAPSHOT",
                    "skip_tests=false",
                    "dry_run=false",
                    "resume_staged_release=false",
                ],
                output_path.read_text(encoding="utf-8").splitlines(),
            )

    def test_append_outputs_uses_explicit_stable_key_order(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output_path = Path(directory, "github-output")

            MODULE.append_outputs(
                output_path,
                OrderedDict(
                    [
                        ("resume_staged_release", "true"),
                        ("dry_run", "false"),
                        ("skip_tests", "true"),
                        ("next_development_version", "1.3.0-SNAPSHOT"),
                        ("release_version", "1.2.9"),
                    ]
                ),
            )

            self.assertEqual(
                [
                    "release_version=1.2.9",
                    "next_development_version=1.3.0-SNAPSHOT",
                    "skip_tests=true",
                    "dry_run=false",
                    "resume_staged_release=true",
                ],
                output_path.read_text(encoding="utf-8").splitlines(),
            )

    def test_cli_reports_missing_event_name_clearly(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output_path = Path(directory, "github-output")
            environment = os.environ | {
                "GITHUB_OUTPUT": str(output_path),
            }
            environment.pop("EVENT_NAME", None)
            result = subprocess.run(
                [sys.executable, str(SCRIPT)],
                env=environment,
                text=True,
                capture_output=True,
                check=False,
            )

            self.assertEqual(1, result.returncode)
            self.assertIn(
                "::error::EVENT_NAME environment variable is required", result.stderr
            )

    def test_cli_reports_missing_github_output_clearly(self) -> None:
        environment = os.environ | {
            "EVENT_NAME": "workflow_dispatch",
            "INPUT_RELEASE_VERSION": "1.2.9",
            "INPUT_NEXT_DEVELOPMENT_VERSION": "1.3.0-SNAPSHOT",
            "INPUT_SKIP_TESTS": "false",
            "INPUT_DRY_RUN": "false",
            "INPUT_RESUME_STAGED_RELEASE": "false",
        }
        environment.pop("GITHUB_OUTPUT", None)
        result = subprocess.run(
            [sys.executable, str(SCRIPT)],
            env=environment,
            text=True,
            capture_output=True,
            check=False,
        )

        self.assertEqual(1, result.returncode)
        self.assertIn(
            "::error::GITHUB_OUTPUT environment variable is required", result.stderr
        )


if __name__ == "__main__":
    unittest.main()
