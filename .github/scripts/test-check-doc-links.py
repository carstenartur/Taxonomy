#!/usr/bin/env python3
"""Regression tests for check-doc-links.py without third-party dependencies."""

from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("check-doc-links.py")
SPEC = importlib.util.spec_from_file_location("taxonomy_check_doc_links", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class DocumentationLinkCheckTest(unittest.TestCase):

    def create_repository(self) -> Path:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        return Path(directory.name)

    def test_reports_broken_link_in_repository_documentation(self) -> None:
        root = self.create_repository()
        guide = root / "docs" / "guide.md"
        guide.parent.mkdir(parents=True)
        guide.write_text("[Missing](missing.md)\n", encoding="utf-8")

        markdown_files, errors = MODULE.check_documentation_links(root, ["docs"])

        self.assertEqual([guide], markdown_files)
        self.assertEqual(["docs/guide.md:1: missing local target: missing.md"], errors)

    def test_ignores_dependency_markdown_below_node_modules(self) -> None:
        root = self.create_repository()
        owned = root / ".github" / "OWNED.md"
        owned.parent.mkdir(parents=True)
        owned.write_text("[Existing](owned-target.txt)\n", encoding="utf-8")
        (owned.parent / "owned-target.txt").write_text("ok\n", encoding="utf-8")

        dependency_readme = root / ".github" / "node_modules" / "dependency" / "README.md"
        dependency_readme.parent.mkdir(parents=True)
        dependency_readme.write_text("[Missing](CONTRIBUTING.md)\n", encoding="utf-8")

        markdown_files, errors = MODULE.check_documentation_links(root, [".github"])

        self.assertEqual([owned], markdown_files)
        self.assertEqual([], errors)

    def test_prunes_all_configured_generated_directories(self) -> None:
        root = self.create_repository()
        docs = root / "docs"
        docs.mkdir()

        for directory_name in MODULE.IGNORED_DIRECTORY_NAMES:
            generated_readme = docs / directory_name / "README.md"
            generated_readme.parent.mkdir(parents=True)
            generated_readme.write_text("[Missing](missing.md)\n", encoding="utf-8")

        markdown_files, errors = MODULE.check_documentation_links(root, ["docs"])

        self.assertEqual([], markdown_files)
        self.assertEqual([], errors)


if __name__ == "__main__":
    unittest.main()
