#!/usr/bin/env python3
"""Fail the build when aggregate JaCoCo instruction coverage is below the configured floor."""

from __future__ import annotations

import argparse
import glob
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def report_counter(path: Path) -> tuple[int, int]:
    root = ET.parse(path).getroot()
    for counter in root.findall("counter"):
        if counter.get("type") == "INSTRUCTION":
            return int(counter.get("covered", "0")), int(counter.get("missed", "0"))
    raise ValueError(f"No report-level INSTRUCTION counter in {path}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--minimum", type=float, default=0.81,
                        help="Minimum aggregate instruction ratio, e.g. 0.81 for 81%%")
    parser.add_argument("--pattern", default="**/target/site/jacoco/jacoco.xml")
    parser.add_argument("--report", default="target/coverage-gate.txt")
    args = parser.parse_args()

    paths = [Path(path) for path in sorted(glob.glob(args.pattern, recursive=True))]
    if not paths:
        print(f"Coverage gate failed: no JaCoCo reports matched {args.pattern}", file=sys.stderr)
        return 1

    covered = 0
    missed = 0
    details: list[str] = []
    try:
        for path in paths:
            report_covered, report_missed = report_counter(path)
            covered += report_covered
            missed += report_missed
            total = report_covered + report_missed
            ratio = report_covered / total if total else 0.0
            details.append(f"{path}: {ratio:.2%} ({report_covered}/{total} instructions)")
    except (OSError, ET.ParseError, ValueError) as error:
        print(f"Coverage gate failed: {error}", file=sys.stderr)
        return 1

    total = covered + missed
    ratio = covered / total if total else 0.0
    passed = total > 0 and ratio >= args.minimum
    lines = [
        "Taxonomy JaCoCo instruction coverage",
        "",
        *details,
        "",
        f"Aggregate: {ratio:.2%} ({covered}/{total} instructions)",
        f"Required:  {args.minimum:.2%}",
        f"Result:    {'PASS' if passed else 'FAIL'}",
        "",
    ]
    text = "\n".join(lines)
    report_path = Path(args.report)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(text, encoding="utf-8")
    print(text, end="")
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
