const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '../../../..');
const views = readFileSync(path.join(root,
    'taxonomy-app/src/main/resources/static/js/core/taxonomy-views.js'), 'utf8');
const css = readFileSync(path.join(root,
    'taxonomy-app/src/main/resources/static/css/taxonomy.css'), 'utf8');

function luminance(hex) {
    const rgb = hex.slice(1).match(/../g).map(v => parseInt(v, 16) / 255)
        .map(v => v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4));
    return 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2];
}
function contrast(a, b) {
    const x = luminance(a), y = luminance(b);
    return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
}

test('decision map score meaning is not encoded by green opacity or white score thresholds', () => {
    const start = views.indexOf('function renderDecisionMap');
    const end = views.indexOf('function renderDecisionTable', start);
    const block = views.slice(start, end);
    assert.doesNotMatch(block, /rgba\(0\s*,\s*128\s*,\s*0/);
    assert.doesNotMatch(block, />=\s*60\s*\?\s*['"]#fff/);
    assert.match(block, /--decision-score-surface/);
    assert.match(block, /Magnitude is encoded by the numeric label, not hue\/opacity/);
});

test('decision map light and dark semantic surfaces keep readable text contrast', () => {
    const pairs = [
        ['#1f2937', '#dbeafe'], ['#1f2937', '#f1f5f9'],
        ['#1f2937', '#f6c344'], ['#1f2937', '#cbd5e1'], ['#1f2937', '#c98958'],
        ['#f8fafc', '#173b66'], ['#f8fafc', '#334155'],
        ['#f8fafc', '#765b00'], ['#f8fafc', '#475569'], ['#f8fafc', '#713f20']
    ];
    for (const [foreground, background] of pairs) {
        assert.ok(contrast(foreground, background) >= 4.5,
            `${foreground} on ${background} is below 4.5:1`);
    }
    assert.match(css, /\[data-bs-theme="dark"\]\s*\{[\s\S]*--decision-score-surface:/);
    assert.match(css, /\.decision-score-badge\s*\{[\s\S]*border:\s*2px solid var\(--decision-score-stroke\)/);
});

test('decision map keeps semantic theme tokens live after a theme toggle', () => {
    const start = views.indexOf('function renderDecisionMap');
    const end = views.indexOf('function renderDecisionTable', start);
    const block = views.slice(start, end);
    assert.match(block, /return ['"]var\\(['"] \\+ token/);
    assert.doesNotMatch(block, /getComputedStyle\\(document\\.documentElement\\)\\.getPropertyValue\\(token\\)/);
});
