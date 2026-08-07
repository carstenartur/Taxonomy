#!/usr/bin/env python3
"""One-shot, fail-closed source transformation for #620 lazy taxonomy rendering."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BROWSE = ROOT / "taxonomy-app/src/main/resources/static/js/core/taxonomy-browse.js"
SCORING = ROOT / "taxonomy-app/src/main/resources/static/js/core/taxonomy-scoring.js"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one source match, found {count}")
    return text.replace(old, new, 1)


def patch_browse(source: str) -> str:
    source = replace_once(
        source,
        """            case 'list':
                renderTree(data, scores);
                if (scores) { SC().expandMatched(scores); }
                document.getElementById('taxonomyTree').setAttribute('data-view-rendered', 'list');
                break;
            case 'tabs':
                renderTabsView(data, scores);
                if (scores) { SC().expandMatched(scores); }
                document.getElementById('taxonomyTree').setAttribute('data-view-rendered', 'tabs');
                break;
""",
        """            case 'list':
                renderTree(data, scores);
                if (scores) {
                    materializeScoredPaths(scores);
                    SC().expandMatched(scores);
                }
                document.getElementById('taxonomyTree').setAttribute('data-view-rendered', 'list');
                break;
            case 'tabs':
                renderTabsView(data, scores);
                if (scores) {
                    materializeScoredPaths(scores);
                    SC().expandMatched(scores);
                }
                document.getElementById('taxonomyTree').setAttribute('data-view-rendered', 'tabs');
                break;
