#!/usr/bin/env python3
"""Regression tests for freely selectable release workflow parameters."""

from __future__ import annotations

from collections import OrderedDict
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("resolve-release-parameters.py").resolve()
SPEC = importlib.util.spec_from_file_location("resolve_release_parameters", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Cannot load {SCRIPT}")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def write_pom(root: Path, version: str) -> None:
    root.joinpath("pom.xml").write_text(
        """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.taxonomy</groupId>
  <artifactId>taxonomy</artifactId>
  <version>{version}</version>
</project>
""".format(version=version),
        encoding="utf-8",
    )


class ReleaseParameterTest(unittest.TestCase):
    def test_dispatch_uses_exact_next_development_version(self) -> None:
        parameters = MODULE.resolve_parameters(
            "workflow_dispatch",
            {
                "INPUT_NEXT_VERSION_INCREMENT": "patch",
                "INPUT_NEXT_DEVELOPMENT_VERSION": " 2.0.0-SNAPSHOT ",
                "INPUT_SKIP_TESTS": "false",
                "INPUT_DRY_RUN": "false",
            },
            current_version="1.2.9-SNAPSHOT",
        )

        self.assertEqual(
            {
                "release_version": "1.2.9",
                "next_development_version": "2.0.0-SNAPSHOT",
                "skip_tests": "false",
                "dry_run": "false",
                "resume_staged_release": "false",
            },
            parameters,
        )

    def test_exact_next_version_may_skip_multiple_versions(self) -> None:
        parameters = MODULE.resolve_parameters(
            "workflow_dispatch",
            {
                "INPUT_NEXT_VERSION_INCREMENT": "minor",
                "INPUT_NEXT_DEVELOPMENT_VERSION": "3.7.4-SNAPSHOT",
            },
            current_version="1.2.9-SNAPSHOT",
        )
        self.assertEqual(
            "3.7.4-SNAPSHOT", parameters["next_development_version"]
        )

    def test_dispatch_derives_patch_version_when_exact_value_is_empty(self) -> None:
        parameters = MODULE.resolve_parameters(
            "workflow_dispatch",
            {
                "INPUT_NEXT_VERSION_INCREMENT": "patch",
                "INPUT_NEXT_DEVELOPMENT_VERSION": "   ",
            },
            current_version="1.2.9-SNAPSHOT",
        )
        self.assertEqual("1.2.10-SNAPSHOT", parameters["next_development_version"])

    def test_dispatch_fallback_supports_minor_and_major_choices(self) -> None:
        minor = MODULE.resolve_parameters(
            "workflow_dispatch",
            {
                "INPUT_NEXT_VERSION_INCREMENT": "minor",
                "INPUT_NEXT_DEVELOPMENT_VERSION": "",
            },
            current_version="1.2.9-SNAPSHOT",
        )
        major = MODULE.resolve_parameters(
            "workflow_dispatch",
            {
                "INPUT_NEXT_VERSION_INCREMENT": "major",
                "INPUT_NEXT_DEVELOPMENT_VERSION": "",
            },
            current_version="1.2.9-SNAPSHOT",
        )

        self.assertEqual("1.3.0-SNAPSHOT", minor["next_development_version"])
        self.assertEqual("2.0.0-SNAPSHOT", major["next_development_version"])

    def test_push_request_keeps_reviewed_exact_versions_for_resume(self) -> None:
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

    def test_empty_next_version_remains_supported_for_normal_push_request(self) -> None:
        parameters = MODULE.resolve_parameters(
            "push",
            {},
            {
                "release_version": "1.3.0",
                "skip_tests": False,
                "dry_run": False,
            },
        )
        self.assertEqual("", parameters["next_development_version"])

    def test_resume_requires_explicit_next_development_version(self) -> None:
        with self.assertRaisesRegex(ValueError, "next_development_version is required"):
            MODULE.resolve_parameters(
                "push",
                {},
                {
                    "release_version": "1.2.9",
                    "next_development_version": "",
                    "resume_staged_release": True,
                },
            )

    def test_resume_cannot_be_combined_with_dry_run(self) -> None:
        with self.assertRaisesRegex(ValueError, "cannot be combined with dry_run"):
            MODULE.resolve_parameters(
                "push",
                {},
                {
                    "release_version": "1.2.9",
                    "next_development_version": "1.3.0-SNAPSHOT",
                    "dry_run": True,
                    "resume_staged_release": True,
                },
            )

    def test_dispatch_rejects_invalid_increment_when_fallback_is_used(self) -> None:
        with self.assertRaisesRegex(ValueError, "patch, minor or major"):
            MODULE.resolve_parameters(
                "workflow_dispatch",
                {
                    "INPUT_NEXT_VERSION_INCREMENT": "custom",
                    "INPUT_NEXT_DEVELOPMENT_VERSION": "",
                },
                current_version="1.2.9-SNAPSHOT",
            )

    def test_exact_value_overrides_irrelevant_increment(self) -> None:
        parameters = MODULE.resolve_parameters(
            "workflow_dispatch",
            {
                "INPUT_NEXT_VERSION_INCREMENT": "not-used",
                "INPUT_NEXT_DEVELOPMENT_VERSION": "2.0.0-SNAPSHOT",
            },
            current_version="1.2.9-SNAPSHOT",
        )
        self.assertEqual("2.0.0-SNAPSHOT", parameters["next_development_version"])

    def test_dispatch_rejects_non_snapshot_repository_version(self) -> None:
        with self.assertRaisesRegex(ValueError, "must use X.Y.Z-SNAPSHOT"):
            MODULE.resolve_parameters(
                "workflow_dispatch",
                {"INPUT_NEXT_VERSION_INCREMENT": "patch"},
                current_version="1.2.9",
            )

    def test_dispatch_rejects_next_version_not_newer_than_release(self) -> None:
        with self.assertRaisesRegex(ValueError, "must be newer"):
            MODULE.resolve_parameters(
                "workflow_dispatch",
                {"INPUT_NEXT_DEVELOPMENT_VERSION": "1.2.9-SNAPSHOT"},
                current_version="1.2.9-SNAPSHOT",
            )

    def test_push_rejects_next_version_not_newer_than_release(self) -> None:
        with self.assertRaisesRegex(ValueError, "must be newer"):
            MODULE.resolve_parameters(
                "push",
                {},
                {
                    "release_version": "1.2.9",
                    "next_development_version": "1.2.9-SNAPSHOT",
                },
            )

    def test_unsupported_event_is_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "unsupported release event"):
            MODULE.resolve_parameters("schedule", {})

    def test_cli_preserves_exact_major_transition_and_writes_stable_outputs(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_pom(root, "1.2.9-SNAPSHOT")
            output_path = root / "github-output"
            environment = os.environ | {
                "EVENT_NAME": "workflow_dispatch",
                "INPUT_NEXT_VERSION_INCREMENT": "patch",
                "INPUT_NEXT_DEVELOPMENT_VERSION": " 2.0.0-SNAPSHOT ",
                "INPUT_SKIP_TESTS": "false",
                "INPUT_DRY_RUN": "false",
                "GITHUB_OUTPUT": str(output_path),
            }
            result = subprocess.run(
                [sys.executable, str(SCRIPT)],
                cwd=root,
                env=environment,
                text=True,
                capture_output=True,
                check=False,
            )

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(
                [
                    "release_version=1.2.9",
                    "next_development_version=2.0.0-SNAPSHOT",
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
                        ("next_development_version", "2.0.0-SNAPSHOT"),
                        ("release_version", "1.2.9"),
                    ]
                ),
            )
            self.assertEqual(
                [
                    "release_version=1.2.9",
                    "next_development_version=2.0.0-SNAPSHOT",
                    "skip_tests=true",
                    "dry_run=false",
                    "resume_staged_release=true",
                ],
                output_path.read_text(encoding="utf-8").splitlines(),
            )

    def test_cli_reports_missing_event_name_clearly(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output_path = root / "github-output"
            environment = os.environ | {"GITHUB_OUTPUT": str(output_path)}
            environment.pop("EVENT_NAME", None)
            result = subprocess.run(
                [sys.executable, str(SCRIPT)],
                cwd=root,
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
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_pom(root, "1.2.9-SNAPSHOT")
            environment = os.environ | {
                "EVENT_NAME": "workflow_dispatch",
                "INPUT_NEXT_VERSION_INCREMENT": "patch",
                "INPUT_NEXT_DEVELOPMENT_VERSION": "",
            }
            environment.pop("GITHUB_OUTPUT", None)
            result = subprocess.run(
                [sys.executable, str(SCRIPT)],
                cwd=root,
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
