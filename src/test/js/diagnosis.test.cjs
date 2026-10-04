const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

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
  vm.runInNewContext(fs.readFileSync('src/main/resources/static/js/main.js', 'utf8'), {
    document: { addEventListener(type, fn) { fn(); },
      querySelector(selector) { return controls[selector] || null; },
      querySelectorAll(selector) { return selector.includes('.question') ? questions : []; },
    }, window: { addEventListener(type, fn) { windowHandlers[type] = fn; } },
  });
  return { questions, controls, windowHandlers,
    choose(index, option = 0) {
      questions[index].radios.forEach((r, i) => { r.checked = i === option; });
      questions[index].radios[option].handlers.change();
    },
    next() { let prevented = false; controls['#diagnosis-form'].handlers.submit({ preventDefault() { prevented = true; } }); return !prevented; },
    back() { controls['#quiz-back'].handlers.click(); },
    get position() { return controls['#quiz-position'].textContent; },
  };
}

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
