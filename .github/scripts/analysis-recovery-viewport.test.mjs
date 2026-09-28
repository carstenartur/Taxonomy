import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
// The same geometry function is called by the actual mounted status/dialog renderer.
const source = readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/core/taxonomy-recovery-viewport.js', import.meta.url), 'utf8');
const window = {};
vm.runInNewContext(source, { window });
for (const size of [
  { width: 1280, height: 800, offsetLeft: 0, offsetTop: 0 },
  { width: 320, height: 480, offsetLeft: 0, offsetTop: 0 },
  { width: 256, height: 160, offsetLeft: 58, offsetTop: 412 },
  { width: 768, height: 220, offsetLeft: 0, offsetTop: 580 }
]) {
  test(`bar remains in the visual viewport ${JSON.stringify(size)}`, () => {
    const box = window.TaxonomyRecoveryViewport.bounds(size, 90);
    assert(box.left >= size.offsetLeft && box.top >= size.offsetTop);
    assert(box.left + box.width <= size.offsetLeft + size.width);
    assert(box.top + box.height <= size.offsetTop + size.height);
  });
}
test('focused control near bottom chooses the other viewport edge', () => {
  const viewport = { width: 768, height: 240, offsetLeft: 0, offsetTop: 80 };
  const box = window.TaxonomyRecoveryViewport.bounds(viewport, 70, { left: 0, right: 750, top: 240, bottom: 300 });
  assert.equal(box.top, 88);
});
