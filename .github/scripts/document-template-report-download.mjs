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

await mkdir(outputDir, { recursive: true });
const managementScreenshot = path.join(
  outputDir, 'document-template-management.png');
const reportPath = path.join(
  outputDir, 'decision-rationale-template-test.docx');
const reportScreenshot = path.join(
  outputDir, 'decision-rationale-template-test-report.png');

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

  await page.goto(`${baseUrl}/admin/document-templates`, {
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

  const manage = row.locator(
    `a[href$="/admin/document-templates/${templateId}"]`).first();
  await manage.click();
  await page.locator('#templateDetail').waitFor({
    state: 'visible',
    timeout: 30_000
  });

  const testReportLink = page.locator('a[href$="/test.docx"]').first();
  await testReportLink.waitFor({ state: 'visible' });
  const downloadUrl = await testReportLink.getAttribute('href');
  const [download] = await Promise.all([
    page.waitForEvent('download', { timeout: 60_000 }),
    testReportLink.click()
  ]);
  const downloadFailure = await download.failure();
  if (downloadFailure) {
    throw new Error(`Report download failed: ${downloadFailure}`);
  }
  await download.saveAs(reportPath);

  const reportBytes = await readFile(reportPath);
  if (reportBytes.length < 1_000
      || reportBytes[0] !== 0x50
      || reportBytes[1] !== 0x4b) {
    throw new Error(
      `Downloaded report is not a plausible DOCX ZIP (${reportBytes.length} bytes)`);
  }
  if (download.suggestedFilename()
      !== 'decision-rationale-template-test.docx') {
    throw new Error(
      `Unexpected downloaded filename: ${download.suggestedFilename()}`);
  }

  if (renderPreview) {
    await renderDownloadedReport(reportPath, reportScreenshot);
  }

  const evidence = {
    schemaVersion: 1,
    capturedAt: new Date().toISOString(),
    baseUrl,
    templateId,
    templateCommitAbbreviation: commit,
    managementUrl: `${baseUrl}/admin/document-templates`,
    detailUrl: page.url(),
    wordEditUri,
    webDavUrl,
    downloadUrl: new URL(downloadUrl, baseUrl).href,
    downloadedFilename: download.suggestedFilename(),
    downloadedBytes: reportBytes.length,
    downloadedSha256: createHash('sha256')
      .update(reportBytes)
      .digest('hex'),
    screenshots: {
      management: path.basename(managementScreenshot),
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
    await copyFile(
      managementScreenshot,
      path.join(docsImageDir, path.basename(managementScreenshot)));
    await copyFile(
      reportScreenshot,
      path.join(docsImageDir, path.basename(reportScreenshot)));
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

async function renderDownloadedReport(docxPath, pngPath) {
  const profile = await mkdtemp(
    path.join(os.tmpdir(), 'taxonomy-libreoffice-'));
  const profileUrl = pathToFileURL(profile).href;
  const office = process.env.LIBREOFFICE_EXECUTABLE || 'libreoffice';

  try {
    const { stdout, stderr } = await execFileAsync(office, [
      `-env:UserInstallation=${profileUrl}`,
      '--headless',
      '--convert-to', 'pdf',
      '--outdir', outputDir,
      docxPath
    ], {
      timeout: 120_000,
      maxBuffer: 5 * 1024 * 1024
    });
    if (stdout) process.stdout.write(stdout);
    if (stderr) process.stderr.write(stderr);
  } catch (error) {
    if (office === 'libreoffice' && error?.code === 'ENOENT') {
      await execFileAsync('soffice', [
        `-env:UserInstallation=${profileUrl}`,
        '--headless',
        '--convert-to', 'pdf',
        '--outdir', outputDir,
        docxPath
      ], {
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
  if (pdfStats.size < 1_000) {
    throw new Error(`Rendered PDF is unexpectedly small: ${pdfStats.size}`);
  }

  const outputPrefix = pngPath.slice(0, -'.png'.length);
  await execFileAsync('pdftoppm', [
    '-png',
    '-f', '1',
    '-singlefile',
    '-r', '144',
    pdfPath,
    outputPrefix
  ], {
    timeout: 120_000,
    maxBuffer: 5 * 1024 * 1024
  });
  const pngStats = await stat(pngPath);
  if (pngStats.size < 1_000) {
    throw new Error(`Rendered report screenshot is too small: ${pngStats.size}`);
  }
}
