import { createHash } from 'node:crypto';
import { execFile } from 'node:child_process';
import {
  copyFile,
  mkdir,
  mkdtemp,
  readFile,
  stat,
  writeFile
} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import process from 'node:process';
import { promisify } from 'node:util';
import { pathToFileURL } from 'node:url';

import { chromium } from '@playwright/test';

const execFileAsync = promisify(execFile);
const baseUrl = (process.env.TAXONOMY_BASE_URL || 'http://127.0.0.1:8080')
  .replace(/\/+$/, '');
const username = process.env.TAXONOMY_UI_USERNAME || 'admin';
const password = process.env.TAXONOMY_UI_PASSWORD || 'admin';
const outputDir = path.resolve(
  process.env.TAXONOMY_UI_OUTPUT_DIR
    || 'target/ui-verification/document-template-report');
const docsImageDir = process.env.TAXONOMY_DOCS_IMAGE_DIR
  ? path.resolve(process.env.TAXONOMY_DOCS_IMAGE_DIR)
  : null;
const renderPreview =
  String(process.env.TAXONOMY_RENDER_DOCX_PREVIEW || 'false') === 'true';
const templateId = 'decision-rationale-report';
const docxMediaType =
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document';
const expectedReportFilename = 'taxonomy-decision-rationale-report.docx';
const hospitalRequirement =
  'Ein kommunales Krankenhaus benötigt eine integrierte Kommunikations- und ' +
  'Koordinationsplattform für Pflege, ärztlichen Dienst, Notaufnahme, Radiologie ' +
  'und Labor. Die Lösung muss sicheren Echtzeit-Sprach- und Datenaustausch, ' +
  'strukturierte Patientenübergaben, gemeinsame klinische Aufgabenlisten, ' +
  'rollenbasierte Zugriffe, revisionssichere Protokollierung und einen ' +
  'ausfallsicheren Betrieb ermöglichen.';

await mkdir(outputDir, { recursive: true });
const managementScreenshot = path.join(
  outputDir, 'document-template-management.png');
const analysisScreenshot = path.join(
  outputDir, 'hospital-requirement-analysis.png');
const graphScreenshot = path.join(
  outputDir, 'hospital-requirement-architecture-graph.png');
const reportPath = path.join(outputDir, expectedReportFilename);
const reportScreenshot = path.join(
  outputDir, 'decision-rationale-template-test-report.png');
const reportTextPath = path.join(
  outputDir, 'taxonomy-decision-rationale-report.txt');

const browser = await chromium.launch({ headless: true });
const context = await browser.newContext({
  viewport: { width: 1440, height: 1000 },
  acceptDownloads: true,
  reducedMotion: 'reduce'
});
const page = await context.newPage();

try {
  await login(page);
  await waitForSeededTemplate(context);

  const templateEvidence = await verifyTemplateManagement(page);
  const analysisEvidence = await evaluateHospitalRequirement(page);
  const reportRequest = await currentReportRequest(page);
  const reportModel = await verifyDecisionModel(context, reportRequest);
  const downloadEvidence = await downloadDecisionReport(page, reportPath);

  let renderingEvidence = null;
  if (renderPreview) {
    renderingEvidence = await renderAndInspectDownloadedReport(
      reportPath,
      reportScreenshot,
      reportTextPath,
      reportModel);
  }

  const reportBytes = await readFile(reportPath);
  const evidence = {
    schemaVersion: 2,
    capturedAt: new Date().toISOString(),
    baseUrl,
    templateId,
    requirement: hospitalRequirement,
    template: templateEvidence,
    analysis: analysisEvidence,
    reportModel: {
      status: reportModel.status,
      chapterCount: reportModel.chapters.length,
      leadingLeafCount: reportModel.leadingLeaves.length,
      leadingLeaf: reportModel.executiveSummary?.leadingLeaf || null,
      completenessPercent: reportModel.metadata?.completenessPercent,
      evaluatedNodeCount: reportModel.metadata?.evaluatedNodeCount,
      positiveNodeCount: reportModel.metadata?.positiveNodeCount,
      warningCount: reportModel.warnings.length
    },
    download: {
      ...downloadEvidence,
      downloadedBytes: reportBytes.length,
      downloadedSha256: createHash('sha256')
        .update(reportBytes)
        .digest('hex')
    },
    rendering: renderingEvidence,
    screenshots: {
      management: path.basename(managementScreenshot),
      analysis: path.basename(analysisScreenshot),
      architectureGraph: path.basename(graphScreenshot),
      report: renderPreview ? path.basename(reportScreenshot) : null
    }
  };
  await writeFile(
    path.join(outputDir, 'report-download-evidence.json'),
    `${JSON.stringify(evidence, null, 2)}\n`,
    'utf8');

  if (docsImageDir) {
    if (!renderPreview) {
      throw new Error(
        'TAXONOMY_DOCS_IMAGE_DIR requires TAXONOMY_RENDER_DOCX_PREVIEW=true');
    }
    await mkdir(docsImageDir, { recursive: true });
    for (const screenshot of [
      managementScreenshot,
      analysisScreenshot,
      graphScreenshot,
      reportScreenshot
    ]) {
      await copyFile(
        screenshot,
        path.join(docsImageDir, path.basename(screenshot)));
    }
  }

  console.log(JSON.stringify(evidence, null, 2));
} finally {
  await context.close();
  await browser.close();
}

