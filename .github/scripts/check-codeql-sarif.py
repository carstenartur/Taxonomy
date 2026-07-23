#!/usr/bin/env python3
"""Fail a pull request on high-severity CodeQL security findings."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


def security_severity(rule: dict) -> float:
    properties = rule.get("properties", {})
    value = properties.get("security-severity")
    try:
        return float(value)
    except (TypeError, ValueError):
        return 0.0


def inspect(paths: list[Path], threshold: float) -> tuple[list[dict], int]:
    blocking: list[dict] = []
    result_count = 0
    for path in paths:
        report = json.loads(path.read_text(encoding="utf-8"))
        for run in report.get("runs", []):
            rules = {
                rule.get("id"): rule
                for rule in run.get("tool", {}).get("driver", {}).get("rules", [])
            }
            for result in run.get("results", []):
                result_count += 1
                rule_id = result.get("ruleId", "unknown")
                rule = rules.get(rule_id, {})
                severity = security_severity(rule)
                level = result.get("level", "warning")
                if severity >= threshold or level == "error":
                    blocking.append({
                        "file": str(path),
                        "ruleId": rule_id,
                        "securitySeverity": severity,
                        "level": level,
                        "message": result.get("message", {}).get("text", ""),
                    })
    return blocking, result_count


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("paths", nargs="+", type=Path)
    parser.add_argument("--threshold", type=float, default=7.0)
    parser.add_argument("--report", type=Path, default=Path("target/codeql-gate.json"))
    args = parser.parse_args()

    existing = [path for path in args.paths if path.is_file()]
    if not existing:
        print("CodeQL gate failed: no SARIF files supplied", file=sys.stderr)
        return 1

    blocking, result_count = inspect(existing, args.threshold)
    output = {
        "sarifFiles": [str(path) for path in existing],
        "resultCount": result_count,
        "threshold": args.threshold,
        "blocking": blocking,
        "status": "FAIL" if blocking else "PASS",
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(output, indent=2) + "\n", encoding="utf-8")

    print(f"CodeQL results: {result_count}; blocking: {len(blocking)}")
    for finding in blocking:
        print(
            f"- [{finding['level']}/security-severity={finding['securitySeverity']}] "
            f"{finding['ruleId']}: {finding['message']}",
            file=sys.stderr,
        )
    return 1 if blocking else 0


if __name__ == "__main__":
    raise SystemExit(main())
