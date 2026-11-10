const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

test('keyboard activation moves focus into an open hover menu and Escape returns it', () => {
  const documentEvents = {};
  const document = {
    addEventListener(type, handler) {
      if (type === 'DOMContentLoaded') handler();
      else documentEvents[type] = handler;
    },
    querySelector(selector) {
      return { '.menu-toggle': toggle, '#site-menu': menu }[selector] ?? null;
    },
    querySelectorAll() { return []; },
  };
  class Element {
    handlers = {};
    attributes = {};
    style = {};
    addEventListener(type, handler) { this.handlers[type] = handler; }
    setAttribute(name, value) { this.attributes[name] = value; }
    focus() { document.activeElement = this; }
    getBoundingClientRect() { return { bottom: 60, right: 360 }; }
  }
  const toggle = new Element();
  const close = new Element();
  const menu = Object.assign(new Element(), {
    open: false,
    querySelector() { return close; },
    contains(element) { return element === close; },
    show() { this.open = true; close.focus(); },
    close() { this.open = false; this.handlers.close(); },
  });
  const window = { innerWidth: 390, addEventListener() {}, clearTimeout() {} };
  vm.runInNewContext(fs.readFileSync('src/main/resources/static/js/main.js', 'utf8'), {
    document, window, HTMLElement: Element,
  });

  toggle.focus();
  toggle.handlers.pointerenter({ pointerType: 'mouse' });
  assert.equal(menu.open, true);
  assert.equal(document.activeElement, toggle);

  toggle.handlers.keydown({ key: 'ArrowDown', preventDefault() {} });
  assert.equal(toggle.attributes['aria-expanded'], 'true');
  assert.equal(document.activeElement, close);

  documentEvents.keydown({ key: 'Escape', preventDefault() {} });
  assert.equal(menu.open, false);
  assert.equal(toggle.attributes['aria-expanded'], 'false');
  assert.equal(document.activeElement, toggle);
});
