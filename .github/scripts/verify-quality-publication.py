#!/usr/bin/env python3
"""Verify that published quality evidence is complete, commit-bound, and internally consistent."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import time
from typing import Callable
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen

JsonFetcher = Callable[[str], object]
TextFetcher = Callable[[str], str]

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

    passed = int(tests.get("passed", -1))
    if passed < 0:
        raise ValueError("quality summary contains an invalid passed-test count")
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
    base = base_url.rstrip("/")
    separator = "&" if "?" in relative else "?"
    return f"{base}/{relative}{separator}verified={quote(expected_commit)}"


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
