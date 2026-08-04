function uniqueSuffix() {
  return `${Date.now().toString(36)}-${process.pid.toString(36)}`.toUpperCase();
}

async function waitUntilIdle(page) {
  await page.locator('#portfolioBusy').waitFor({ state: 'hidden', timeout: 60_000 });
}

async function createRequirement(page, key, title, text) {
  await page.locator('[data-bs-target="#requirementModal"]').click();
  const modal = page.locator('#requirementModal');
  await modal.waitFor({ state: 'visible', timeout: 20_000 });
  await modal.locator('#requirementKey').fill(key);
  await modal.locator('#requirementTitle').fill(title);
  await modal.locator('#requirementType').selectOption('NON_FUNCTIONAL');
  await modal.locator('#requirementText').fill(text);
  const responsePromise = page.waitForResponse(response =>
    response.request().method() === 'POST'
      && /^\/api\/projects\/\d+\/requirements$/.test(new URL(response.url()).pathname),
  { timeout: 60_000 });
  await modal.locator('button[type="submit"]').click();
  const response = await responsePromise;
  if (!response.ok()) throw new Error(`Requirement creation failed: HTTP ${response.status()}`);
  await modal.waitFor({ state: 'hidden', timeout: 20_000 });
  await waitUntilIdle(page);
}

/** Acceptance for guided taxonomy lookup, product comparison and conflict decisions. */
export async function runPortfolioGuidedDecisionWorkflows({ page, baseUrl, evidence }) {
  const suffix = uniqueSuffix();
  const cloudKey = `REQ-CLOUD-${suffix}`;
  const hostingKey = `REQ-HOST-${suffix}`;
  await page.goto(`${baseUrl}/projects?lang=en`, { waitUntil: 'networkidle' });
  await page.locator('#projectWorkspace').waitFor({ state: 'visible', timeout: 30_000 });

  await createRequirement(page, cloudKey, 'Mandatory public cloud',
    'The solution must use public cloud hosting for all runtime services.');
  await createRequirement(page, hostingKey, 'External hosting prohibited',
    'The solution must not use external hosting and must remain on premises.');

  const conflictResponse = page.waitForResponse(response =>
    response.request().method() === 'POST'
      && /^\/api\/projects\/\d+\/conflicts\/detect$/.test(
        new URL(response.url()).pathname),
  { timeout: 60_000 });
  await page.locator('#detectConflictsBtn').click();
  evidence.assert((await conflictResponse).ok(), 'Conflict detection request failed');
  await waitUntilIdle(page);
  await page.locator('#conflicts-tab').click();
  const conflictCard = page.locator('.portfolio-conflict-card')
    .filter({ hasText: cloudKey }).filter({ hasText: hostingKey }).first();
  await conflictCard.waitFor({ state: 'visible', timeout: 30_000 });

  await conflictCard.locator('.conflict-review[data-status="CONFIRMED"]').click();
  const dialog = page.locator('#guidedConflictDialog');
  await dialog.waitFor({ state: 'visible', timeout: 20_000 });
  evidence.assert((await dialog.locator('#guidedConflictRequirementA').innerText()).length > 0,
    'Guided conflict dialog does not show requirement A');
  evidence.assert((await dialog.locator('#guidedConflictRequirementB').innerText()).length > 0,
    'Guided conflict dialog does not show requirement B');
  evidence.assert((await dialog.locator('#guidedConflictEvidence').innerText()).length > 0,
    'Guided conflict dialog does not show evidence');
  await dialog.locator('#guidedConflictDecision').selectOption('RESOLVED');
  await dialog.locator('#guidedConflictResolution').fill(
    'The approved deployment profile uses a private on-premises cloud; public-cloud wording is superseded for this project.');
  const patchPromise = page.waitForResponse(response =>
    response.request().method() === 'PATCH'
      && /^\/api\/projects\/\d+\/conflicts\/\d+$/.test(
        new URL(response.url()).pathname),
  { timeout: 60_000 });
  await dialog.locator('button[type="submit"]').click();
  evidence.assert((await patchPromise).ok(), 'Guided conflict decision failed');
  await dialog.waitFor({ state: 'hidden', timeout: 20_000 });
  evidence.passed('contextual evidence-backed conflict resolution');

  await page.locator('#solutions-tab').click();
  const solutionCard = page.locator('.portfolio-solution-card').first();
  await solutionCard.waitFor({ state: 'visible', timeout: 20_000 });
  const coverageDetails = solutionCard.locator('details').first();
  if (!(await coverageDetails.getAttribute('open'))) await coverageDetails.locator('summary').click();
  const taxonomyInput = solutionCard.locator('.solution-node-code');
  evidence.assert(await taxonomyInput.getAttribute('list') === 'taxonomyNodeOptions',
    'Taxonomy coverage still uses an unassisted free-text code field');
  await taxonomyInput.fill('secure');
  await page.waitForFunction(() =>
    document.querySelectorAll('#taxonomyNodeOptions option').length > 0,
  { timeout: 30_000 });
  const suggestion = await page.locator('#taxonomyNodeOptions option').first().getAttribute('value');
  evidence.assert(Boolean(suggestion), 'Taxonomy search returned no selectable node code');
  await taxonomyInput.fill(suggestion);
  await taxonomyInput.press('Tab');
  evidence.assert((await taxonomyInput.getAttribute('title') || '').includes(suggestion),
    'Selected taxonomy suggestion is not explained with title metadata');

  const comparison = solutionCard.locator('.product-comparison');
  await comparison.waitFor({ state: 'visible', timeout: 30_000 });
  await comparison.locator('summary').click();
  evidence.assert(await comparison.locator('tbody tr').count() >= 1,
    'Product candidates are not available as a comparable table');
  await evidence.axeState('portfolio-guided-decisions', '#portfolioMain');
  await evidence.saveState('portfolio-guided-decisions', '#portfolioMain');
  evidence.passed('taxonomy picker and comparable product alternatives');
}
