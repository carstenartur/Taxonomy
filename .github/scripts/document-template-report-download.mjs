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
const expectedReportFilename = 'taxonomy-decision-rationale-report.docx';
const docxMediaType =
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document';
const hospitalRequirement =
  'Ein kommunales Krankenhaus benötigt eine integrierte Kommunikations- und ' +
  'Koordinationsplattform für Pflege, ärztlichen Dienst, Notaufnahme, Radiologie ' +
  'und Labor. Die Lösung muss sicheren Echtzeit-Sprach- und Datenaustausch, ' +
  'strukturierte Patientenübergaben, gemeinsame klinische Aufgabenlisten, ' +
  'rollenbasierte Zugriffe, revisionssichere Protokollierung und einen ' +
  'ausfallsicheren Betrieb ermöglichen.';

await mkdir(outputDir, { recursive: true });
const files = {
  management: path.join(outputDir, 'document-template-management.png'),
  onboarding: path.join(outputDir, 'hospital-requirement-onboarding.png'),
  analysis: path.join(outputDir, 'hospital-requirement-analysis.png'),
  graph: path.join(outputDir, 'hospital-requirement-architecture-graph.png'),
  docx: path.join(outputDir, expectedReportFilename),
  report: path.join(outputDir, 'decision-rationale-template-test-report.png'),
  text: path.join(outputDir, 'taxonomy-decision-rationale-report.txt')
};

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
  const template = await verifyTemplateManagement(page);
  const analysis = await evaluateHospitalRequirement(page);
  const request = await currentReportRequest(page);
  const reportModel = await verifyDecisionModel(page, request);
  const download = await downloadDecisionReport(page, files.docx);
  const rendering = renderPreview
    ? await renderAndInspectReport(files.docx, files.report, files.text, reportModel)
    : null;
  const reportBytes = await readFile(files.docx);
  const productScoreBrowser = await verifyProductScoreBrowser(page);

  const evidence = {
    schemaVersion: 2,
    capturedAt: new Date().toISOString(),
    baseUrl,
    templateId,
    requirement: hospitalRequirement,
    template,
    analysis,
    productScoreBrowser,
    reportModel: {
      status: reportModel.status,
      chapterCount: reportModel.chapters.length,
      leadingLeafCount: reportModel.leadingLeaves.length,
      leadingLeaf: reportModel.executiveSummary?.leadingLeaf || null,
      completenessPercent: reportModel.metadata?.completenessPercent,
      evaluatedNodeCount: reportModel.metadata?.evaluatedNodeCount,
      positiveNodeCount: reportModel.metadata?.positiveNodeCount,
      warningCount: reportModel.warnings.length,
      productCoverageGapCount: reportModel.productCoverageGaps.length
    },
    download: {
      ...download,
      downloadedBytes: reportBytes.length,
      downloadedSha256: createHash('sha256').update(reportBytes).digest('hex')
    },
    rendering,
    screenshots: {
      management: path.basename(files.management),
      onboarding: path.basename(files.onboarding),
      analysis: path.basename(files.analysis),
      architectureGraph: path.basename(files.graph),
      report: renderPreview ? path.basename(files.report) : null
    }
  };
  await writeFile(
    path.join(outputDir, 'report-download-evidence.json'),
    `${JSON.stringify(evidence, null, 2)}\n`,
    'utf8');
  await copyDocumentationEvidence();
  console.log(JSON.stringify(evidence, null, 2));
} finally {
  await context.close();
  await browser.close();
}

