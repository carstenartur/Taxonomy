import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const source = await readFile(new URL(
  './ui-primary-session-workflow.mjs', import.meta.url), 'utf8');

test('authoritative reset draft verification resolves the application base path', () => {
  assert.match(source,
    /const path = `\/api\/analysis-drafts\/\$\{encodeURIComponent\(state\.workspaceId\)\}`/);
  assert.match(source,
    /TaxonomyI18n\?\.resolveUrl\?\.\(path\) \|\| path/);
  assert.doesNotMatch(source,
    /fetch\(\s*`\/api\/analysis-drafts\//);
});