async function login(page) {
  await page.goto(`${baseUrl}/login`, { waitUntil: 'domcontentloaded' });
  await page.locator('input[name="username"]').fill(username);
  await page.locator('input[name="password"]').fill(password);
  await Promise.all([
    page.waitForURL(url => !url.pathname.endsWith('/login'), {
      timeout: 30_000
    }),
    page.keyboard.press('Enter')
  ]);
}

async function waitForSeededTemplate(context) {
  const deadline = Date.now() + 120_000;
  let lastStatus = null;
  let lastBody = '';
  while (Date.now() < deadline) {
    const response = await context.request.get(
      `${baseUrl}/api/admin/document-templates`, {
        failOnStatusCode: false
      });
    lastStatus = response.status();
    lastBody = await response.text();
    if (response.ok()) {
      const templates = JSON.parse(lastBody);
      if (Array.isArray(templates)
          && templates.some(item => item.templateId === templateId)) {
        return;
      }
    }
    await new Promise(resolve => setTimeout(resolve, 2_000));
  }
  throw new Error(
    `Template ${templateId} was not seeded in time; `
      + `last response ${lastStatus}: ${lastBody.slice(0, 500)}`);
}

async function verifyTemplateManagement(page) {
  await page.goto(`${baseUrl}/admin/document-templates?lang=de`, {
    waitUntil: 'networkidle'
  });
  const row = page.locator(`tr[data-template-id="${templateId}"]`);
  await row.waitFor({ state: 'visible', timeout: 60_000 });

  const editLink = row.locator('a[href^="ms-word:ofe|u|"]').first();
  await editLink.waitFor({ state: 'visible' });
  const wordEditUri = await editLink.getAttribute('href');
  if (!wordEditUri?.includes(
    `/dav/templates/${templateId}.dotx`)) {
    throw new Error(`Unexpected Word edit URI: ${wordEditUri}`);
  }

  const webDavAddress = row.locator('code').filter({
    hasText: `/dav/templates/${templateId}.dotx`
  }).first();
  await webDavAddress.waitFor({ state: 'visible' });
  const webDavUrl = (await webDavAddress.textContent())?.trim();
  if (!webDavUrl?.startsWith('http')) {
    throw new Error(`WebDAV address is not visible: ${webDavUrl}`);
  }

  const commit = (await row.locator('td').nth(1).locator('code')
    .first().textContent())?.trim() || null;

  await page.screenshot({
    path: managementScreenshot,
    fullPage: true
  });
  return {
    commitAbbreviation: commit,
    managementUrl: page.url(),
    wordEditUri,
    webDavUrl
  };
}

