#!/usr/bin/env python3
"""Contract tests for check-release-blockers.py."""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path

SCRIPT = Path(__file__).with_name("check-release-blockers.py")


def run(payload: object) -> subprocess.CompletedProcess[str]:
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", suffix=".json", delete=False) as fixture:
        json.dump(payload, fixture)
        path = Path(fixture.name)
    try:
        return subprocess.run(
            [sys.executable, str(SCRIPT), "--input-file", str(path)],
            check=False,
            text=True,
            capture_output=True,
        )
    finally:
        path.unlink(missing_ok=True)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def main() -> int:
    clear = run({"total_count": 0, "items": []})
    require(clear.returncode == 0, clear.stderr)

    blocked = run({
        "total_count": 2,
        "items": [
            {
                "number": 574,
                "title": "Workspace isolation",
                "html_url": "https://example.invalid/issues/574",
            },
            {
                "number": 575,
                "title": "Release guard",
                "html_url": "https://example.invalid/pull/575",
                "pull_request": {},
            },
        ],
    })
    require(blocked.returncode == 1, blocked.stderr)
    require("Issue #574" in blocked.stderr, blocked.stderr)
    require("PR #575" in blocked.stderr, blocked.stderr)

    malformed = run({"total_count": 1})
    require(malformed.returncode == 2, malformed.stderr)
    require("failed closed" in malformed.stderr, malformed.stderr)

    print("Release blocker preflight tests passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
