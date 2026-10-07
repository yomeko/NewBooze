// 診断画面の質問移動、回答の保持、最後の送信、二重送信防止を確認するテスト。
// ブラウザの要素を小さな代役オブジェクトに置き換え、main.jsを実行して操作を再現する。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

// 画面要素と操作の代役を作り、対象のJavaScriptを実行してテスト用の操作口を返す。
function setup() {
  const element = () => ({ disabled: false, handlers: {}, addEventListener(type, fn) { this.handlers[type] = fn; }, focus() {}, classList: { toggle() {} } });
  const questions = Array.from({ length: 4 }, (_, index) => {
    const answer = { disabled: true, value: '' };
    const radios = [0, 1].map(option => Object.assign(element(), { value: `${index * 2 + option + 1}`, checked: false }));
    return Object.assign(element(), { answer, radios,
      querySelector(selector) {
        if (selector.includes(':checked')) return radios.find(r => r.checked);
        if (selector.includes('hidden')) return answer;
        return element();
      }, querySelectorAll() { return radios; },
    });
  });
  const controls = Object.fromEntries(['#diagnosis-form', '#quiz-back', '#quiz-next', '#quiz-position', '#quiz-progress'].map(id => [id, element()]));
  const windowHandlers = {};
  const documentHandlers = {};
  const confirmations = [];
  let confirmResult = false;
  vm.runInNewContext(fs.readFileSync('src/main/resources/static/js/main.js', 'utf8'), {
    URL,
    document: { addEventListener(type, fn) { if (type === 'DOMContentLoaded') fn(); else documentHandlers[type] = fn; },
      querySelector(selector) { return controls[selector] || null; },
      querySelectorAll(selector) { return selector.includes('.question') ? questions : []; },
    }, window: { addEventListener(type, fn) { windowHandlers[type] = fn; },
      location: new URL('https://example.com/diagnosis'),
      confirm(message) { confirmations.push(message); return confirmResult; },
    },
  });
  return { questions, controls, windowHandlers, documentHandlers, confirmations,
    allowLeaving() { confirmResult = true; },
    navigate(href = '/search', options = {}) {
      let prevented = false;
      const link = { href, target: options.linkTarget || '', hasAttribute() { return false; }, getAttribute() { return href; } };
      documentHandlers.click({ button: 0, target: { closest() { return link; } },
        preventDefault() { prevented = true; }, ...options });
      return !prevented;
    },
    unload() {
      const event = { prevented: false, preventDefault() { this.prevented = true; } };
      windowHandlers.beforeunload(event);
      return event;
    },
    choose(index, option = 0) {
      questions[index].radios.forEach((r, i) => { r.checked = i === option; });
      questions[index].radios[option].handlers.change();
    },
    next() { let prevented = false; controls['#diagnosis-form'].handlers.submit({ preventDefault() { prevented = true; } }); return !prevented; },
    back() { controls['#quiz-back'].handlers.click(); },
    get position() { return controls['#quiz-position'].textContent; },
  };
}

// 選択だけでは次へ進まず、戻った質問の回答を保持・変更できることを確認する。
test('choosing an answer stays on the question; back preserves and can replace an answer', () => {
  const quiz = setup();
  assert.equal(quiz.position, '全4問中 1問目');
  assert.equal(quiz.controls['#quiz-next'].disabled, true);
  assert.equal(quiz.next(), false);
  quiz.choose(0);
  assert.equal(quiz.position, '全4問中 1問目');
  assert.equal(quiz.next(), false);
  assert.equal(quiz.position, '全4問中 2問目');
  quiz.back();
  assert.equal(quiz.questions[0].radios[0].checked, true);
  quiz.choose(0, 1);
  assert.equal(quiz.questions[0].answer.value, '2');
  assert.equal(quiz.next(), false);
  assert.equal(quiz.questions[0].answer.value, '2');
});

test('leaving asks for confirmation; cancelling keeps the current question and answer', () => {
  const quiz = setup();
  quiz.choose(0);
  quiz.next();
  assert.equal(quiz.navigate(), false);
  assert.match(quiz.confirmations[0], /また最初からになります/);
  assert.equal(quiz.position, '全4問中 2問目');
  assert.equal(quiz.questions[0].answer.value, '1');
  assert.equal(quiz.unload().prevented, true);
  quiz.allowLeaving();
  assert.equal(quiz.navigate(), true);
  assert.equal(quiz.unload().prevented, false);
  quiz.windowHandlers.pageshow();
  assert.equal(quiz.unload().prevented, true);
});

test('search and logout require confirmation, but quiz actions and new tabs do not', () => {
  const quiz = setup();
  let prevented = false;
  quiz.documentHandlers.submit({ target: {}, preventDefault() { prevented = true; } });
  assert.equal(prevented, true);
  quiz.documentHandlers.submit({ target: quiz.controls['#diagnosis-form'], preventDefault() { assert.fail('quiz submission intercepted'); } });
  assert.equal(quiz.navigate('#help'), true);
  assert.equal(quiz.navigate('/search', { ctrlKey: true }), true);
  assert.equal(quiz.navigate('/search', { linkTarget: '_blank' }), true);
  assert.equal(quiz.confirmations.length, 1);
  for (let index = 0; index < 4; index++) { quiz.choose(index); quiz.next(); }
  assert.equal(quiz.unload().prevented, false);
});

// 全4問を答えた最後の操作だけで送信し、続けて押しても二重送信しないことを確認する。
test('all four answers submit only on the explicit final action and prevent duplicate submission', () => {
  const quiz = setup();
  for (let index = 0; index < 3; index++) { quiz.choose(index); assert.equal(quiz.next(), false); }
  assert.equal(quiz.position, '全4問中 4問目');
  assert.equal(quiz.controls['#quiz-next'].textContent, '診断結果を見る');
  quiz.choose(3);
  assert.equal(quiz.controls['#quiz-next'].disabled, false);
  assert.equal(quiz.next(), true);
  assert.equal(quiz.controls['#quiz-next'].disabled, true);
  assert.equal(quiz.next(), false);
  assert.deepEqual(quiz.questions.map(q => q.answer.value), ['1', '3', '5', '7']);
  quiz.windowHandlers.pageshow();
  assert.equal(quiz.controls['#quiz-next'].disabled, false);
});
