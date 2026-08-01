#!/usr/bin/env python3
"""Regression tests for release workflow parameter normalization."""

from __future__ import annotations

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
            },
        )

        self.assertEqual("1.2.9", parameters["release_version"])
        self.assertEqual(
            "1.3.0-SNAPSHOT", parameters["next_development_version"]
        )
        self.assertEqual("false", parameters["skip_tests"])
        self.assertEqual("false", parameters["dry_run"])

    def test_push_request_uses_the_same_normalization(self) -> None:
        parameters = MODULE.resolve_parameters(
            "push",
            {},
            {
                "release_version": "1.2.9 ",
                "next_development_version": " 1.3.0-SNAPSHOT",
                "skip_tests": False,
                "dry_run": True,
            },
        )

        self.assertEqual(
            {
                "release_version": "1.2.9",
                "next_development_version": "1.3.0-SNAPSHOT",
                "skip_tests": "false",
                "dry_run": "true",
            },
            parameters,
        )

    def test_empty_next_version_remains_supported(self) -> None:
        parameters = MODULE.resolve_parameters(
            "workflow_dispatch",
            {
                "INPUT_RELEASE_VERSION": "1.2.9",
                "INPUT_NEXT_DEVELOPMENT_VERSION": "   ",
                "INPUT_SKIP_TESTS": "false",
                "INPUT_DRY_RUN": "false",
            },
        )

        self.assertEqual("", parameters["next_development_version"])

    def test_invalid_version_is_rejected_before_writing_outputs(self) -> None:
        with self.assertRaisesRegex(ValueError, "next_development_version"):
            MODULE.resolve_parameters(
                "workflow_dispatch",
                {
                    "INPUT_RELEASE_VERSION": "1.2.9",
                    "INPUT_NEXT_DEVELOPMENT_VERSION": "1.3-SNAPSHOT",
                    "INPUT_SKIP_TESTS": "false",
                    "INPUT_DRY_RUN": "false",
                },
            )

    def test_cli_writes_only_normalized_single_line_outputs(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output_path = Path(directory, "github-output")
            environment = os.environ | {
                "EVENT_NAME": "workflow_dispatch",
                "INPUT_RELEASE_VERSION": "1.2.9 ",
                "INPUT_NEXT_DEVELOPMENT_VERSION": " 1.3.0-SNAPSHOT ",
                "INPUT_SKIP_TESTS": "false",
                "INPUT_DRY_RUN": "false",
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
                ],
                output_path.read_text(encoding="utf-8").splitlines(),
            )


if __name__ == "__main__":
    unittest.main()
