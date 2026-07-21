#!/usr/bin/env python3
"""Apply the reviewed CodeMirror accessibility corrections and remove this helper."""

from pathlib import Path

path = Path("taxonomy-app/src/main/resources/static/js/shared/taxonomy-dsl-codemirror.mjs")
text = path.read_text(encoding="utf-8")

replacements = {
    "{ tag: tags.typeName,        color: '#d97706' }":
        "{ tag: tags.typeName,        color: '#92400e' }",
    "{ tag: tags.propertyName,    color: '#059669' }":
        "{ tag: tags.propertyName,    color: '#047857' }",
    "{ tag: tags.string,          color: '#16a34a' }":
        "{ tag: tags.string,          color: '#166534' }",
}
for old, new in replacements.items():
    if text.count(old) != 1:
        raise SystemExit(f"Expected exactly one CodeMirror colour declaration: {old}")
    text = text.replace(old, new, 1)

old = """    });

    window.dslCmView = view;
"""
new = """    });

    // CodeMirror keeps scrolling on a separate DOM node. Make that region
    // keyboard reachable and explicitly named instead of excluding it from axe.
    view.scrollDOM.tabIndex = 0;
    view.scrollDOM.setAttribute('role', 'region');
    view.scrollDOM.setAttribute('aria-label', 'TaxDSL editor scroll area');

    window.dslCmView = view;
"""
if text.count(old) != 1:
    raise SystemExit("Expected exactly one CodeMirror view initialization terminator")
text = text.replace(old, new, 1)

path.write_text(text, encoding="utf-8")
Path(__file__).unlink()
