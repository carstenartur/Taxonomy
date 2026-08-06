#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
from pathlib import Path

SCRIPT = Path(__file__).with_name("verify-deployment.py")
spec = importlib.util.spec_from_file_location("verify_deployment", SCRIPT)
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(module)

assert module.commit_matches("abcdef123456", "abcdef1")
assert module.commit_matches("abcdef1", "abcdef123456")
assert not module.commit_matches("abcdef1", "1234567")
assert module.commit_matches(None, None)
assert not module.commit_matches(None, "abcdef1")

print("verify-deployment tests passed")
