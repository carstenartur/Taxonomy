import { writeFile, unlink } from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';

const TEST_PDF_BASE64 = 'JVBERi0xLjMKJZOMi54gUmVwb3J0TGFiIEdlbmVyYXRlZCBQREYgZG9jdW1lbnQgKG9wZW5zb3VyY2UpCjEgMCBvYmoKPDwKL0YxIDIgMCBSIC9GMiAzIDAgUgo+PgplbmRvYmoKMiAwIG9iago8PAovQmFzZUZvbnQgL0hlbHZldGljYSAvRW5jb2RpbmcgL1dpbkFuc2lFbmNvZGluZyAvTmFtZSAvRjEgL1N1YnR5cGUgL1R5cGUxIC9UeXBlIC9Gb250Cj4+CmVuZG9iagozIDAgb2JqCjw8Ci9CYXNlRm9udCAvSGVsdmV0aWNhLUJvbGQgL0VuY29kaW5nIC9XaW5BbnNpRW5jb2RpbmcgL05hbWUgL0YyIC9TdWJ0eXBlIC9UeXBlMSAvVHlwZSAvRm9udAo+PgplbmRvYmoKNCAwIG9iago8PAovQ29udGVudHMgOCAwIFIgL01lZGlhQm94IFsgMCAwIDU5NS4yNzU2IDg0MS44ODk4IF0gL1BhcmVudCA3IDAgUiAvUmVzb3VyY2VzIDw8Ci9Gb250IDEgMCBSIC9Qcm9jU2V0IFsgL1BERiAvVGV4dCAvSW1hZ2VCIC9JbWFnZUMgL0ltYWdlSSBdCj4+IC9Sb3RhdGUgMCAvVHJhbnMgPDwKCj4+IAogIC9UeXBlIC9QYWdlCj4+CmVuZG9iago1IDAgb2JqCjw8Ci9QYWdlTW9kZSAvVXNlTm9uZSAvUGFnZXMgNyAwIFIgL1R5cGUgL0NhdGFsb2cKPj4KZW5kb2JqCjYgMCBvYmoKPDwKL0F1dGhvciAoYW5vbnltb3VzKSAvQ3JlYXRpb25EYXRlIChEOjIwMjYwODAzMjM1NzI4KzAwJzAwJykgL0NyZWF0b3IgKGFub255bW91cykgL0tleXdvcmRzICgpIC9Nb2REYXRlIChEOjIwMjYwODAzMjM1NzI4KzAwJzAwJykgL1Byb2R1Y2VyIChSZXBvcnRMYWIgUERGIExpYnJhcnkgLSBcKG9wZW5zb3VyY2VcKSkgCiAgL1N1YmplY3QgKHVuc3BlY2lmaWVkKSAvVGl0bGUgKHVudGl0bGVkKSAvVHJhcHBlZCAvRmFsc2UKPj4KZW5kb2JqCjcgMCBvYmoKPDwKL0NvdW50IDEgL0tpZHMgWyA0IDAgUiBdIC9UeXBlIC9QYWdlcwo+PgplbmRvYmoKOCAwIG9iago8PAovRmlsdGVyIFsgL0FTQ0lJODVEZWNvZGUgL0ZsYXRlRGVjb2RlIF0gL0xlbmd0aCAzMTkKPj4Kc3RyZWFtCkdhczJEYnRjMiInU1o7USdQRkJ0bEhrUS5CNT00bD0zOm1uXWpuNXA7Iy4qYW1BNWVeYGwnXEYoNUorQl0tKz8oOTw+JExecUlcUlVPWGE5OkNyPEAhO1Y5UVxRZVlcLVltJCltNVl0SUxvKlI9KyxkUWprIkM/LShYZ0VIU0ZLOHBNKjJgXjBqSSlbNmYuX2JaViU9P1dbUm07PG4vJzpIYnFiQEddTVhgb1AsM3NIOGZoNitLUEgnN2Q/WTsxIS1fJU9lInFEQGQwXlM0cXJNPlFeW20vVlQpUDZDbl5EcCpDb2NgJjoraTZxJi9UJ2pgU24zRkEvOlFPbGhiXSluTlVwMktkYSxhPk9YYzgzVDhWXj5aQ0ckaTViQVhdYylqbFpjU3NRaWlMT1BOJCM9bGRDW0gyazBdJ3Jyfj5lbmRzdHJlYW0KZW5kb2JqCnhyZWYKMCA5CjAwMDAwMDAwMDAgNjU1MzUgZiAKMDAwMDAwMDA2MSAwMDAwMCBuIAowMDAwMDAwMTAyIDAwMDAwIG4gCjAwMDAwMDAyMDkgMDAwMDAgbiAKMDAwMDAwMDMyMSAwMDAwMCBuIAowMDAwMDAwNTI0IDAwMDAwIG4gCjAwMDAwMDA1OTIgMDAwMDAgbiAKMDAwMDAwMDg1MyAwMDAwMCBuIAowMDAwMDAwOTEyIDAwMDAwIG4gCnRyYWlsZXIKPDwKL0lEIApbPGM0ZjUyZDg4M2ZjYjU3NTBiNmM2ZGFmMDdjZTRkZWZiPjxjNGY1MmQ4ODNmY2I1NzUwYjZjNmRhZjA3Y2U0ZGVmYj5dCiUgUmVwb3J0TGFiIGdlbmVyYXRlZCBQREYgZG9jdW1lbnQgLS0gZGlnZXN0IChvcGVuc291cmNlKQoKL0luZm8gNiAwIFIKL1Jvb3QgNSAwIFIKL1NpemUgOQo+PgpzdGFydHhyZWYKMTMyMQolJUVPRgo=';

