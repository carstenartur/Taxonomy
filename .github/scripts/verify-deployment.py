#!/usr/bin/env python3
"""Wait for a Render deployment and prove that the expected commit is live."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import time
from typing import Callable
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

JsonFetcher = Callable[[str], object]
StatusFetcher = Callable[[str], int]

RENDER_SUCCESS_STATE = "live"
RENDER_FAILURE_STATES = {
    "build_failed",
    "update_failed",
    "pre_deploy_failed",
    "canceled",
    "cancelled",
    "deactivated",
    "failed",
}


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def fetch_json(url: str) -> object:
    request = Request(
        url,
        headers={
            "Accept": "application/json",
            "Cache-Control": "no-cache",
            "User-Agent": "taxonomy-delivery-check",
        },
    )
    with urlopen(request, timeout=15) as response:
        return json.load(response)


def fetch_status(url: str) -> int:
    request = Request(
        url,
        headers={
            "Accept": "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
            "Cache-Control": "no-cache",
            "User-Agent": "taxonomy-delivery-check",
        },
    )
    try:
        with urlopen(request, timeout=15) as response:
            return int(response.status)
    except HTTPError as error:
        return int(error.code)


def fetch_render_deploy(service_id: str, deploy_id: str, api_key: str) -> object:
    request = Request(
        f"https://api.render.com/v1/services/{service_id}/deploys/{deploy_id}",
        headers={
            "Accept": "application/json",
            "Authorization": f"Bearer {api_key}",
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


def deploy_id_from_payload(payload: object) -> str | None:
    if isinstance(payload, dict):
        direct = payload.get("id")
        if isinstance(direct, str) and direct.startswith("dep-"):
            return direct
        for value in payload.values():
            found = deploy_id_from_payload(value)
            if found:
                return found
    elif isinstance(payload, list):
        for value in payload:
            found = deploy_id_from_payload(value)
            if found:
                return found
    return None


def deploy_status(payload: object) -> str | None:
    if isinstance(payload, dict):
        status = payload.get("status")
        if isinstance(status, str):
            return status.strip().lower()
        for key in ("deploy", "data"):
            if key in payload:
                nested = deploy_status(payload[key])
                if nested:
                    return nested
    return None


def render_status_decision(deploy_id: str, status: str | None) -> tuple[str, str]:
    normalized = (status or "").strip().lower()
    if normalized in RENDER_FAILURE_STATES:
        return "failure", f"Render deploy {deploy_id} ended with {normalized}"
    if normalized != RENDER_SUCCESS_STATE:
        return "pending", (
            f"Render deploy {deploy_id} is {normalized or 'unknown'}; waiting for live"
        )
    return "live", f"Render deploy {deploy_id} is live"


def verify_once(
    service_url: str,
    expected: str,
    fetcher: JsonFetcher = fetch_json,
    smoke_fetcher: StatusFetcher | None = None,
) -> tuple[bool, str]:
    base = service_url.rstrip("/")
    readiness = fetcher(f"{base}/actuator/health/readiness")
    if not isinstance(readiness, dict) or readiness.get("status") != "UP":
        return False, f"readiness is not UP: {readiness!r}"
    info = fetcher(f"{base}/actuator/info")
    if not contains_commit(info, expected):
        return False, f"actuator info does not identify expected commit {expected}"
    if smoke_fetcher is not None:
        status = smoke_fetcher(f"{base}/")
        if status < 200 or status >= 400:
            return False, f"root smoke test returned HTTP {status}"
    return True, "readiness is UP, expected commit is live, and root smoke test passed"


def write_evidence(path: Path | None, payload: dict[str, object]) -> None:
    if path is None:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def load_deploy_id(path: Path | None) -> str | None:
    if path is None or not path.is_file():
        return None
    try:
        return deploy_id_from_payload(json.loads(path.read_text(encoding="utf-8")))
    except json.JSONDecodeError:
        return None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--expected-commit", required=True)
    parser.add_argument("--attempts", type=int, default=60)
    parser.add_argument("--interval-seconds", type=float, default=10.0)
    parser.add_argument("--evidence-file", type=Path)
    parser.add_argument("--deploy-response-file", type=Path)
    parser.add_argument("--render-service-id")
    parser.add_argument("--render-api-key")
    args = parser.parse_args()

    attempts = max(1, args.attempts)
    interval = max(0.0, args.interval_seconds)
    started_at = utc_now()
    deploy_id = load_deploy_id(args.deploy_response_file)
    last_error = "deployment did not answer"
    last_render_status: str | None = None
    api_status_enabled = bool(args.render_api_key and args.render_service_id and deploy_id)

    evidence: dict[str, object] = {
        "schemaVersion": 2,
        "deploymentState": "verifying",
        "targetUrl": args.base_url.rstrip("/"),
        "expectedCommit": args.expected_commit,
        "startedAt": started_at,
        "renderDeployId": deploy_id,
        "renderApiStatusVerification": api_status_enabled,
        "result": "in_progress",
    }
    write_evidence(args.evidence_file, evidence)

    for attempt in range(1, attempts + 1):
        try:
            if api_status_enabled:
                render_payload = fetch_render_deploy(
                    args.render_service_id,
                    deploy_id,
                    args.render_api_key,
                )
                last_render_status = deploy_status(render_payload)
                evidence["renderDeployStatus"] = last_render_status
                decision, render_detail = render_status_decision(
                    deploy_id, last_render_status
                )
                if decision == "failure":
                    last_error = render_detail
                    evidence.update(
                        {
                            "attempts": attempt,
                            "endedAt": utc_now(),
                            "deploymentState": "failed",
                            "result": "failure",
                            "detail": last_error,
                        }
                    )
                    write_evidence(args.evidence_file, evidence)
                    print(f"::error::{last_error}")
                    return 1
                if decision != "live":
                    last_error = render_detail
                    print(
                        f"Render verification attempt {attempt}/{attempts} not ready: "
                        f"{last_error}"
                    )
                    if attempt < attempts:
                        time.sleep(interval)
                    continue

            ok, detail = verify_once(
                args.base_url,
                args.expected_commit,
                fetch_json,
                fetch_status,
            )
            if ok:
                evidence.update(
                    {
                        "attempts": attempt,
                        "endedAt": utc_now(),
                        "deploymentState": "succeeded",
                        "result": "success",
                        "detail": detail,
                        "renderDeployStatus": last_render_status,
                    }
                )
                write_evidence(args.evidence_file, evidence)
                print(f"Render deployment verified on attempt {attempt}: {detail}")
                return 0
            last_error = detail
        except (HTTPError, URLError, TimeoutError, ValueError, json.JSONDecodeError) as error:
            last_error = str(error)
            if api_status_enabled and isinstance(error, HTTPError):
                last_error = f"Render/application verification HTTP error: {error}"
        print(f"Render verification attempt {attempt}/{attempts} not ready: {last_error}")
        if attempt < attempts:
            time.sleep(interval)

    evidence.update(
        {
            "attempts": attempts,
            "endedAt": utc_now(),
            "deploymentState": "failed",
            "result": "failure",
            "detail": last_error,
            "renderDeployStatus": last_render_status,
        }
    )
    write_evidence(args.evidence_file, evidence)
    print(f"::error::Render deployment verification failed: {last_error}")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
