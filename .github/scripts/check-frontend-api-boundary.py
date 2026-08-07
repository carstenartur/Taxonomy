#!/usr/bin/env python3
"""Prevent new direct frontend fetch debt outside the central API layer.

The current UI still contains legacy feature modules that call ``fetch()``
directly. Rewriting all of them in one release-blocker change would create a
large regression surface, so this check acts as a ratchet instead:

* API-client modules under ``static/js/api`` may own HTTP transport calls;
* the base-path bootstrap wrapper is an explicit infrastructure exception;
* every other JavaScript module may only keep or reduce the number of direct
  ``fetch()`` calls that existed in the comparison revision;
* a new non-API module may not introduce a direct ``fetch()`` call at all.

This makes the legacy count monotonically non-increasing while allowing the
migration to the central client to be performed incrementally.
"""

from __future__ import annotations

import argparse
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
JS_ROOT = ROOT / "taxonomy-app" / "src" / "main" / "resources" / "static" / "js"
REPO_JS_PREFIX = "taxonomy-app/src/main/resources/static/js/"
DIRECT_FETCH = re.compile(r"\bfetch\s*\(")
INFRASTRUCTURE_EXCEPTIONS = {
    "taxonomy-i18n.js",  # installs the application-base-path-aware fetch wrapper
}


def count_direct_fetch(text: str) -> int:
    """Return the number of direct fetch() call sites in JavaScript source."""
    return len(DIRECT_FETCH.findall(text))


def is_transport_owner(relative_path: str) -> bool:
    normalized = relative_path.replace("\\", "/")
    return normalized.startswith("api/") or normalized in INFRASTRUCTURE_EXCEPTIONS


def current_counts(root: Path = JS_ROOT) -> dict[str, int]:
    counts: dict[str, int] = {}
    for path in sorted(root.rglob("*")):
        if not path.is_file() or path.suffix not in {".js", ".mjs"}:
            continue
        relative = path.relative_to(root).as_posix()
        count = count_direct_fetch(path.read_text(encoding="utf-8"))
        if count:
            counts[relative] = count
    return counts


def git_text(base_ref: str, repository_path: str) -> str | None:
    result = subprocess.run(
        ["git", "show", f"{base_ref}:{repository_path}"],
        cwd=ROOT,
        text=True,
        capture_output=True,
        check=False,
    )
    if result.returncode == 0:
        return result.stdout
    if "does not exist" in result.stderr or "exists on disk, but not in" in result.stderr:
        return None
    raise RuntimeError(
        f"git show failed for {base_ref}:{repository_path}: {result.stderr.strip()}"
    )


def baseline_counts(base_ref: str, paths: set[str]) -> dict[str, int]:
    counts: dict[str, int] = {}
    for relative in sorted(paths):
        text = git_text(base_ref, REPO_JS_PREFIX + relative)
        if text is None:
            continue
        count = count_direct_fetch(text)
        if count:
            counts[relative] = count
    return counts


def evaluate(current: dict[str, int], baseline: dict[str, int]) -> list[str]:
    failures: list[str] = []
    current_debt = {
        path: count for path, count in current.items() if not is_transport_owner(path)
    }
    baseline_debt = {
        path: count for path, count in baseline.items() if not is_transport_owner(path)
    }

    for path, count in sorted(current_debt.items()):
        previous = baseline_debt.get(path, 0)
        if previous == 0:
            failures.append(
                f"{path}: introduces {count} direct fetch() call(s); use static/js/api instead"
            )
        elif count > previous:
            failures.append(
                f"{path}: direct fetch() count increased from {previous} to {count}"
            )

    current_total = sum(current_debt.values())
    baseline_total = sum(baseline_debt.values())
    if current_total > baseline_total:
        failures.append(
            f"legacy direct fetch() debt increased from {baseline_total} to {current_total}"
        )
    return failures


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--base-ref",
        required=True,
        help="Git revision used as the maximum allowed legacy direct-fetch baseline",
    )
    args = parser.parse_args()

    current = current_counts()
    baseline = baseline_counts(args.base_ref, set(current))
    failures = evaluate(current, baseline)

    current_debt = {
        path: count for path, count in current.items() if not is_transport_owner(path)
    }
    baseline_debt = {
        path: count for path, count in baseline.items() if not is_transport_owner(path)
    }
    print(
        "Frontend API boundary: "
        f"legacy direct fetch debt {sum(current_debt.values())} call(s) in "
        f"{len(current_debt)} file(s); baseline "
        f"{sum(baseline_debt.values())} call(s) in {len(baseline_debt)} file(s)."
    )

    if failures:
        print("Frontend API boundary check failed:", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1

    print("No new direct frontend fetch debt was introduced outside the API layer.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
