#!/usr/bin/env python3
"""Wait for a Render deployment and prove that the expected commit is live."""

from __future__ import annotations

import argparse
import json
import time
from typing import Callable
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

JsonFetcher = Callable[[str], object]


def fetch_json(url: str) -> object:
    request = Request(
        url,
        headers={
            "Accept": "application/json",
            "User-Agent": "taxonomy-delivery-check",
        },
    )
    with urlopen(request, timeout=15) as response:
        return json.load(response)


def string_values(value: object):
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for child in value.values():
            yield from string_values(child)
    elif isinstance(value, list):
        for child in value:
            yield from string_values(child)


def contains_commit(payload: object, expected: str) -> bool:
    expected = expected.strip().lower()
    for value in string_values(payload):
        candidate = value.strip().lower()
        if candidate == expected:
            return True
        if len(candidate) >= 7 and (
            candidate.startswith(expected) or expected.startswith(candidate)
        ):
            return True
    return False


def verify_once(
    service_url: str,
    expected: str,
    fetcher: JsonFetcher = fetch_json,
) -> tuple[bool, str]:
    base = service_url.rstrip("/")
    readiness = fetcher(f"{base}/actuator/health/readiness")
    if not isinstance(readiness, dict) or readiness.get("status") != "UP":
        return False, f"readiness is not UP: {readiness!r}"
    info = fetcher(f"{base}/actuator/info")
    if not contains_commit(info, expected):
        return False, f"actuator info does not identify expected commit {expected}"
    return True, "readiness is UP and expected commit is live"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--expected-commit", required=True)
    parser.add_argument("--attempts", type=int, default=60)
    parser.add_argument("--interval-seconds", type=float, default=10.0)
    args = parser.parse_args()

    last_error = "deployment did not answer"
    for attempt in range(1, args.attempts + 1):
        try:
            ok, detail = verify_once(args.base_url, args.expected_commit)
            if ok:
                print(f"Render deployment verified on attempt {attempt}: {detail}")
                return 0
            last_error = detail
        except (HTTPError, URLError, TimeoutError, ValueError, json.JSONDecodeError) as error:
            last_error = str(error)
        print(f"Render verification attempt {attempt}/{args.attempts} not ready: {last_error}")
        if attempt < args.attempts:
            time.sleep(args.interval_seconds)
    print(f"::error::Render deployment verification failed: {last_error}")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
