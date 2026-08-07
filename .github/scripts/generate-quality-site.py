#!/usr/bin/env python3
"""Generate commit-bound quality badges and a compact test report from CI evidence."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import html
import json
from pathlib import Path
import xml.etree.ElementTree as ET


def _integer(element: ET.Element, name: str) -> int:
    value = element.get(name, "0")
    try:
        return int(float(value))
    except ValueError as error:
        raise ValueError(f"invalid {name}={value!r} in {element.tag}") from error


def collect_tests(root: Path) -> dict[str, int]:
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    files = sorted(root.rglob("TEST-*.xml"))
    if not files:
        raise FileNotFoundError(f"no JUnit XML reports found below {root}")

    for report in files:
        document = ET.parse(report).getroot()
        if document.tag.endswith("testsuite"):
            suites = [document]
        else:
            suites = [child for child in document if child.tag.endswith("testsuite")]
        if not suites:
            raise ValueError(f"no testsuite element in {report}")
        for suite in suites:
            for key in totals:
                totals[key] += _integer(suite, key)

    totals["passed"] = (
        totals["tests"] - totals["failures"] - totals["errors"] - totals["skipped"]
    )
    if totals["passed"] < 0:
        raise ValueError(f"inconsistent test totals: {totals}")
    if totals["tests"] <= 0:
        raise ValueError("JUnit reports contained zero tests")
    totals["reportFiles"] = len(files)
    return totals


def collect_coverage(report: Path) -> dict[str, float | int]:
    if not report.is_file():
        raise FileNotFoundError(f"aggregate JaCoCo report missing: {report}")
    root = ET.parse(report).getroot()
    result: dict[str, float | int] = {}
    for counter in root.findall("counter"):
        metric = counter.get("type", "").lower()
        if not metric:
            continue
        missed = _integer(counter, "missed")
        covered = _integer(counter, "covered")
        total = missed + covered
        result[f"{metric}Missed"] = missed
        result[f"{metric}Covered"] = covered
        result[f"{metric}Percent"] = round(100.0 * covered / total, 2) if total else 100.0
    if "instructionPercent" not in result:
        raise ValueError(f"aggregate instruction counter missing in {report}")
    return result


def coverage_color(percent: float) -> str:
    if percent >= 90:
        return "brightgreen"
    if percent >= 80:
        return "green"
    if percent >= 70:
        return "yellowgreen"
    if percent >= 60:
        return "yellow"
    return "red"


def write_json(path: Path, payload: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def write_outputs(root: Path, commit: str, generated_at: str) -> dict[str, object]:
    tests = collect_tests(root / "tests")
    coverage = collect_coverage(root / "coverage" / "jacoco.xml")
    instruction_percent = float(coverage["instructionPercent"])
    clean = tests["failures"] == 0 and tests["errors"] == 0

    summary: dict[str, object] = {
        "schemaVersion": 1,
        "commit": commit,
        "verifiedCommit": commit,
        "generatedAt": generated_at,
        "tests": tests,
        "coverage": coverage,
    }
    write_json(root / "quality-summary.json", summary)
    write_json(
        root / "tests" / "badge.json",
        {
            "schemaVersion": 1,
            "label": "tests",
            "message": f"{tests['passed']} passed",
            "color": "brightgreen" if clean else "red",
        },
    )
    write_json(
        root / "coverage" / "badge.json",
        {
            "schemaVersion": 1,
            "label": "reactor coverage",
            "message": f"{instruction_percent:.2f}%",
            "color": coverage_color(instruction_percent),
        },
    )
    root.joinpath(".nojekyll").touch()

    branch_percent = float(coverage.get("branchPercent", 0.0))
    report = f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Taxonomy test report</title>
<style>
body {{ font: 16px/1.5 system-ui, sans-serif; max-width: 70rem; margin: 2rem auto; padding: 0 1rem; }}
table {{ border-collapse: collapse; }}
th, td {{ border: 1px solid #bbb; padding: .45rem .7rem; text-align: right; }}
th:first-child, td:first-child {{ text-align: left; }}
code {{ overflow-wrap: anywhere; }}
</style>
</head>
<body>
<h1>Taxonomy test report</h1>
<p>Verified commit: <code>{html.escape(commit)}</code></p>
<p>Generated: {html.escape(generated_at)}</p>
<table>
<thead><tr><th>Metric</th><th>Value</th></tr></thead>
<tbody>
<tr><td>Registered tests</td><td>{tests['tests']}</td></tr>
<tr><td>Passed</td><td>{tests['passed']}</td></tr>
<tr><td>Skipped</td><td>{tests['skipped']}</td></tr>
<tr><td>Failures</td><td>{tests['failures']}</td></tr>
<tr><td>Errors</td><td>{tests['errors']}</td></tr>
<tr><td>Instruction coverage</td><td>{instruction_percent:.2f}%</td></tr>
<tr><td>Branch coverage</td><td>{branch_percent:.2f}%</td></tr>
</tbody>
</table>
<p><a href="../quality-summary.json">Machine-readable quality summary</a></p>
</body>
</html>
"""
    (root / "tests" / "surefire-report.html").write_text(report, encoding="utf-8")
    index = f"""<!doctype html>
<html lang="en">
<head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Taxonomy verified quality reports</title></head>
<body>
<h1>Taxonomy verified quality reports</h1>
<p>Verified commit: <code>{html.escape(commit)}</code></p>
<ul>
<li><a href="tests/surefire-report.html">Test report</a></li>
<li><a href="coverage/index.html">JaCoCo coverage report</a></li>
<li><a href="quality-summary.json">Machine-readable provenance and metrics</a></li>
</ul>
</body></html>
"""
    (root / "index.html").write_text(index, encoding="utf-8")
    return summary


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("target/quality-reports"))
    parser.add_argument("--commit", required=True)
    parser.add_argument(
        "--generated-at",
        default=datetime.now(timezone.utc).isoformat(timespec="seconds"),
    )
    args = parser.parse_args()
    summary = write_outputs(args.root, args.commit.strip(), args.generated_at)
    print(json.dumps(summary, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
