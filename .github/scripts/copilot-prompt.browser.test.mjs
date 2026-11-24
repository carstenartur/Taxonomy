import test, { before, after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const { chromium } = createRequire(import.meta.url)('playwright');
const resources = process.env.TAXONOMY_TEST_RESOURCE_ROOT
    || fileURLToPath(new URL('../../taxonomy-app/src/main/resources', import.meta.url));
const output = path.join(process.env.TAXONOMY_UI_OUTPUT_ROOT
    || fileURLToPath(new URL('../../target', import.meta.url)), 'copilot-prompt-browser');
let browser;
before(async () => {
    browser = await chromium.launch({
        headless: true,
        ...(process.env.CHROMIUM_EXECUTABLE ? { executablePath: process.env.CHROMIUM_EXECUTABLE } : {}),
        args: ['--no-sandbox', '--disable-dev-shm-usage']
    });
});
after(async () => { await browser?.close(); });

async function fixture(t, width, diagnostics) {
    const page = await browser.newPage({ viewport: { width, height: 850 } });
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    t.after(async () => {
        await page.close();
        assert.deepEqual(errors, [], 'No uncaught browser errors');
    });
    await page.route('**/*', route => route.abort());
    await page.route('http://localhost/', route => route.fulfill({ contentType: 'text/html', body: `<!doctype html>
        <html lang="en"><head><meta name="viewport" content="width=device-width, initial-scale=1">
        <link rel="stylesheet" href="/css/taxonomy-ergonomics.css"></head>
        <body><main><h1>Copilot diagnostics</h1><div id="statusArea"></div><div id="llmCommLogContent"></div></main>
        </body></html>` }));
    await page.route('http://localhost/css/taxonomy-ergonomics.css', route => route.fulfill({
        contentType: 'text/css', path: path.join(resources, 'static/css/taxonomy-ergonomics.css')
    }));
    await page.goto('http://localhost/');
    if (process.env.BOOTSTRAP_CSS) await page.addStyleTag({ path: process.env.BOOTSTRAP_CSS });
    await page.addStyleTag({ path: path.join(resources, 'static/css/taxonomy.css') });
    await page.evaluate(diagnostics => {
        const operationId = 'copilot-prompt-fixture';
        const startedAt = 1795518000000;
        const calls = diagnostics.map((_, index) => ({ id: index + 1, provider: 'MOCK', node: 'IP',
            status: 'COMPLETED', startedAt, durationMillis: 1500 }));
        window.__TaxonomyAnalysisSessionContext = { runtime: { workspaceId: 'workspace-fixture', analysisGeneration: 1 } };
        window.TaxonomyAnalysisSessionApi = {
            getRunStatus: async () => Response.json({ operationId, sequence: 1, phase: 'FINISHED', status: 'COMPLETED',
                startedAt, serverTime: startedAt + 1500, lastActivityAt: startedAt + 1500,
                evaluatedNodes: calls.length, calls, memory: {} }),
            getRunCallDetail: async (_, call) => Response.json(diagnostics[call - 1])
        };
        // Use the production copy helper up to the native clipboard API boundary.
        window.clipboardWrites = [];
        Object.defineProperty(navigator, 'clipboard', { configurable: true, value: {
            async writeText(value) { window.clipboardWrites.push(value); }
        } });
    }, diagnostics);
    for (const source of ['shared/taxonomy-utils.js', 'core/taxonomy-prompt-display.js', 'core/taxonomy-analysis-progress.js']) {
        await page.addScriptTag({ path: path.join(resources, 'static/js', source) });
    }
    await page.evaluate(() => { window.TaxonomyAnalysisProgress.start('copilot-prompt-fixture'); });
    return page;
}

async function openPrompt(page, index = 0) {
    const call = page.locator('#llmCommLogContent > details').nth(index);
    await call.locator(':scope > summary').click();
    const prompt = call.locator('.llm-prompt-field');
    await prompt.waitFor();
    return prompt;
}

for (const width of [1280, 390]) {
    test(`structured Copilot prompt remains readable and copies its original at ${width}px`, async t => {
        const instructions = 'Evaluate the offered taxonomy nodes.\r\nINPUT\r\n';
        const original = 'Retain this requirement.\n\nKeep a second paragraph with literal \\n and '
            + '<img alt="" onerror="window.promptMarkupExecuted = true">'
            + '<script>window.promptMarkupExecuted = true</script>';
        const description = 'Catalogue detail.\nA long identifier follows.\n' + 'abc123'.repeat(40);
        const prompt = instructions + JSON.stringify({ original, nodes: [{ id: 'IP', description }] }) + '\r\n';
        const page = await fixture(t, width, [{ prompt, response: '{"selected":["IP"]}', promptLength: prompt.length }]);
        const field = await openPrompt(page);
        const readable = field.locator('.llm-prompt-readable');
        const blocks = await readable.locator('pre').allTextContents();
        assert.ok(blocks.includes(instructions));
        assert.ok(blocks.includes(original));
        assert.ok(blocks.includes(description));
        assert.equal(await readable.locator('pre').first().evaluate(block => getComputedStyle(block).whiteSpace), 'pre-wrap');
        assert.equal(await field.locator('script, img').count(), 0);
        assert.equal(await page.evaluate(() => Boolean(window.promptMarkupExecuted)), false);

        const disclosure = field.locator('details > summary');
        const raw = field.locator('details > pre');
        assert.equal(await disclosure.textContent(), 'Unchanged raw prompt');
        assert.equal(await raw.isVisible(), false);
        await mkdir(output, { recursive: true });
        await page.screenshot({ path: path.join(output, `structured-prompt-${width}.png`), fullPage: true });
        await disclosure.click();
        assert.equal(await raw.isVisible(), true);
        assert.equal(await raw.textContent(), prompt);
        await field.getByRole('button', { name: 'Copy raw prompt' }).click();
        await field.getByRole('button', { name: 'Copied', exact: true }).waitFor();
        assert.deepEqual(await page.evaluate(() => window.clipboardWrites), [prompt]);
        assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth), true);
        assert.equal(await field.locator('pre').evaluateAll(blocks => blocks.every(block => block.scrollWidth <= block.clientWidth + 1)), true);
    });
}

test('plain and truncated Copilot prompts display the retained evidence verbatim', async t => {
    const plain = 'Plain instructions.\n\nA second line with literal \\n.';
    const truncated = 'Evaluate the offered nodes.\nINPUT\n{"original":"First line\\nSecond line\n[truncated]';
    const page = await fixture(t, 390, [
        { prompt: plain, promptLength: plain.length },
        { prompt: truncated, promptLength: 20000, truncated: true }
    ]);
    for (const [index, expected] of [plain, truncated].entries()) {
        const field = await openPrompt(page, index);
        assert.equal(await field.locator('pre').textContent(), expected);
        assert.equal(await field.locator('.llm-prompt-readable').count(), 0);
        if (index === 1) assert.match(await field.textContent(), /Truncated preview.*20000/);
    }
});