async function evaluateHospitalRequirement(page) {
  await page.goto(`${baseUrl}/?lang=de`, { waitUntil: 'domcontentloaded' });
  await page.locator('#businessText').waitFor({
    state: 'visible',
    timeout: 60_000
  });
  await page.waitForFunction(() => {
    const state = window.TaxonomyState;
    return Array.isArray(state?.taxonomyData)
      && state.taxonomyData.length > 0
      && Boolean(document.querySelector('#taxonomyTree[data-view-rendered]'));
  }, null, { timeout: 180_000 });
  await page.waitForFunction(() => window.TaxonomyI18n?.isLoaded?.() === true,
    null, { timeout: 30_000 });

  const interactive = page.locator('#interactiveMode');
  if (await interactive.isChecked()) {
    await interactive.uncheck();
  }
  const architectureView = page.locator('#includeArchitectureView');
  if (!(await architectureView.isChecked())) {
    await architectureView.check();
  }

  // Select a non-list view so the normal all-at-once /api/analyze path is used.
  await page.locator('#viewDecision').click();
  await page.locator('#businessText').fill(hospitalRequirement);

  const [analysisResponse] = await Promise.all([
    page.waitForResponse(response => {
      const url = new URL(response.url());
      return url.pathname.endsWith('/api/analyze')
        && response.request().method() === 'POST';
    }, { timeout: 180_000 }),
    page.locator('#analyzeBtn').click()
  ]);
  if (analysisResponse.status() !== 200) {
    throw new Error(
      `Hospital requirement analysis failed with HTTP ${analysisResponse.status()}: `
        + (await analysisResponse.text()).slice(0, 1_000));
  }
  const analysis = await analysisResponse.json();
  if (analysis.status !== 'SUCCESS') {
    throw new Error(`Mock analysis did not complete successfully: ${analysis.status}`);
  }

  const scores = analysis.scores || {};
  const reasons = analysis.reasons || {};
  const positiveEntries = Object.entries(scores)
    .filter(([, value]) => Number(value) > 0)
    .sort((left, right) => Number(right[1]) - Number(left[1]));
  if (positiveEntries.length < 3) {
    throw new Error(
      `Mock hospital analysis returned only ${positiveEntries.length} positive scores`);
  }

  const architecture = analysis.architectureView || {};
  const architectureElements = architecture.includedElements || [];
  const architectureRelationships = architecture.includedRelationships || [];
  if (architectureElements.length < 3) {
    throw new Error(
      `Architecture view contains only ${architectureElements.length} elements`);
  }
  if (architectureRelationships.length < 1) {
    throw new Error('Architecture view contains no relationships');
  }

  await page.waitForFunction(() =>
    window.TaxonomyState?.lastAnalysisStatus === 'SUCCESS',
  null, { timeout: 60_000 });
  await page.locator('#taxonomyTree[data-view-rendered="summary"]').waitFor({
    state: 'visible',
    timeout: 60_000
  });
  await page.screenshot({ path: analysisScreenshot, fullPage: true });

  await page.locator('a[data-page="architecture"]').click();
  await page.locator('#architectureViewPanel').waitFor({
    state: 'visible',
    timeout: 60_000
  });
  const graph = page.locator('#impactGraphView svg').first();
  await graph.waitFor({ state: 'visible', timeout: 60_000 });
  await page.waitForTimeout(1_200);
  await page.locator('#impactGraphView').screenshot({ path: graphScreenshot });

  await page.locator('a[data-page="analyze"]').click();
  await page.locator('#exportDecisionReportDocx').waitFor({
    state: 'visible',
    timeout: 30_000
  });

  return {
    status: analysis.status,
    provider: analysis.provider || null,
    evaluatedScoreCount: Object.keys(scores).length,
    positiveScoreCount: positiveEntries.length,
    suppliedReasonCount: Object.keys(reasons).length,
    leadingScores: Object.fromEntries(positiveEntries.slice(0, 12)),
    architectureElementCount: architectureElements.length,
    architectureRelationshipCount: architectureRelationships.length,
    architectureAnchorCount: (architecture.anchors || []).length,
    summaryRendered: true,
    impactGraphRendered: true
  };
}

async function currentReportRequest(page) {
  const request = await page.evaluate(() => {
    const state = window.TaxonomyState;
    const text = document.getElementById('businessText')?.value.trim() || '';
    return {
      scores: state?.currentScores || {},
      reasons: state?.currentReasons || {},
      businessText: text,
      provider: state?.lastAnalysisProvider || 'MOCK',
      analysisStatus: state?.lastAnalysisStatus || 'UNKNOWN',
      discrepancies: state?.currentDiscrepancies || [],
      language: window.TaxonomyI18n?.getLocale?.()
        || document.documentElement.lang
        || 'de'
    };
  });
  if (request.businessText !== hospitalRequirement) {
    throw new Error('The report request lost or changed the hospital requirement');
  }
  return request;
}

