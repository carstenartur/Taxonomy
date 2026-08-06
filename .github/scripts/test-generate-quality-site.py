#!/usr/bin/env python3
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path

SCRIPT = Path(__file__).with_name("generate-quality-site.py")

with tempfile.TemporaryDirectory() as temporary:
    root = Path(temporary)
    tests = root / "tests" / "module" / "target" / "surefire-reports"
    tests.mkdir(parents=True)
    (tests / "TEST-sample.xml").write_text(
        '<testsuite tests="5" failures="1" errors="0" skipped="1"></testsuite>',
        encoding="utf-8",
    )
    coverage = root / "coverage"
    coverage.mkdir()
    (coverage / "jacoco.xml").write_text(
        '<report name="aggregate"><counter type="LINE" missed="20" covered="80"/></report>',
        encoding="utf-8",
    )
    completed = subprocess.run(
        [sys.executable, str(SCRIPT), "--root", str(root), "--commit", "abc1234"],
        check=True, text=True, capture_output=True,
    )
    output = json.loads(completed.stdout)
    assert output["verifiedCommit"] == "abc1234"
    assert output["tests"]["passed"] == 3
    assert output["tests"]["failed"] == 1
    assert output["coverage"]["percent"] == 80.0
    assert "1 failed" in (root / "badges" / "tests.svg").read_text(encoding="utf-8")
    assert "80.00%" in (root / "badges" / "coverage.svg").read_text(encoding="utf-8")
    assert (root / "index.html").is_file()

print("generate-quality-site tests passed")
