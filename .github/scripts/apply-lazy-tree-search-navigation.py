#!/usr/bin/env python3
"""Materialize lazy taxonomy paths before highlighting search results."""

from pathlib import Path

search_path = Path("taxonomy-app/src/main/resources/static/js/shared/taxonomy-search.js")
search = search_path.read_text(encoding="utf-8")
old = """    function revealNodeInTree(code) {
        var node = Array.from(document.querySelectorAll('.tax-node'))
            .find(function (candidate) {
                return candidate.dataset && candidate.dataset.code === code;
            });
"""
new = """    function revealNodeInTree(code) {
        if (window.TaxonomyBrowse && window.TaxonomyBrowse.ensureNodeRendered) {
            window.TaxonomyBrowse.ensureNodeRendered(
                code,
                window.TaxonomyState ? window.TaxonomyState.currentScores : null);
        }
        var node = Array.from(document.querySelectorAll('.tax-node'))
            .find(function (candidate) {
                return candidate.dataset && candidate.dataset.code === code;
            });
"""
if new not in search:
    if search.count(old) != 1:
        raise SystemExit(f"Expected one search reveal function, found {search.count(old)}")
    search_path.write_text(search.replace(old, new, 1), encoding="utf-8")

test_path = Path("taxonomy-app/src/test/java/com/taxonomy/ui/TaxonomyLazyTreeContractTest.java")
test = test_path.read_text(encoding="utf-8")
marker = """    @Test
    void explicitExpandAllAndManualScoringMayMaterializeTheFullTree() throws Exception {
"""
addition = """    @Test
    void searchNavigationMaterializesTheExactLazyPathBeforeHighlighting() throws Exception {
        String search = resource("/static/js/shared/taxonomy-search.js");

        assertThat(search)
                .contains("window.TaxonomyBrowse.ensureNodeRendered(")
                .contains("window.TaxonomyState ? window.TaxonomyState.currentScores : null")
                .contains("header.classList.add('search-highlight')");
    }

"""
if addition not in test:
    if test.count(marker) != 1:
        raise SystemExit("Could not locate lazy-tree test insertion point")
    test_path.write_text(test.replace(marker, addition + marker, 1), encoding="utf-8")

print("Connected search-result highlighting to lazy path materialization.")
