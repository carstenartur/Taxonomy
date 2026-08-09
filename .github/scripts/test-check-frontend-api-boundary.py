#!/usr/bin/env python3
"""Unit tests for check-frontend-api-boundary.py."""

from __future__ import annotations

import importlib.util
from pathlib import Path
import unittest

SCRIPT = Path(__file__).with_name("check-frontend-api-boundary.py")
SPEC = importlib.util.spec_from_file_location("frontend_api_boundary", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class FrontendApiBoundaryTest(unittest.TestCase):

    def test_counts_fetch_calls(self) -> None:
        self.assertEqual(2, MODULE.count_direct_fetch("fetch('/a'); window.fetch('/b');"))

    def test_api_clients_and_base_path_wrapper_are_transport_owners(self) -> None:
        self.assertTrue(MODULE.is_transport_owner("api/taxonomy-api-client.js"))
        self.assertTrue(MODULE.is_transport_owner("taxonomy-i18n.js"))
        self.assertFalse(MODULE.is_transport_owner("shared/taxonomy-search.js"))

    def test_existing_legacy_call_count_may_decrease(self) -> None:
        failures = MODULE.evaluate(
            {"shared/search.js": 2, "api/client.js": 5},
            {"shared/search.js": 3, "api/client.js": 1},
        )
        self.assertEqual([], failures)

    def test_existing_legacy_call_count_may_not_increase(self) -> None:
        failures = MODULE.evaluate(
            {"shared/search.js": 4},
            {"shared/search.js": 3},
        )
        self.assertTrue(any("increased from 3 to 4" in failure for failure in failures))

    def test_new_feature_module_may_not_introduce_fetch(self) -> None:
        failures = MODULE.evaluate(
            {"workspace/new-feature.js": 1},
            {},
        )
        self.assertTrue(any("introduces 1 direct fetch()" in failure for failure in failures))

    def test_new_api_client_may_own_transport(self) -> None:
        failures = MODULE.evaluate(
            {"api/new-client.js": 7},
            {},
        )
        self.assertEqual([], failures)

    def test_total_legacy_debt_cannot_grow_by_shifting_calls(self) -> None:
        failures = MODULE.evaluate(
            {"shared/a.js": 1, "shared/b.js": 2},
            {"shared/a.js": 2, "shared/b.js": 0},
        )
        self.assertTrue(any("debt increased from 2 to 3" in failure for failure in failures))


if __name__ == "__main__":
    unittest.main()
