// ホームのトピック切り替えを、ブラウザの代役で確認するテスト。
// vmでhome.jsを実行し、ボタンクリックとタイマーを再現して表示中の記事を調べる。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
// 画面要素と操作の代役を作り、対象のJavaScriptを実行してテスト用の操作口を返す。
function setup(reduced = false) {
  const element = () => {
    const classes = new Set();
    return {
      hidden: false, handlers: {}, attributes: {}, children: [],
      style: { values: {}, setProperty(k, v) { this.values[k] = v; } },
      classList: {
        add(c) { classes.add(c); }, remove(c) { classes.delete(c); },
        toggle(c, active) { if (active) classes.add(c); else classes.delete(c); },
        contains(c) { return classes.has(c); }
      },
      addEventListener(t, f) { this.handlers[t] = f; },
      setAttribute(k, v) { this.attributes[k] = v; },
      append(c) { this.children.push(c); }, prepend(c) { this.children.unshift(c); },
      cloneNode() { return element(); }, setPointerCapture() {}
    };
  };
  const slides = [element(), element(), element()];
  const selectors = Object.fromEntries(['.topic-slides', '.topic-dots', '.topic-controls', '.topic-position', '[data-topic="pause"]', '[data-topic="previous"]', '[data-topic="next"]'].map(s => [s, element()]));
  const track = selectors['.topic-slides'];
  track.children = [...slides];
  track.querySelectorAll = () => slides;
  const root = Object.assign(element(), { querySelector: s => selectors[s] });
  let pending;
  const motion = { matches: reduced, addEventListener(t, f) { this.changed = f; } };
  const document = { hidden: false, handlers: {}, querySelector: () => root, createElement: element, addEventListener(t, f) { this.handlers[t] = f; if (t === 'DOMContentLoaded') f(); } };
  vm.runInNewContext(fs.readFileSync('src/main/resources/static/js/home.js', 'utf8'), {
    document, window: { matchMedia: () => motion },
    setTimeout(f) { pending = f; return 1; }, clearTimeout() { pending = undefined; }
  });
  return { slides, selectors, document, track, motion, dots: selectors['.topic-dots'].children,
    click(name) { document.activeElement = selectors[`[data-topic="${name}"]`]; document.activeElement.handlers.click(); },
    tick() { assert.ok(pending); pending(); }, running() { return !!pending; }, root,
    finish() { track.handlers.transitionend({ target: track, propertyName: 'transform' }); },
    active() { return slides.findIndex(s => s.attributes['aria-hidden'] === 'false'); }
  };
}
// ボタンにフォーカスとマウスが残っていても自動で切り替わり、停止ボタンで止める。
test('carousel starts manual, autoplays with focused controls, wraps and stops', () => {
  const c = setup();
  assert.equal(c.active(), 0);
  assert.equal(c.track.children.length, 5);
  assert.equal(c.root.classList.contains('is-carousel'), true);
  assert.deepEqual(c.dots.map(d => d.attributes['aria-current']), ['true', 'false', 'false']);
  for (const clone of [c.track.children[0], c.track.children[4]]) {
    assert.equal(clone.inert, true);
    assert.equal(clone.attributes['aria-hidden'], 'true');
  }
  assert.equal(c.running(), false);
  assert.equal(c.selectors['[data-topic="pause"]'].attributes['aria-label'], '自動切り替えを開始');
  c.root.handlers.mouseenter?.();
  c.click('pause');
  c.root.handlers.focusin?.();
  assert.equal(c.selectors['[data-topic="pause"]'].attributes['aria-label'], '自動切り替えを停止');
  c.tick();
  assert.equal(c.active(), 1);
  c.click('previous'); c.click('previous');
  assert.equal(c.active(), 2);
  assert.equal(c.track.style.values['--topic-offset'], 0);
  c.finish();
  assert.equal(c.track.style.values['--topic-offset'], 3);
  c.click('next');
  assert.equal(c.selectors['.topic-position'].textContent, '1 / 3');
  assert.equal(c.track.style.values['--topic-offset'], 4);
  c.finish();
  assert.equal(c.track.style.values['--topic-offset'], 1);
  c.click('pause'); assert.equal(c.running(), false);
  c.click('pause'); assert.equal(c.running(), true);
  c.tick(); c.tick();
  assert.equal(c.selectors['.topic-position'].textContent, '3 / 3');
  c.document.hidden = true; c.document.handlers.visibilitychange();
  assert.equal(c.running(), false);
  c.document.hidden = false; c.document.handlers.visibilitychange();
  assert.equal(c.running(), true);
  c.click('pause');
  assert.equal(c.selectors['[data-topic="pause"]'].attributes['aria-label'], '自動切り替えを開始');
  assert.equal(c.running(), false);
});
// 動きを減らす設定では自動切り替えを開始せず、手動で前後へ移れることを確認する。
test('reduced motion starts paused while manual navigation remains available', () => {
  const c = setup(true);
  assert.equal(c.running(), false);
  c.click('next');
  assert.equal(c.active(), 1);
  assert.equal(c.running(), false);
  c.click('previous'); c.click('previous');
  assert.equal(c.track.style.values['--topic-offset'], 3);
  c.click('pause');
  c.motion.changed();
  assert.equal(c.running(), false);
});

test('dots, keyboard and rapid wraparound keep the selected article in sync', () => {
  const c = setup();
  c.dots[2].handlers.click();
  assert.equal(c.active(), 2);
  assert.equal(c.track.style.values['--topic-offset'], 3);
  c.click('next'); c.click('next');
  c.finish();
  assert.equal(c.active(), 1);
  assert.equal(c.track.style.values['--topic-offset'], 2);
  let prevented = false;
  c.root.handlers.keydown({ key: 'ArrowLeft', preventDefault() { prevented = true; } });
  assert.equal(prevented, true);
  assert.equal(c.active(), 0);
  assert.deepEqual(c.dots.map(d => d.attributes['aria-current']), ['true', 'false', 'false']);
  assert.deepEqual(c.slides.map(s => s.inert), [false, true, true]);
});

test('horizontal swipes navigate, while vertical scrolling and cancelled gestures do not', () => {
  const c = setup();
  const down = { pointerType: 'touch', isPrimary: true, pointerId: 1, clientX: 200, clientY: 100 };
  c.track.handlers.pointerdown(down);
  c.track.handlers.pointerup({ pointerId: 1, clientX: 120, clientY: 105 });
  assert.equal(c.active(), 1);
  c.track.handlers.pointerdown(down);
  c.track.handlers.pointerup({ pointerId: 1, clientX: 150, clientY: 220 });
  assert.equal(c.active(), 1);
  c.track.handlers.pointerdown(down);
  c.track.handlers.pointercancel();
  c.track.handlers.pointerup({ pointerId: 1, clientX: 100, clientY: 100 });
  assert.equal(c.active(), 1);
});