async function verifyDecisionModel(context, reportRequest) {
  const response = await context.request.post(
    `${baseUrl}/api/decision-report/json`, {
      data: reportRequest,
      failOnStatusCode: false
    });
  const body = await response.text();
  if (!response.ok()) {
    throw new Error(
      `Decision report JSON verification failed with HTTP ${response.status()}: `
        + body.slice(0, 1_000));
  }
  const report = JSON.parse(body);
  if (!Array.isArray(report.chapters) || report.chapters.length < 1) {
    throw new Error('The evaluated requirement produced no decision chapters');
  }
  if (!Array.isArray(report.leadingLeaves) || report.leadingLeaves.length < 1) {
    throw new Error('The evaluated requirement produced no leading leaf candidate');
  }
  if (Number(report.metadata?.completenessPercent) !== 100) {
    throw new Error(
      `The deterministic mock analysis is incomplete: `
        + `${report.metadata?.completenessPercent}%`);
  }
  if (report.status === 'DRAFT_INCOMPLETE' || report.status === 'NO_RESULT') {
    throw new Error(`Unexpected decision report status: ${report.status}`);
  }
  return report;
}

async function downloadDecisionReport(page, reportPath) {
  const endpoint = `${baseUrl}/api/decision-report/docx`;
  const [download, reportResponse] = await Promise.all([
    page.waitForEvent('download', { timeout: 120_000 }),
    page.waitForResponse(response =>
      response.url() === endpoint
        && response.request().method() === 'POST',
    { timeout: 120_000 }),
    page.locator('#exportDecisionReportDocx').click()
  ]);
  if (reportResponse.status() !== 200) {
    throw new Error(
      `Decision report download failed with HTTP ${reportResponse.status()}: `
        + (await reportResponse.text()).slice(0, 1_000));
  }
  const downloadFailure = await download.failure();
  if (downloadFailure) {
    throw new Error(`Report download failed: ${downloadFailure}`);
  }

  const responseHeaders = reportResponse.headers();
  const responseContentType = String(
    responseHeaders['content-type'] || '').toLowerCase();
  if (!responseContentType.startsWith(docxMediaType)) {
    throw new Error(
      `Unexpected report Content-Type: ${responseContentType || '<missing>'}`);
  }
  const responseContentDisposition = String(
    responseHeaders['content-disposition'] || '');
  if (!responseContentDisposition.includes(expectedReportFilename)) {
    throw new Error(
      'Report Content-Disposition does not contain the expected filename: '
        + responseContentDisposition);
  }

  await download.saveAs(reportPath);
  const reportBytes = await readFile(reportPath);
  if (reportBytes.length < 10_000
      || reportBytes[0] !== 0x50
      || reportBytes[1] !== 0x4b) {
    throw new Error(
      `Downloaded report is not a plausible DOCX ZIP (${reportBytes.length} bytes)`);
  }
  if (download.suggestedFilename() !== expectedReportFilename) {
    throw new Error(
      `Unexpected downloaded filename: ${download.suggestedFilename()}`);
  }

  return {
    url: endpoint,
    responseContentType,
    responseContentDisposition,
    downloadedFilename: download.suggestedFilename()
  };
}

