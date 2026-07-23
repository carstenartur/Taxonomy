#!/usr/bin/env python3
"""Reject mutable GitHub Action references and production image tags."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ACTION = re.compile(r"^\s*-?\s*uses:\s*([^\s#]+)")
SHA_REF = re.compile(r"^[^@]+@[0-9a-fA-F]{40}$")
FROM = re.compile(r"^\s*FROM\s+([^\s]+)", re.IGNORECASE)
COMPOSE_IMAGE = re.compile(r"^\s*image:\s*([^\s#]+)")
DIGEST = re.compile(r"^[^@]+@sha256:[0-9a-fA-F]{64}$")


def action_failures(root: Path) -> list[str]:
    failures: list[str] = []
    for workflow in sorted((root / ".github" / "workflows").glob("*.y*ml")):
        for line_number, line in enumerate(workflow.read_text(encoding="utf-8").splitlines(), 1):
            match = ACTION.match(line)
            if not match:
                continue
            reference = match.group(1).strip('"\'')
            if reference.startswith("./"):
                continue
            if not SHA_REF.fullmatch(reference):
                failures.append(
                    f"{workflow.relative_to(root)}:{line_number}: mutable action reference {reference}"
                )
    return failures


def image_failures(root: Path) -> list[str]:
    failures: list[str] = []
    dockerfile = root / "Dockerfile"
    if dockerfile.is_file():
        for line_number, line in enumerate(dockerfile.read_text(encoding="utf-8").splitlines(), 1):
            match = FROM.match(line)
            if not match:
                continue
            image = match.group(1)
            if image.lower() == "scratch" or image.startswith("${"):
                continue
            if not DIGEST.fullmatch(image):
                failures.append(
                    f"Dockerfile:{line_number}: production build image is not digest-pinned: {image}"
                )

    compose = root / "docker-compose.prod.yml"
    if compose.is_file():
        for line_number, line in enumerate(compose.read_text(encoding="utf-8").splitlines(), 1):
            match = COMPOSE_IMAGE.match(line)
            if not match:
                continue
            image = match.group(1).strip('"\'')
            if image.startswith("${"):
                continue
            if not DIGEST.fullmatch(image):
                failures.append(
                    f"docker-compose.prod.yml:{line_number}: production image is not digest-pinned: {image}"
                )
    return failures


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("."))
    args = parser.parse_args()
    root = args.root.resolve()

    failures = action_failures(root) + image_failures(root)
    if failures:
        print("Supply-chain pinning violations:", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1

    print("All external GitHub Actions and production images are immutably pinned.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
