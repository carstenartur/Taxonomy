#!/usr/bin/env python3
"""Fail closed when open release-blocker issues or pull requests exist."""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--repository",
        default=os.environ.get("GITHUB_REPOSITORY", ""),
        help="GitHub repository in owner/name form",
    )
    parser.add_argument(
        "--api-url",
        default=os.environ.get("GITHUB_API_URL", "https://api.github.com"),
    )
    parser.add_argument(
        "--token",
        default=os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN", ""),
    )
    parser.add_argument(
        "--input-file",
        type=Path,
        help="Read a GitHub search response from a fixture instead of the API",
    )
    return parser.parse_args()


def load_response(args: argparse.Namespace) -> dict[str, Any]:
    if args.input_file:
        return json.loads(args.input_file.read_text(encoding="utf-8"))
    if not args.repository or "/" not in args.repository:
        raise RuntimeError("GITHUB_REPOSITORY or --repository must be owner/name")
    if not args.token:
        raise RuntimeError("GH_TOKEN or GITHUB_TOKEN is required")

    query = f'repo:{args.repository} is:open label:"release-blocker"'
    url = f"{args.api_url.rstrip('/')}/search/issues?q={urllib.parse.quote(query)}&per_page=100"
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {args.token}",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "taxonomy-release-blocker-check",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"GitHub blocker query failed with HTTP {error.code}: {detail}") from error
    except urllib.error.URLError as error:
        raise RuntimeError(f"GitHub blocker query failed: {error.reason}") from error


def blocker_rows(payload: dict[str, Any]) -> list[tuple[int, str, str, str]]:
    items = payload.get("items")
    if not isinstance(items, list):
        raise RuntimeError("GitHub response does not contain an items array")

    rows: list[tuple[int, str, str, str]] = []
    for item in items:
        if not isinstance(item, dict):
            raise RuntimeError("GitHub response contains a non-object item")
        number = item.get("number")
        title = item.get("title")
        url = item.get("html_url")
        if not isinstance(number, int) or not isinstance(title, str) or not isinstance(url, str):
            raise RuntimeError("GitHub blocker item is missing number, title or html_url")
        kind = "PR" if isinstance(item.get("pull_request"), dict) else "Issue"
        rows.append((number, kind, title, url))
    return sorted(rows)


def main() -> int:
    args = parse_args()
    try:
        rows = blocker_rows(load_response(args))
    except (OSError, ValueError, RuntimeError) as error:
        print(f"::error::Release blocker check failed closed: {error}", file=sys.stderr)
        return 2

    if rows:
        print("::error::Release publication is blocked by open release-blocker items", file=sys.stderr)
        for number, kind, title, url in rows:
            print(f"- {kind} #{number}: {title} ({url})", file=sys.stderr)
        summary = os.environ.get("GITHUB_STEP_SUMMARY")
        if summary:
            with Path(summary).open("a", encoding="utf-8") as output:
                output.write("## Release blocked\n\n")
                for number, kind, title, url in rows:
                    output.write(f"- [{kind} #{number}: {title}]({url})\n")
        return 1

    print("No open release-blocker issues or pull requests were found.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
