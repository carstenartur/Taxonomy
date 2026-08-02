#!/usr/bin/env python3
"""Regression tests for the Maven-owned Taxonomy release check."""

from __future__ import annotations

import importlib.util
from pathlib import Path
import subprocess
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
  {parent}
  <groupId>com.taxonomy</groupId>
  <artifactId>taxonomy</artifactId>
  <version>{version}</version>
  <packaging>pom</packaging>
  <properties><external.version>{external}</external.version></properties>
  <modules>{modules}</modules>
  {extra}
</project>
"""
MODULE_TEMPLATE = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>com.taxonomy</groupId>
    <artifactId>taxonomy</artifactId>
    <version>{parent_version}</version>
  </parent>
  {group_id}
  <artifactId>{artifact_id}</artifactId>
  {version}
  <dependencies>
    <dependency>
      <groupId>com.taxonomy</groupId>
      <artifactId>taxonomy</artifactId>
      <version>${{project.version}}</version>
    </dependency>
    {dependency}
  </dependencies>
  {extra}
</project>
"""
EXTERNAL_PARENT = """<parent>
  <groupId>org.example</groupId>
  <artifactId>external-parent</artifactId>
  <version>{version}</version>
  <relativePath/>
</parent>"""


def module_element(path: str) -> str:
    return f"<module>{path}</module>"


def write_module(
    root: Path,
    path: str = "module-a",
    *,
    parent_version: str = "1.3.0-SNAPSHOT",
    artifact_id: str = "module-a",
    group_id: str = "",
    version: str = "",
    dependency: str = "",
    extra: str = "",
) -> None:
    module = root / path
    module.mkdir(parents=True, exist_ok=True)
    module.joinpath("pom.xml").write_text(
        MODULE_TEMPLATE.format(
            parent_version=parent_version,
            artifact_id=artifact_id,
            group_id=f"<groupId>{group_id}</groupId>" if group_id else "",
            version=f"<version>{version}</version>" if version else "",
            dependency=dependency,
            extra=extra,
        ),
        encoding="utf-8",
    )


def write_project(
    root: Path,
    version: str = "1.3.0-SNAPSHOT",
    external: str = "1.0.0",
    extra: str = "",
    dependency: str = "",
    parent_version: str = "1.0.0",
    modules: tuple[str, ...] = ("module-a",),
) -> None:
    root.joinpath("pom.xml").write_text(
        ROOT_TEMPLATE.format(
            parent=EXTERNAL_PARENT.format(version=parent_version),
            version=version,
            external=external,
            modules="".join(module_element(module) for module in modules),
            extra=extra,
        ),
        encoding="utf-8",
    )
    for module in modules:
        write_module(
            root,
            module,
            parent_version=version,
            artifact_id=Path(module).name,
            dependency=dependency if module == "module-a" else "",
        )


def git(root: Path, *args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", *args],
        cwd=root,
        text=True,
        capture_output=True,
        check=True,
    )


def commit_project(root: Path) -> None:
    git(root, "init", "-q")
    git(root, "add", ".")
    git(
        root,
        "-c",
        "user.name=Release QA",
        "-c",
        "user.email=qa@example.invalid",
        "commit",
        "-qm",
        "initial",
    )


