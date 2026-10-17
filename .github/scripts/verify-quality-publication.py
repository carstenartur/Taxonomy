#!/usr/bin/env python3
"""Verify that published quality evidence is complete, commit-bound, and internally consistent."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import time
from typing import Callable
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qsl, urlencode, urljoin, urlsplit, urlunsplit
from urllib.request import Request, urlopen

JsonFetcher = Callable[[str], object]
TextFetcher = Callable[[str], str]


_SPEC = importlib.util.spec_from_file_location("quality_report_validation", Path(__file__).with_name("generate-quality-site.py"))
_GENERATOR = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_GENERATOR)


def validate_report_path(path: object) -> None:
    if (not isinstance(path, str) or not path.startswith("tests/")
            or ".." in Path(path).parts or not path.endswith(".xml")
            or "\\" in path or "?" in path or "#" in path):
        raise ValueError(f"invalid raw JUnit report path: {path}")


def verify_report_totals(summary: dict, reports: list[tuple[str, str]]) -> None:
    totals = dict.fromkeys(("tests", "failures", "errors", "skipped"), 0)
    paths = summary.get("testReports")
    if not isinstance(paths, list) or not paths or len(set(paths)) != len(paths):
        raise ValueError("quality summary must inventory every raw JUnit report")
    if sorted(paths) != sorted(path for path, _ in reports):
        raise ValueError("raw JUnit report inventory differs from the summary")
    for path, content in reports:
        validate_report_path(path)
        counts = _GENERATOR.parse_junit_report(content, path)
        for key in totals:
            totals[key] += counts[key]
    totals["executed"] = totals["tests"] - totals["skipped"]
    totals["passed"] = totals["executed"] - totals["failures"] - totals["errors"]
    totals["reportFiles"] = len(reports)
    if totals != summary["tests"]:
        raise ValueError(f"raw JUnit outcomes differ from summary: {totals}")


def verify_application_shards(root: Path, summary: dict) -> None:
    """Retain every source-bound original after merging independent lane outputs."""
    evidence = root / "evidence/application-test-shards"
    if not evidence.is_dir():
        return

    def verify_digest(path: Path, expected: object) -> None:
        if not path.is_file():
            raise ValueError(f"application shard original is missing: {path}")
        if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise ValueError(f"application shard original digest differs: {path}")

    for shard in ("core", "browser", "recovery"):
        manifest_path = evidence / shard / "manifest.json"
        if not manifest_path.is_file():
            raise ValueError(f"application shard manifest is missing: {shard}")
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        expected_metadata = {
            "schemaVersion": 1, "shard": shard, "commit": summary["commit"],
            "sourceTree": summary["sourceTree"], "buildId": f"{summary['buildId']}.{shard}",
        }
        if not isinstance(manifest, dict) or any(manifest.get(key) != value for key, value in expected_metadata.items()):
            raise ValueError(f"application shard source/build metadata differs: {shard}")
        coverage = manifest.get("coverage")
        if not isinstance(coverage, dict) or coverage.get("path") != "coverage.exec":
            raise ValueError(f"application shard coverage metadata is invalid: {shard}")
        verify_digest(evidence / shard / "coverage.exec", coverage.get("sha256"))

        reports = manifest.get("reports")
        if not isinstance(reports, list) or not reports:
            raise ValueError(f"application shard report inventory is empty: {shard}")
        destination = root / ("tests/taxonomy-app/target" if shard == "core"
                              else f"tests/application-test-shards/{shard}")
        originals = set()
        for report in reports:
            if not isinstance(report, dict):
                raise ValueError(f"application shard report metadata is invalid: {shard}")
            relative = report.get("path")
            validate_report_path(relative)
            path = destination / Path(relative).relative_to("tests")
            if path in originals:
                raise ValueError(f"application shard repeats an original report: {path}")
            originals.add(path)
            verify_digest(path, report.get("sha256"))
            counts = _GENERATOR.parse_junit_report(path.read_text(encoding="utf-8"), path)
            if any(report.get(key) != value for key, value in counts.items()):
                raise ValueError(f"application shard original outcomes differ: {path}")
        if originals != set(destination.rglob("TEST-*.xml")):
            raise ValueError(f"application shard original report inventory differs: {shard}")


REQUIRED_FILES = (
    "index.html",
    "quality-summary.json",
    "tests/badge.json",
    "tests/surefire-report.html",
    "coverage/badge.json",
    "coverage/index.html",
    ".nojekyll",
)


def _normalise_commit(value: object) -> str:
    return str(value or "").strip().lower()


def verify_payloads(
    summary: object,
    test_badge: object,
    coverage_badge: object,
    expected_commit: str,
) -> None:
    if not isinstance(summary, dict):
        raise ValueError("quality-summary.json must contain a JSON object")
    if not isinstance(test_badge, dict) or not isinstance(coverage_badge, dict):
        raise ValueError("badge JSON must contain JSON objects")

    expected = _normalise_commit(expected_commit)
    actual = _normalise_commit(summary.get("commit") or summary.get("verifiedCommit"))
    if not expected or actual != expected:
        raise ValueError(f"quality report commit {actual!r} does not match {expected!r}")

    for key in ("generatedAt", "sourceTree", "buildId", "tests", "coverage", "tools"):
        if key not in summary:
            raise ValueError(f"quality summary is missing required metadata field {key!r}")

    tests = summary.get("tests")
    coverage = summary.get("coverage")
    if not isinstance(tests, dict) or not isinstance(coverage, dict):
        raise ValueError("quality summary tests/coverage fields must be objects")

    try:
        registered = int(tests["tests"])
        executed = int(tests["executed"])
        passed = int(tests["passed"])
        skipped = int(tests["skipped"])
        failures = int(tests["failures"])
        errors = int(tests["errors"])
    except (KeyError, TypeError, ValueError) as error:
        raise ValueError("quality summary has incomplete or invalid test totals") from error
    if min(registered, executed, passed, skipped, failures, errors) < 0:
        raise ValueError("quality summary contains negative test totals")
    if executed != registered - skipped:
        raise ValueError("quality summary executed-test total is inconsistent")
    if passed != executed - failures - errors:
        raise ValueError("quality summary passed-test total is inconsistent")

    expected_test_message = f"{passed} passed"
    if test_badge.get("message") != expected_test_message:
        raise ValueError(
            f"test badge {test_badge.get('message')!r} does not match summary {expected_test_message!r}"
        )

    try:
        instruction_percent = float(coverage["instructionPercent"])
    except (KeyError, TypeError, ValueError) as error:
        raise ValueError("quality summary has no valid instructionPercent") from error
    expected_coverage_message = f"{instruction_percent:.2f}%"
    if coverage_badge.get("message") != expected_coverage_message:
        raise ValueError(
            "coverage badge "
            f"{coverage_badge.get('message')!r} does not match summary {expected_coverage_message!r}"
        )


def verify_local(root: Path, expected_commit: str) -> None:
    missing = [relative for relative in REQUIRED_FILES if not root.joinpath(relative).is_file()]
    if missing:
        raise FileNotFoundError(f"quality publication is missing: {', '.join(missing)}")
    summary = json.loads(root.joinpath("quality-summary.json").read_text(encoding="utf-8"))
    test_badge = json.loads(root.joinpath("tests/badge.json").read_text(encoding="utf-8"))
    coverage_badge = json.loads(root.joinpath("coverage/badge.json").read_text(encoding="utf-8"))
    verify_payloads(summary, test_badge, coverage_badge, expected_commit)
    verify_report_totals(summary, [(str(path.relative_to(root)), path.read_text(encoding="utf-8"))
                                   for path in sorted((root / "tests").rglob("TEST-*.xml"))])
    verify_application_shards(root, summary)


def _request(url: str) -> Request:
    return Request(
        url,
        headers={
            "Accept": "application/json,text/html;q=0.9,*/*;q=0.8",
            "Cache-Control": "no-cache",
            "Pragma": "no-cache",
            "User-Agent": "taxonomy-quality-publication-check",
        },
    )


def fetch_json(url: str) -> object:
    with urlopen(_request(url), timeout=20) as response:
        return json.load(response)


def fetch_text(url: str) -> str:
    with urlopen(_request(url), timeout=20) as response:
        return response.read().decode("utf-8", errors="replace")


def _remote_url(base_url: str, relative: str, expected_commit: str) -> str:
    target = urljoin(base_url.rstrip("/") + "/", relative)
    parts = urlsplit(target)
    query = parse_qsl(parts.query, keep_blank_values=True)
    query.append(("verified", expected_commit))
    return urlunsplit(
        (parts.scheme, parts.netloc, parts.path, urlencode(query), parts.fragment)
    )


def verify_remote_once(
    base_url: str,
    expected_commit: str,
    json_fetcher: JsonFetcher = fetch_json,
    text_fetcher: TextFetcher = fetch_text,
) -> None:
    summary = json_fetcher(_remote_url(base_url, "quality-summary.json", expected_commit))
    test_badge = json_fetcher(_remote_url(base_url, "tests/badge.json", expected_commit))
    coverage_badge = json_fetcher(_remote_url(base_url, "coverage/badge.json", expected_commit))
    verify_payloads(summary, test_badge, coverage_badge, expected_commit)

    for path in summary.get("testReports", []):
        validate_report_path(path)
    verify_report_totals(summary, [(path, text_fetcher(_remote_url(base_url, path, expected_commit)))
                                  for path in summary.get("testReports", [])])
    index = text_fetcher(_remote_url(base_url, "index.html", expected_commit))
    report = text_fetcher(_remote_url(base_url, "tests/surefire-report.html", expected_commit))
    if expected_commit.lower() not in index.lower():
        raise ValueError("published index does not identify the expected commit")
    if expected_commit.lower() not in report.lower():
        raise ValueError("published test report does not identify the expected commit")


def verify_remote(
    base_url: str,
    expected_commit: str,
    attempts: int,
    interval_seconds: float,
) -> None:
    last_error = "publication did not answer"
    for attempt in range(1, attempts + 1):
        try:
            verify_remote_once(base_url, expected_commit)
            print(
                f"Published quality evidence verified for {expected_commit} "
                f"on attempt {attempt}."
            )
            return
        except (HTTPError, URLError, TimeoutError, ValueError, json.JSONDecodeError) as error:
            last_error = str(error)
        print(
            f"Quality publication attempt {attempt}/{attempts} not ready: {last_error}"
        )
        if attempt < attempts:
            time.sleep(interval_seconds)
    raise RuntimeError(f"published quality evidence verification failed: {last_error}")


def main() -> int:
    parser = argparse.ArgumentParser()
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--root", type=Path)
    source.add_argument("--base-url")
    parser.add_argument("--expected-commit", required=True)
    parser.add_argument("--attempts", type=int, default=30)
    parser.add_argument("--interval-seconds", type=float, default=10.0)
    args = parser.parse_args()

    try:
        if args.root is not None:
            verify_local(args.root, args.expected_commit)
            print(f"Local quality evidence verified for {args.expected_commit}.")
        else:
            verify_remote(
                args.base_url,
                args.expected_commit,
                max(1, args.attempts),
                max(0.0, args.interval_seconds),
            )
    except (FileNotFoundError, RuntimeError, ValueError, json.JSONDecodeError) as error:
        print(f"::error::{error}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
