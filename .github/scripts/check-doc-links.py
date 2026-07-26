#!/usr/bin/env python3
"""Check repository-owned local Markdown links and image references.

External URLs are ignored so CI does not fail because of transient remote
outages. Generated output and dependency trees are excluded because their
Markdown files are not part of Taxonomy's documentation contract.
"""

from __future__ import annotations

import argparse
import html
import os
from pathlib import Path
import re
import sys
from urllib.parse import unquote

MARKDOWN_LINK_RE = re.compile(r"!?\[[^\]]*\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")
REFERENCE_DEF_RE = re.compile(r"^\s*\[[^\]]+\]:\s+(\S+)")
HTML_ATTR_RE = re.compile(r"\b(?:src|href)=[\"']([^\"']+)[\"']", re.IGNORECASE)

EXTERNAL_PREFIXES = (
    "http://",
    "https://",
    "mailto:",
    "tel:",
    "data:",
    "javascript:",
)

DEFAULT_ROOTS = ("README.md", "CITATION.md", "RESEARCH.md", "docs", ".github")
IGNORED_DIRECTORY_NAMES = frozenset({
    ".git",
    ".gradle",
    ".pytest_cache",
    ".venv",
    "__pycache__",
    "build",
    "dist",
    "node_modules",
    "target",
    "venv",
})


def is_ignored_path(root: Path, path: Path) -> bool:
    """Return whether *path* lives in a generated or third-party directory."""
    try:
        relative = path.resolve().relative_to(root.resolve())
    except ValueError:
        return False
    return any(part in IGNORED_DIRECTORY_NAMES for part in relative.parts)


def iter_markdown_files(root: Path, inputs: list[str]) -> list[Path]:
    """Collect repository-owned Markdown files without descending into ignored trees."""
    files: set[Path] = set()
    for item in inputs:
        path = root / item
        if is_ignored_path(root, path):
            continue
        if path.is_file() and path.suffix.lower() == ".md":
            files.add(path)
        elif path.is_dir():
            for directory, child_directories, file_names in os.walk(path):
                child_directories[:] = sorted(
                    name for name in child_directories
                    if name not in IGNORED_DIRECTORY_NAMES
                )
                directory_path = Path(directory)
                for file_name in sorted(file_names):
                    candidate = directory_path / file_name
                    if candidate.suffix.lower() == ".md" and not is_ignored_path(root, candidate):
                        files.add(candidate)
    return sorted(files)


def is_external_or_runtime_route(target: str) -> bool:
    lowered = target.lower()
    if lowered.startswith(EXTERNAL_PREFIXES):
        return True
    if target.startswith("#"):
        return True
    # Application routes such as /swagger-ui.html are not repository files.
    if target.startswith("/") and not target.startswith("/docs/") and not target.startswith("/.github/"):
        return True
    return False


def normalize_target(target: str) -> str:
    target = html.unescape(target.strip())
    if target.startswith("<") and target.endswith(">"):
        target = target[1:-1]
    target = target.split("#", 1)[0]
    target = target.split("?", 1)[0]
    return unquote(target)


def resolve_local_target(repo_root: Path, md_file: Path, target: str) -> Path:
    if target.startswith("/"):
        return (repo_root / target.lstrip("/")).resolve()
    return (md_file.parent / target).resolve()


def collect_targets(line: str) -> list[str]:
    targets = [m.group(1) for m in MARKDOWN_LINK_RE.finditer(line)]
    targets.extend(m.group(1) for m in HTML_ATTR_RE.finditer(line))
    ref_match = REFERENCE_DEF_RE.match(line)
    if ref_match:
        targets.append(ref_match.group(1))
    return targets


def check_documentation_links(repo_root: Path, inputs: list[str]) -> tuple[list[Path], list[str]]:
    """Return the checked Markdown files and all invalid repository-local targets."""
    markdown_files = iter_markdown_files(repo_root, inputs)
    errors: list[str] = []

    for md_file in markdown_files:
        rel_md = md_file.relative_to(repo_root)
        try:
            lines = md_file.read_text(encoding="utf-8").splitlines()
        except UnicodeDecodeError as exc:
            errors.append(f"{rel_md}: cannot read as UTF-8: {exc}")
            continue

        for line_no, line in enumerate(lines, start=1):
            for raw_target in collect_targets(line):
                target = normalize_target(raw_target)
                if not target or is_external_or_runtime_route(target):
                    continue
                resolved = resolve_local_target(repo_root, md_file, target)
                try:
                    resolved.relative_to(repo_root)
                except ValueError:
                    errors.append(f"{rel_md}:{line_no}: target escapes repository: {raw_target}")
                    continue
                if not resolved.exists():
                    errors.append(f"{rel_md}:{line_no}: missing local target: {raw_target}")

    return markdown_files, errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("paths", nargs="*", default=list(DEFAULT_ROOTS))
    args = parser.parse_args()

    repo_root = Path.cwd().resolve()
    markdown_files, errors = check_documentation_links(repo_root, args.paths)

    if errors:
        print("Documentation link check failed:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1

    print(f"Checked {len(markdown_files)} Markdown file(s); all local links and images exist.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
