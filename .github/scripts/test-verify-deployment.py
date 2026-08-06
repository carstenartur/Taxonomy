#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
from pathlib import Path
import unittest

SCRIPT = Path(__file__).with_name("verify-deployment.py").resolve()
SPEC = importlib.util.spec_from_file_location("verify_deployment", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Cannot load {SCRIPT}")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class VerifyRenderDeploymentTest(unittest.TestCase):
    def test_accepts_nested_full_commit(self) -> None:
        payload = {"git": {"commit": {"id": "abcdef1234567890"}}}
        self.assertTrue(MODULE.contains_commit(payload, "abcdef1234567890"))

    def test_accepts_abbreviated_commit(self) -> None:
        payload = {"git": {"commit": {"id": "abcdef1"}}}
        self.assertTrue(MODULE.contains_commit(payload, "abcdef1234567890"))

    def test_verify_once_requires_readiness_and_commit(self) -> None:
        def fetcher(url: str):
            if url.endswith("/readiness"):
                return {"status": "UP"}
            return {"git": {"commit": {"id": "0123456789abcdef"}}}

        self.assertEqual(
            (True, "readiness is UP and expected commit is live"),
            MODULE.verify_once("https://example.invalid/", "0123456789abcdef", fetcher),
        )

    def test_verify_once_rejects_stale_commit(self) -> None:
        def fetcher(url: str):
            if url.endswith("/readiness"):
                return {"status": "UP"}
            return {"git": {"commit": {"id": "oldcommit"}}}

        ok, detail = MODULE.verify_once(
            "https://example.invalid", "0123456789abcdef", fetcher
        )
        self.assertFalse(ok)
        self.assertIn("expected commit", detail)


if __name__ == "__main__":
    unittest.main()
