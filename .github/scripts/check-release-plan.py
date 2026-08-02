#!/usr/bin/env python3
"""Validate Taxonomy's Maven release plan without creating SCM state."""

from __future__ import annotations

import argparse
from collections import deque
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
    parent_coordinate: Coordinate | None


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


def reactor_pom_paths(root: Path) -> list[Path]:
    """Return only POMs reachable through Maven's declared module graph."""
    root = root.resolve()
    root_pom = root / "pom.xml"
    if not root_pom.is_file():
        raise ValueError(f"root pom.xml does not exist below {root}")

    pending: deque[Path] = deque([root_pom])
    discovered: list[Path] = []
    seen: set[Path] = set()
    while pending:
        pom = pending.popleft().resolve()
        if pom in seen:
            continue
        if not pom.is_file():
            raise ValueError(f"declared Maven module POM does not exist: {pom}")
        try:
            pom.relative_to(root)
        except ValueError as error:
            raise ValueError(
                f"declared Maven module escapes repository root: {pom}"
            ) from error

        project = ET.parse(pom).getroot()
        seen.add(pom)
        discovered.append(pom)
        for module_element in project.findall("m:modules/m:module", NS):
            module = text(module_element)
            if not module:
                raise ValueError(
                    f"{pom.relative_to(root)} contains an empty Maven module declaration"
                )
            module_path = (pom.parent / module).resolve()
            module_pom = (
                module_path if module_path.name == "pom.xml" else module_path / "pom.xml"
            )
            try:
                relative_module = module_pom.relative_to(root)
            except ValueError as error:
                raise ValueError(
                    f"{pom.relative_to(root)} declares module {module!r} outside the repository"
                ) from error
            if not module_pom.is_file():
                raise ValueError(
                    f"{pom.relative_to(root)} declares module {module!r}, but "
                    f"{relative_module} does not exist"
                )
            pending.append(module_pom)
    return discovered


def model_for(path: Path) -> PomModel:
    root = ET.parse(path).getroot()
    parent = root.find("m:parent", NS)
    parent_coordinate = None
    group_id = child_text(root, "groupId")
    version = child_text(root, "version")
    if parent is not None:
        parent_coordinate = Coordinate(
            child_text(parent, "groupId"), child_text(parent, "artifactId")
        )
        group_id = group_id or parent_coordinate.group_id
        version = version or child_text(parent, "version")
    artifact_id = child_text(root, "artifactId")
    if not group_id or not artifact_id or not version:
        raise ValueError(
            f"{path}: Maven coordinates must provide effective groupId, artifactId and version"
        )

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
                "project.parent.groupId": parent_coordinate.group_id,
                "project.parent.artifactId": parent_coordinate.artifact_id,
                "project.parent.version": child_text(parent, "version"),
            }
        )
    return PomModel(
        path,
        root,
        group_id,
        artifact_id,
        version,
        properties,
        parent_coordinate,
    )


def resolve_properties(value: str, properties: dict[str, str]) -> str:
    result = value.strip()
    for _ in range(12):
        changed = False

        def replace(match: re.Match[str]) -> str:
            nonlocal changed
            replacement = properties.get(match.group(1))
            if replacement is None:
                return match.group(0)
            changed = True
            return replacement

        updated = PROPERTY_PATTERN.sub(replace, result)
        result = updated
        if not changed:
            break
    return result


def reactor_models_by_coordinate(models: list[PomModel]) -> dict[Coordinate, PomModel]:
    indexed: dict[Coordinate, PomModel] = {}
    for model in models:
        coordinate = Coordinate(model.group_id, model.artifact_id)
        previous = indexed.get(coordinate)
        if previous is not None:
            raise ValueError(
                "duplicate Maven reactor coordinate "
                f"{coordinate.group_id}:{coordinate.artifact_id}: "
                f"{previous.path} and {model.path}"
            )
        indexed[coordinate] = model
    return indexed


def effective_properties(
    model: PomModel,
    models_by_coordinate: dict[Coordinate, PomModel],
    cache: dict[Path, dict[str, str]],
    visiting: set[Path] | None = None,
) -> dict[str, str]:
    cached = cache.get(model.path)
    if cached is not None:
        return cached

    visiting = set() if visiting is None else visiting
    if model.path in visiting:
        raise ValueError(f"cyclic Maven parent relationship involving {model.path}")
    visiting.add(model.path)

    inherited: dict[str, str] = {}
    if model.parent_coordinate is not None:
        parent_model = models_by_coordinate.get(model.parent_coordinate)
        if parent_model is not None:
            inherited = effective_properties(
                parent_model, models_by_coordinate, cache, visiting
            )

    result = inherited | model.properties
    cache[model.path] = result
    visiting.remove(model.path)
    return result


def versioned_elements(model: PomModel) -> list[tuple[str, Coordinate | None, str]]:
    entries: list[tuple[str, Coordinate | None, str]] = []
    parent = model.root.find("m:parent", NS)
    if parent is not None:
        parent_version = child_text(parent, "version")
        if parent_version:
            entries.append(("parent", model.parent_coordinate, parent_version))

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

    paths = reactor_pom_paths(root)
    models = [model_for(path) for path in paths]
    root_model = models[0]
    if root_model.path != root / "pom.xml":
        raise ValueError("reactor discovery did not start at the root pom.xml")

    models_by_coordinate = reactor_models_by_coordinate(models)
    internal_coordinates = set(models_by_coordinate)
    property_cache: dict[Path, dict[str, str]] = {}
    failures: list[str] = []

    for model in models:
        relative = model.path.relative_to(root)
        properties = effective_properties(
            model, models_by_coordinate, property_cache
        )
        resolved_model_version = resolve_properties(model.version, properties)
        if PROPERTY_PATTERN.search(resolved_model_version):
            failures.append(
                f"{relative}: reactor version {model.version!r} contains an "
                f"unresolved version property (resolved as {resolved_model_version!r})"
            )
        elif resolved_model_version != current_version:
            failures.append(
                f"{relative}: reactor version {resolved_model_version!r} differs from "
                f"{current_version}"
            )

        for kind, coordinate, raw_version in versioned_elements(model):
            if kind == "forbidden-plugin":
                failures.append(
                    f"{relative}: maven-release-plugin would create a second SCM release authority"
                )
                continue
            resolved = resolve_properties(raw_version, properties)
            coordinate_text = (
                f" {coordinate.group_id}:{coordinate.artifact_id}" if coordinate else ""
            )
            if PROPERTY_PATTERN.search(resolved):
                failures.append(
                    f"{relative}: {kind}{coordinate_text} uses unresolved version property "
                    f"{raw_version!r} (resolved as {resolved!r})"
                )
                continue
            if "SNAPSHOT" not in resolved.upper():
                continue
            if (
                kind in {"dependency", "parent"}
                and coordinate in internal_coordinates
                and resolved == current_version
                and state in {"development", "advanced"}
            ):
                continue
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