async function verifyProductScoreBrowser(target) {
  // Use the application's actual loaded catalogue and renderer. This checks raw local batches
  // without calling a provider, replacing HTTP responses, or constructing a test taxonomy.
  return target.evaluate(() => {
    const state = window.TaxonomyState;
    const scoring = window.TaxonomyScoring;
    const nodes = new Map();
    function visit(tree) {
      for (const node of tree || []) {
        nodes.set(node.code, node);
        visit(node.children);
      }
    }
    visit(state.taxonomyData);
    const product = [...nodes.values()].find(node => node.analysisRole === 'PRODUCT'
      && nodes.has(node.parentCode));
    if (!product) throw new Error('The real catalogue contains no parented product.');
    const family = nodes.get(product.parentCode);
    const keys = ['currentScores', 'currentRawScores', 'currentEffectiveScores',
      'currentScoreDetails', 'currentProductSuitabilityScores', 'scoreSemanticsVersion',
      'currentScoreSemanticsWarnings', 'currentView'];
    const original = Object.fromEntries(keys.map(key => [key, state[key]]));
    const require = (condition, message) => {
      if (!condition) throw new Error(`Product score browser regression: ${message}`);
    };
    const visualEvidence = [];
    function verifyVisuals(stage) {
      const views = window.TaxonomyViews;
      const label = scoring.describeScore(product.code, state.currentScores[product.code]).label;
      // Render an actual catalogue subtree so the product is visible before depth-based collapse.
      const tree = [family];
      require(views.buildMermaidTreeExport(tree, state.currentScores).includes(label),
        `${stage}: Mermaid product label`);
      const svg = views.buildExportSVG(tree, state.currentScores);
      require([...svg.querySelectorAll('text')].some(node => node.textContent === label),
        `${stage}: SVG product label`);
      const container = document.createElement('div');
      container.style.width = '1500px';
      document.body.appendChild(container);
      const drawn = [];
      const originalFillText = CanvasRenderingContext2D.prototype.fillText;
      try {
        views.renderDecisionMap(container, tree, state.currentScores);
        require([...container.querySelectorAll('.dm-label')].some(node =>
          node.textContent.includes(product.code) && node.textContent.includes(label)),
        `${stage}: decision map retains product evidence`);
        if (state.currentScores[product.code] === 0 && state.currentScores[family.code] === 0) {
          require([...container.querySelectorAll('.dm-rank')].every(node => !node.textContent),
            `${stage}: zero relevance must not receive a positive rank`);
        }
        CanvasRenderingContext2D.prototype.fillText = function (text, ...args) {
          if (this.canvas.parentNode === container) drawn.push(String(text));
          return originalFillText.call(this, text, ...args);
        };
        views.renderTreeCanvas(container, tree, state.currentScores);
        require(drawn.includes(label), `${stage}: canvas product badge`);
        visualEvidence.push({ stage, label, effective: state.currentScores[product.code] });
      } finally {
        CanvasRenderingContext2D.prototype.fillText = originalFillText;
        container._taxObserver?.disconnect();
        container.remove();
      }
    }
    try {
      scoring.applyLocalRawScores({ [product.code]: 80 }, true);
      require(state.currentScores[product.code] === 0, 'missing family must fail closed');
      require(state.currentScoreSemanticsWarnings.length === 1, 'missing family warning');
      verifyVisuals('missing family');
      scoring.applyLocalRawScores({ [family.code]: 0 });
      require(state.currentScoreDetails[product.code].parentScore === 0, 'explicit family zero');
      require(state.currentScoreSemanticsWarnings.length === 0, 'resolved family warning');
      verifyVisuals('explicit family zero');
      scoring.applyLocalRawScores({ [family.code]: 40 });
      require(state.currentScores[product.code] === 32, 'delayed family correction');
      scoring.applyLocalRawScores({ [product.code]: 80 });
      require(state.currentRawScores[product.code] === 80, 'raw suitability retention');
      require(state.currentScores[product.code] === 32, 'no repeated weighting');
      verifyVisuals('positive effective relevance');
      const rendered = [];
      for (const view of ['list', 'tabs', 'list']) {
        state.currentView = view;
        window.TaxonomyBrowse.renderView(state.taxonomyData, state.currentScores);
        const node = document.querySelector('.tax-node[data-code="' + CSS.escape(product.code) + '"]');
        const badge = node?.querySelector(':scope > .tax-node-header .tax-pct');
        require(/80%.*32\/100/.test(badge?.textContent || ''), `${view}: suitability/effective badge`);
        require(/Suitability|Eignung/.test(badge.textContent), `${view}: typed label`);
        require(/80.*32/.test(node.getAttribute('aria-label') || ''), `${view}: accessible values`);
        rendered.push({ view, badge: badge.textContent, ariaLabel: node.getAttribute('aria-label') });
      }
      require(/80%.*32\/100/.test(window.TaxonomyViews.buildMermaidTreeExport(
        state.taxonomyData, state.currentScores)), 'typed exported tree');
      const evidence = { productCode: product.code, familyCode: family.code,
        raw: state.currentRawScores[product.code], effective: state.currentScores[product.code],
        detail: state.currentScoreDetails[product.code], rendered, visualEvidence };
      scoring.applyLocalRawScores({ [product.code]: 0 });
      verifyVisuals('zero suitability');
      scoring.applyLocalRawScores({ [family.code]: 40 }, true);
      require(!Object.prototype.hasOwnProperty.call(state.currentProductSuitabilityScores, product.code),
        'reset stale suitability');
      return evidence;
    } finally {
      Object.assign(state, original);
      window.TaxonomyBrowse.renderView(state.taxonomyData, state.currentScores);
    }
  });
}

