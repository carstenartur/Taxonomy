#!/usr/bin/env python3
"""Synchronize ARIA and responsive navigation immediately after keyboard activation."""

from pathlib import Path

path = Path("taxonomy-app/src/main/resources/static/js/shared/taxonomy-utils.js")
text = path.read_text(encoding="utf-8")
old = """            tabs[next].focus();
            tabs[next].click();
"""
new = """            tabs[next].focus();
            tabs[next].click();
            syncMainNavigation();
            syncResponsiveMainNavigation();
"""
if text.count(old) != 1:
    raise SystemExit(f"Expected one keyboard activation block, found {text.count(old)}")
path.write_text(text.replace(old, new, 1), encoding="utf-8")
print("Synchronized navigation semantics immediately after keyboard activation.")
