#!/usr/bin/env python3
"""Apply the final axe-confirmed ARIA containment corrections exactly once."""
from pathlib import Path

path = Path("taxonomy-app/src/main/resources/static/js/shared/taxonomy-utils.js")
text = path.read_text(encoding="utf-8")

old = """        Array.from(tabList.querySelectorAll('.nav-link[data-page]')).forEach(function (link) {
            var page = link.getAttribute('data-page');
            var active = link.classList.contains('active');
            if (!link.id) link.id = 'main-tab-' + page;
"""
new = """        Array.from(tabList.querySelectorAll('.nav-link[data-page]')).forEach(function (link) {
            var page = link.getAttribute('data-page');
            var active = link.classList.contains('active');
            var wrapper = link.closest('li');
            if (wrapper) wrapper.setAttribute('role', 'presentation');
            if (!link.id) link.id = 'main-tab-' + page;
"""
if old not in text:
    if "wrapper.setAttribute('role', 'presentation')" not in text:
        raise SystemExit("Main navigation insertion point not found")
else:
    text = text.replace(old, new, 1)

old = """    function installTreeAccessibilityObserver() {
        var tree = document.getElementById('taxonomyTree');
"""
new = """    function syncTreeContainerSemantics(tree) {
        if (tree.querySelector('[role=\"treeitem\"]')) {
            tree.setAttribute('role', 'tree');
            tree.removeAttribute('aria-busy');
        } else {
            tree.removeAttribute('role');
            tree.setAttribute('aria-busy', 'true');
        }
    }

    function installTreeAccessibilityObserver() {
        var tree = document.getElementById('taxonomyTree');
"""
if old not in text:
    if "function syncTreeContainerSemantics" not in text:
        raise SystemExit("Tree semantics insertion point not found")
else:
    text = text.replace(old, new, 1)

old = """        var syncAll = function () {
            tree.querySelectorAll('[role=\"treeitem\"]').forEach(syncTreeItemAccessibility);
            refreshNodeCodeSuggestions();
        };
"""
new = """        var syncAll = function () {
            syncTreeContainerSemantics(tree);
            tree.querySelectorAll('[role=\"treeitem\"]').forEach(syncTreeItemAccessibility);
            refreshNodeCodeSuggestions();
        };
"""
if old not in text:
    if "syncTreeContainerSemantics(tree);\n            tree.querySelectorAll" not in text:
        raise SystemExit("Tree syncAll insertion point not found")
else:
    text = text.replace(old, new, 1)

old = """            });
            refreshNodeCodeSuggestions();
        }).observe(tree, {
"""
new = """            });
            syncTreeContainerSemantics(tree);
            refreshNodeCodeSuggestions();
        }).observe(tree, {
"""
if old not in text:
    if text.count("syncTreeContainerSemantics(tree);") < 2:
        raise SystemExit("Tree mutation insertion point not found")
else:
    text = text.replace(old, new, 1)

path.write_text(text, encoding="utf-8")
print("Applied ARIA tablist and tree containment fixes")