""",
        "renderView scored-path materialization",
    )

    source = replace_once(
        source,
        """    function cleanupD3(container) {
        if (container._taxObserver) {
            container._taxObserver.disconnect();
            container._taxObserver = null;
        }
    }

    function buildNodeEl(node, scores) {
""",
        """    function cleanupD3(container) {
        if (container._taxObserver) {
            container._taxObserver.disconnect();
            container._taxObserver = null;
        }
    }

    // ── Incremental tree materialization ─────────────────────────────────────
    // Collapsed descendants are represented by their source node, not thousands
    // of hidden DOM elements. A subtree is materialized only when the user opens
    // it or a scored path needs to become visible.
    function findNodePath(code) {
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

    function materializeChildren(wrapper, scores) {
        if (!wrapper) { return null; }
        const childContainer = wrapper.querySelector(':scope > .tax-children');
        if (!childContainer) { return null; }
        if (childContainer.dataset.renderState !== 'deferred') { return childContainer; }

        const sourceNode = wrapper._taxonomyNode;
        const children = sourceNode && Array.isArray(sourceNode.children)
            ? sourceNode.children : [];
        childContainer.dataset.renderState = 'materializing';
        const fragment = document.createDocumentFragment();
        children.forEach(child => fragment.appendChild(buildNodeEl(child, scores)));
        childContainer.appendChild(fragment);
        childContainer.dataset.renderState = 'ready';
        return childContainer;
    }

    function ensureNodeRendered(code, scores) {
        const tree = document.getElementById('taxonomyTree');
        if (!tree || !code) { return null; }
        let target = tree.querySelector('[data-code="' + CSS.escape(code) + '"]');
        if (target) { return target; }

        const path = findNodePath(code);
        if (path.length === 0) { return null; }
        let parentEl = null;
        for (let i = 0; i < path.length; i++) {
            const current = path[i];
            let currentEl = tree.querySelector('[data-code="' + CSS.escape(current.code) + '"]');
            if (!currentEl && parentEl) {
                materializeChildren(parentEl, scores);
                currentEl = parentEl.querySelector(
                    ':scope > .tax-children > [data-code="' + CSS.escape(current.code) + '"]');
            }
            // In tabs view the taxonomy root is represented by the tab itself,
            // while its direct children are the first treeitems in the pane.
            if (!currentEl && S.currentView === 'tabs' && i === 0) { continue; }
            if (!currentEl) { return null; }
            parentEl = currentEl;
        }
        return tree.querySelector('[data-code="' + CSS.escape(code) + '"]');
    }

    function materializeScoredPaths(scores) {
        if (!scores) { return; }
        Object.entries(scores).forEach(function (entry) {
            if (entry[1] > 0) { ensureNodeRendered(entry[0], scores); }
        });
    }

    function materializeAllTaxonomyNodes(scores) {
        const tree = document.getElementById('taxonomyTree');
        if (!tree) { return; }
        let deferred = Array.from(
            tree.querySelectorAll('.tax-children[data-render-state="deferred"]'));
        while (deferred.length > 0) {
            deferred.forEach(function (group) {
                const wrapper = group.parentElement;
                if (wrapper && wrapper.classList.contains('tax-node')) {
                    materializeChildren(wrapper, scores);
                }
            });
            deferred = Array.from(
                tree.querySelectorAll('.tax-children[data-render-state="deferred"]'));
        }
    }

    function buildNodeEl(node, scores) {
""",
        "insert incremental materialization helpers",
    )

    source = replace_once(
        source,
        """        wrapper.className = 'tax-node tax-level-' + node.level;
        wrapper.dataset.code = node.code;

        // ARIA treeview role (WCAG 4.1.2)
""",
        """        wrapper.className = 'tax-node tax-level-' + node.level;
        wrapper.dataset.code = node.code;
        wrapper._taxonomyNode = node;

        // ARIA treeview role (WCAG 4.1.2)
""",
        "retain source node on rendered wrapper",
    )

    source = replace_once(
        source,
        """        // Children container
        if (hasChildren) {
            const childContainer = document.createElement('div');
            childContainer.className = 'tax-children';
            childContainer.setAttribute('role', 'group');
            childContainer.style.display = 'none'; // collapsed by default
            node.children.forEach(child => childContainer.appendChild(buildNodeEl(child, scores)));
            wrapper.appendChild(childContainer);
        }
""",
        """        // Children container. Descendants remain deferred while collapsed;
        // materializeChildren() creates only the next requested level.
        if (hasChildren) {
            const childContainer = document.createElement('div');
            childContainer.className = 'tax-children';
            childContainer.setAttribute('role', 'group');
            childContainer.dataset.renderState = 'deferred';
            childContainer.style.display = 'none'; // collapsed by default
            wrapper.appendChild(childContainer);
        }
""",
        "defer recursive child creation",
    )

    source = replace_once(
        source,
        """    function toggleNode(wrapper, toggleEl) {
        const children = wrapper.querySelector(':scope > .tax-children');
        if (!children) return;
        const isHidden = children.style.display === 'none';

        if (isHidden && S.interactiveMode && S.storedBusinessText) {
""",
        """    function toggleNode(wrapper, toggleEl) {
        let children = wrapper.querySelector(':scope > .tax-children');
        if (!children) return;
        const isHidden = children.style.display === 'none';
        if (isHidden) {
            children = materializeChildren(wrapper, S.currentScores) || children;
        }

        if (isHidden && S.interactiveMode && S.storedBusinessText) {
""",
        "materialize one level before expanding",
    )

    source = replace_once(
        source,
        """                if (children) {
                    children.style.display = '';
                    toggle.textContent = '▼';
                }

                // Update currentScores
""",
        """                if (children) {
                    children.style.display = '';
                    toggle.textContent = '▼';
                    wrapper.setAttribute('aria-expanded', 'true');
                }

                // Update currentScores
""",
        "interactive success aria expansion",
    )

    source = replace_once(
        source,
        """                if (children) {
                    children.style.display = '';
                    toggle.textContent = '▼';
                }
            });
    }

    function expandAll() {
""",
        """                if (children) {
                    children.style.display = '';
                    toggle.textContent = '▼';
                    wrapper.setAttribute('aria-expanded', 'true');
                }
            });
    }

    function expandAll() {
        materializeAllTaxonomyNodes(S.currentScores);
""",
        "interactive failure aria and explicit full expansion",
    )

    source = replace_once(
        source,
        """        renderView(S.taxonomyData, null);
        addManualScoreInputs();
""",
        """        renderView(S.taxonomyData, null);
        // Manual scoring is an explicit expert action over the complete taxonomy.
        materializeAllTaxonomyNodes(null);
        addManualScoreInputs();
""",
        "manual scoring full materialization",
    )

    source = replace_once(
        source,
        """    window.TaxonomyBrowse = {
        renderView: renderView,
        switchView: switchView,
        showStatus: showStatus,
        clearStatus: clearStatus,
        escapeHtml: escapeHtml,
        updateExportGroupVisibility: updateExportGroupVisibility
    };
""",
        """    window.TaxonomyBrowse = {
        renderView: renderView,
        switchView: switchView,
        showStatus: showStatus,
        clearStatus: clearStatus,
        escapeHtml: escapeHtml,
        updateExportGroupVisibility: updateExportGroupVisibility,
        ensureNodeRendered: ensureNodeRendered,
        materializeChildren: materializeChildren,
        materializeAllTaxonomyNodes: materializeAllTaxonomyNodes
    };
""",
        "expose incremental rendering API",
    )
    return source


def patch_scoring(source: str) -> str:
    source = replace_once(
        source,
        """    function applyScoreToNode(code, pct, reason) {
        const el = document.querySelector('[data-code="' + CSS.escape(code) + '"]');
""",
        """    function applyScoreToNode(code, pct, reason) {
        B().ensureNodeRendered(code, S.currentScores);
        const el = document.querySelector('[data-code="' + CSS.escape(code) + '"]');
""",
        "streaming score materializes target",
    )
    source = replace_once(
        source,
        """    function expandNodeByCode(code) {
        const el = document.querySelector('[data-code="' + CSS.escape(code) + '"]');
""",
        """    function expandNodeByCode(code) {
        B().ensureNodeRendered(code, S.currentScores);
        const el = document.querySelector('[data-code="' + CSS.escape(code) + '"]');
""",
        "expand by code materializes target",
    )
    source = replace_once(
        source,
        """        if (children) {
            children.style.display = '';
            const toggle = el.querySelector(':scope > .tax-node-header > .tax-toggle');
""",
        """        if (children) {
            B().materializeChildren(el, S.currentScores);
            children.style.display = '';
            const toggle = el.querySelector(':scope > .tax-node-header > .tax-toggle');
""",
        "expand by code materializes child level",
    )
    source = replace_once(
        source,
        """    function markNodeAsEvaluating(code) {
        const el = document.querySelector('[data-code="' + CSS.escape(code) + '"]');
""",
        """    function markNodeAsEvaluating(code) {
        B().ensureNodeRendered(code, S.currentScores);
        const el = document.querySelector('[data-code="' + CSS.escape(code) + '"]');
""",
        "evaluating marker materializes target",
    )
    source = replace_once(
        source,
        """            if (pct > 0) {
                const el = document.querySelector('[data-code="' + CSS.escape(code) + '"]');
                if (!el) return;
                // expand this node
                const children = el.querySelector(':scope > .tax-children');
                if (children) {
                    children.style.display = '';
                    const toggle = el.querySelector(':scope > .tax-node-header > .tax-toggle');
                    if (toggle) toggle.textContent = '▼';
                }
""",
        """            if (pct > 0) {
                const el = B().ensureNodeRendered(code, scores)
                    || document.querySelector('[data-code="' + CSS.escape(code) + '"]');
                if (!el) return;
                // expand this node only after its immediate child level exists
                const children = el.querySelector(':scope > .tax-children');
                if (children) {
                    B().materializeChildren(el, scores);
                    children.style.display = '';
                    const toggle = el.querySelector(':scope > .tax-node-header > .tax-toggle');
                    if (toggle) toggle.textContent = '▼';
                    el.setAttribute('aria-expanded', 'true');
                }
""",
        "matched score materializes target path",
    )
    source = replace_once(
        source,
        """                        if (parentNode) {
                            const t = parentNode.querySelector(':scope > .tax-node-header > .tax-toggle');
                            if (t) t.textContent = '▼';
                        }
""",
        """                        if (parentNode) {
                            const t = parentNode.querySelector(':scope > .tax-node-header > .tax-toggle');
                            if (t) t.textContent = '▼';
                            parentNode.setAttribute('aria-expanded', 'true');
                        }
""",
        "matched score ancestor aria state",
    )
    return source


def main() -> None:
    browse = BROWSE.read_text(encoding="utf-8")
    scoring = SCORING.read_text(encoding="utf-8")
    if "function materializeChildren(wrapper, scores)" in browse:
        print("Lazy rendering already applied; nothing to do.")
        return
    BROWSE.write_text(patch_browse(browse), encoding="utf-8")
    SCORING.write_text(patch_scoring(scoring), encoding="utf-8")
    print("Applied fail-closed lazy taxonomy rendering transformation.")


if __name__ == "__main__":
    main()
