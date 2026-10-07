import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const resources = new URL('../../taxonomy-app/src/main/resources/static/js/', import.meta.url);
const source = readFileSync(new URL('portfolio/requirement-detail.js', resources), 'utf8');
const utilities = readFileSync(new URL('shared/taxonomy-utils.js', resources), 'utf8');

function element() {
    const attributes = new Map();
    const node = { children: [], dataset: {}, className: '', innerHTML: '',
        appendChild(child) { this.children.push(child); },
        setAttribute(name, value) { attributes.set(name, String(value)); },
        getAttribute(name) { return attributes.get(name) ?? null; },
        removeAttribute(name) { attributes.delete(name); },
        querySelectorAll(selector) {
            return selector === '[data-version-id]' ? this.children.filter(child => child.dataset.versionId != null) : [];
        },
        querySelector() { return null; }
    };
    node.classList = {
        contains(name) { return node.className.split(/\s+/).includes(name); },
        toggle(name, force) {
            const classes = new Set(node.className.split(/\s+/).filter(Boolean));
            if (force ?? !classes.has(name)) classes.add(name); else classes.delete(name);
            node.className = [...classes].join(' ');
        }
    };
    Object.defineProperty(node, 'textContent', { set(value) { this.children = []; this.innerHTML = String(value); } });
    return node;
}

function harness(locale = 'en') {
    const elements = new Map(['versionList', 'versionDetail'].map(id => [id, element()]));
    const document = { readyState: 'loading', documentElement: { lang: locale }, addEventListener() {},
        getElementById(id) { return elements.get(id); }, createElement: element };
    const window = { location: { pathname: '/projects/1/requirements/2', search: '' } };
    const context = vm.createContext({ window, document, URLSearchParams });
    vm.runInContext(utilities, context);
    // Execute the complete production closure and expose only the functions under review.
    vm.runInContext(source.replace(/\}\)\(\);\s*$/,
        'window.review = { state, renderTextDiff, renderVersions, selectVersion }; })();'), context);
    return { ...window.review, elements, context };
}

function decode(value) {
    return value.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"')
        .replace(/&#39;/g, "'").replace(/&amp;/g, '&');
}

function renderedLines(html) {
    return [...html.matchAll(/<div class="(text-[^"]+)">([\s\S]*?)<\/div>/g)].map(([, css, content]) => {
        const plain = decode(content.replace(/<[^>]+>/g, ''));
        const kind = css.includes('danger') ? 'removed' : css.includes('success') ? 'added' : 'unchanged';
        return { kind, text: plain.slice(2) };
    });
}

function assertLines(actual, expected, message) {
    assert.equal(actual.length, expected.length, message + ': line count');
    expected.forEach((line, index) => assert.equal(actual[index], line, `${message}: line ${index + 1}`));
}

function compare(older, newer, locale) {
    const h = harness(locale);
    const html = h.renderTextDiff(older, newer);
    const rows = renderedLines(html);
    assertLines(rows.filter(row => row.kind !== 'added').map(row => row.text), older.split(/\r?\n/),
        'Removing additions must reconstruct every original line in order');
    assertLines(rows.filter(row => row.kind !== 'removed').map(row => row.text), newer.split(/\r?\n/),
        'Removing deletions must reconstruct every revised line in order');
    return { html, rows };
}

test('moving ordered requirement steps is a visible change', () => {
    const { rows } = compare('Stop the pump\nClose the valve', 'Close the valve\nStop the pump');
    assert.ok(rows.some(row => row.kind === 'removed'));
    assert.ok(rows.some(row => row.kind === 'added'));
});

test('removing a repeated approval check remains visible', () => {
    const { rows } = compare('Verify approval\nRelease package\nVerify approval', 'Verify approval\nRelease package');
    assert.deepEqual(rows.filter(row => row.kind === 'removed'), [{ kind: 'removed', text: 'Verify approval' }]);
});

test('adding an identical repeated line remains visible', () => {
    const { rows } = compare('Check\nRelease', 'Check\nCheck\nRelease');
    assert.deepEqual(rows.filter(row => row.kind === 'added'), [{ kind: 'added', text: 'Check' }]);
});

test('separate edits preserve unchanged context between them', () => {
    const { rows } = compare('Heading\nOld first\nStable context\nOld last\nEnd',
        'Heading\nNew first\nStable context\nNew last\nEnd');
    assert.deepEqual(rows.filter(row => row.kind === 'unchanged').map(row => row.text),
        ['Heading', 'Stable context', 'End']);
});

