import assert from 'node:assert/strict';
import test from 'node:test';
import vm from 'node:vm';
import { pageFitsViewport } from './document-template-layout.mjs';

function deferred() {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return { promise, resolve };
}

// Only the browser transport is substituted. Evaluate runs the real measurement
// callback against a controlled document with observable geometry reads.
function fixture(width = 390) {
  const loaded = deferred(), fonts = deferred(), evaluated = deferred();
  let reads = 0;
  const state = { width };
  const document = { fonts: { ready: fonts.promise }, documentElement: {
    get scrollWidth() { reads++; return state.width; }
  } };
  const page = {
    async waitForLoadState(event) {
      assert.equal(event, 'load');
      await loaded.promise;
    },
    evaluate(callback) {
      evaluated.resolve();
      return vm.runInNewContext('(' + callback.toString() + ')()', {
        document, window: { innerWidth: 390 }
      });
    }
  };
  return { page, loaded, fonts, evaluated, state, reads: () => reads };
}

test('measurement waits for stylesheets and fonts before reading geometry', async () => {
  const f = fixture(720);
  const result = pageFitsViewport(f.page);
  assert.equal(f.reads(), 0, 'Do not measure while the navigation is still loading');
  f.loaded.resolve();
  await f.evaluated.promise;
  assert.equal(f.reads(), 0, 'Do not measure while fonts are still loading');
  f.state.width = 390; // The loaded responsive stylesheet now contains the table.
  f.fonts.resolve();
  assert.equal(await result, true);
  assert.equal(f.reads(), 1);
});

for (const [width, expected] of [[390, true], [392, true], [393, false], [720, false]]) {
  test(`rendered width ${width} preserves the existing overflow threshold`, async () => {
    const f = fixture(width);
    f.loaded.resolve();
    f.fonts.resolve();
    assert.equal(await pageFitsViewport(f.page), expected);
    assert.equal(f.reads(), 1);
  });
}

test('a load failure is propagated, not retried or accepted as a fitting page', async () => {
  const error = new Error('stylesheet load timed out');
  await assert.rejects(pageFitsViewport({
    waitForLoadState: async () => { throw error; },
    evaluate: () => { throw new Error('Must not measure an unloaded document'); }
  }), failure => failure === error);
});
