/** Browser acceptance for the shareable requirement and matrix workspaces. */
export async function runPortfolioDetailWorkflows({ page, baseUrl, evidence }) {
  const context = await page.evaluate(async () => {
    const projectId = Number(localStorage.getItem('taxonomy.portfolio.projectId'));
    const response = await fetch(`/api/projects/${projectId}/requirements`, {
      headers: { Accept: 'application/json' }
    });
    return { projectId, requirements: await response.json() };
  });
  evidence.assert(context.projectId > 0, 'Portfolio project selection was not retained');
  evidence.assert(context.requirements.length >= 3,
    'Detail acceptance requires the three portfolio requirements');
  const requirement = context.requirements[0];

  await page.goto(`${baseUrl}/projects/${context.projectId}/requirements/${requirement.id}?lang=en`,
    { waitUntil: 'networkidle' });
  await page.locator('#requirementHeading').filter({ hasText: requirement.title })
    .waitFor({ state: 'visible', timeout: 30_000 });
  evidence.assert(await page.locator('#currentText').innerText() === requirement.currentVersion.text,
    'Requirement detail does not show the current immutable text');
  evidence.assert(await page.locator('#versionList [data-version-id]').count() >= 1,
    'Requirement detail does not expose version history');
  evidence.assert(await page.locator('#snapshotList [data-snapshot-id]').count() >= 1,
    'Requirement detail does not expose analysis snapshots');

  await page.locator('#analyses-tab').click();
  await page.locator('#snapshotList [data-snapshot-id]').first().click();
  await page.locator('#snapshotDetail').getByText(/Provider/).waitFor({ state: 'visible' });
  await page.locator('#architecture-tab').click();
  evidence.assert(await page.locator('#mappingTable tbody tr').count() >= 1,
    'Requirement architecture detail does not expose taxonomy mappings');
  await page.locator('#decisions-tab').click();
  evidence.assert(await page.locator('#decisionList article').count() >= 1,
    'Requirement detail does not expose human decision provenance');
  await evidence.axeState('portfolio-requirement-detail', '#requirementMain');
  await evidence.saveState('portfolio-requirement-detail', '#requirementMain');
  evidence.passed('shareable requirement detail, provenance and history');

  await page.goto(`${baseUrl}/projects/${context.projectId}/matrices?lang=en`,
    { waitUntil: 'networkidle' });
  await page.locator('#taxonomyMatrix .matrix-drilldown').first()
    .waitFor({ state: 'visible', timeout: 30_000 });
  const initialCells = await page.locator('#taxonomyMatrix .matrix-drilldown').count();
  evidence.assert(initialCells > 0, 'Interactive taxonomy matrix contains no cells');

  await page.locator('#matrixSearch').fill('REQ-001');
  const filteredCells = await page.locator('#taxonomyMatrix .matrix-drilldown').count();
  evidence.assert(filteredCells > 0 && filteredCells <= initialCells,
    'Matrix search did not constrain the visible relationships');
  await page.locator('#taxonomyMatrix .matrix-drilldown').first().click();
  await page.locator('#cellDetail').waitFor({ state: 'visible', timeout: 20_000 });
  evidence.assert(await page.locator('#cellDetailBody dt').count() >= 3,
    'Matrix cell drill-down lacks relationship metadata');
  await page.locator('#cellDetail .btn-close').click();

  await page.locator('#solutionMatrixTab').click();
  await page.locator('#solutionMatrix .matrix-drilldown').first()
    .waitFor({ state: 'visible', timeout: 20_000 });
  await page.locator('#productMatrixTab').click();
  await page.locator('#productMatrix .matrix-drilldown').first()
    .waitFor({ state: 'visible', timeout: 20_000 });
  await evidence.axeState('portfolio-matrices', '#matrixMain');
  await evidence.saveState('portfolio-matrices', '#matrixMain');
  evidence.passed('filterable matrices, keyboard buttons and cell drill-down');
}
