#!/usr/bin/env python3
"""Make onboarding re-initialisation safe for global expert keyboard shortcuts."""

from pathlib import Path

source_path = Path("taxonomy-app/src/main/resources/static/js/shared/taxonomy-onboarding.js")
source = source_path.read_text(encoding="utf-8")
state_marker = "    var overlayLaneRefreshFrame = null;\n"
state_line = "    var expertShortcutsInstalled = false;\n"
if state_line not in source:
    if source.count(state_marker) != 1:
        raise SystemExit("Could not locate shortcut installation state marker")
    source = source.replace(state_marker, state_marker + state_line, 1)

guard_marker = "        if (operationalSummary) {\n            operationalSummary.title = t('analysis.task.shortcut.operational');\n        }\n        document.addEventListener('keydown', function (event) {\n"
guarded = "        if (operationalSummary) {\n            operationalSummary.title = t('analysis.task.shortcut.operational');\n        }\n        if (expertShortcutsInstalled) {\n            return;\n        }\n        expertShortcutsInstalled = true;\n        document.addEventListener('keydown', function (event) {\n"
if guarded not in source:
    if source.count(guard_marker) != 1:
        raise SystemExit("Could not locate expert shortcut listener")
    source = source.replace(guard_marker, guarded, 1)
source_path.write_text(source, encoding="utf-8")

test_path = Path("taxonomy-app/src/test/java/com/taxonomy/ui/TaxonomyOverlayContractTest.java")
test = test_path.read_text(encoding="utf-8")
assertion_marker = "                .contains(\"returnFocus.focus({ preventScroll: true })\")\n"
assertions = (
    assertion_marker
    + "                .contains(\"var expertShortcutsInstalled = false;\")\n"
    + "                .contains(\"if (expertShortcutsInstalled) {\")\n"
    + "                .contains(\"expertShortcutsInstalled = true;\")\n"
)
if "var expertShortcutsInstalled = false;" not in test:
    if test.count(assertion_marker) != 1:
        raise SystemExit("Could not locate overlay contract assertion marker")
    test = test.replace(assertion_marker, assertions, 1)
test_path.write_text(test, encoding="utf-8")

print("Made expert shortcut installation idempotent and locked the contract.")
