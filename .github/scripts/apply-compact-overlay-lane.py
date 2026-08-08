#!/usr/bin/env python3
"""Keep stacked undo notifications compact at high zoom and tiny viewports."""

from pathlib import Path

css_path = Path("taxonomy-app/src/main/resources/static/css/taxonomy-ergonomics.css")
css = css_path.read_text(encoding="utf-8")
old = """    .taxonomy-overlay-lane {
        width: calc(100vw - 1rem - env(safe-area-inset-left) - env(safe-area-inset-right));
        inset-block-end: max(0.5rem, env(safe-area-inset-bottom));
        inset-inline-end: max(0.5rem, env(safe-area-inset-right));
    }
}
"""
new = """    .taxonomy-overlay-lane {
        gap: 0.25rem;
        width: calc(100vw - 1rem - env(safe-area-inset-left) - env(safe-area-inset-right));
        inset-block-end: max(0.5rem, env(safe-area-inset-bottom));
        inset-inline-end: max(0.5rem, env(safe-area-inset-right));
    }
    .taxonomy-overlay-lane > .undo-toast {
        min-height: 44px;
        max-width: min(13rem, calc(100vw - 1rem));
        padding: 0 0 0 0.5rem;
        gap: 0.25rem;
    }
    .taxonomy-overlay-lane > .undo-toast > span {
        display: block;
        min-width: 0;
        max-width: 7rem;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
    }
}
"""
if new not in css:
    if css.count(old) != 1:
        raise SystemExit(f"Expected one narrow overlay media block, found {css.count(old)}")
    css_path.write_text(css.replace(old, new, 1), encoding="utf-8")

test_path = Path("taxonomy-app/src/test/java/com/taxonomy/ui/TaxonomyOverlayContractTest.java")
test = test_path.read_text(encoding="utf-8")
marker = """                .contains("min-height: 44px !important")
                .contains("overscroll-behavior: contain");
"""
replacement = """                .contains("min-height: 44px !important")
                .contains(".taxonomy-overlay-lane > .undo-toast > span")
                .contains("max-width: 7rem")
                .contains("text-overflow: ellipsis")
                .contains("white-space: nowrap")
                .contains("overscroll-behavior: contain");
"""
if replacement not in test:
    if test.count(marker) != 1:
        raise SystemExit("Could not locate overlay CSS assertions")
    test_path.write_text(test.replace(marker, replacement, 1), encoding="utf-8")

print("Compacted stacked overlay notifications for high zoom and tiny viewports.")
