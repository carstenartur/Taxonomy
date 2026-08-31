#!/usr/bin/env python3
"""Keep the existing score assertions valid after architecture-summary navigation."""

from pathlib import Path

path = Path('.github/scripts/ui-acceptance.mjs')
content = path.read_text(encoding='utf-8')
old = """    assert(await page.locator('.tax-pct').count() > 0,
      'Real UI analysis completed without rendering scored taxonomy nodes');
    assert(await page.locator('[role=\"treeitem\"][aria-label*=\"Relevance\"]').count() > 0,
      'Dynamic scores were not synchronized to accessible tree-item names');

    await navigateTo('architecture');"""
new = """    assert(await page.evaluate(() => Object.values(window.TaxonomyState?.currentScores || {})
      .some(score => Number(score) > 0)),
      'Real UI analysis completed without producing scored taxonomy state');
    await page.locator('#viewList').click();
    await page.locator('.tax-pct').first().waitFor({ state: 'attached', timeout: 10_000 });
    assert(await page.locator('.tax-pct').count() > 0,
      'Scored taxonomy nodes were not restored in the result list');
    assert(await page.locator('[role=\"treeitem\"][aria-label*=\"Relevance\"]').count() > 0,
      'Dynamic scores were not synchronized to accessible tree-item names');

    await navigateTo('architecture');"""
if content.count(old) != 1:
    raise SystemExit(f'Expected exactly one UI acceptance target, found {content.count(old)}')
path.write_text(content.replace(old, new, 1), encoding='utf-8')
