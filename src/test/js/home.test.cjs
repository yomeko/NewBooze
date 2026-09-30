const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
function setup(reduced = false) {
  const element = () => ({ hidden: false, handlers: {}, addEventListener(t, f) { this.handlers[t] = f; } });
  const slides = [element(), element(), element()];
  const selectors = Object.fromEntries(['.topic-controls', '.topic-position', '[data-topic="pause"]', '[data-topic="previous"]', '[data-topic="next"]'].map(s => [s, element()]));
  const root = Object.assign(element(), { querySelector: s => selectors[s], querySelectorAll: () => slides, contains: () => false });
  let pending;
  const document = { hidden: false, querySelector: () => root, addEventListener(t, f) { if (t === 'DOMContentLoaded') f(); } };
  vm.runInNewContext(fs.readFileSync('src/main/resources/static/js/home.js', 'utf8'), {
    document, window: { matchMedia: () => ({ matches: reduced, addEventListener() {} }) },
    setTimeout(f) { pending = f; return 1; }, clearTimeout() { pending = undefined; }
  });
  return { slides, selectors, click(name) { selectors[`[data-topic="${name}"]`].handlers.click(); },
    tick() { assert.ok(pending); pending(); }, running() { return !!pending; }, root };
}
test('carousel advances automatically, wraps in both directions, and pauses', () => {
  const c = setup();
  assert.deepEqual(c.slides.map(s => s.hidden), [false, true, true]);
  c.tick();
  assert.deepEqual(c.slides.map(s => s.hidden), [true, false, true]);
  c.click('previous'); c.click('previous');
  assert.deepEqual(c.slides.map(s => s.hidden), [true, true, false]);
  c.click('next');
  assert.equal(c.selectors['.topic-position'].textContent, '1 / 3');
  c.click('pause'); assert.equal(c.running(), false);
  c.click('pause'); assert.equal(c.running(), true);
  c.root.handlers.mouseenter(); assert.equal(c.running(), false);
  c.root.handlers.mouseleave(); assert.equal(c.running(), true);
});
test('reduced motion starts paused while manual navigation remains available', () => {
  const c = setup(true);
  assert.equal(c.running(), false);
  c.click('next');
  assert.deepEqual(c.slides.map(s => s.hidden), [true, false, true]);
  assert.equal(c.running(), false);
});
