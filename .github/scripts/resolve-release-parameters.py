#!/usr/bin/env python3
"""Resolve, normalize and validate inputs for the release workflow."""

from __future__ import annotations

import json
import os
from pathlib import Path
import re
import sys
from typing import Mapping

_RELEASE_VERSION = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")
_DEVELOPMENT_VERSION = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+-SNAPSHOT$")
_OUTPUT_KEYS = (
    "release_version",
    "next_development_version",
    "skip_tests",
    "dry_run",
)


def normalize_version(value: object, field: str, *, optional: bool = False) -> str:
    """Return a trimmed, single-line semantic version suitable for GitHub outputs."""
    if value is None and optional:
        return ""
    if not isinstance(value, str):
        raise ValueError(f"{field} must be a string")

    normalized = value.strip()
    if value != normalized:
        print(
            f"::notice title=Normalized release input::{field} contained "
            "surrounding whitespace and was trimmed"
        )

    if optional and not normalized:
        return ""
    pattern = _DEVELOPMENT_VERSION if field == "next_development_version" else _RELEASE_VERSION
    expected = "X.Y.Z-SNAPSHOT" if field == "next_development_version" else "X.Y.Z"
    if not pattern.fullmatch(normalized):
        raise ValueError(f"{field} must use {expected}")
    return normalized


def normalize_boolean(value: object, field: str) -> str:
    """Normalize JSON booleans and workflow-dispatch strings to true or false."""
    if isinstance(value, bool):
        return str(value).lower()
    if isinstance(value, str):
        normalized = value.strip().lower()
        if normalized in {"true", "false"}:
            return normalized
    raise ValueError(f"{field} must be true or false")


def resolve_parameters(
    event_name: str,
    environment: Mapping[str, str],
    request: Mapping[str, object] | None = None,
) -> dict[str, str]:
    """Resolve parameters from either a release request or workflow-dispatch inputs."""
    if event_name == "push":
        if request is None:
            raise ValueError("release request is required for a push event")
        release_value = request.get("release_version")
        next_value = request.get("next_development_version", "")
        skip_value = request.get("skip_tests", False)
        dry_run_value = request.get("dry_run", False)
    else:
        release_value = environment.get("INPUT_RELEASE_VERSION", "")
        next_value = environment.get("INPUT_NEXT_DEVELOPMENT_VERSION", "")
        skip_value = environment.get("INPUT_SKIP_TESTS", "false") or "false"
        dry_run_value = environment.get("INPUT_DRY_RUN", "false") or "false"

    return {
        "release_version": normalize_version(release_value, "release_version"),
        "next_development_version": normalize_version(
            next_value, "next_development_version", optional=True
        ),
        "skip_tests": normalize_boolean(skip_value, "skip_tests"),
        "dry_run": normalize_boolean(dry_run_value, "dry_run"),
    }


def append_outputs(output_path: Path, parameters: Mapping[str, str]) -> None:
    with output_path.open("a", encoding="utf-8") as output:
        for key in _OUTPUT_KEYS:
            print(f"{key}={parameters[key]}", file=output)


def require_env(name: str) -> str:
    value = os.environ.get(name)
    if value is None:
        raise ValueError(f"{name} environment variable is required")
    return value


def main() -> int:
    try:
        event_name = require_env("EVENT_NAME")
        request = None
        if event_name == "push":
            request_path = Path(
                os.environ.get("RELEASE_REQUEST_PATH", ".github/release-request.json")
            )
            loaded = json.loads(request_path.read_text(encoding="utf-8"))
            if not isinstance(loaded, dict):
                raise ValueError("release request must contain a JSON object")
            request = loaded

        parameters = resolve_parameters(event_name, os.environ, request)
        append_outputs(Path(require_env("GITHUB_OUTPUT")), parameters)
    except (OSError, json.JSONDecodeError, ValueError) as error:
        print(f"::error::{error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
