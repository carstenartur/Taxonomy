#!/usr/bin/env python3
"""Apply the final static accessibility corrections for PR #406 exactly once."""

from __future__ import annotations

from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
TEMPLATE = ROOT / "taxonomy-app/src/main/resources/templates/index.html"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one {label}; found {count}")
    return text.replace(old, new, 1)


def add_attribute_to_id(text: str, element_id: str, attribute: str) -> str:
    pattern = re.compile(rf'(<(?:input|select)\b(?=[^>]*\bid="{re.escape(element_id)}")[^>]*)(>)')
    match = pattern.search(text)
    if not match:
        raise SystemExit(f"Element #{element_id} not found")
    if re.search(rf'\b{re.escape(attribute.split("=", 1)[0])}=', match.group(1)):
        return text
    replacement = match.group(1) + " " + attribute + match.group(2)
    return text[:match.start()] + replacement + text[match.end():]


def main() -> None:
    text = TEMPLATE.read_text(encoding="utf-8")

    text = replace_once(
        text,
        '<ul class="nav nav-tabs nav-tabs-page mb-0" id="mainNavTabs">',
        '<ul class="nav nav-tabs nav-tabs-page mb-0" id="mainNavTabs" role="tablist" aria-label="Main sections">',
        "main navigation list",
    )

    start = text.index('<ul class="nav nav-tabs nav-tabs-page mb-0" id="mainNavTabs"')
    end = text.index('</ul>', start) + len('</ul>')
    block = text[start:end]
    block = re.sub(r'<li(\s+)(?![^>]*\brole=)', r'<li role="presentation"\1', block)
    text = text[:start] + block + text[end:]

    text = replace_once(
        text,
        '<div id="taxonomyTree" role="tree" aria-label="Taxonomy Tree">',
        '<div id="taxonomyTree" aria-label="Taxonomy Tree" aria-busy="true">',
        "taxonomy tree container",
    )

    text = add_attribute_to_id(text, "dslAuthorInput", 'aria-label="Commit author"')
    text = add_attribute_to_id(text, "dslBranchSelect", 'aria-label="DSL branch"')

    def make_table_region(match: re.Match[str]) -> str:
        tag = match.group(0)
        if 'tabindex=' not in tag:
            tag = tag[:-1] + ' tabindex="0"'
        if 'role=' not in tag:
            tag += ' role="region"'
        if 'aria-label=' not in tag:
            tag += ' aria-label="Scrollable data table"'
        return tag + '>'

    text = re.sub(
        r'<div\b(?=[^>]*\bclass="[^"]*\btable-responsive\b[^"]*")[^>]*>',
        make_table_region,
        text,
    )

    TEMPLATE.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    main()