async function renderAndInspectDownloadedReport(
  docxPath,
  pngPath,
  textPath,
  reportModel) {
  const profile = await mkdtemp(
    path.join(os.tmpdir(), 'taxonomy-libreoffice-'));
  const profileUrl = pathToFileURL(profile).href;
  const office = process.env.LIBREOFFICE_EXECUTABLE || 'libreoffice';

  const officeArguments = [
    `-env:UserInstallation=${profileUrl}`,
    '--headless',
    '--convert-to', 'pdf',
    '--outdir', outputDir,
    docxPath
  ];
  try {
    const { stdout, stderr } = await execFileAsync(
      office,
      officeArguments,
      { timeout: 120_000, maxBuffer: 5 * 1024 * 1024 });
    if (stdout) process.stdout.write(stdout);
    if (stderr) process.stderr.write(stderr);
  } catch (error) {
    if (office === 'libreoffice' && error?.code === 'ENOENT') {
      await execFileAsync('soffice', officeArguments, {
        timeout: 120_000,
        maxBuffer: 5 * 1024 * 1024
      });
    } else {
      throw error;
    }
  }

  const pdfPath = path.join(
    outputDir,
    `${path.basename(docxPath, path.extname(docxPath))}.pdf`);
  const pdfStats = await stat(pdfPath);
  if (pdfStats.size < 10_000) {
    throw new Error(`Rendered PDF is unexpectedly small: ${pdfStats.size}`);
  }

  const { stdout: pdfInfo } = await execFileAsync('pdfinfo', [pdfPath], {
    timeout: 30_000,
    maxBuffer: 2 * 1024 * 1024
  });
  const pageMatch = /^Pages:\s+(\d+)$/m.exec(pdfInfo);
  const pageCount = pageMatch ? Number(pageMatch[1]) : 0;
  if (pageCount < 3) {
    throw new Error(
      `The evaluated decision report is unexpectedly short (${pageCount} pages)`);
  }

  await execFileAsync('pdftotext', ['-layout', pdfPath, textPath], {
    timeout: 30_000,
    maxBuffer: 5 * 1024 * 1024
  });
  const reportText = await readFile(textPath, 'utf8');
  assertReportText(reportText, reportModel);

  const screenshotPage = await findDecisionChapterPage(pdfPath, pageCount);
  const outputPrefix = pngPath.slice(0, -'.png'.length);
  await execFileAsync('pdftoppm', [
    '-png',
    '-f', String(screenshotPage),
    '-l', String(screenshotPage),
    '-singlefile',
    '-r', '144',
    pdfPath,
    outputPrefix
  ], {
    timeout: 120_000,
    maxBuffer: 5 * 1024 * 1024
  });
  const pngStats = await stat(pngPath);
  if (pngStats.size < 10_000) {
    throw new Error(`Rendered report screenshot is too small: ${pngStats.size}`);
  }

  return {
    pdfFilename: path.basename(pdfPath),
    pdfBytes: pdfStats.size,
    pageCount,
    screenshotPage,
    textFilename: path.basename(textPath),
    textCharacters: reportText.length,
    hospitalRequirementFound: true,
    decisionChapterFound: true,
    previewWarningAbsent: true
  };
}

function assertReportText(reportText, reportModel) {
  const normalized = reportText.replace(/\s+/g, ' ');
  for (const fragment of [
    'kommunales Krankenhaus',
    'Patientenübergaben'
  ]) {
    if (!normalized.toLocaleLowerCase('de-DE')
      .includes(fragment.toLocaleLowerCase('de-DE'))) {
      throw new Error(`Rendered report does not contain requirement fragment: ${fragment}`);
    }
  }
  if (/Preview only|no architecture decision was evaluated|template test report/i
    .test(normalized)) {
    throw new Error('Rendered report still contains synthetic preview-only content');
  }
  const leadingCode = reportModel.executiveSummary?.leadingLeaf?.code;
  if (!leadingCode || !normalized.includes(leadingCode)) {
    throw new Error(
      `Rendered report does not name its leading leaf: ${leadingCode || '<missing>'}`);
  }
  const percentMatches = normalized.match(/\b\d{1,3}\s*%/g) || [];
  if (percentMatches.length < 3) {
    throw new Error(
      `Rendered report contains only ${percentMatches.length} visible percentage values`);
  }
  if (!/(Entscheidung|Bewertung|Decision)/i.test(normalized)) {
    throw new Error('Rendered report contains no recognizable decision chapter text');
  }
}

async function findDecisionChapterPage(pdfPath, pageCount) {
  for (let pageNumber = 2; pageNumber <= pageCount; pageNumber += 1) {
    const { stdout } = await execFileAsync('pdftotext', [
      '-f', String(pageNumber),
      '-l', String(pageNumber),
      '-layout',
      pdfPath,
      '-'
    ], {
      timeout: 30_000,
      maxBuffer: 2 * 1024 * 1024
    });
    if (/(Entscheidung|Bewertung|Decision)/i.test(stdout)
        && /\b\d{1,3}\s*%/.test(stdout)) {
      return pageNumber;
    }
  }
  return Math.min(3, pageCount);
}
