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


def _tag(element: ET.Element) -> str:
    return element.tag.rsplit("}", 1)[-1]


def validate_junit_report(document: ET.Element, report: object) -> dict[str, int]:
    """Count execution occurrences; nested suite counters include their descendants.

    Parameters and repeated executions are distinct testcase occurrences. A testcase
    has at most one terminal outcome. Rerun/flaky evidence is rejected: a clean
    publication may not silently turn an earlier unsuccessful attempt into a pass.
    """
    keys = ("tests", "failures", "errors", "skipped")
    def visit(suite: ET.Element) -> dict[str, int]:
        if _tag(suite) not in ("testsuite", "testsuites"):
            raise ValueError(f"invalid JUnit root in {report}: {_tag(suite)}")
        if suite.get("flakes", "0") != "0":
            raise ValueError(f"flaky suite evidence is not publishable in {report}")
        actual = dict.fromkeys(keys, 0)
        for child in suite:
            tag = _tag(child)
            if tag in ("testsuite", "testsuites"):
                nested = visit(child)
                for key in keys:
                    actual[key] += nested[key]
            elif tag == "testcase":
                actual["tests"] += 1
                outcomes = [_tag(item) for item in child if _tag(item) in ("failure", "error", "skipped")]
                if len(outcomes) > 1:
                    raise ValueError(f"multiple testcase outcomes in {report}")
                if any(_tag(item) in ("rerunFailure", "rerunError", "flakyFailure", "flakyError") for item in child):
                    raise ValueError(f"rerun/flaky testcase evidence is not publishable in {report}")
                if outcomes:
                    actual[{"failure": "failures", "error": "errors", "skipped": "skipped"}[outcomes[0]]] += 1
        # A testsuites wrapper may omit all counters; individual suites may not.
        if _tag(suite) == "testsuite" or any(suite.get(key) is not None for key in keys):
            for key in keys:
                value = suite.get(key, "")
                if not value.isascii() or not value.isdecimal() or int(value) != actual[key]:
                    raise ValueError(f"inconsistent JUnit {key}={value!r}, actual={actual[key]} in {report} ({suite.get('name', '')})")
        return actual
    actual = visit(document)
    if sum(1 for element in document.iter() if _tag(element) == "testcase") != actual["tests"]:
        raise ValueError(f"testcase outside a supported suite in {report}")
    for tag, key in (("failure", "failures"), ("error", "errors"), ("skipped", "skipped")):
        if sum(1 for element in document.iter() if _tag(element) == tag) != actual[key]:
            raise ValueError(f"{tag} outside a testcase terminal outcome in {report}")
    if any(_tag(element) in ("rerunFailure", "rerunError", "flakyFailure", "flakyError") for element in document.iter()):
        raise ValueError(f"rerun/flaky evidence is not publishable in {report}")
    return actual


def parse_junit_report(content: str, report: object) -> dict[str, int]:
    if "<!DOCTYPE" in content.upper() or "<!ENTITY" in content.upper():
        raise ValueError(f"DTD/entity declarations are not allowed in {report}")
    return validate_junit_report(ET.fromstring(content), report)


def collect_tests(root: Path) -> dict[str, int]:
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    files = sorted(root.rglob("TEST-*.xml"))
    if not files:
        raise FileNotFoundError(f"no JUnit XML reports found below {root}")
    for report in files:
        actual = parse_junit_report(report.read_text(encoding="utf-8"), report)
        for key in totals:
            totals[key] += actual[key]
    totals["executed"] = totals["tests"] - totals["skipped"]
    totals["passed"] = totals["executed"] - totals["failures"] - totals["errors"]
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


def parse_tools(entries: list[str]) -> dict[str, str]:
    tools: dict[str, str] = {}
    for entry in entries:
        name, separator, value = entry.partition("=")
        name = name.strip()
        value = value.strip()
        if not separator or not name or not value:
            raise ValueError(f"tool metadata must use NAME=VALUE, got {entry!r}")
        tools[name] = value
    return tools


