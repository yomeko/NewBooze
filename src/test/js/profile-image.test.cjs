// プロフィール画像の選択・切り抜き・キャンセル・保存を確認するテスト。
// 画像やファイル送信の代役を用意し、描いた範囲と送信回数を確認する。実際の画像は送信しない。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

// 画面要素と操作の代役を作り、対象のJavaScriptを実行してテスト用の操作口を返す。
function setup() {
  const elements = new Map();
  const element = id => {
    if (!elements.has(id)) elements.set(id, {
      value: '50', hidden: true, style: {}, dataset: {}, files: [], handlers: {},
      addEventListener(type, handler) { this.handlers[type] = handler; },
      focus() {}, setPointerCapture() {}, clientWidth: 400,
      async decode() {}, naturalWidth: 1200, naturalHeight: 800,
    });
    return elements.get(id);
  };
  let submitted = 0, drawn = null, revoked = 0;
  const context = {
    document: {
      addEventListener(type, handler) { handler(); },
      querySelector(selector) {
        if (['.menu-toggle', 'header nav', '.site-header', '#open-image-editor'].includes(selector)) return null;
        return element(selector);
      },
      querySelectorAll() { return []; },
      body: { classList: { add() {}, remove() {} } },
      createElement() { return {
        getContext() { return { fillRect() {}, drawImage(...args) { drawn = args; } }; },
        toBlob(callback) { callback({ type: 'image/jpeg' }); },
      }; },
    },
    URL: { createObjectURL() { return 'blob:chosen'; }, revokeObjectURL() { revoked++; } },
    DataTransfer: class { constructor() { this.files = []; this.items = { add: file => this.files.push(file) }; } },
    File: class { constructor(parts, name, options) { Object.assign(this, { parts, name }, options); } },
    HTMLFormElement: { prototype: { submit() { submitted++; } } },
  };
  vm.runInNewContext(fs.readFileSync('src/main/resources/static/js/main.js', 'utf8'), context);
  return { element, get submitted() { return submitted; }, get drawn() { return drawn; }, get revoked() { return revoked; } };
}
async function choose(app, file = { type: 'image/png', size: 1024 }) {
  const input = app.element('#profile-image-file');
  input.files = [file];
  input.handlers.change();
  await new Promise(resolve => setImmediate(resolve));
}

// 画像選択でプレビューだけを開き、キャンセルすると保存済みの画像を変更しないことを確認する。
test('first upload opens the round-frame preview without submitting; cancel keeps the saved image', async () => {
  const app = setup();
  await choose(app);
  assert.equal(app.element('#image-editor').hidden, false);
  assert.equal(app.element('#profile-position-preview').style.width, '150%');
  assert.equal(app.submitted, 0);
  app.element('#cancel-image-crop').handlers.click();
  assert.equal(app.element('#image-editor').hidden, true);
  assert.equal(app.element('#profile-image-file').value, '');
  assert.equal(app.submitted, 0);
  assert.equal(app.revoked, 1);
});

// 表示した範囲と同じ切り抜きをJPEGにして、1回だけフォーム送信することを確認する。
test('save submits the exact selected crop once as a JPEG', async () => {
  const app = setup();
  await choose(app);
  app.element('#image-zoom').value = 200;
  app.element('#position-x').value = 100;
  app.element('#position-y').value = 0;
  await app.element('#save-image-crop').handlers.click();
  assert.deepEqual(app.drawn.slice(1), [800, 0, 400, 400, 0, 0, 512, 512]);
  assert.equal(app.element('#profile-image-file').files[0].type, 'image/jpeg');
  assert.equal(app.submitted, 1);
  await app.element('#save-image-crop').handlers.click();
  assert.equal(app.submitted, 1);
});

// 形式が違うファイルや20MBを超えるファイルでは、編集画面も送信も開始しないことを確認する。
test('invalid and oversized files never open the editor or submit', async () => {
  for (const file of [{ type: 'text/plain', size: 10 }, { type: 'image/png', size: 21 * 1024 * 1024 }]) {
    const app = setup();
    await choose(app, file);
    assert.equal(app.element('#image-editor').hidden, true);
    assert.equal(app.element('#profile-image-error').hidden, false);
    assert.equal(app.submitted, 0);
  }
});

// 読み込めない画像は、保存せずエラーを表示することを確認する。
test('an unreadable image reports an error without saving', async () => {
  const app = setup();
  app.element('#profile-position-preview').decode = async () => { throw Error('invalid image'); };
  await choose(app);
  assert.equal(app.element('#profile-image-error').hidden, false);
  assert.equal(app.element('#image-editor').hidden, true);
  assert.equal(app.submitted, 0);
});
