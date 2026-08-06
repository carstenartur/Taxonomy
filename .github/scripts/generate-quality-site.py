#!/usr/bin/env python3
"""Build an immutable, commit-bound quality-report site from verified CI output."""

from __future__ import annotations

import argparse
import html
import json
from datetime import datetime, timezone
from pathlib import Path
import xml.etree.ElementTree as ET


def _int_attr(element: ET.Element, name: str) -> int:
    value = element.attrib.get(name, "0")
    try:
        return int(float(value))
    except ValueError as exc:
        raise ValueError(f"Invalid {name}={value!r} in {element.tag}") from exc


def read_test_totals(report_root: Path) -> dict[str, int]:
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    reports = sorted((report_root / "tests").rglob("TEST-*.xml"))
    if not reports:
        raise FileNotFoundError("No JUnit TEST-*.xml reports found in quality-reports/tests")

    for report in reports:
        root = ET.parse(report).getroot()
        suites: list[ET.Element]
        if root.tag.endswith("testsuite"):
            suites = [root]
        elif root.tag.endswith("testsuites") and "tests" in root.attrib:
            suites = [root]
        else:
            suites = [child for child in root if child.tag.endswith("testsuite")]
        for suite in suites:
            for key in totals:
                totals[key] += _int_attr(suite, key)
    if totals["tests"] <= 0:
        raise ValueError("JUnit reports contained zero tests")
    return totals


def read_line_coverage(report_root: Path) -> dict[str, int | float]:
    report = report_root / "coverage" / "jacoco.xml"
    if not report.is_file():
        raise FileNotFoundError(f"Missing aggregate JaCoCo XML report: {report}")
    root = ET.parse(report).getroot()
    counter = next((item for item in root.findall("counter")
                    if item.attrib.get("type") == "LINE"), None)
    if counter is None:
        raise ValueError("Aggregate JaCoCo report contains no LINE counter")
    missed = _int_attr(counter, "missed")
    covered = _int_attr(counter, "covered")
    total = missed + covered
    if total <= 0:
        raise ValueError("Aggregate JaCoCo LINE counter contains zero lines")
    return {
        "covered": covered,
        "missed": missed,
        "total": total,
        "percent": round(covered * 100.0 / total, 2),
    }


def badge(label: str, message: str, colour: str) -> str:
    label_width = max(44, 7 * len(label) + 14)
    message_width = max(44, 7 * len(message) + 14)
    width = label_width + message_width
    label_escaped = html.escape(label)
    message_escaped = html.escape(message)
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="20" role="img" aria-label="{label_escaped}: {message_escaped}">
  <title>{label_escaped}: {message_escaped}</title>
  <linearGradient id="s" x2="0" y2="100%">
    <stop offset="0" stop-color="#fff" stop-opacity=".7"/>
    <stop offset=".1" stop-color="#aaa" stop-opacity=".1"/>
    <stop offset=".9" stop-color="#000" stop-opacity=".3"/>
    <stop offset="1" stop-color="#000" stop-opacity=".5"/>
  </linearGradient>
  <clipPath id="r"><rect width="{width}" height="20" rx="3" fill="#fff"/></clipPath>
  <g clip-path="url(#r)">
    <rect width="{label_width}" height="20" fill="#555"/>
    <rect x="{label_width}" width="{message_width}" height="20" fill="{colour}"/>
    <rect width="{width}" height="20" fill="url(#s)"/>
  </g>
  <g fill="#fff" text-anchor="middle" font-family="Verdana,DejaVu Sans,sans-serif" font-size="11">
    <text x="{label_width / 2:g}" y="15" fill="#010101" fill-opacity=".3">{label_escaped}</text>
    <text x="{label_width / 2:g}" y="14">{label_escaped}</text>
    <text x="{label_width + message_width / 2:g}" y="15" fill="#010101" fill-opacity=".3">{message_escaped}</text>
    <text x="{label_width + message_width / 2:g}" y="14">{message_escaped}</text>
  </g>
</svg>
'''


def build_site(report_root: Path, commit: str) -> dict[str, object]:
    if not commit.strip():
        raise ValueError("Verified commit SHA must not be empty")
    tests = read_test_totals(report_root)
    coverage = read_line_coverage(report_root)
    failed = tests["failures"] + tests["errors"]
    passed = tests["tests"] - failed - tests["skipped"]
    tests.update({"passed": passed, "failed": failed})

    generated_at = datetime.now(timezone.utc).isoformat()
    metadata: dict[str, object] = {
        "schemaVersion": 1,
        "verifiedCommit": commit,
        "generatedAt": generated_at,
        "tests": tests,
        "coverage": coverage,
    }

    badges = report_root / "badges"
    badges.mkdir(parents=True, exist_ok=True)
    test_message = f'{passed} passed' if failed == 0 else f'{failed} failed'
    (badges / "tests.svg").write_text(
        badge("tests", test_message, "#4c1" if failed == 0 else "#e05d44"),
        encoding="utf-8",
    )
    percent = float(coverage["percent"])
    colour = "#4c1" if percent >= 80 else "#dfb317" if percent >= 60 else "#e05d44"
    (badges / "coverage.svg").write_text(
        badge("coverage", f"{percent:.2f}%", colour), encoding="utf-8"
    )
    (report_root / "metadata.json").write_text(
        json.dumps(metadata, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    index = f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Taxonomy verified quality reports</title>
  <style>
    body {{ max-width: 64rem; margin: 2rem auto; padding: 0 1rem; font: 16px/1.5 system-ui, sans-serif; }}
    code {{ overflow-wrap: anywhere; }}
    .badges img {{ margin-right: .5rem; }}
  </style>
</head>
<body>
  <h1>Taxonomy verified quality reports</h1>
  <p class="badges"><img src="badges/tests.svg" alt="Test result"> <img src="badges/coverage.svg" alt="Line coverage"></p>
  <p>Verified commit: <code>{html.escape(commit)}</code></p>
  <p>Generated: <time datetime="{html.escape(generated_at)}">{html.escape(generated_at)}</time></p>
  <ul>
    <li><a href="coverage/index.html">JaCoCo coverage report</a></li>
    <li><a href="metadata.json">Machine-readable provenance and metrics</a></li>
    <li><a href="README.txt">Verification provenance</a></li>
  </ul>
</body>
</html>
"""
    (report_root / "index.html").write_text(index, encoding="utf-8")
    return metadata


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--commit", required=True)
    args = parser.parse_args()
    metadata = build_site(args.root, args.commit)
    print(json.dumps(metadata, sort_keys=True))


if __name__ == "__main__":
    main()
