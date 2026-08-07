#!/usr/bin/env python3
"""Apply focused overlay performance and determinism fixes from PR #634 review."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ONBOARDING = ROOT / "taxonomy-app/src/main/resources/static/js/shared/taxonomy-onboarding.js"
ROLE_FLOW = ROOT / ".github/scripts/ui-role-state-flow.mjs"
TEST = ROOT / "taxonomy-app/src/test/java/com/taxonomy/ui/TaxonomyOverlayContractTest.java"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


def patch_onboarding(source: str) -> str:
    source = replace_once(
        source,
        """            lane.dataset.position = selected;
        });
    }
""",
        """            lane.dataset.position = selected;
            lane.dataset.refreshVersion = String(
                Number.parseInt(lane.dataset.refreshVersion || '0', 10) + 1);
        });
    }
""",
        "publish completed overlay refresh version",
    )
    source = replace_once(
        source,
        """        overlayLaneObserver.observe(document.body, { childList: true, subtree: true });
""",
        """        // Undo toasts are mounted as direct body children. Observe only that boundary;
        // routeOverlayNode still handles a newly added wrapper containing a toast.
        overlayLaneObserver.observe(document.body, { childList: true });
""",
        "limit observer scope",
    )
    return source


def patch_role_flow(source: str) -> str:
    source = replace_once(
        source,
        """  await businessText.scrollIntoViewIfNeeded();
  await businessText.focus();
  await page.evaluate(() => window.TaxonomyOnboarding.refreshOverlayLane());
  await page.waitForFunction(() =>
    document.getElementById('taxonomyOverlayLane')?.dataset.position);
""",
        """  await businessText.scrollIntoViewIfNeeded();
  await businessText.focus();
  const overlayRefreshVersion = await page.evaluate(() => {
    const lane = document.getElementById('taxonomyOverlayLane');
    const previous = Number.parseInt(lane?.dataset.refreshVersion || '0', 10);
    window.TaxonomyOnboarding.refreshOverlayLane();
    return previous;
  });
  await page.waitForFunction(previous => {
    const current = Number.parseInt(
      document.getElementById('taxonomyOverlayLane')?.dataset.refreshVersion || '0', 10);
    return current > previous;
  }, overlayRefreshVersion);
""",
        "wait for completed animation-frame refresh",
    )
    return source


def patch_test(source: str) -> str:
    source = replace_once(
        source,
        """                .contains("overlayLaneObserver.observe(document.body");
""",
        """                .contains("overlayLaneObserver.observe(document.body, { childList: true })")
                .contains("lane.dataset.refreshVersion = String(")
                .doesNotContain("overlayLaneObserver.observe(document.body, { childList: true, subtree: true })");
""",
        "lock bounded observer and refresh completion signal",
    )
    return source


def main() -> None:
    source = ONBOARDING.read_text(encoding="utf-8")
    if "lane.dataset.refreshVersion = String(" in source:
        print("Overlay review fixes already applied.")
        return
    ONBOARDING.write_text(patch_onboarding(source), encoding="utf-8")
    ROLE_FLOW.write_text(patch_role_flow(ROLE_FLOW.read_text(encoding="utf-8")), encoding="utf-8")
    TEST.write_text(patch_test(TEST.read_text(encoding="utf-8")), encoding="utf-8")
    print("Applied bounded overlay observation and deterministic refresh evidence.")


if __name__ == "__main__":
    main()
