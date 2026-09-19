import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const source = await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/architecture-workbench.js', import.meta.url), 'utf8');
// Exercise the actual Fit handler's geometry without a browser or a copied formula.
const start = source.indexOf('    function fitView() {');
const end = source.indexOf('    function centerOnNode(', start);
assert.ok(start >= 0 && end > start, 'the production Fit handler must be present');
const fitHandler = source.slice(start, end);

function fit(bounds, viewport) {
    let result;
    let extent = [0.2, 4];
    const transform = {
        x: 0, y: 0, k: 1,
        translate(x, y) { this.x = x; this.y = y; return this; },
        scale(k) { this.k = k; return this; }
    };
    const context = vm.createContext({
        state: { bounds, autoFit: false },
        viewportSize: () => viewport,
        reducedMotion: true,
        d3: { zoomIdentity: transform },
        zoomBehavior: {
            transform() {},
            scaleExtent(value) { extent = value; return this; }
        },
        svgSelection: { call(handler, value) { result = value; } }
    });
    vm.runInContext(fitHandler + '\nfitView();', context);
    return { result, extent };
}

for (const [name, bounds, viewport] of [
    ['civilian overview on a phone', { x: -14, y: 4, width: 2516, height: 1220 }, { width: 320, height: 540 }],
    ['tall graph', { x: 20, y: -70, width: 900, height: 6000 }, { width: 1100, height: 600 }],
    ['compact focused graph', { x: 100, y: 40, width: 400, height: 260 }, { width: 1200, height: 900 }]
]) {
    test(`Fit keeps every bound inside the viewport: ${name}`, () => {
        const { result: { x, y, k }, extent } = fit(bounds, viewport);
        assert.ok(k > 0 && k <= 1.35, 'finite readable magnification ceiling');
        assert.ok(x + bounds.x * k >= 33.99, 'left margin');
        assert.ok(y + bounds.y * k >= 33.99, 'top margin');
        assert.ok(x + (bounds.x + bounds.width) * k <= viewport.width - 33.99, 'right margin');
        assert.ok(y + (bounds.y + bounds.height) * k <= viewport.height - 33.99, 'bottom margin');
        assert.ok(extent[0] <= k, 'manual zoom must retain the fitted scale instead of jumping to 20%');
    });
}
