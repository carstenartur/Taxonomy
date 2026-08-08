#!/usr/bin/env python3
"""Remove per-node path allocations from incremental taxonomy traversal."""

from pathlib import Path

browse_path = Path('taxonomy-app/src/main/resources/static/js/core/taxonomy-browse.js')
browse = browse_path.read_text(encoding='utf-8')
old = """    function findNodePath(code) {
        let found = null;
        function visit(nodes, path) {
            for (const node of (nodes || [])) {
                const nextPath = path.concat(node);
                if (node.code === code) {
                    found = nextPath;
                    return true;
                }
                if (visit(node.children || [], nextPath)) { return true; }
            }
            return false;
        }
        visit(S.taxonomyData || [], []);
        return found || [];
    }
"""
new = """    function findNodePath(code) {
        const path = [];
        let found = null;
        function visit(nodes) {
            for (const node of (nodes || [])) {
                path.push(node);
                if (node.code === code) {
                    found = path.slice();
                    return true;
                }
                if (visit(node.children || [])) { return true; }
                path.pop();
            }
            return false;
        }
        visit(S.taxonomyData || []);
        return found || [];
    }
"""
if browse.count(old) != 1:
    raise SystemExit(f'Expected one findNodePath implementation, found {browse.count(old)}')
browse_path.write_text(browse.replace(old, new, 1), encoding='utf-8')

test_path = Path('taxonomy-app/src/test/java/com/taxonomy/ui/TaxonomyLazyTreeContractTest.java')
test = test_path.read_text(encoding='utf-8')
marker = """                .contains("materializeScoredPaths(scores)")
                .doesNotContain("node.children.forEach(child => childContainer.appendChild(buildNodeEl(child, scores)))");
"""
replacement = """                .contains("materializeScoredPaths(scores)")
                .contains("path.push(node)")
                .contains("found = path.slice()")
                .contains("path.pop()")
                .doesNotContain("path.concat(node)")
                .doesNotContain("node.children.forEach(child => childContainer.appendChild(buildNodeEl(child, scores)))");
"""
if replacement not in test:
    if test.count(marker) != 1:
        raise SystemExit('Could not locate lazy-tree contract assertion block')
    test = test.replace(marker, replacement, 1)
test_path.write_text(test, encoding='utf-8')
print('Replaced recursive path concatenation with a mutable traversal stack.')
