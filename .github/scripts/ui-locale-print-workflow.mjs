import { expect } from '@playwright/test';
import path from 'node:path';
import { navigateToPage } from './ui-role-fixtures.mjs';
import { runDiagramKeyboardWorkflow } from './ui-diagram-keyboard-workflow.mjs';
import { runWorkbenchPrintWorkflow } from './ui-workbench-print-workflow.mjs';

/** Browser acceptance for the locale and report-state corrections.
 * API fixtures are intentionally local to this journey; templates, scripts,
 * styles, navigation, requests, blob frames and print layout are real.
 */
export async function runLocalePrintWorkflow({ page, baseUrl, evidence, outputDir }) {
  const originalLocale = await page.locator('html').getAttribute('lang') || 'en';
  const originalPreference = await page.evaluate(() => localStorage.getItem('taxonomy_language'));
  const routes = [];
  let releaseSearch;
  let releasePreview;
  const route = async (pattern, handler) => {
    await page.route(pattern, handler);
    routes.push([pattern, handler]);
  };
  const json = value => ({ status: 200, contentType: 'application/json', body: JSON.stringify(value) });

  try {
    await page.evaluate(() => localStorage.setItem('taxonomy_language', 'en'));
    await page.goto(baseUrl + '/?lang=de', { waitUntil: 'domcontentloaded' });
    await page.evaluate(() => window.TaxonomyI18n.ready());
    await expect(page.locator('html')).toHaveAttribute('lang', 'de');
    await expect(page.locator('#viewTree')).toContainText('Baum');
    await expect(page.locator('#viewSummary')).toContainText('Zusammenfassung');
    await expect(page.locator('#searchInput')).toHaveAttribute('aria-label', 'Suchanfrage');
    evidence.passed('explicit German link overrides stale English preference for SSR, dynamic locale and accessible names');
    await navigateToPage(page, 'analyze');

    await page.locator('#analysisSecondaryTools').evaluate(element => { element.open = true; });
    await page.locator('#searchPanel').evaluate(element => { element.open = true; });
    let sawSearch;
    const searchSeen = new Promise(resolve => { sawSearch = resolve; });
    const searchGate = new Promise(resolve => { releaseSearch = resolve; });
    await route('**/api/search?**', async request => {
      if (new URL(request.request().url()).searchParams.get('q') !== 'qa-pending-clear') {
        return request.continue();
      }
      sawSearch();
      await searchGate;
      await request.fulfill(json([{ code: 'BP-QA-OLD', name: 'Obsolete search result', score: 1 }]))
        .catch(() => undefined); // The real AbortController may already have cancelled it.
    });
    await page.locator('#searchInput').fill('qa-pending-clear');
    await page.locator('#searchBtn').click();
    await searchSeen;
    await expect(page.locator('#searchClearBtn')).toBeVisible();
    await page.locator('#searchClearBtn').click();
    releaseSearch();
    await expect(page.locator('#searchInput')).toHaveValue('');
    await expect(page.locator('#searchInput')).toBeFocused();
    await expect(page.locator('#searchResultsArea')).toBeHidden();
    await expect(page.locator('#searchResultsArea')).toBeEmpty();
    evidence.passed('clearing a pending search aborts it, clears results and returns keyboard focus');

    await runDiagramKeyboardWorkflow({ page, evidence });

    // Exercise the production decision renderer, including the actual print CSS.
    await page.evaluate(() => {
      const children = Array.from({ length: 20 }, (_, index) => ({
        code: 'QA-' + String(index + 1).padStart(2, '0'),
        name: 'QA Original source title ' + String(index + 1).padStart(2, '0'),
        children: []
      }));
      const scores = Object.fromEntries(children.map((node, index) => [node.code, 100 - index]));
      window.TaxonomyViews.renderDecisionMap(document.getElementById('taxonomyTree'),
        [{ code: 'QA', name: 'QA catalogue', children }], scores);
    });
    await expect(page.locator('#decision-table-body tr')).toHaveCount(20);
    await expect(page.locator('.decision-table thead')).toContainText('Bewertung');
    await expect(page.locator('.decision-filter')).toContainText('Nur Blattknoten');
    await evidence.saveRequiredViewportState('qa-german-decision-results', '#taxonomyTree');
    await page.emulateMedia({ media: 'print' });
    await expect(page.locator('.decision-table-wrapper')).toBeVisible();
    const printTable = await page.locator('.decision-table').evaluate(table => ({
      header: getComputedStyle(table.tHead).display,
      color: getComputedStyle(table.tBodies[0].rows[19].cells[2]).color,
      last: table.tBodies[0].rows[19].textContent
    }));
    evidence.assert(printTable.header === 'table-header-group', 'Decision table header must repeat in print');
    evidence.assert(printTable.color === 'rgb(0, 0, 0)', 'Printed decision text must remain legible');
    evidence.assert(printTable.last.includes('QA-20'), 'The last visible decision row must remain in print');
    if (outputDir && page.context().browser().browserType().name() === 'chromium') {
      await page.pdf({ path: path.join(outputDir, 'decision-results-print.pdf'),
        format: 'A4', printBackground: true, preferCSSPageSize: true });
    }
    await page.emulateMedia({ media: 'screen' });
    evidence.passed('German decision labels and all 20 visible result rows remain available to browser printing');

    await route('**/api/projects/73101', request => request.fulfill(json({
      id: 73101, projectKey: 'QA-PRINT', title: 'QA report workflow'
    })));
    await route('**/api/projects/73101/requirements', request => request.fulfill(json([
      { id: 1, requirementKey: 'QA-A', title: 'First requirement' },
      { id: 2, requirementKey: 'QA-B', title: 'Second requirement' }
    ])));
    let delayPreview = false;
    let sawPreview;
    let previewSeen;
    await route('**/api/projects/73101/reports/html*', async request => {
      if (delayPreview) {
        sawPreview();
        await new Promise(resolve => { releasePreview = resolve; });
      }
      const rows = Array.from({ length: 80 }, (_, index) =>
        '<tr><td>QA report row ' + String(index + 1).padStart(2, '0') + '</td></tr>').join('');
      await request.fulfill({ status: 200, contentType: 'text/html',
        body: '<!doctype html><html lang="de"><head><meta charset="UTF-8"><title>QA Bericht</title>'
          + '<style>@page{size:A4;margin:16mm}body{font:12pt sans-serif}thead{display:table-header-group}'
          + 'td{padding:6mm}tr{break-inside:avoid}</style></head><body><h1>QA Bericht</h1>'
          + '<table><thead><tr><th>Berichtszeilen</th></tr></thead><tbody>' + rows
          + '</tbody></table></body></html>'
      }).catch(() => undefined);
    });
    await page.goto(baseUrl + '/projects/73101/reports?lang=de', { waitUntil: 'domcontentloaded' });
    await expect(page.locator('#reportsProject')).toContainText('QA-PRINT');
    await expect(page.locator('#printReport')).toBeDisabled();
    await expect(page.locator('#reportPreviewFrame')).toHaveAttribute('title', 'Vorschau des Portfolio-Berichts');
    await expect(page.locator('#matrixType option')).toHaveText([
      'Anforderungen × Taxonomie', 'Anforderungen × Lösungen', 'Lösungen × Produkte'
    ]);
    await page.locator('#previewReport').click();
    await expect(page.locator('#printReport')).toBeEnabled();
    const reportFrame = page.frameLocator('#reportPreviewFrame');
    await expect(reportFrame.locator('tbody tr')).toHaveCount(80);
    const blobUrl = await page.locator('#reportPreviewFrame').getAttribute('src');
    evidence.assert(blobUrl.startsWith('blob:'), 'The preview must be the downloaded report document');
    await page.locator('#reportPreviewFrame').evaluate(frame => {
      frame.contentWindow.__qaPrintEvents = 0;
      frame.contentWindow.addEventListener('beforeprint', () => { frame.contentWindow.__qaPrintEvents++; });
    });
    await page.locator('#printReport').click();
    await expect.poll(() => page.locator('#reportPreviewFrame').evaluate(
      frame => frame.contentWindow.__qaPrintEvents)).toBeGreaterThan(0);
    await evidence.axeState('qa-german-report-preview', '#reportsMain');
    await evidence.saveRequiredViewportState('qa-german-report-preview', '#reportsMain');
    if (outputDir && page.context().browser().browserType().name() === 'chromium') {
      const printable = await page.context().newPage();
      try {
        await printable.goto(blobUrl);
        await printable.pdf({ path: path.join(outputDir, 'report-frame-print.pdf'),
          format: 'A4', printBackground: true, preferCSSPageSize: true });
      } finally {
        await printable.close();
      }
    }
    evidence.passed('print action targets the loaded report frame, including its complete multipage document');
    await page.locator('#reportScope').selectOption('requirement');
    await expect(page.locator('#printReport')).toBeDisabled();
    await expect(page.locator('#reportPreviewFrame')).toBeHidden();
    await expect(page.locator('#emptyPreview')).toContainText('geändert');
    delayPreview = true;
    previewSeen = new Promise(resolve => { sawPreview = resolve; });
    await page.locator('#previewReport').click();
    await previewSeen;
    await page.locator('#reportRequirement').selectOption('2');
    releasePreview();
    await expect(page.locator('#printReport')).toBeDisabled();
    await expect(page.locator('#reportPreviewFrame')).toBeHidden();
    evidence.passed('scope and requirement changes invalidate a report and prevent stale preview/print revival');

    await route('**/api/projects/73101/architecture-workbench/qa-locale', request => request.fulfill(json({
      snapshotId: 'qa-locale', snapshotStatus: 'SUCCESS',
      requirementText: 'Original English requirement\n' + Array.from({ length: 80 }, (_, index) =>
        'QA Requirement row ' + String(index + 1).padStart(2, '0') + ': original requirement content retained in the complete print document.').join('\n'),
      provider: 'QA', policyTitleKey: 'archview.policy.title.defaultImpact',
      branchName: 'qa-verifikation', commitSha: '0123456789012345678901234567890123456789',
      scene: { title: 'archview.policy.title.defaultImpact', nodes: [
        { id: 'QA-01', label: 'Original English architecture title', type: 'BP', layer: 'BP',
          x: 40, y: 80, width: 260, height: 90, relevance: 0.9, anchor: true },
        { id: 'QA-02', label: 'Original distant architecture title', type: 'BP', layer: 'BP',
          x: 2200, y: 1800, width: 260, height: 90, relevance: 0.7, anchor: false }
      ], edges: [] },
      elements: { 'QA-01': { directScore: 90, reviewStatus: 'PROPOSED',
        presenceReason: 'Original English evidence' } }, relations: {}
    })));
    await page.goto(baseUrl + '/architecture/workbench?projectId=73101&snapshotId=qa-locale&lang=de',
      { waitUntil: 'domcontentloaded' });
    await expect(page.locator('#architectureStatus')).toContainText('2 Elemente und 0 Beziehungen');
    await expect(page.locator('#architectureSearch')).toHaveAttribute('placeholder', 'Code, Titel oder Ebene suchen…');
    await expect(page.locator('#fitArchitecture')).toContainText('Einpassen');
    await expect(page.locator('#architectureCanvas')).toContainText('Original English architecture title');
    const architectureNode = page.locator('[data-node-id="QA-01"]');
    await architectureNode.focus();
    await page.keyboard.press('Enter');
    await expect(page.locator('#architectureDetails')).toContainText('Original English evidence');
    await expect(page.locator('#architectureDetails')).toContainText('Relevanz');
    await evidence.axeState('qa-german-architecture-workbench', '.workbench-grid');
    await evidence.saveRequiredViewportState('qa-german-architecture-workbench', '.workbench-grid');
    evidence.passed('German workbench controls and keyboard-selected detail labels preserve original source content');
    await runWorkbenchPrintWorkflow({ page, evidence, outputDir });

    // Inspect authenticated server-rendered attributes without triggering unrelated
    // requirement/import API workflows or modifying any real portfolio data.
    const renderedSurfaces = [
      { url: '/change-password?lang=de', checks: [
        { selector: 'a[href="#changePasswordForm"]', text: 'Zum Passwortformular springen' }
      ] },
      { url: '/projects/73101/import?lang=de', checks: [
        { selector: 'nav.navbar', attribute: 'aria-label', text: 'Navigation zum Anforderungsimport' },
        { selector: 'nav .btn-group[role="group"]', attribute: 'aria-label', text: 'Sprache' },
        { selector: 'ol[aria-label]', attribute: 'aria-label', text: 'Importfortschritt' },
        { selector: 'label[for="documentFile"]', text: 'Dokumentdatei' },
        { selector: '#sourceType option[value="REGULATION"]', text: 'Verwaltungsvorschrift' },
        { selector: '#sourceType option[value="POLICY"]', text: 'Richtlinie' },
        { selector: '#sourceType option[value="STANDARD"]', text: 'Standard' },
        { selector: '#sourceType option[value="UPLOADED_DOCUMENT"]', text: 'Dokument' },
        { selector: '#reviewHeading', text: 'Kandidaten vor dem Speichern prüfen' },
        { selector: '#summaryHeading', text: 'Import bestätigen' }
      ] },
      { url: '/projects/73101/requirements/1?lang=de', checks: [
        { selector: 'nav.navbar', attribute: 'aria-label', text: 'Navigation zur Anforderung' },
        { selector: 'nav .btn-group[role="group"]', attribute: 'aria-label', text: 'Sprache' },
        { selector: '#newVersionTitle', text: 'Unveränderliche Anforderungsversion erstellen' },
        { selector: 'label[for="versionText"]', text: 'Anforderungstext' },
        { selector: 'label[for="changeReason"]', text: 'Änderungsgrund' },
        { selector: '#newVersionModal details summary', text: 'Quellenreferenz' },
        { selector: 'label[for="sourceSection"]', text: 'Abschnitt' },
        { selector: 'label[for="sourcePage"]', text: 'Seite' },
        { selector: 'label[for="sourceOriginal"]', text: 'Originaltext' },
        { selector: '#newVersionModal .btn-close', attribute: 'aria-label', text: 'Schließen' },
        { selector: '#newVersionModal .modal-footer [data-bs-dismiss="modal"]', text: 'Abbrechen' },
        { selector: '#newVersionModal .modal-footer [type="submit"]', text: 'Version erstellen' },
        { selector: '#architectureWorkbenchLink', text: 'Architekturansicht öffnen' }
      ] },
      { url: '/architecture/editor?lang=de', checks: [
        { selector: '#editorElementStatus option[value="draft"]', text: 'Entwurf' },
        { selector: '#editorElementStatus option[value="active"]', text: 'Aktiv' },
        { selector: '#editorElementStatus option[value="retired"]', text: 'Außer Betrieb' }
      ] }
    ];
    for (const surface of renderedSurfaces) {
      const response = await page.request.get(baseUrl + surface.url);
      evidence.assert(response.ok(), 'German template did not render: ' + surface.url);
      const actual = await page.evaluate(({ html, checks }) => {
        const document = new DOMParser().parseFromString(html, 'text/html');
        return checks.map(check => {
          const element = document.querySelector(check.selector);
          return element ? (check.attribute
            ? element.getAttribute(check.attribute) : element.textContent.trim()) : null;
        });
      }, { html: await response.text(), checks: surface.checks });
      expect(actual, surface.url).toEqual(surface.checks.map(check => check.text));
    }
    evidence.passed('rendered German account, import, requirement and editor templates expose localized accessible labels and status options');
  } finally {
    releaseSearch?.();
    releasePreview?.();
    await page.emulateMedia({ media: 'screen' });
    for (const [pattern, handler] of routes) await page.unroute(pattern, handler);
    await page.evaluate(value => {
      if (value === null) localStorage.removeItem('taxonomy_language');
      else localStorage.setItem('taxonomy_language', value);
    }, originalPreference).catch(() => undefined);
    await page.goto(baseUrl + '/?lang=' + encodeURIComponent(originalLocale),
      { waitUntil: 'domcontentloaded' });
    await page.evaluate(() => window.TaxonomyI18n.ready());
  }
}
