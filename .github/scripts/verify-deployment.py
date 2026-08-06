#!/usr/bin/env python3
"""Poll a deployed Taxonomy instance until readiness and build provenance match."""

from __future__ import annotations

import argparse
import json
import time
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


def _fetch_json(url: str, timeout: float) -> dict[str, Any]:
    request = Request(url, headers={"Accept": "application/json", "User-Agent": "taxonomy-deployment-smoke/1"})
    with urlopen(request, timeout=timeout) as response:
        if response.status != 200:
            raise RuntimeError(f"{url} returned HTTP {response.status}")
        payload = json.load(response)
    if not isinstance(payload, dict):
        raise RuntimeError(f"{url} did not return a JSON object")
    return payload


def _nested(payload: dict[str, Any], *path: str) -> Any:
    current: Any = payload
    for key in path:
        if not isinstance(current, dict) or key not in current:
            return None
        current = current[key]
    return current


def commit_matches(actual: str | None, expected: str | None) -> bool:
    if not expected:
        return True
    if not actual:
        return False
    actual = actual.lower().strip()
    expected = expected.lower().strip()
    return actual.startswith(expected) or expected.startswith(actual)


def verify_once(base_url: str, expected_commit: str | None,
                expected_version: str | None, timeout: float) -> tuple[bool, str]:
    base = base_url.rstrip("/")
    readiness = _fetch_json(f"{base}/actuator/health/readiness", timeout)
    status = readiness.get("status")
    if status != "UP":
        return False, f"readiness status is {status!r}, expected 'UP'"

    info = _fetch_json(f"{base}/actuator/info", timeout)
    actual_commit = _nested(info, "git", "commit", "id")
    actual_version = _nested(info, "build", "version") or _nested(info, "app", "version")

    if expected_commit and not commit_matches(
            str(actual_commit) if actual_commit is not None else None, expected_commit):
        return False, f"deployed commit is {actual_commit!r}, expected {expected_commit!r}"
    if expected_version and str(actual_version) != expected_version:
        return False, f"deployed version is {actual_version!r}, expected {expected_version!r}"
    return True, f"ready; version={actual_version!r}; commit={actual_commit!r}"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--expected-commit")
    parser.add_argument("--expected-version")
    parser.add_argument("--attempts", type=int, default=40)
    parser.add_argument("--interval-seconds", type=float, default=15.0)
    parser.add_argument("--timeout-seconds", type=float, default=10.0)
    args = parser.parse_args()

    if args.attempts < 1:
        parser.error("--attempts must be at least 1")
    last_detail = "deployment was not checked"
    for attempt in range(1, args.attempts + 1):
        try:
            ok, detail = verify_once(
                args.base_url, args.expected_commit, args.expected_version,
                args.timeout_seconds)
        except (HTTPError, URLError, TimeoutError, OSError, RuntimeError, json.JSONDecodeError) as exc:
            ok, detail = False, f"{type(exc).__name__}: {exc}"
        last_detail = detail
        print(f"deployment smoke attempt {attempt}/{args.attempts}: {detail}", flush=True)
        if ok:
            return
        if attempt < args.attempts:
            time.sleep(args.interval_seconds)
    raise SystemExit(f"Deployment did not converge: {last_detail}")


if __name__ == "__main__":
    main()
