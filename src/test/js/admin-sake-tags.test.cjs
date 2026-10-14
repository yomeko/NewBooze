const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

// チェック操作、送信するhidden項目、画面の重みをまとめて検証する。
function setup(initialIds = []) {
  const inputs = {
    children: initialIds.map(value => ({ value })),
    querySelectorAll() { return this.children; },
    replaceChildren(...fields) { this.children = fields; },
  };
  const summary = { textContent: '' };
  const choices = ['フルーティー', '辛口', '軽快', '甘口'].map((name, index) => {
    const badge = { hidden: true, textContent: '' };
    const label = {
      querySelector(selector) { return selector === '[data-tag-name]' ? { textContent: name } : badge; },
    };
    return {
      value: String(index + 1), checked: false, disabled: true, badge,
      closest() { return label; },
      addEventListener(type, handler) { this.change = handler; },
    };
  });
  vm.runInNewContext(fs.readFileSync('src/main/resources/static/js/admin-sake-tags.js', 'utf8'), {
    document: {
      getElementById(id) {
        return {
          'tag-weights': { querySelectorAll() { return choices; } },
          'tag-order-inputs': inputs,
          'tag-weight-summary': summary,
        }[id];
      },
      createElement() { return {}; },
    },
  });
  return {
    choices, summary,
    get submittedIds() { return inputs.children.map(field => field.value); },
    get fields() { return inputs.children; },
    toggle(id, checked) {
      const choice = choices.find(choice => choice.value === id);
      choice.checked = checked;
      choice.change();
    },
  };
}

test('click order determines the submitted order and weights for three and four tags', () => {
  const app = setup();
  assert.ok(app.choices.every(choice => !choice.disabled));
  ['2', '3', '1'].forEach(id => app.toggle(id, true));
  assert.deepEqual(app.submittedIds, ['2', '3', '1']);
  assert.equal(app.summary.textContent, '選択順と重み：辛口（3） → 軽快（2） → フルーティー（1）');
  assert.equal(app.choices[1].badge.textContent, '重み 3');
  assert.equal(app.choices[0].badge.textContent, '重み 1');
  app.toggle('4', true);
  assert.deepEqual(app.submittedIds, ['2', '3', '1', '4']);
  assert.equal(app.summary.textContent, '選択順と重み：辛口（4） → 軽快（3） → フルーティー（2） → 甘口（1）');
  assert.ok(app.fields.every(field => field.name === 'tagIds' && field.type === 'hidden'));
});

test('removing a tag recalculates weights and selecting it again appends it', () => {
  const app = setup(['2', '3', '1', '4']);
  app.toggle('3', false);
  assert.deepEqual(app.submittedIds, ['2', '1', '4']);
  assert.equal(app.choices[2].badge.hidden, true);
  assert.equal(app.choices[1].badge.textContent, '重み 3');
  app.toggle('3', true);
  assert.deepEqual(app.submittedIds, ['2', '1', '4', '3']);
  assert.equal(app.choices[2].badge.textContent, '重み 1');
});

test('an error response restores the server order, ignoring duplicate and unavailable IDs', () => {
  const app = setup(['3', '2', '3', '999', '1']);
  assert.deepEqual(app.submittedIds, ['3', '2', '1']);
  assert.equal(app.summary.textContent, '選択順と重み：軽快（3） → 辛口（2） → フルーティー（1）');
  assert.equal(app.choices[2].checked, true);
  assert.equal(app.choices[3].checked, false);
});

test('one or no selected tags has weight one or no submitted tag IDs', () => {
  const app = setup();
  assert.deepEqual(app.submittedIds, []);
  assert.equal(app.summary.textContent, 'タグは未選択です。');
  app.toggle('2', true);
  assert.equal(app.choices[1].badge.textContent, '重み 1');
  app.toggle('2', false);
  assert.deepEqual(app.submittedIds, []);
  assert.equal(app.choices[1].badge.hidden, true);
  assert.equal(app.summary.textContent, 'タグは未選択です。');
});