test('indentation, duplicate empty lines and the final newline remain reviewable', () => {
    const { html } = compare('  First\n\n\n  Last\n', '    First\n\n  Last\n');
    assert.match(html, /portfolio-requirement-text/, 'The existing pre-wrap text style preserves significant spacing');
});

test('equal lines and empty texts have no invented additions or removals', () => {
    for (const value of ['', 'Same\nSame\n\nSame']) {
        const { rows } = compare(value, value);
        assert.ok(rows.every(row => row.kind === 'unchanged'));
    }
});

test('line ending normalization still treats CRLF and LF as the same text', () => {
    const { rows } = compare('First\r\nSecond\r\n', 'First\nSecond\n');
    assert.ok(rows.every(row => row.kind === 'unchanged'));
});

test('both sides retain literal HTML-like requirement text without creating markup', () => {
    const { html } = compare('<img src=x onerror="run()">\nKeep & verify', '<script>run()</script>\nKeep & verify');
    assert.doesNotMatch(html, /<(?:img|script)\b/);
    assert.match(html, /&lt;img src=x onerror=&quot;run\(\)&quot;&gt;/);
    assert.match(html, /&lt;script&gt;run\(\)&lt;\/script&gt;/);
    assert.match(html, /Keep &amp; verify/);
});

test('a long changed block uses an explicit bounded comparison without omitting any lines', () => {
    const h = harness();
    const before = Array.from({ length: 2000 }, (_, index) => `Step ${index}`);
    h.context.before = before.join('\n');
    h.context.after = [...before].reverse().join('\n');
    const html = vm.runInContext('window.review.renderTextDiff(before, after)', h.context, { timeout: 1000 });
    const rows = renderedLines(html);
    assertLines(rows.filter(row => row.kind !== 'added').map(row => row.text), before, 'Complete original text');
    assertLines(rows.filter(row => row.kind !== 'removed').map(row => row.text), [...before].reverse(), 'Complete revised text');
    assert.match(html, /large changed block/i, 'Explain the broad replacement instead of implying a minimal diff');
});

test('a small edit in a long requirement retains the common prefix and suffix', () => {
    const context = Array.from({ length: 2000 }, (_, index) => `Unchanged ${index}`).join('\n');
    const { rows, html } = compare(context + '\nBefore\n' + context, context + '\nAfter\n' + context);
    assert.equal(rows.filter(row => row.kind !== 'unchanged').length, 2);
    assert.doesNotMatch(html, /large changed block/i);
});

test('German long-comparison guidance is translated', () => {
    const before = Array.from({ length: 1100 }, (_, index) => `Zeile ${index}`);
    const { html } = compare(before.join('\n'), [...before].reverse().join('\n'), 'de');
    assert.match(html, /große Änderungsblock/i);
    assert.doesNotMatch(html, /large changed block/i);
});

function versions() {
    return [{ id: 12, versionNumber: 2, text: 'Revised', changeReason: 'Second' },
        { id: 11, versionNumber: 1, text: 'Original', changeReason: 'First' }];
}

test('the initially displayed version has exactly one visible and accessible selection marker', () => {
    const h = harness(); h.state.versions = versions(); h.state.selectedVersion = h.state.versions[0];
    h.renderVersions();
    const buttons = h.elements.get('versionList').children;
    assert.equal(buttons.filter(button => button.classList.contains('active')).length, 1);
    assert.equal(buttons[0].getAttribute('aria-current'), 'true');
    assert.equal(buttons[1].getAttribute('aria-current'), null);
});

test('selecting another version moves its marker and detail while preserving the existing focus target', () => {
    const h = harness(); h.state.versions = versions(); h.state.selectedVersion = h.state.versions[0];
    h.renderVersions();
    const originalButtons = [...h.elements.get('versionList').children];
    h.selectVersion(11);
    const buttons = h.elements.get('versionList').children;
    assert.equal(buttons[0], originalButtons[0]);
    assert.equal(buttons[1], originalButtons[1]);
    assert.equal(buttons[0].classList.contains('active'), false);
    assert.equal(buttons[0].getAttribute('aria-current'), null);
    assert.equal(buttons[1].classList.contains('active'), true);
    assert.equal(buttons[1].getAttribute('aria-current'), 'true');
    assert.match(h.elements.get('versionDetail').innerHTML, /Original/);
    assert.doesNotMatch(h.elements.get('versionDetail').innerHTML, /Revised/);
});
