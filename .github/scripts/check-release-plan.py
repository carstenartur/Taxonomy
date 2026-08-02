#!/usr/bin/env python3
"""Validate Taxonomy's Maven release plan without creating SCM state."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
VERSION_PATTERN = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")
DEVELOPMENT_VERSION_PATTERN = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+-SNAPSHOT$")
PROPERTY_PATTERN = re.compile(r"\$\{([^}]+)}")
STATES = {"development", "release", "advanced"}


@dataclass(frozen=True)
class Coordinate:
    group_id: str
    artifact_id: str


@dataclass(frozen=True)
class PomModel:
    path: Path
    root: ET.Element
    group_id: str
    artifact_id: str
    version: str
    properties: dict[str, str]


def text(element: ET.Element | None) -> str:
    return (element.text or "").strip() if element is not None else ""


def child_text(root: ET.Element, name: str) -> str:
    return text(root.find(f"m:{name}", NS))


def normalized_version(value: str, field: str, pattern: re.Pattern[str]) -> str:
    normalized = value.strip()
    if normalized.startswith("${"):
        raise ValueError(
            f"{field} was not supplied. Pass -D{field} with an explicit version."
        )
    if not pattern.fullmatch(normalized):
        expected = (
            "X.Y.Z-SNAPSHOT" if pattern is DEVELOPMENT_VERSION_PATTERN else "X.Y.Z"
        )
        raise ValueError(f"{field} must use {expected}, got {normalized!r}")
    return normalized


def version_tuple(version: str) -> tuple[int, int, int]:
    return tuple(map(int, version.removesuffix("-SNAPSHOT").split(".")))


def expected_current_version(
    release_version: str, next_development_version: str, state: str
) -> str:
    if state == "development":
        return f"{release_version}-SNAPSHOT"
    if state == "release":
        return release_version
    if state == "advanced":
        return next_development_version
    raise ValueError(f"releaseCheckCurrentState must be one of {sorted(STATES)}")


def pom_paths(root: Path) -> list[Path]:
    return sorted(
        path
        for path in root.rglob("pom.xml")
        if ".git" not in path.parts and "target" not in path.parts
    )


def model_for(path: Path) -> PomModel:
    root = ET.parse(path).getroot()
    parent = root.find("m:parent", NS)
    group_id = child_text(root, "groupId")
    version = child_text(root, "version")
    if parent is not None:
        group_id = group_id or child_text(parent, "groupId")
        version = version or child_text(parent, "version")
    artifact_id = child_text(root, "artifactId")
    properties = {
        property_element.tag.rsplit("}", 1)[-1]: text(property_element)
        for property_element in root.findall("m:properties/*", NS)
    }
    properties.update(
        {
            "project.groupId": group_id,
            "pom.groupId": group_id,
            "project.artifactId": artifact_id,
            "pom.artifactId": artifact_id,
            "project.version": version,
            "pom.version": version,
        }
    )
    if parent is not None:
        properties.update(
            {
                "project.parent.groupId": child_text(parent, "groupId"),
                "project.parent.artifactId": child_text(parent, "artifactId"),
                "project.parent.version": child_text(parent, "version"),
            }
        )
    return PomModel(path, root, group_id, artifact_id, version, properties)


def resolve_properties(value: str, properties: dict[str, str]) -> str:
    result = value.strip()
    for _ in range(12):
        changed = False

        def replace(match: re.Match[str]) -> str:
            nonlocal changed
            key = match.group(1)
            replacement = properties.get(key)
            if replacement is None:
                return match.group(0)
            changed = True
            return replacement

        resolved = PROPERTY_PATTERN.sub(replace, result)
        result = resolved
        if not changed:
            break
    return result


def effective_properties(model: PomModel, root_properties: dict[str, str]) -> dict[str, str]:
    return root_properties | model.properties


def versioned_elements(model: PomModel) -> list[tuple[str, Coordinate | None, str]]:
    entries: list[tuple[str, Coordinate | None, str]] = []
    for dependency in model.root.findall(".//m:dependency", NS):
        version = child_text(dependency, "version")
        if version:
            entries.append(
                (
                    "dependency",
                    Coordinate(
                        child_text(dependency, "groupId"),
                        child_text(dependency, "artifactId"),
                    ),
                    version,
                )
            )
    for plugin in model.root.findall(".//m:plugin", NS):
        version = child_text(plugin, "version")
        artifact_id = child_text(plugin, "artifactId")
        if artifact_id == "maven-release-plugin":
            entries.append(("forbidden-plugin", None, version or "<managed>"))
        elif version:
            entries.append(
                (
                    "plugin",
                    Coordinate(
                        child_text(plugin, "groupId") or "org.apache.maven.plugins",
                        artifact_id,
                    ),
                    version,
                )
            )
    for extension in model.root.findall(".//m:extension", NS):
        version = child_text(extension, "version")
        if version:
            entries.append(
                (
                    "build extension",
                    Coordinate(
                        child_text(extension, "groupId"),
                        child_text(extension, "artifactId"),
                    ),
                    version,
                )
            )
    return entries


def check_git_clean(root: Path) -> None:
    if not (root / ".git").exists():
        return
    result = subprocess.run(
        ["git", "status", "--porcelain"],
        cwd=root,
        text=True,
        capture_output=True,
        check=False,
    )
    if result.returncode != 0:
        raise ValueError(f"git status failed: {result.stderr.strip()}")
    if result.stdout.strip():
        raise ValueError(
            "release verification requires a clean checkout; commit or stash local changes"
        )


def validate_release_plan(
    root: Path,
    current_version: str,
    release_version: str,
    next_development_version: str,
    state: str = "development",
    *,
    require_clean: bool = True,
) -> dict[str, object]:
    root = root.resolve()
    release_version = normalized_version(
        release_version, "releaseVersion", VERSION_PATTERN
    )
    next_development_version = normalized_version(
        next_development_version,
        "nextDevelopmentVersion",
        DEVELOPMENT_VERSION_PATTERN,
    )
    current_version = current_version.strip()
    if state not in STATES:
        raise ValueError(f"releaseCheckCurrentState must be one of {sorted(STATES)}")
    if version_tuple(next_development_version) <= version_tuple(release_version):
        raise ValueError(
            f"next development version {next_development_version} must be newer than "
            f"release {release_version}"
        )

    expected = expected_current_version(
        release_version, next_development_version, state
    )
    if current_version != expected:
        raise ValueError(
            f"release state {state} expects project version {expected}, "
            f"but Maven resolved {current_version}"
        )

    paths = pom_paths(root)
    if not paths or root / "pom.xml" not in paths:
        raise ValueError(f"no root pom.xml found below {root}")
    models = [model_for(path) for path in paths]
    root_model = next(model for model in models if model.path == root / "pom.xml")
    if root_model.version != current_version:
        raise ValueError(
            f"root pom.xml declares {root_model.version}, not Maven's {current_version}"
        )

    internal_coordinates = {
        Coordinate(model.group_id, model.artifact_id) for model in models
    }
    failures: list[str] = []
    for model in models:
        relative = model.path.relative_to(root)
        if model.group_id == root_model.group_id and model.version != current_version:
            failures.append(
                f"{relative}: reactor version {model.version!r} differs from {current_version}"
            )
        properties = effective_properties(model, root_model.properties)
        for kind, coordinate, raw_version in versioned_elements(model):
            if kind == "forbidden-plugin":
                failures.append(
                    f"{relative}: maven-release-plugin would create a second SCM release authority"
                )
                continue
            resolved = resolve_properties(raw_version, properties)
            if "SNAPSHOT" not in resolved.upper():
                continue
            if (
                kind == "dependency"
                and coordinate in internal_coordinates
                and resolved == current_version
                and state in {"development", "advanced"}
            ):
                continue
            coordinate_text = (
                f" {coordinate.group_id}:{coordinate.artifact_id}" if coordinate else ""
            )
            failures.append(
                f"{relative}: external {kind}{coordinate_text} uses snapshot version "
                f"{raw_version!r} (resolved as {resolved!r})"
            )

    if (root / "release.properties").exists():
        failures.append("release.properties: stale Maven Release Plugin state is present")
    for backup in root.rglob("pom.xml.releaseBackup"):
        if "target" not in backup.parts:
            failures.append(
                f"{backup.relative_to(root)}: stale Maven Release Plugin backup is present"
            )

    if failures:
        raise ValueError("release plan validation failed:\n- " + "\n- ".join(failures))
    if require_clean:
        check_git_clean(root)

    return {
        "current_version": current_version,
        "release_version": release_version,
        "next_development_version": next_development_version,
        "state": state,
        "pom_count": len(models),
    }


def parse_bool(value: str) -> bool:
    normalized = value.strip().lower()
    if normalized == "true":
        return True
    if normalized == "false":
        return False
    raise argparse.ArgumentTypeError("expected true or false")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--current-version", required=True)
    parser.add_argument("--release-version", required=True)
    parser.add_argument("--next-development-version", required=True)
    parser.add_argument("--state", default="development", choices=sorted(STATES))
    parser.add_argument("--require-clean", type=parse_bool, default=True)
    args = parser.parse_args()
    try:
        result = validate_release_plan(
            args.root,
            args.current_version,
            args.release_version,
            args.next_development_version,
            args.state,
            require_clean=args.require_clean,
        )
    except (OSError, ET.ParseError, ValueError) as error:
        print(f"Release check failed: {error}", file=sys.stderr)
        return 1
    print(
        "Maven release check passed: "
        f"{result['current_version']} -> {result['release_version']} -> "
        f"{result['next_development_version']} "
        f"({result['state']}, {result['pom_count']} reactor POMs)."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
