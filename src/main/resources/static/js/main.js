// 全画面共通のUI挙動をまとめたスクリプト。
// 出田担当の本番デザイン差し替え時も、th:src="@{/js/main.js}" として
// 各テンプレート(home/search/detail/diagnosis等)から共通で読み込まれる想定。
document.addEventListener('DOMContentLoaded', () => {
  // ---- ハンバーガーメニュー（画面幅が狭い時のナビゲーション開閉） ----
  const toggle = document.querySelector('.menu-toggle');
  const nav = document.querySelector('header nav');
  if (toggle && nav) {
    toggle.setAttribute('aria-expanded', 'false');
    // クリックのたびに.openクラスを付け外しし、CSS側(@media(max-width:700px))で
    // nav.openの表示・非表示を切り替える仕組み
    toggle.addEventListener('click', () => {
      const isOpen = nav.classList.toggle('open');
      toggle.setAttribute('aria-expanded', String(isOpen));
      toggle.setAttribute('aria-label', isOpen ? 'メニューを閉じる' : 'メニューを開く');
    });
  }

  // 写真は確認画面で確定するまで送信せず、表示範囲と同じ正方形を保存する。
  const imageEditor = document.querySelector('#image-editor');
  if (imageEditor) {
    const preview = document.querySelector('#profile-position-preview');
    const stage = document.querySelector('#crop-stage');
    const fileInput = document.querySelector('#profile-image-file');
    const uploadForm = document.querySelector('#profile-image-upload');
    const zoom = document.querySelector('#image-zoom');
    const x = document.querySelector('#position-x');
    const y = document.querySelector('#position-y');
    const save = document.querySelector('#save-image-crop');
    const error = document.querySelector('#image-crop-error');
    const fileError = document.querySelector('#profile-image-error');
    const close = document.querySelector('.editor-close');
    let sourceUrl = null;
    let returnFocus = null;
    let busy = false;
    let requestId = 0;
    let drag = null;
    const crop = () => {
      const side = Math.min(preview.naturalWidth, preview.naturalHeight) * 100 / Number(zoom.value);
      return { side, left: (preview.naturalWidth - side) * Number(x.value) / 100,
        top: (preview.naturalHeight - side) * Number(y.value) / 100 };
    };
    const render = () => {
      if (!preview.naturalWidth) return;
      const { side, left, top } = crop();
      preview.style.width = `${preview.naturalWidth / side * 100}%`;
      preview.style.height = `${preview.naturalHeight / side * 100}%`;
      preview.style.left = `${-left / side * 100}%`;
      preview.style.top = `${-top / side * 100}%`;
    };
    const reset = () => { x.value = y.value = 50; zoom.value = 100; render(); };
    const dismiss = () => {
      if (busy) return;
      requestId++;
      imageEditor.hidden = true;
      document.body.classList.remove('modal-open');
      fileInput.value = '';
      if (sourceUrl) URL.revokeObjectURL(sourceUrl);
      sourceUrl = null;
      drag = null;
      returnFocus?.focus();
    };
    const open = async (src, existing = false) => {
      const id = ++requestId;
      returnFocus = existing ? document.querySelector('#open-image-editor') : fileInput;
      fileError.hidden = true;
      preview.src = src;
      try {
        await preview.decode();
        if (id !== requestId) return;
        reset();
        if (existing) {
          x.value = x.dataset.currentValue || 50;
          y.value = y.dataset.currentValue || 50;
          zoom.value = save.dataset.currentZoom || 100;
        }
        error.hidden = true;
        imageEditor.hidden = false;
        document.body.classList.add('modal-open');
        render();
        close.focus();
      } catch {
        if (id !== requestId) return;
        dismiss();
        fileError.textContent = '画像を読み込めませんでした。別の写真を選択してください。';
        fileError.hidden = false;
      }
    };
    uploadForm.addEventListener('submit', event => event.preventDefault());
    fileInput.addEventListener('change', () => {
      const file = fileInput.files[0];
      if (!file) return;
      if (!['image/jpeg', 'image/png', 'image/gif'].includes(file.type) || file.size > 20 * 1024 * 1024) {
        fileError.textContent = '20MB以下のJPEG・PNG・GIF画像を選択してください。';
        fileError.hidden = false;
        fileInput.value = '';
        return;
      }
      if (sourceUrl) URL.revokeObjectURL(sourceUrl);
      sourceUrl = URL.createObjectURL(file);
      open(sourceUrl);
    });
    document.querySelector('#open-image-editor')?.addEventListener('click', () => open(preview.dataset.currentSrc, true));
    close.addEventListener('click', dismiss);
    document.querySelector('#cancel-image-crop').addEventListener('click', dismiss);
    imageEditor.addEventListener('click', event => { if (event.target === imageEditor) dismiss(); });
    imageEditor.addEventListener('keydown', event => {
      if (event.key === 'Escape') { event.preventDefault(); dismiss(); }
      if (event.key === 'Tab') {
        const controls = [...imageEditor.querySelectorAll('button, input')].filter(el => !el.disabled);
        const first = controls[0], last = controls.at(-1);
        if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
        else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
      }
    });
    [x, y, zoom].forEach(control => control.addEventListener('input', render));
    document.querySelector('#reset-image-position').addEventListener('click', reset);
    stage.addEventListener('pointerdown', event => {
      if (busy || (event.pointerType === 'mouse' && event.button !== 0)) return;
      drag = { x: event.clientX, y: event.clientY };
      stage.setPointerCapture(event.pointerId);
    });
    stage.addEventListener('pointermove', event => {
      if (!drag) return;
      const { side } = crop();
      const scale = side / stage.clientWidth;
      const update = (control, delta, overflow) => {
        if (overflow > 0) control.value = Math.max(0, Math.min(100, Number(control.value) - delta * scale / overflow * 100));
      };
      update(x, event.clientX - drag.x, preview.naturalWidth - side);
      update(y, event.clientY - drag.y, preview.naturalHeight - side);
      drag = { x: event.clientX, y: event.clientY };
      render();
    });
    ['pointerup', 'pointercancel', 'lostpointercapture'].forEach(type => stage.addEventListener(type, () => { drag = null; }));
    save.addEventListener('click', async () => {
      if (busy) return;
      busy = true;
      save.disabled = true;
      save.textContent = '保存中…';
      error.hidden = true;
      try {
        const { side, left, top } = crop();
        const canvas = document.createElement('canvas');
        canvas.width = canvas.height = 512;
        const context = canvas.getContext('2d');
        context.fillStyle = '#fff';
        context.fillRect(0, 0, 512, 512);
        context.drawImage(preview, left, top, side, side, 0, 0, 512, 512);
        const blob = await new Promise(resolve => canvas.toBlob(resolve, 'image/jpeg', 0.92));
        if (!blob) throw new Error('crop');
        const data = new DataTransfer();
        data.items.add(new File([blob], 'profile.jpg', { type: 'image/jpeg' }));
        fileInput.files = data.files;
        // ネイティブ送信で既存のCSRFトークン、保存後のリダイレクトと通知を利用する。
        HTMLFormElement.prototype.submit.call(uploadForm);
      } catch {
        busy = false;
        save.disabled = false;
        save.textContent = '切り抜いて使用';
        error.textContent = '画像を保存できませんでした。もう一度お試しください。';
        error.hidden = false;
      }
    });
  }

  // ---- S02診断画面：1問ずつ表示するウィザード形式の制御 ----
  // 診断画面(diagnosis.html)以外ではこの要素が存在しないため、
  // 見つからなければ何もせず終了する（他画面でエラーにならないようにするガード）
  const questions = [...document.querySelectorAll('.question')];
  if (!questions.length) return;

  let step = 0; // 現在表示中の設問インデックス（0始まり）
  const previous = document.querySelector('#previous');
  const next = document.querySelector('#next');
  const submit = document.querySelector('#submit');

  // 現在のstepに応じて、表示する設問・各種ボタンの表示/非表示を切り替える
  const render = () => {
    questions.forEach((question, index) => question.classList.toggle('active', index === step));
    previous.hidden = step === 0;                       // 最初の設問では「戻る」を隠す
    next.hidden = step === questions.length - 1;         // 最後の設問では「次へ」を隠す
    submit.hidden = step !== questions.length - 1;       // 最後の設問でのみ「結果を見る」を表示
  };

  // 設問ごとに回答を保持し、選択したら次の設問へ進む。
  questions.forEach((question, index) => {
    question.querySelectorAll('input[type="radio"]').forEach(radio => {
      radio.addEventListener('click', () => {
        if (index !== step || !radio.checked) return;
        const answer = question.querySelector('input[type="hidden"][name="choice"]');
        answer.value = radio.value;
        answer.disabled = false;
        if (step < questions.length - 1) {
          step++;
          render();
        }
      });
    });
  });

  next.addEventListener('click', () => {
    // 未回答のまま次へ進ませない簡易バリデーション（サーバー側のth:requiredとは別に、
    // 途中の設問でも回答漏れに早く気づけるようクライアント側でもチェックする）
    if (!questions[step].querySelector('input:checked')) {
      alert('選択肢をひとつ選んでください。');
      return;
    }
    step++;
    render();
  });
  previous.addEventListener('click', () => {
    step--;
    render();
  });

  render(); // 初期表示（1問目のみ表示した状態にする）
});
