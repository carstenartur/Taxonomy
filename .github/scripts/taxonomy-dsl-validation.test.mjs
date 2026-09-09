import assert from 'node:assert/strict';
import test from 'node:test';
import { createDslValidationSource } from '../../taxonomy-app/src/main/resources/static/js/shared/taxonomy-dsl-validation.mjs';

function document(text) {
    const lines = text.split('\n');
    return {
        toString: () => text,
        lines: lines.length,
        line(number) {
            const from = lines.slice(0, number - 1).reduce((length, line) => length + line.length + 1, 0);
            return { from, to: from + lines[number - 1].length };
        }
    };
}

function fixture() {
    const lifecycle = new EventTarget();
    const calls = [];
    const api = { request(url, init, options) {
        return new Promise((resolve, reject) => {
            options.signal.addEventListener('abort', () => reject(new Error('cancelled')), { once: true });
            calls.push({ url, init, options, resolve: data => resolve({ json: async () => data }), reject });
        });
    } };
    return { lifecycle, calls, source: createDslValidationSource(api, lifecycle),
        view: { state: { doc: document('first\nsecond') } } };
}

test('validation preserves errors, warnings and exact source line ranges', async () => {
    const { source, calls, view } = fixture();
    const result = source.lint(view);
    assert.equal(calls[0].url, '/api/dsl/validate');
    assert.equal(calls[0].init.body, 'first\nsecond');
    assert.equal(calls[0].init.method, 'POST');
    calls[0].resolve({ errors: ['line 2: invalid'], warnings: ['line 99: outside', { warning: 'review' }] });
    assert.deepEqual(await result, [
        { from: 6, to: 12, severity: 'error', message: 'line 2: invalid' },
        { from: 0, to: 1, severity: 'warning', message: 'line 99: outside' },
        { from: 0, to: 1, severity: 'warning', message: '{"warning":"review"}' }
    ]);
    source.dispose();
});

test('a new lint call cancels and settles the superseded request', async () => {
    const { source, calls, view } = fixture();
    const first = source.lint(view);
    const second = source.lint(view);
    assert.equal(calls[0].options.signal.aborted, true);
    assert.deepEqual(await first, []);
    calls[1].resolve({ errors: ['current diagnostic'] });
    assert.equal((await second)[0].message, 'current diagnostic');
    source.dispose();
});

test('a response for a replaced document never marks ranges in its replacement', async () => {
    const { source, calls, view } = fixture();
    const result = source.lint(view);
    view.state.doc = document('short');
    calls[0].resolve({ errors: ['line 2: stale'] });
    assert.deepEqual(await result, []);
    source.dispose();
});

test('navigation cancels validation and blocks delayed CodeMirror callbacks until pageshow', async () => {
    const { source, calls, view, lifecycle } = fixture();
    const pending = source.lint(view);
    lifecycle.dispatchEvent(new Event('pagehide'));
    assert.equal(calls[0].options.signal.aborted, true);
    assert.deepEqual(await pending, []);
    assert.deepEqual(await source.lint(view), []);
    assert.equal(calls.length, 1, 'no request may start from the departing document');
    lifecycle.dispatchEvent(new Event('pageshow'));
    const restored = source.lint(view);
    calls[1].resolve({ warnings: ['restored document'] });
    assert.equal((await restored)[0].message, 'restored document');
    source.dispose();
});

test('failed requests settle and do not stop the next validation', async () => {
    const { source, calls, view } = fixture();
    const failed = source.lint(view);
    calls[0].reject(new Error('HTTP 503'));
    assert.deepEqual(await failed, []);
    const retried = source.lint(view);
    calls[1].resolve({ errors: [], warnings: [] });
    assert.deepEqual(await retried, []);
    source.dispose();
});

test('disposing one editor cancels only its own pending validation', async () => {
    const first = fixture();
    const second = fixture();
    const cancelled = first.source.lint(first.view);
    const surviving = second.source.lint(second.view);
    first.source.dispose();
    assert.deepEqual(await cancelled, []);
    first.lifecycle.dispatchEvent(new Event('pageshow'));
    assert.deepEqual(await first.source.lint(first.view), []);
    assert.equal(first.calls.length, 1);
    assert.equal(second.calls[0].options.signal.aborted, false);
    second.calls[0].resolve({ errors: ['still active'] });
    assert.equal((await surviving)[0].message, 'still active');
    second.source.dispose();
});
