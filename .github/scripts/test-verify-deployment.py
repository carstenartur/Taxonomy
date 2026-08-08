#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

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

    def test_verify_once_requires_readiness_commit_and_smoke(self) -> None:
        def fetcher(url: str):
            if url.endswith("/readiness"):
                return {"status": "UP"}
            return {"git": {"commit": {"id": "0123456789abcdef"}}}

        self.assertEqual(
            (
                True,
                "readiness is UP, expected commit is live, and root smoke test passed",
            ),
            MODULE.verify_once(
                "https://example.invalid/",
                "0123456789abcdef",
                fetcher,
                lambda _url: 200,
            ),
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

    def test_verify_once_rejects_4xx_and_5xx_smoke_responses(self) -> None:
        def fetcher(url: str):
            if url.endswith("/readiness"):
                return {"status": "UP"}
            return {"git": {"commit": {"id": "0123456789abcdef"}}}

        for status in (401, 404, 503):
            with self.subTest(status=status):
                ok, detail = MODULE.verify_once(
                    "https://example.invalid",
                    "0123456789abcdef",
                    fetcher,
                    lambda _url, value=status: value,
                )
                self.assertFalse(ok)
                self.assertIn(f"HTTP {status}", detail)

    def test_fetch_status_preserves_http_error_status_code(self) -> None:
        error = MODULE.HTTPError(
            "https://example.invalid/",
            404,
            "Not Found",
            hdrs=None,
            fp=None,
        )
        with patch.object(MODULE, "urlopen", side_effect=error):
            self.assertEqual(404, MODULE.fetch_status("https://example.invalid/"))

    def test_extracts_render_deploy_id_and_status(self) -> None:
        payload = {"deploy": {"id": "dep-xyz789", "status": "build_in_progress"}}
        self.assertEqual("dep-xyz789", MODULE.deploy_id_from_payload(payload))
        self.assertEqual("build_in_progress", MODULE.deploy_status(payload))
        self.assertIn("update_failed", MODULE.RENDER_FAILURE_STATES)

    def test_render_status_requires_live_before_application_verification(self) -> None:
        decision, detail = MODULE.render_status_decision(
            "dep-xyz789", "build_in_progress"
        )
        self.assertEqual("pending", decision)
        self.assertIn("waiting for live", detail)

        decision, detail = MODULE.render_status_decision("dep-xyz789", None)
        self.assertEqual("pending", decision)
        self.assertIn("unknown", detail)

        decision, detail = MODULE.render_status_decision("dep-xyz789", "live")
        self.assertEqual("live", decision)
        self.assertIn("is live", detail)

        decision, detail = MODULE.render_status_decision("dep-xyz789", "update_failed")
        self.assertEqual("failure", decision)
        self.assertIn("ended with update_failed", detail)

    def test_load_deploy_id_is_tolerant_of_queued_or_invalid_response(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "hook.json"
            path.write_text(json.dumps({"message": "queued"}), encoding="utf-8")
            self.assertIsNone(MODULE.load_deploy_id(path))
            path.write_text("not json", encoding="utf-8")
            self.assertIsNone(MODULE.load_deploy_id(path))

    def test_writes_machine_readable_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "nested" / "evidence.json"
            MODULE.write_evidence(path, {"result": "success", "attempts": 2})
            payload = json.loads(path.read_text(encoding="utf-8"))
            self.assertEqual("success", payload["result"])
            self.assertEqual(2, payload["attempts"])


    def test_verify_once_rejects_readiness_failure(self) -> None:
        def fetcher(url: str):
            if url.endswith("/readiness"):
                return {"status": "DOWN"}
            return {"git": {"commit": {"id": "0123456789abcdef"}}}

        ok, detail = MODULE.verify_once(
            "https://example.invalid",
            "0123456789abcdef",
            fetcher,
            lambda _url: 200,
        )
        self.assertFalse(ok)
        self.assertIn("readiness is not UP", detail)

    def test_explicit_delivery_state_vocabulary_is_stable(self) -> None:
        expected = {"disabled", "triggered", "verifying", "succeeded", "failed"}
        workflow = (SCRIPT.parents[1] / "workflows" / "delivery.yml").read_text(
            encoding="utf-8"
        )
        verifier = SCRIPT.read_text(encoding="utf-8")
        for state in expected:
            with self.subTest(state=state):
                self.assertIn(f'"deploymentState": "{state}"', workflow + verifier)



if __name__ == "__main__":
    unittest.main()
