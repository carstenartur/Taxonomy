#!/usr/bin/env python3
"""Focused regression tests for check-version-state.py."""

from __future__ import annotations

import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("check-version-state.py")


def write_repository(root: Path, version: str, release: bool = False) -> None:
    root.joinpath("module").mkdir(parents=True)
    root.joinpath("deploy/helm/taxonomy").mkdir(parents=True)
    root.joinpath("pom.xml").write_text(
        """<project xmlns="http://maven.apache.org/POM/4.0.0">
<modelVersion>4.0.0</modelVersion><groupId>com.taxonomy</groupId>
<artifactId>taxonomy</artifactId><version>{version}</version></project>
""".format(version=version), encoding="utf-8")
    root.joinpath("module/pom.xml").write_text(
        """<project xmlns="http://maven.apache.org/POM/4.0.0">
<modelVersion>4.0.0</modelVersion><parent><groupId>com.taxonomy</groupId>
<artifactId>taxonomy</artifactId><version>{version}</version></parent>
<artifactId>module</artifactId></project>
""".format(version=version), encoding="utf-8")
    date_line = 'date-released: "2026-07-31"\n' if release else ""
    root.joinpath("CITATION.cff").write_text(
        f'version: "{version}"\n{date_line}', encoding="utf-8")
    bibtex_date = "  date         = {2026-07-31},\n" if release else ""
    root.joinpath("CITATION.md").write_text(
        f"Carsten Hammer. **Taxonomy Architecture Analyzer**. Version {version}. 2026.\n"
        f"  version      = {{{version}}},\n{bibtex_date}", encoding="utf-8")
    zenodo = {"version": version}
    codemeta = {"version": version}
    if release:
        zenodo["publication_date"] = "2026-07-31"
        codemeta["datePublished"] = "2026-07-31"
    root.joinpath(".zenodo.json").write_text(json.dumps(zenodo), encoding="utf-8")
    root.joinpath("codemeta.json").write_text(json.dumps(codemeta), encoding="utf-8")
    root.joinpath("deploy/helm/taxonomy/Chart.yaml").write_text(
        f'apiVersion: v2\nname: taxonomy\nappVersion: "{version}"\n', encoding="utf-8")


def run_check(root: Path, mode: str, *extra: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(SCRIPT), "--root", str(root), "--mode", mode, *extra],
        text=True, capture_output=True, check=False,
    )


class VersionStateTest(unittest.TestCase):
    def test_accepts_consistent_development_state(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_repository(root, "1.2.9-SNAPSHOT")
            result = run_check(root, "development")
            self.assertEqual(0, result.returncode, result.stderr)

    def test_accepts_consistent_release_state_and_tag(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_repository(root, "1.2.9", release=True)
            result = run_check(root, "release", "--tag", "v1.2.9")
            self.assertEqual(0, result.returncode, result.stderr)

    def test_rejects_snapshot_on_release_path(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_repository(root, "1.2.9-SNAPSHOT")
            result = run_check(root, "release")
            self.assertNotEqual(0, result.returncode)
            self.assertIn("not a valid release version", result.stderr)

    def test_rejects_helm_version_drift(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_repository(root, "1.2.9-SNAPSHOT")
            root.joinpath("deploy/helm/taxonomy/Chart.yaml").write_text(
                'apiVersion: v2\nname: taxonomy\nappVersion: "1.2.8"\n', encoding="utf-8")
            result = run_check(root, "development")
            self.assertNotEqual(0, result.returncode)
            self.assertIn("Helm Chart.yaml appVersion", result.stderr)


if __name__ == "__main__":
    unittest.main()
