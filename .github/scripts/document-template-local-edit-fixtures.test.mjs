import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { editDownloadedTemplate, readDocumentPart } from './document-template-local-edit-acceptance.mjs';

const original = fileURLToPath(new URL(
  '../../taxonomy-app/src/main/resources/document-templates/decision-rationale-report.dotx', import.meta.url));
const digest = bytes => createHash('sha256').update(bytes).digest('hex');

test('browser fixture edits preserve the original archive and final Word section properties', async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'taxonomy-fixture-test-'));
  try {
    const originalBytes = await readFile(original);
    const source = await readDocumentPart(original);
    for (const marker of ['accepted-local-edit-de', 'rejected-local-edit-en']) {
      const edited = path.join(directory, marker + '.dotx');
      await editDownloadedTemplate(original, edited, marker);
      const xml = await readDocumentPart(edited);
      assert.equal(digest(await readFile(original)), digest(originalBytes));
      assert.notEqual(digest(await readFile(edited)), digest(originalBytes));
      const inserted = `<w:p><w:r><w:t>${marker}</w:t></w:r></w:p>`;
      assert.equal(xml.replace(inserted, ''), source);
      const end = xml.lastIndexOf('</w:body>');
      const section = xml.lastIndexOf('<w:sectPr', end);
      if (section >= 0) assert.ok(xml.indexOf(inserted) < section);
    }
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test('fixture marker cannot inject XML or filesystem syntax', async () => {
  for (const marker of ['<w:p/>', '../unsafe', 'name&value', '']) {
    await assert.rejects(editDownloadedTemplate(original, '/unused.dotx', marker));
  }
});