class ReleasePlanTest(unittest.TestCase):
    def validate(
        self,
        root: Path,
        current: str = "1.3.0-SNAPSHOT",
        release: str = "1.3.0",
        next_version: str = "1.3.1-SNAPSHOT",
        state: str = "development",
        *,
        require_clean: bool = False,
    ) -> dict[str, object]:
        return MODULE.validate_release_plan(
            root,
            current,
            release,
            next_version,
            state,
            require_clean=require_clean,
        )

    def test_development_plan_allows_internal_snapshot_reactor(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_project(root)
            result = self.validate(root)
            self.assertEqual(2, result["pom_count"])

    def test_nested_declared_modules_are_discovered(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_project(root)
            aggregator = root / "module-a" / "pom.xml"
            aggregator.write_text(
                aggregator.read_text(encoding="utf-8").replace(
                    "</project>", "<modules><module>nested</module></modules></project>"
                ),
                encoding="utf-8",
            )
            write_module(
                root,
                "module-a/nested",
                artifact_id="nested",
                parent_version="1.3.0-SNAPSHOT",
            )
            result = self.validate(root)
            self.assertEqual(3, result["pom_count"])

    def test_unrelated_pom_is_not_part_of_the_reactor(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_project(root)
            unrelated = root / "examples"
            unrelated.mkdir()
            unrelated.joinpath("pom.xml").write_text(
                ROOT_TEMPLATE.format(
                    parent=EXTERNAL_PARENT.format(version="9.0.0-SNAPSHOT"),
                    version="9.0.0-SNAPSHOT",
                    external="9.0.0-SNAPSHOT",
                    modules="",
                    extra="",
                ),
                encoding="utf-8",
            )
            result = self.validate(root)
            self.assertEqual(2, result["pom_count"])

    def test_missing_declared_module_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_project(root, modules=())
            pom = root / "pom.xml"
            pom.write_text(
                pom.read_text(encoding="utf-8").replace(
                    "<modules></modules>",
                    "<modules><module>missing</module></modules>",
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ValueError, "does not exist"):
                self.validate(root)

    def test_module_outside_repository_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "repo"
            root.mkdir()
            write_project(root, modules=())
            pom = root / "pom.xml"
            pom.write_text(
                pom.read_text(encoding="utf-8").replace(
                    "<modules></modules>",
                    "<modules><module>../outside</module></modules>",
                ),
                encoding="utf-8",
            )
            outside = root.parent / "outside"
            outside.mkdir()
            outside.joinpath("pom.xml").write_text("<project/>", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "outside the repository"):
                self.validate(root)

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

    def test_external_snapshot_parent_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_project(root, parent_version="9.0.0-SNAPSHOT")
            with self.assertRaisesRegex(
                ValueError, "external parent org.example:external-parent"
            ):
                self.validate(root)

    def test_external_snapshot_dependency_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            dependency = """<dependency>
              <groupId>example</groupId><artifactId>unstable</artifactId>
              <version>${external.version}</version>
            </dependency>"""
            write_project(root, external="9.0.0-SNAPSHOT", dependency=dependency)
            with self.assertRaisesRegex(
                ValueError, "external dependency example:unstable"
            ):
                self.validate(root)

    def test_external_snapshot_plugin_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            extra = """<build><plugins><plugin>
              <groupId>example</groupId><artifactId>unstable-plugin</artifactId>
              <version>9.0.0-SNAPSHOT</version>
            </plugin></plugins></build>"""
            write_project(root, extra=extra)
            with self.assertRaisesRegex(
                ValueError, "external plugin example:unstable-plugin"
            ):
                self.validate(root)

    def test_external_snapshot_extension_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            extra = """<build><extensions><extension>
              <groupId>example</groupId><artifactId>unstable-extension</artifactId>
              <version>9.0.0-SNAPSHOT</version>
            </extension></extensions></build>"""
            write_project(root, extra=extra)
            with self.assertRaisesRegex(
                ValueError, "external build extension example:unstable-extension"
            ):
                self.validate(root)

    def test_reactor_version_mismatch_is_rejected_even_with_another_group(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_project(root)
            write_module(
                root,
                group_id="org.other",
                version="1.2.9-SNAPSHOT",
                parent_version="1.3.0-SNAPSHOT",
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

    def test_stale_release_plugin_state_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_project(root)
            root.joinpath("release.properties").write_text("stale", encoding="utf-8")
            root.joinpath("module-a", "pom.xml.releaseBackup").write_text(
                "stale", encoding="utf-8"
            )
            with self.assertRaisesRegex(ValueError, "stale Maven Release Plugin"):
                self.validate(root)

    def test_dirty_checkout_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_project(root)
            commit_project(root)
            root.joinpath("untracked.txt").write_text("dirty", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "clean checkout"):
                self.validate(root, require_clean=True)

    def test_clean_checkout_is_accepted(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_project(root)
            commit_project(root)
            result = self.validate(root, require_clean=True)
            self.assertEqual(2, result["pom_count"])

    def test_linked_worktree_dirty_state_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source"
            source.mkdir()
            write_project(source)
            commit_project(source)
            worktree = Path(directory) / "worktree"
            git(source, "worktree", "add", "-q", "-b", "qa-worktree", str(worktree))
            self.assertTrue((worktree / ".git").is_file())
            worktree.joinpath("untracked.txt").write_text("dirty", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "clean checkout"):
                self.validate(worktree, require_clean=True)

    def test_unresolved_maven_property_reports_missing_argument(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_project(root)
            with self.assertRaisesRegex(ValueError, "was not supplied"):
                self.validate(root, release="${releaseVersion}")


if __name__ == "__main__":
    unittest.main()
