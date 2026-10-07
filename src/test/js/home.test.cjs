// ホームのトピック切り替えを、ブラウザの代役で確認するテスト。
// vmでhome.jsを実行し、ボタンクリックとタイマーを再現して表示中の記事を調べる。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
// 画面要素と操作の代役を作り、対象のJavaScriptを実行してテスト用の操作口を返す。
function setup(reduced = false) {
  const element = () => ({ hidden: false, handlers: {}, addEventListener(t, f) { this.handlers[t] = f; } });
  const slides = [element(), element(), element()];
  const selectors = Object.fromEntries(['.topic-controls', '.topic-position', '[data-topic="pause"]', '[data-topic="previous"]', '[data-topic="next"]'].map(s => [s, element()]));
  const root = Object.assign(element(), { querySelector: s => selectors[s], querySelectorAll: () => slides, contains: el => Object.values(selectors).includes(el) });
  let pending;
  const document = { hidden: false, handlers: {}, querySelector: () => root, addEventListener(t, f) { this.handlers[t] = f; if (t === 'DOMContentLoaded') f(); } };
  vm.runInNewContext(fs.readFileSync('src/main/resources/static/js/home.js', 'utf8'), {
    document, window: { matchMedia: () => ({ matches: reduced, addEventListener() {} }) },
    setTimeout(f) { pending = f; return 1; }, clearTimeout() { pending = undefined; }
  });
  return { slides, selectors, document, click(name) { document.activeElement = selectors[`[data-topic="${name}"]`]; document.activeElement.handlers.click(); },
    tick() { assert.ok(pending); pending(); }, running() { return !!pending; }, root };
}
// ボタンにフォーカスとマウスが残っていても自動で切り替わり、stopで停止する。
test('carousel starts manual, autoplays with focused controls, wraps and stops', () => {
  const c = setup();
  assert.deepEqual(c.slides.map(s => s.hidden), [false, true, true]);
  assert.equal(c.running(), false);
  assert.equal(c.selectors['[data-topic="pause"]'].textContent, 'start');
  c.root.handlers.mouseenter?.();
  c.click('pause');
  c.root.handlers.focusin?.();
  assert.equal(c.selectors['[data-topic="pause"]'].textContent, 'stop');
  c.tick();
  assert.deepEqual(c.slides.map(s => s.hidden), [true, false, true]);
  c.click('previous'); c.click('previous');
  assert.deepEqual(c.slides.map(s => s.hidden), [true, true, false]);
  c.click('next');
  assert.equal(c.selectors['.topic-position'].textContent, '1 / 3');
  c.click('pause'); assert.equal(c.running(), false);
  c.click('pause'); assert.equal(c.running(), true);
  c.tick(); c.tick();
  assert.equal(c.selectors['.topic-position'].textContent, '3 / 3');
  c.document.hidden = true; c.document.handlers.visibilitychange();
  assert.equal(c.running(), false);
  c.document.hidden = false; c.document.handlers.visibilitychange();
  assert.equal(c.running(), true);
  c.click('pause');
  assert.equal(c.selectors['[data-topic="pause"]'].textContent, 'start');
  assert.equal(c.running(), false);
});
// 動きを減らす設定では自動切り替えを開始せず、手動で前後へ移れることを確認する。
test('reduced motion starts paused while manual navigation remains available', () => {
  const c = setup(true);
  assert.equal(c.running(), false);
  c.click('next');
  assert.deepEqual(c.slides.map(s => s.hidden), [true, false, true]);
  assert.equal(c.running(), false);
});
