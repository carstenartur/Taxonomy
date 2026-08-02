#!/usr/bin/env python3
"""Regression tests for the Maven-owned Taxonomy release check."""

from __future__ import annotations

import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("check-release-plan.py").resolve()
SPEC = importlib.util.spec_from_file_location("check_release_plan", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Cannot load {SCRIPT}")
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)

ROOT_TEMPLATE = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.taxonomy</groupId>
  <artifactId>taxonomy</artifactId>
  <version>{version}</version>
  <packaging>pom</packaging>
  <properties><external.version>{external}</external.version></properties>
  <modules><module>module-a</module></modules>
  {extra}
</project>
"""
MODULE_TEMPLATE = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>com.taxonomy</groupId>
    <artifactId>taxonomy</artifactId>
    <version>{version}</version>
  </parent>
  <artifactId>module-a</artifactId>
  <dependencies>
    <dependency>
      <groupId>com.taxonomy</groupId>
      <artifactId>taxonomy</artifactId>
      <version>${{project.version}}</version>
    </dependency>
    {dependency}
  </dependencies>
</project>
"""


def write_project(
    root: Path,
    version: str = "1.3.0-SNAPSHOT",
    external: str = "1.0.0",
    extra: str = "",
    dependency: str = "",
) -> None:
    root.joinpath("pom.xml").write_text(
        ROOT_TEMPLATE.format(version=version, external=external, extra=extra),
        encoding="utf-8",
    )
    module = root / "module-a"
    module.mkdir()
    module.joinpath("pom.xml").write_text(
        MODULE_TEMPLATE.format(version=version, dependency=dependency),
        encoding="utf-8",
    )


class ReleasePlanTest(unittest.TestCase):
    def validate(
        self,
        root: Path,
        current: str = "1.3.0-SNAPSHOT",
        release: str = "1.3.0",
        next_version: str = "1.3.1-SNAPSHOT",
        state: str = "development",
    ) -> dict[str, object]:
        return MODULE.validate_release_plan(
            root,
            current,
            release,
            next_version,
            state,
            require_clean=False,
        )

    def test_development_plan_allows_internal_snapshot_reactor(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_project(root)
            result = self.validate(root)
            self.assertEqual(2, result["pom_count"])

    def test_major_next_version_is_supported(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_project(root)
            result = self.validate(root, next_version="2.0.0-SNAPSHOT")
            self.assertEqual("2.0.0-SNAPSHOT", result["next_development_version"])

    def test_repeated_current_snapshot_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_project(root)
            with self.assertRaisesRegex(ValueError, "must be newer"):
                self.validate(root, next_version="1.3.0-SNAPSHOT")

    def test_release_state_requires_release_poms(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_project(root, version="1.3.0")
            result = self.validate(root, current="1.3.0", state="release")
            self.assertEqual("release", result["state"])

    def test_advanced_state_accepts_next_snapshot(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_project(root, version="1.3.1-SNAPSHOT")
            result = self.validate(
                root, current="1.3.1-SNAPSHOT", state="advanced"
            )
            self.assertEqual("advanced", result["state"])

    def test_external_snapshot_dependency_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            dependency = """<dependency>
              <groupId>example</groupId><artifactId>unstable</artifactId>
              <version>${external.version}</version>
            </dependency>"""
            write_project(
                root, external="9.0.0-SNAPSHOT", dependency=dependency
            )
            with self.assertRaisesRegex(
                ValueError, "external dependency example:unstable"
            ):
                self.validate(root)

    def test_reactor_version_mismatch_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_project(root)
            module_pom = root / "module-a" / "pom.xml"
            module_pom.write_text(
                module_pom.read_text(encoding="utf-8").replace(
                    "1.3.0-SNAPSHOT", "1.2.9-SNAPSHOT"
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ValueError, "reactor version"):
                self.validate(root)

    def test_maven_release_plugin_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            extra = """<build><plugins><plugin>
              <groupId>org.apache.maven.plugins</groupId>
              <artifactId>maven-release-plugin</artifactId>
              <version>3.1.1</version>
            </plugin></plugins></build>"""
            write_project(root, extra=extra)
            with self.assertRaisesRegex(ValueError, "second SCM release authority"):
                self.validate(root)

    def test_unresolved_maven_property_reports_missing_argument(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_project(root)
            with self.assertRaisesRegex(ValueError, "was not supplied"):
                self.validate(root, release="${releaseVersion}")


if __name__ == "__main__":
    unittest.main()