async function login(target) {
  await target.goto(`${baseUrl}/login`, { waitUntil: 'domcontentloaded' });
  await target.locator('input[name="username"]').fill(username);
  await target.locator('input[name="password"]').fill(password);
  await Promise.all([
    target.waitForURL(url => !url.pathname.endsWith('/login'), {
      timeout: 30_000
    }),
    target.keyboard.press('Enter')
  ]);
}

async function waitForSeededTemplate(requestContext) {
  const deadline = Date.now() + 120_000;
  let lastStatus = null;
  let lastBody = '';
  while (Date.now() < deadline) {
    const response = await requestContext.request.get(
      `${baseUrl}/api/admin/document-templates`, { failOnStatusCode: false });
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

async function verifyTemplateManagement(target) {
  await target.goto(`${baseUrl}/admin/document-templates?lang=de`, {
    waitUntil: 'networkidle'
  });
  const row = target.locator(`tr[data-template-id="${templateId}"]`);
  await row.waitFor({ state: 'visible', timeout: 60_000 });
  const editLink = row.locator('a[href^="ms-word:ofe|u|"]').first();
  await editLink.waitFor({ state: 'visible' });
  const wordEditUri = await editLink.getAttribute('href');
  if (!wordEditUri?.includes(`/dav/templates/${templateId}.dotx`)) {
    throw new Error(`Unexpected Word edit URI: ${wordEditUri}`);
  }
  const webDav = row.locator('code').filter({
    hasText: `/dav/templates/${templateId}.dotx`
  }).first();
  const webDavUrl = (await webDav.textContent())?.trim();
  if (!webDavUrl?.startsWith('http')) {
    throw new Error(`WebDAV address is not visible: ${webDavUrl}`);
  }
  const commit = (await row.locator('td').nth(1).locator('code')
    .first().textContent())?.trim() || null;
  await target.screenshot({ path: files.management, fullPage: true });
  return {
    commitAbbreviation: commit,
    managementUrl: target.url(),
    wordEditUri,
    webDavUrl
  };
}

async function evaluateHospitalRequirement(target) {
  await target.goto(`${baseUrl}/?lang=de`, { waitUntil: 'domcontentloaded' });
  await target.locator('#businessText').waitFor({
    state: 'visible',
    timeout: 60_000
  });
  await target.waitForFunction(() => {
    const state = window.TaxonomyState;
    return Array.isArray(state?.taxonomyData)
      && state.taxonomyData.length > 0
      && Boolean(document.querySelector('#taxonomyTree[data-view-rendered]'));
  }, null, { timeout: 180_000 });
  const onboarding = await completeOnboarding(target);

  const interactive = target.locator('#interactiveMode');
  if (await interactive.isChecked()) await interactive.uncheck();
  const architectureView = target.locator('#includeArchitectureView');
  if (!(await architectureView.isChecked())) await architectureView.check();
  await target.locator('#viewDecision').click();
  await target.locator('#businessText').fill(hospitalRequirement);

  const [response] = await Promise.all([
    target.waitForResponse(candidate => {
      const url = new URL(candidate.url());
      return url.pathname.endsWith('/api/analyze')
        && candidate.request().method() === 'POST';
    }, { timeout: 180_000 }),
    target.locator('#analyzeBtn').click()
  ]);
  const responseText = await response.text();
  if (response.status() !== 200) {
    throw new Error(
      `Hospital requirement analysis failed with HTTP ${response.status()}: `
        + responseText.slice(0, 1_000));
  }
  const result = JSON.parse(responseText);
  if (result.status !== 'SUCCESS') {
    throw new Error(`Mock analysis did not complete successfully: ${result.status}`);
  }

  const scores = result.scores || {};
  const reasons = result.reasons || {};
  const positive = Object.entries(scores)
    .filter(([, value]) => Number(value) > 0)
    .sort((left, right) => Number(right[1]) - Number(left[1]));
  if (positive.length < 3) {
    throw new Error(`Mock analysis returned only ${positive.length} positive scores`);
  }
  const architecture = result.architectureView || {};
  const elements = architecture.includedElements || [];
  const relationships = architecture.includedRelationships || [];
  if (elements.length < 3) {
    throw new Error(`Architecture view contains only ${elements.length} elements`);
  }
  if (relationships.length < 1) {
    throw new Error('Architecture view contains no relationships');
  }

  await target.waitForFunction(() =>
    window.TaxonomyState?.lastAnalysisStatus === 'SUCCESS',
  null, { timeout: 60_000 });
  await target.waitForTimeout(1_000);
  await target.screenshot({ path: files.analysis, fullPage: true });

  await target.locator('a[data-page="architecture"]').click();
  await target.locator('#architectureViewPanel').waitFor({
    state: 'visible',
    timeout: 60_000
  });
  const graph = target.locator('#impactGraphView svg').first();
  await graph.waitFor({ state: 'visible', timeout: 60_000 });
  await target.waitForTimeout(1_200);
  await target.locator('#impactGraphView').screenshot({ path: files.graph });

  await target.locator('a[data-page="analyze"]').click();
  await target.locator('#exportDecisionReportDocx').waitFor({
    state: 'visible',
    timeout: 30_000
  });
  return {
    status: result.status,
    provider: result.provider || null,
    onboarding,
    evaluatedScoreCount: Object.keys(scores).length,
    positiveScoreCount: positive.length,
    productCoverageGapCount: (result.productCoverageGaps || []).length,
    suppliedReasonCount: Object.keys(reasons).length,
    leadingScores: Object.fromEntries(positive.slice(0, 12)),
    architectureElementCount: elements.length,
    architectureRelationshipCount: relationships.length,
    architectureAnchorCount: (architecture.anchors || []).length,
    summaryRendered: true,
    impactGraphRendered: true
  };
}

async function completeOnboarding(target) {
  const dialog = target.locator('#onboardingOverlay');
  await dialog.waitFor({ state: 'visible', timeout: 30_000 });
  const title = (await dialog.locator('#onboardingTitle').textContent())?.trim();
  const intro = (await dialog.locator('#onboardingIntro').textContent())?.trim();
  const stepCount = await dialog.locator('.step-item').count();
  if (!title || !intro || stepCount !== 3) {
    throw new Error(
      `Onboarding is incomplete (title=${Boolean(title)}, `
        + `intro=${Boolean(intro)}, steps=${stepCount})`);
  }
  const dismiss = target.locator('#onboardingDismiss');
  await dismiss.waitFor({ state: 'visible', timeout: 10_000 });
  await target.waitForFunction(() =>
    document.activeElement?.id === 'onboardingDismiss',
  null, { timeout: 10_000 });
  const overflow = await dialog.evaluate(element => ({
    horizontal: element.scrollWidth > element.clientWidth + 1,
    vertical: element.scrollHeight > element.clientHeight + 1
  }));
  if (overflow.horizontal) {
    throw new Error('Onboarding requires horizontal scrolling at 1440×1000');
  }
  await dialog.screenshot({ path: files.onboarding });
  await dismiss.click();
  await dialog.waitFor({ state: 'detached', timeout: 30_000 });
  return {
    title,
    intro,
    stepCount,
    dismissInitiallyFocused: true,
    horizontalOverflow: overflow.horizontal,
    verticalOverflow: overflow.vertical
  };
}

async function currentReportRequest(target) {
  const request = await target.evaluate(() => {
    const state = window.TaxonomyState;
    return {
      scores: state?.currentScores || {},
      rawScores: state?.currentRawScores || state?.currentScores || {},
      effectiveScores: state?.currentEffectiveScores || state?.currentScores || {},
      scoreDetails: state?.currentScoreDetails || {},
      productSuitabilityScores: state?.currentProductSuitabilityScores || {},
      scoreSemanticsVersion: state?.scoreSemanticsVersion || 0,
      reasons: state?.currentReasons || {},
      businessText: document.getElementById('businessText')?.value.trim() || '',
      provider: state?.lastAnalysisProvider || 'MOCK',
      analysisStatus: state?.lastAnalysisStatus || 'UNKNOWN',
      discrepancies: state?.currentDiscrepancies || [],
      productCoverageGaps: state?.currentProductCoverageGaps || [],
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

async function verifyDecisionModel(target, request) {
  const result = await target.evaluate(async ({ url, payload }) => {
    const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content
      || 'X-CSRF-TOKEN';
    const headers = {
      Accept: 'application/json',
      'Content-Type': 'application/json'
    };
    if (csrfToken) headers[csrfHeader] = csrfToken;
    const response = await fetch(url, {
      method: 'POST',
      credentials: 'same-origin',
      headers,
      body: JSON.stringify(payload)
    });
    return {
      ok: response.ok,
      status: response.status,
      body: await response.text()
    };
  }, {
    url: `${baseUrl}/api/decision-report/json`,
    payload: request
  });
  if (!result.ok) {
    throw new Error(
      `Decision report JSON failed with HTTP ${result.status}: `
        + result.body.slice(0, 1_000));
  }
  const report = JSON.parse(result.body);
  if (!Array.isArray(report.chapters) || report.chapters.length < 1) {
    throw new Error('The evaluated requirement produced no decision chapters');
  }
  if (!Array.isArray(report.leadingLeaves) || report.leadingLeaves.length < 1) {
    throw new Error('The evaluated requirement produced no leading leaf candidate');
  }
  if (Number(report.metadata?.completenessPercent) !== 100) {
    throw new Error(
      `Deterministic mock analysis is incomplete: `
        + `${report.metadata?.completenessPercent}%`);
  }
  if (!Array.isArray(report.productCoverageGaps)) {
    throw new Error('Decision report omitted structured product coverage gaps');
  }
  const requestedGaps = Array.isArray(request.productCoverageGaps)
    ? request.productCoverageGaps : [];
  if (report.productCoverageGaps.length !== requestedGaps.length) {
    throw new Error(
      `Decision report preserved ${report.productCoverageGaps.length} of `
        + `${requestedGaps.length} product coverage gaps`);
  }
  for (const gap of report.productCoverageGaps) {
    if (!report.warnings.some(warning => warning.includes(gap.productFamilyCode))) {
      throw new Error(
        `Decision report did not explain product coverage gap ${gap.productFamilyCode}`);
    }
  }
  if (requestedGaps.length > 0 && report.status !== 'FINAL_WITH_WARNINGS') {
    throw new Error(
      `Completed product coverage gaps require FINAL_WITH_WARNINGS, got ${report.status}; `
        + `warnings=${JSON.stringify(report.warnings || [])}`);
  }
  if (report.status === 'DRAFT_INCOMPLETE' || report.status === 'NO_RESULT') {
    throw new Error(`Unexpected decision report status: ${report.status}`);
  }
  return report;
}

async function downloadDecisionReport(target, reportPath) {
  const [download, response] = await Promise.all([
    target.waitForEvent('download', { timeout: 120_000 }),
    target.waitForResponse(candidate => {
      const url = new URL(candidate.url());
      return url.pathname.endsWith('/api/decision-report/docx')
        && candidate.request().method() === 'POST';
    }, { timeout: 120_000 }),
    target.locator('#exportDecisionReportDocx').click()
  ]);
  if (response.status() !== 200) {
    throw new Error(
      `Decision report download failed with HTTP ${response.status()}: `
        + (await response.text()).slice(0, 1_000));
  }
  const failure = await download.failure();
  if (failure) throw new Error(`Report download failed: ${failure}`);
  const headers = response.headers();
  const contentType = String(headers['content-type'] || '').toLowerCase();
  const disposition = String(headers['content-disposition'] || '');
  if (!contentType.startsWith(docxMediaType)) {
    throw new Error(`Unexpected report Content-Type: ${contentType || '<missing>'}`);
  }
  if (!disposition.includes(expectedReportFilename)) {
    throw new Error(`Unexpected Content-Disposition: ${disposition}`);
  }
  await download.saveAs(reportPath);
  const bytes = await readFile(reportPath);
  if (bytes.length < 10_000 || bytes[0] !== 0x50 || bytes[1] !== 0x4b) {
    throw new Error(`Downloaded report is not a plausible DOCX (${bytes.length} bytes)`);
  }
  if (download.suggestedFilename() !== expectedReportFilename) {
    throw new Error(`Unexpected downloaded filename: ${download.suggestedFilename()}`);
  }
  return {
    url: response.url(),
    responseContentType: contentType,
    responseContentDisposition: disposition,
    downloadedFilename: download.suggestedFilename()
  };
}

async function renderAndInspectReport(docxPath, screenshotPath, textPath, model) {
  const profile = await mkdtemp(path.join(os.tmpdir(), 'taxonomy-libreoffice-'));
  const arguments_ = [
    `-env:UserInstallation=${pathToFileURL(profile).href}`,
    '--headless',
    '--convert-to', 'pdf',
    '--outdir', outputDir,
    docxPath
  ];
  try {
    await execFileAsync(
      process.env.LIBREOFFICE_EXECUTABLE || 'libreoffice',
      arguments_,
      { timeout: 120_000, maxBuffer: 5 * 1024 * 1024 });
  } catch (error) {
    if (error?.code !== 'ENOENT') throw error;
    await execFileAsync('soffice', arguments_, {
      timeout: 120_000,
      maxBuffer: 5 * 1024 * 1024
    });
  }

  const pdfPath = path.join(
    outputDir,
    `${path.basename(docxPath, path.extname(docxPath))}.pdf`);
  const pdfStats = await stat(pdfPath);
  if (pdfStats.size < 10_000) {
    throw new Error(`Rendered PDF is unexpectedly small: ${pdfStats.size}`);
  }
  const { stdout: info } = await execFileAsync('pdfinfo', [pdfPath], {
    timeout: 30_000,
    maxBuffer: 2 * 1024 * 1024
  });
  const pageCount = Number(/^Pages:\s+(\d+)$/m.exec(info)?.[1] || 0);
  if (pageCount < 3) {
    throw new Error(`Evaluated decision report has only ${pageCount} pages`);
  }
  await execFileAsync('pdftotext', ['-layout', pdfPath, textPath], {
    timeout: 30_000,
    maxBuffer: 5 * 1024 * 1024
  });
  const reportText = await readFile(textPath, 'utf8');
  assertReportText(reportText, model);
  const screenshotPage = await findDecisionPage(pdfPath, pageCount);
  await execFileAsync('pdftoppm', [
    '-png',
    '-f', String(screenshotPage),
    '-l', String(screenshotPage),
    '-singlefile',
    '-r', '144',
    pdfPath,
    screenshotPath.slice(0, -'.png'.length)
  ], {
    timeout: 120_000,
    maxBuffer: 5 * 1024 * 1024
  });
  if ((await stat(screenshotPath)).size < 10_000) {
    throw new Error('Rendered report screenshot is unexpectedly small');
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

function assertReportText(text, model) {
  const normalized = text.replace(/\s+/g, ' ');
  for (const fragment of ['Krankenhaus', 'Patienten']) {
    if (!normalized.toLocaleLowerCase('de-DE')
      .includes(fragment.toLocaleLowerCase('de-DE'))) {
      throw new Error(`Rendered report lacks requirement fragment: ${fragment}`);
    }
  }
  if (/Preview only|no architecture decision was evaluated|template test report/i
    .test(normalized)) {
    throw new Error('Rendered report still contains preview-only content');
  }
  const leadingCode = model.executiveSummary?.leadingLeaf?.code;
  if (!leadingCode || !normalized.includes(leadingCode)) {
    throw new Error(`Rendered report does not name leading leaf ${leadingCode}`);
  }
  if ((normalized.match(/\b\d{1,3}\s*%/g) || []).length < 3) {
    throw new Error('Rendered report contains fewer than three percentages');
  }
  if (!/(Entscheidung|Bewertung|Decision)/i.test(normalized)) {
    throw new Error('Rendered report contains no decision chapter text');
  }
}

async function findDecisionPage(pdfPath, pageCount) {
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

async function copyDocumentationEvidence() {
  if (!docsImageDir) return;
  if (!renderPreview) {
    throw new Error(
      'TAXONOMY_DOCS_IMAGE_DIR requires TAXONOMY_RENDER_DOCX_PREVIEW=true');
  }
  await mkdir(docsImageDir, { recursive: true });
  for (const source of [
    files.management,
    files.onboarding,
    files.analysis,
    files.graph,
    files.report
  ]) {
    await copyFile(source, path.join(docsImageDir, path.basename(source)));
  }
}
