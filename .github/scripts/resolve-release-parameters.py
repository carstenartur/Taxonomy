#!/usr/bin/env python3
"""Resolve, derive and validate parameters for the release workflow."""

from __future__ import annotations

import json
import os
from pathlib import Path
import re
import sys
from typing import Mapping
import xml.etree.ElementTree as ET

_RELEASE_VERSION = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")
_DEVELOPMENT_VERSION = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+-SNAPSHOT$")
_INCREMENT_VALUES = {"patch", "minor", "major"}
_OUTPUT_KEYS = (
    "release_version",
    "next_development_version",
    "skip_tests",
    "dry_run",
    "resume_staged_release",
)


def normalize_version(value: object, field: str, *, optional: bool = False) -> str:
    """Return a canonical version while rejecting malformed content."""
    if value is None and optional:
        return ""
    if not isinstance(value, str):
        raise ValueError(f"{field} must be a string")

    normalized = value.strip()
    if optional and not normalized:
        return ""
    pattern = (
        _DEVELOPMENT_VERSION
        if field == "next_development_version"
        else _RELEASE_VERSION
    )
    expected = (
        "X.Y.Z-SNAPSHOT"
        if field == "next_development_version"
        else "X.Y.Z"
    )
    if not pattern.fullmatch(normalized):
        raise ValueError(f"{field} must use {expected}")
    return normalized


def normalize_boolean(value: object, field: str) -> str:
    """Normalize JSON booleans and workflow-dispatch booleans to true or false."""
    if isinstance(value, bool):
        return str(value).lower()
    if isinstance(value, str):
        normalized = value.strip().lower()
        if normalized in {"true", "false"}:
            return normalized
    raise ValueError(f"{field} must be true or false")


def repository_version(root: Path) -> str:
    project = ET.parse(root / "pom.xml").getroot()
    value = project.findtext("{http://maven.apache.org/POM/4.0.0}version")
    if not value:
        raise ValueError("root pom.xml has no project version")
    return value.strip()


def version_tuple(version: str) -> tuple[int, int, int]:
    return tuple(map(int, version.removesuffix("-SNAPSHOT").split(".")))


def default_next_version(release_version: str, increment: str) -> str:
    """Calculate a conventional next version when no exact version was supplied."""
    normalized_increment = increment.strip().lower()
    if normalized_increment not in _INCREMENT_VALUES:
        raise ValueError("next_version_increment must be patch, minor or major")

    major, minor, patch = version_tuple(release_version)
    if normalized_increment == "patch":
        patch += 1
    elif normalized_increment == "minor":
        minor += 1
        patch = 0
    else:
        major += 1
        minor = 0
        patch = 0
    return f"{major}.{minor}.{patch}-SNAPSHOT"


def require_newer_next_version(
    release_version: str,
    next_version: str,
    *,
    current_version: str | None = None,
) -> None:
    """Reject a non-advancing next version with trigger-appropriate guidance."""
    if not next_version or version_tuple(next_version) > version_tuple(release_version):
        return

    if current_version is not None:
        context = (
            f"current project version {current_version} means this run releases "
            f"{release_version}"
        )
        guidance = (
            "Leave next_development_version empty to use the selected patch, minor "
            "or major increment, or enter a higher X.Y.Z-SNAPSHOT version."
        )
    else:
        context = f"release request publishes {release_version}"
        guidance = (
            "Set next_development_version to a higher X.Y.Z-SNAPSHOT version, "
            "or leave it empty when no post-release version advance is required."
        )

    raise ValueError(
        f"{context}; next development version {next_version} must be newer. "
        f"{guidance}"
    )


def derive_release_versions(
    current_version: str,
    increment: str,
    exact_next_version: object = "",
) -> tuple[str, str]:
    """Derive the release and preserve an optional exact next development version."""
    if not _DEVELOPMENT_VERSION.fullmatch(current_version):
        raise ValueError(
            f"current project version {current_version!r} must use X.Y.Z-SNAPSHOT"
        )

    release_version = current_version.removesuffix("-SNAPSHOT")
    next_version = normalize_version(
        exact_next_version,
        "next_development_version",
        optional=True,
    )
    if not next_version:
        next_version = default_next_version(release_version, increment)

    require_newer_next_version(
        release_version,
        next_version,
        current_version=current_version,
    )
    return release_version, next_version


def resolve_parameters(
    event_name: str,
    environment: Mapping[str, str],
    request: Mapping[str, object] | None = None,
    *,
    current_version: str | None = None,
) -> dict[str, str]:
    """Resolve a reviewed request or derive a manual release from repository state."""
    if event_name == "push":
        if request is None:
            raise ValueError("release request is required for a push event")
        release_value = request.get("release_version")
        next_value = request.get("next_development_version", "")
        skip_value = request.get("skip_tests", False)
        dry_run_value = request.get("dry_run", False)
        resume_value = request.get("resume_staged_release", False)
        release_version = normalize_version(release_value, "release_version")
        next_version = normalize_version(
            next_value, "next_development_version", optional=True
        )
    elif event_name == "workflow_dispatch":
        if current_version is None:
            raise ValueError(
                "current project version is required for workflow_dispatch"
            )
        release_version, next_version = derive_release_versions(
            current_version,
            environment.get("INPUT_NEXT_VERSION_INCREMENT", "patch") or "patch",
            environment.get("INPUT_NEXT_DEVELOPMENT_VERSION", ""),
        )
        skip_value = environment.get("INPUT_SKIP_TESTS", "false") or "false"
        dry_run_value = environment.get("INPUT_DRY_RUN", "false") or "false"
        resume_value = False
    else:
        raise ValueError(f"unsupported release event: {event_name}")

    parameters = {
        "release_version": release_version,
        "next_development_version": next_version,
        "skip_tests": normalize_boolean(skip_value, "skip_tests"),
        "dry_run": normalize_boolean(dry_run_value, "dry_run"),
        "resume_staged_release": normalize_boolean(
            resume_value, "resume_staged_release"
        ),
    }

    require_newer_next_version(release_version, next_version)

    if parameters["resume_staged_release"] == "true":
        if parameters["dry_run"] == "true":
            raise ValueError("resume_staged_release cannot be combined with dry_run")
        if not parameters["next_development_version"]:
            raise ValueError(
                "next_development_version is required when "
                "resume_staged_release is true"
            )

    return parameters


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
        current_version = None
        if event_name == "push":
            request_path = Path(
                os.environ.get("RELEASE_REQUEST_PATH", ".github/release-request.json")
            )
            loaded = json.loads(request_path.read_text(encoding="utf-8"))
            if not isinstance(loaded, dict):
                raise ValueError("release request must contain a JSON object")
            request = loaded
        elif event_name == "workflow_dispatch":
            current_version = repository_version(Path("."))

        parameters = resolve_parameters(
            event_name,
            os.environ,
            request,
            current_version=current_version,
        )
        append_outputs(Path(require_env("GITHUB_OUTPUT")), parameters)
    except (OSError, ET.ParseError, json.JSONDecodeError, ValueError) as error:
        print(f"::error::{error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