function uniqueSuffix() {
  return `${Date.now().toString(36)}-${process.pid.toString(36)}`.toUpperCase();
}

/** Browser acceptance for PDF → review → mixed atomic import → analysis. */
export async function runPortfolioImportWorkflows({ page, baseUrl, evidence }) {
  const context = await page.evaluate(async () => {
    const projectId = Number(localStorage.getItem('taxonomy.portfolio.projectId'));
    const response = await fetch(`/api/projects/${projectId}/requirements`, {
      headers: { Accept: 'application/json' }
    });
    return { projectId, requirements: await response.json() };
  });
  evidence.assert(context.projectId > 0, 'Portfolio import requires a selected project');
  evidence.assert(context.requirements.length >= 3,
    'Portfolio import acceptance requires existing requirements');
  const targetRequirement = context.requirements.find(item => item.requirementKey === 'REQ-001')
    || context.requirements[0];
  const versionsBefore = await page.evaluate(async ({ projectId, requirementId }) => {
    const response = await fetch(
      `/api/projects/${projectId}/requirements/${requirementId}/versions`,
      { headers: { Accept: 'application/json' } });
    return (await response.json()).length;
  }, { projectId: context.projectId, requirementId: targetRequirement.id });

  const suffix = uniqueSuffix();
  const filePath = path.join(os.tmpdir(), `taxonomy-portfolio-import-${suffix}.pdf`);
  await writeFile(filePath, Buffer.from(TEST_PDF_BASE64, 'base64'));
  try {
    await page.goto(`${baseUrl}/projects/${context.projectId}/import?lang=en`,
      { waitUntil: 'networkidle' });
    await page.locator('#importMain').waitFor({ state: 'visible', timeout: 30_000 });
    await page.locator('#documentFile').setInputFiles(filePath);
    await page.locator('#documentTitle').fill('Portfolio import acceptance source');

    const uploadPromise = page.waitForResponse(response =>
      response.request().method() === 'POST'
        && new URL(response.url()).pathname === '/api/documents/upload',
    { timeout: 120_000 });
    await page.locator('#uploadForm button[type="submit"]').click();
    const uploadResponse = await uploadPromise;
    evidence.assert(uploadResponse.ok(),
      `Document upload failed with HTTP ${uploadResponse.status()}`);
    await page.locator('#reviewStep').waitFor({ state: 'visible', timeout: 30_000 });

    // The parser may return one block or several requirement candidates depending
    // on extraction heuristics. Ensure exactly three independently reviewable
    // items through the GUI, never by concatenating them into one request text.
    while (await page.locator('.candidate-card').count() < 3) {
      await page.locator('#addManualCandidate').click();
    }
    const cards = page.locator('.candidate-card');
    evidence.assert(await cards.count() >= 3,
      'Guided import did not expose three independently reviewable candidates');

    const first = cards.nth(0);
    await first.locator('.candidate-decision').selectOption('VERSION');
    await first.locator('.candidate-target-requirement')
      .selectOption(String(targetRequirement.id));
    await first.locator('.candidate-text').fill(
      'The platform shall provide encrypted communication for all users and record the review source.');

    const second = cards.nth(1);
    await second.locator('.candidate-decision').selectOption('NEW');
    await second.locator('.candidate-key').fill(`REQ-IMP-A-${suffix}`);
    await second.locator('.candidate-title').fill('Imported audit history requirement');
    await second.locator('.candidate-text').fill(
      'The platform shall retain an auditable history of architecture decisions.');

    const third = cards.nth(2);
    await third.locator('.candidate-decision').selectOption('NEW');
    await third.locator('.candidate-key').fill(`REQ-IMP-B-${suffix}`);
    await third.locator('.candidate-title').fill('Imported offline operation requirement');
    await third.locator('.candidate-text').fill(
      'The platform shall continue operating when external connectivity is unavailable.');

    await page.locator('#reviewSummaryButton').click();
    await page.locator('#summaryStep').waitFor({ state: 'visible', timeout: 20_000 });
    evidence.assert(await page.locator('#importSummary tbody tr').count() >= 3,
      'Import summary does not show all retained reviewed candidates');

    const importPromise = page.waitForResponse(response =>
      response.request().method() === 'POST'
        && /^\/api\/projects\/\d+\/requirements\/import-review$/.test(
          new URL(response.url()).pathname),
    { timeout: 120_000 });
    await page.locator('#confirmImport').click();
    const importResponse = await importPromise;
    evidence.assert([201, 202].includes(importResponse.status()),
      `Reviewed import failed with HTTP ${importResponse.status()}`);
    const importResult = await importResponse.json();
    evidence.assert(importResult.newRequirements.length === 2,
      `Expected two new requirements, got ${importResult.newRequirements.length}`);
    evidence.assert(importResult.versionedRequirements.length === 1,
      `Expected one new version, got ${importResult.versionedRequirements.length}`);
    evidence.assert(Boolean(importResult.analysisJob),
      'Analyze-after-import did not enqueue a persisted job');

    const requirementsAfter = await page.evaluate(async projectId => {
      const response = await fetch(`/api/projects/${projectId}/requirements`, {
        headers: { Accept: 'application/json' }
      });
      return response.json();
    }, context.projectId);
    evidence.assert(requirementsAfter.some(item => item.requirementKey === `REQ-IMP-A-${suffix}`),
      'First reviewed new requirement was not persisted');
    evidence.assert(requirementsAfter.some(item => item.requirementKey === `REQ-IMP-B-${suffix}`),
      'Second reviewed new requirement was not persisted');

    const versionsAfter = await page.evaluate(async ({ projectId, requirementId }) => {
      const response = await fetch(
        `/api/projects/${projectId}/requirements/${requirementId}/versions`,
        { headers: { Accept: 'application/json' } });
      return (await response.json()).length;
    }, { projectId: context.projectId, requirementId: targetRequirement.id });
    evidence.assert(versionsAfter === versionsBefore + 1,
      `Reviewed new-version import expected ${versionsBefore + 1} versions, got ${versionsAfter}`);

    await page.goto(`${baseUrl}/projects?lang=en`, { waitUntil: 'networkidle' });
    await page.locator('#portfolioJobCenter').waitFor({ state: 'visible', timeout: 30_000 });
    evidence.assert(await page.locator('#portfolioJobList').getByText(
      String(importResult.analysisJob.id).slice(0, 12)).count() >= 1,
    'Imported analysis job was not restored in the portfolio job center');

    await page.goto(`${baseUrl}/projects/${context.projectId}/import?lang=en`,
      { waitUntil: 'networkidle' });
    await page.locator('#importMain').waitFor({ state: 'visible' });
    await evidence.axeState('portfolio-guided-import', '#importMain');
    await evidence.saveState('portfolio-guided-import', '#importMain');
    evidence.passed('PDF review, mixed atomic import, provenance and analysis job');
  } finally {
    await unlink(filePath).catch(() => undefined);
  }
}