def write_outputs(
    root: Path,
    commit: str,
    generated_at: str,
    *,
    source_tree: str = "unknown",
    build_id: str = "local",
    tools: dict[str, str] | None = None,
) -> dict[str, object]:
    tests = collect_tests(root / "tests")
    coverage = collect_coverage(root / "coverage" / "jacoco.xml")
    instruction_percent = float(coverage["instructionPercent"])
    clean = tests["failures"] == 0 and tests["errors"] == 0
    tool_metadata = dict(tools or {})

    summary: dict[str, object] = {
        "schemaVersion": 2,
        "commit": commit,
        "verifiedCommit": commit,
        "sourceCommit": commit,
        "sourceTree": source_tree,
        "buildId": build_id,
        "generatedAt": generated_at,
        "tools": tool_metadata,
        "tests": tests,
        "testReports": [str(path.relative_to(root)) for path in sorted((root / "tests").rglob("TEST-*.xml"))],
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
    method_percent = float(coverage.get("methodPercent", 0.0))
    class_percent = float(coverage.get("classPercent", 0.0))
    line_percent = float(coverage.get("linePercent", 0.0))
    tool_rows = "".join(
        "<tr><td>" + html.escape(name) + "</td><td><code>" + html.escape(value) + "</code></td></tr>"
        for name, value in sorted(tool_metadata.items())
    ) or '<tr><td colspan="2">No tool metadata recorded</td></tr>'
    report = f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Taxonomy test report</title>
<style>
body {{ font: 16px/1.5 system-ui, sans-serif; max-width: 70rem; margin: 2rem auto; padding: 0 1rem; }}
table {{ border-collapse: collapse; margin-bottom: 1.25rem; }}
th, td {{ border: 1px solid #bbb; padding: .45rem .7rem; text-align: right; }}
th:first-child, td:first-child {{ text-align: left; }}
code {{ overflow-wrap: anywhere; }}
</style>
</head>
<body>
<h1>Taxonomy test report</h1>
<p>Verified commit: <code>{html.escape(commit)}</code></p>
<p>Source tree: <code>{html.escape(source_tree)}</code></p>
<p>Build ID: <code>{html.escape(build_id)}</code></p>
<p>Generated: {html.escape(generated_at)}</p>
<table>
<thead><tr><th>Metric</th><th>Value</th></tr></thead>
<tbody>
<tr><td>Registered tests</td><td>{tests['tests']}</td></tr>
<tr><td>Executed</td><td>{tests['executed']}</td></tr>
<tr><td>Passed</td><td>{tests['passed']}</td></tr>
<tr><td>Skipped</td><td>{tests['skipped']}</td></tr>
<tr><td>Failures</td><td>{tests['failures']}</td></tr>
<tr><td>Errors</td><td>{tests['errors']}</td></tr>
<tr><td>Instruction coverage</td><td>{instruction_percent:.2f}%</td></tr>
<tr><td>Line coverage</td><td>{line_percent:.2f}%</td></tr>
<tr><td>Branch coverage</td><td>{branch_percent:.2f}%</td></tr>
<tr><td>Method coverage</td><td>{method_percent:.2f}%</td></tr>
<tr><td>Class coverage</td><td>{class_percent:.2f}%</td></tr>
</tbody>
</table>
<h2>Toolchain</h2>
<table><thead><tr><th>Tool</th><th>Version/configuration</th></tr></thead><tbody>{tool_rows}</tbody></table>
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
<p>Source tree: <code>{html.escape(source_tree)}</code></p>
<p>Build ID: <code>{html.escape(build_id)}</code></p>
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
    parser.add_argument("--source-tree", default="unknown")
    parser.add_argument("--build-id", default="local")
    parser.add_argument("--tool", action="append", default=[], metavar="NAME=VALUE")
    parser.add_argument(
        "--generated-at",
        default=datetime.now(timezone.utc).isoformat(timespec="seconds"),
    )
    args = parser.parse_args()
    summary = write_outputs(
        args.root,
        args.commit.strip(),
        args.generated_at,
        source_tree=args.source_tree.strip(),
        build_id=args.build_id.strip(),
        tools=parse_tools(args.tool),
    )
    print(json.dumps(summary, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
