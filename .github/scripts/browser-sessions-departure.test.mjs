import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

// Source-order contract for the acceptance observer, not a substitute for its real browser run.
test('session acceptance counts validation from the start of editor departure', () => {
    const source = readFileSync(new URL('./browser-sessions-acceptance.mjs', import.meta.url), 'utf8');
    const finished = source.indexOf('assert.equal(await validation.finished(), null);');
    const departure = source.indexOf("await navigateToPage(page, 'admin');", finished);
    const reset = source.indexOf('validationExpected = false;', finished);
    assert.ok(finished >= 0 && departure > finished && reset > finished, 'expected acceptance lifecycle exists');
    assert.ok(reset < departure, 'validationExpected must be reset before departure begins');
});
