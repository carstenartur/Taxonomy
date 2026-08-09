import assert from 'node:assert/strict';
import test from 'node:test';
import { normalizeEvidenceSelector } from './ui-primary-evidence.mjs';

test('document import evidence targets the visible disclosure panel', () => {
  assert.equal(normalizeEvidenceSelector('#docImportPanel'), '#documentImportPanel');
});

test('other evidence selectors remain unchanged', () => {
  assert.equal(normalizeEvidenceSelector('#mainContent'), '#mainContent');
  assert.equal(normalizeEvidenceSelector('#docCandidateReviewPanel'), '#docCandidateReviewPanel');
});
