function uniqueSuffix() {
  return `${Date.now().toString(36)}-${process.pid.toString(36)}`.toLowerCase();
}

async function csrfFetch(page, url, options = {}) {
  return page.evaluate(async ({ url, options }) => {
    const token = document.querySelector('meta[name="_csrf"]')?.content;
    const header = document.querySelector('meta[name="_csrf_header"]')?.content || 'X-CSRF-TOKEN';
    const headers = { Accept: 'application/json', ...(options.headers || {}) };
    if (token) headers[header] = token;
    const response = await fetch(url, { ...options, headers });
    return {
      status: response.status,
      headers: Object.fromEntries(response.headers.entries()),
      body: await response.text()
    };
  }, { url, options });
}

/** Browser acceptance for GUI-first Git collaboration and report exports. */
export async function runPortfolioGitReportWorkflows({ page, baseUrl, evidence }) {
  const projectId = await page.evaluate(() =>
    Number(localStorage.getItem('taxonomy.portfolio.projectId')));
  evidence.assert(projectId > 0, 'Git/report acceptance requires a selected project');

  await page.goto(`${baseUrl}/projects/${projectId}/versioning?lang=en`,
    { waitUntil: 'networkidle' });
  await page.locator('#versioningMain').waitFor({ state: 'visible', timeout: 30_000 });
  await page.locator('#dslPreview').waitFor({ state: 'visible' });
  evidence.assert((await page.locator('#dslPreview').innerText()).includes('portfolio'),
    'Versioning preview does not contain the projected portfolio TaxDSL');
  evidence.assert(await page.locator('#portfolioCounts .card').count() === 4,
    'Versioning preview lacks project, requirement, solution and product counts');

  const commitMessage = `Browser reviewed portfolio ${Date.now()}`;
  await page.locator('#commitMessage').fill(commitMessage);
  const commitPromise = page.waitForResponse(response =>
    response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/projects/git/commit',
  { timeout: 120_000 });
  await page.locator('#commitForm button[type="submit"]').click();
  const commitResponse = await commitPromise;
  evidence.assert(commitResponse.ok(),
    `Portfolio commit failed with HTTP ${commitResponse.status()}`);
  const commit = await commitResponse.json();
  evidence.assert(Boolean(commit.commitId), 'Portfolio commit did not return a commit ID');
  await page.locator('#headCommit').filter({ hasText: commit.commitId.slice(0, 12) })
    .waitFor({ state: 'visible', timeout: 30_000 });
  evidence.passed('GUI portfolio preview and authenticated commit');

  await page.locator('#previewMaterialize').click();
  await page.locator('#materializePreview dl').waitFor({ state: 'visible', timeout: 30_000 });
  evidence.assert(await page.locator('#materializePreview code').count() >= 1,
    'Materialization preview does not expose the exact target HEAD');
  evidence.passed('materialization preview with expected branch head');

  const variantName = `ui-merge-${uniqueSuffix()}`;
  const variant = await csrfFetch(page,
    `/api/context/variant?name=${encodeURIComponent(variantName)}`,
    { method: 'POST' });
  evidence.assert(variant.status === 200,
    `Creating a merge source branch failed with HTTP ${variant.status}`);
  const variantBody = JSON.parse(variant.body);
  evidence.assert(Boolean(variantBody.branch), 'Variant creation returned no branch');

  await page.locator('#refreshPreview').click();
  await page.locator('#activeBranch').filter({ hasText: variantBody.branch })
    .waitFor({ state: 'visible', timeout: 30_000 });
  await page.locator('#commitMessage').fill(`Variant portfolio commit ${variantName}`);
  const variantCommitPromise = page.waitForResponse(response =>
    response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/projects/git/commit',
  { timeout: 120_000 });
  await page.locator('#commitForm button[type="submit"]').click();
  evidence.assert((await variantCommitPromise).ok(), 'Variant portfolio commit failed');

  const targetOptions = await page.locator('#mergeTarget option').allTextContents();
  const targetBranch = targetOptions.find(branch => branch !== variantBody.branch);
  evidence.assert(Boolean(targetBranch), 'No distinct target branch is available for merge');
  await page.locator('#mergeSource').selectOption(variantBody.branch);
  await page.locator('#mergeTarget').selectOption(targetBranch);
  await page.locator('#mergeMessage').fill(`Merge ${variantBody.branch} into ${targetBranch}`);
  const mergePromise = page.waitForResponse(response =>
    response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/projects/git/merge',
  { timeout: 120_000 });
  await page.locator('#mergeForm button[type="submit"]').click();
  const mergeResponse = await mergePromise;
  evidence.assert(mergeResponse.ok(),
    `Portfolio merge failed with HTTP ${mergeResponse.status()}`);
  const merge = await mergeResponse.json();
  evidence.assert(Boolean(merge.mergeCommitId), 'Merge response lacks merge commit ID');
  await page.locator('#mergeResult .alert-success')
    .waitFor({ state: 'visible', timeout: 30_000 });
  await evidence.axeState('portfolio-versioning', '#versioningMain');
  await evidence.saveState('portfolio-versioning', '#versioningMain');
  evidence.passed('GUI branch creation, commit and merge');

  await page.goto(`${baseUrl}/projects/${projectId}/reports?lang=en`,
    { waitUntil: 'networkidle' });
  await page.locator('#reportsMain').waitFor({ state: 'visible', timeout: 30_000 });
  await page.locator('#previewReport').click();
  await page.locator('#reportPreviewFrame').waitFor({ state: 'visible', timeout: 30_000 });
  await page.waitForFunction(() => {
    const frame = document.querySelector('#reportPreviewFrame');
    return Boolean(frame && frame.contentDocument
      && frame.contentDocument.body.textContent.includes('Project portfolio report'));
  }, { timeout: 30_000 });
  evidence.assert((await page.locator('#previewBaseline').innerText()).length > 0,
    'Report preview does not disclose its current baseline');

  const reportContracts = await page.evaluate(async projectIdValue => {
    async function read(format, extra = '') {
      const response = await fetch(
        `/api/projects/${projectIdValue}/reports/${format}?${extra}`,
        { headers: { Accept: '*/*' } });
      const bytes = new Uint8Array(await response.arrayBuffer());
      return {
        status: response.status,
        type: response.headers.get('content-type'),
        disposition: response.headers.get('content-disposition'),
        prefix: Array.from(bytes.slice(0, 8)),
        text: new TextDecoder().decode(bytes.slice(0, 5000))
      };
    }
    return {
      html: await read('html'),
      markdown: await read('markdown'),
      json: await read('json'),
      csv: await read('csv', 'matrix=solutions'),
      docx: await read('docx')
    };
  }, projectId);
  evidence.assert(reportContracts.html.status === 200
    && reportContracts.html.text.includes('<!doctype html>'),
  'HTML portfolio report contract failed');
  evidence.assert(reportContracts.markdown.status === 200
    && reportContracts.markdown.text.includes('Project portfolio report'),
  'Markdown portfolio report contract failed');
  evidence.assert(reportContracts.json.status === 200
    && reportContracts.json.text.includes('"reportType"'),
  'JSON portfolio report contract failed');
  evidence.assert(reportContracts.csv.status === 200
    && reportContracts.csv.text.startsWith('row'),
  'CSV portfolio matrix report contract failed');
  evidence.assert(reportContracts.docx.status === 200
    && reportContracts.docx.prefix[0] === 80
    && reportContracts.docx.prefix[1] === 75,
  'DOCX portfolio report is not a valid ZIP-based Word package');
  await evidence.axeState('portfolio-reports', '#reportsMain');
  await evidence.saveState('portfolio-reports', '#reportsMain');
  evidence.passed('GUI HTML preview and project report exports');
}
