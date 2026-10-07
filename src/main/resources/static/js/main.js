// 全画面から読み込む操作用スクリプト。メニュー、ヘッダー、画像編集、診断を担当する。
// Javaが用意したHTMLに対して、クリックや入力に応じた表示の切り替えを行う。
// HTMLの読み込みが終わってから動かす。querySelectorは指定したIDやクラスの要素を探す。
// ページごとに存在する要素が違うので、見つかった機能だけを初期化する。
document.addEventListener('DOMContentLoaded', () => {
  // 通常ナビゲーションとは別に、共通のサイトメニューを開く。
  const toggle = document.querySelector('.menu-toggle');
  const menu = document.querySelector('#site-menu');
  if (toggle && menu) {
    toggle.addEventListener('click', () => {
      // dialogを開き、読み上げ用の開閉状態と、背景のスクロールを止めるCSSのクラスも更新する。
      menu.showModal();
      toggle.setAttribute('aria-expanded', 'true');
      document.body.classList.add('site-menu-open');
    });
    menu.querySelector('.site-menu-close').addEventListener('click', () => menu.close());
    menu.addEventListener('click', event => {
      if (event.target === menu) {
        // メニューの長方形の範囲とクリック位置を比較し、外側の背景をクリックしたときだけ閉じる。
        const bounds = menu.getBoundingClientRect();
        if (event.clientX < bounds.left || event.clientX > bounds.right ||
            event.clientY < bounds.top || event.clientY > bounds.bottom) menu.close();
      }
    });
    // Escキーで閉じた場合にも状態とフォーカスを戻す。
    menu.addEventListener('close', () => {
      toggle.setAttribute('aria-expanded', 'false');
      document.body.classList.remove('site-menu-open');
      toggle.focus();
    });
  }

  const header = document.querySelector('.site-header');
  if (header) {
    // 縮小による高さの変化で切り替えが往復しないよう、閾値を離す。
    const compact = () => {
      if (window.scrollY > 160) header.classList.add('is-compact');
      else if (window.scrollY < 24) header.classList.remove('is-compact');
    };
    window.addEventListener('scroll', compact, { passive: true });
    compact();
    const navigation = header.querySelector('#main-navigation');
    const previous = header.querySelector('.nav-previous');
    const next = header.querySelector('.nav-next');
    const hint = header.querySelector('.nav-scroll-hint');
    if (navigation && previous && next && hint) {
      // メニュー項目が横幅に収まらないときだけ矢印と案内を出し、端まで来た矢印は押せなくする。
      const updateNavigation = () => {
        const overflow = navigation.scrollWidth > navigation.clientWidth + 2;
        previous.hidden = !overflow;
        next.hidden = !overflow;
        previous.disabled = navigation.scrollLeft <= 2;
        next.disabled = navigation.scrollLeft + navigation.clientWidth >= navigation.scrollWidth - 2;
        hint.hidden = !overflow;
      };
      navigation.scrollLeft = 0;
      navigation.addEventListener('scroll', updateNavigation, { passive: true });
      window.addEventListener('resize', updateNavigation);
      [previous, next].forEach((button, index) => button.addEventListener('click', () => {
        // 表示幅の70%ずつ横へ移動する。動きを減らす設定の人には、移動アニメーションを使わない。
        navigation.scrollBy({ left: (index === 0 ? -1 : 1) * navigation.clientWidth * 0.7,
          behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth' });
      }));
      updateNavigation();
      document.fonts?.ready.then(updateNavigation);
    }
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
    // sourceUrlは選んだ画像をブラウザ内で見るための一時URL。busyは保存中の二重操作を防ぐ印。
    // requestIdは画像読み込みの順番を区別し、古い読み込みが後から完了しても画面へ反映しないために使う。
    let sourceUrl = null;
    let returnFocus = null;
    let busy = false;
    let requestId = 0;
    let drag = null;
    // 元の画像から切り抜く正方形の辺と左上位置を計算する。
    // 拡大率が上がるほど切り抜く範囲は小さくなり、x・yの0〜100で左右・上下の位置を決める。
    const crop = () => {
      const side = Math.min(preview.naturalWidth, preview.naturalHeight) * 100 / Number(zoom.value);
      return { side, left: (preview.naturalWidth - side) * Number(x.value) / 100,
        top: (preview.naturalHeight - side) * Number(y.value) / 100 };
    };
    // 切り抜く範囲をプレビュー枠いっぱいに拡大し、保存される部分を画面で確認できるようにする。
    const render = () => {
      if (!preview.naturalWidth) return;
      const { side, left, top } = crop();
      preview.style.width = `${preview.naturalWidth / side * 100}%`;
      preview.style.height = `${preview.naturalHeight / side * 100}%`;
      preview.style.left = `${-left / side * 100}%`;
      preview.style.top = `${-top / side * 100}%`;
    };
    const reset = () => { x.value = y.value = 50; zoom.value = 100; render(); };
    // 編集画面を閉じ、入力したファイルと一時URLを解放し、開いたときの操作位置へフォーカスを戻す。
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
    // 画像を読み込んでから編集画面を開く。既存画像の編集なら保存済みの位置・拡大率を復元する。
    const open = async (src, existing = false) => {
      const id = ++requestId;
      returnFocus = existing ? document.querySelector('#open-image-editor') : fileInput;
      fileError.hidden = true;
      preview.src = src;
      try {
        // 画像の読み込み完了を待つ。待っている間に別の画像を開いた・閉じた場合は古い処理を終了する。
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
    // 画像を選んだら、送信前に形式と20MB以下かを確認し、一時URLでプレビューを開く。
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
      // Tabキーで操作しても編集画面の外へ移らないよう、最初と最後のボタンの間を循環させる。
      if (event.key === 'Tab') {
        const controls = [...imageEditor.querySelectorAll('button, input')].filter(el => !el.disabled);
        const first = controls[0], last = controls.at(-1);
        if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
        else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
      }
    });
    [x, y, zoom].forEach(control => control.addEventListener('input', render));
    document.querySelector('#reset-image-position').addEventListener('click', reset);
    // マウスや指で画像を動かすため、押した位置を記録する。枠の外に出ても動きを追跡する。
    stage.addEventListener('pointerdown', event => {
      if (busy || (event.pointerType === 'mouse' && event.button !== 0)) return;
      drag = { x: event.clientX, y: event.clientY };
      stage.setPointerCapture(event.pointerId);
    });
    stage.addEventListener('pointermove', event => {
      if (!drag) return;
      const { side } = crop();
      // 画面上の移動量を元画像のピクセル数へ換算し、位置スライダーの0〜100に収まる値へ変える。
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
    // 保存ボタンを押したら操作を止め、選んだ範囲をJPEGに変換してフォームへ入れる。
    save.addEventListener('click', async () => {
      if (busy) return;
      busy = true;
      save.disabled = true;
      save.textContent = '保存中…';
      error.hidden = true;
      try {
        const { side, left, top } = crop();
        // canvasはブラウザ内で画像を描く領域。プレビューと同じ部分を512×512pxの白背景に描く。
        const canvas = document.createElement('canvas');
        canvas.width = canvas.height = 512;
        const context = canvas.getContext('2d');
        context.fillStyle = '#fff';
        context.fillRect(0, 0, 512, 512);
        context.drawImage(preview, left, top, side, side, 0, 0, 512, 512);
        const blob = await new Promise(resolve => canvas.toBlob(resolve, 'image/jpeg', 0.92));
        if (!blob) throw new Error('crop');
        // 切り抜いたJPEGを新しいファイルとして作り、通常のファイル入力欄にセットする。
        const data = new DataTransfer();
        data.items.add(new File([blob], 'profile.jpg', { type: 'image/jpeg' }));
        fileInput.files = data.files;
        // 通常のフォームとして送信し、本人の操作を確認するCSRFトークンと保存後の画面移動を使う。
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

  // 写真未登録・読み込み失敗でも同じ大きさの比較カードを保つ。
  document.querySelectorAll('.sake-card-image img').forEach(img => {
    const fallback = () => {
      img.hidden = true;
      img.parentElement.querySelector('.sake-image-fallback').hidden = false;
    };
    img.addEventListener('error', fallback);
    if (img.complete && img.naturalWidth === 0) fallback();
  });

  // ここから好み診断の処理。質問画面以外には設問がないので、この時点で終了する。
  // ...は見つかった要素の一覧を配列へ変える書き方。
  const questions = [...document.querySelectorAll('#diagnosis-form .question')];
  if (!questions.length) return;
  const form = document.querySelector('#diagnosis-form');
  const back = document.querySelector('#quiz-back');
  const next = document.querySelector('#quiz-next');
  const position = document.querySelector('#quiz-position');
  const progress = document.querySelector('#quiz-progress');
  let step = 0;
  let submitted = false;
  let leaving = false;
  const leaveMessage = '診断中に他のページへ移動すると、また最初からになります。移動してもよろしいですか？';
  const confirmLeave = event => {
    if (submitted || leaving) return;
    if (window.confirm(leaveMessage)) leaving = true;
    else event.preventDefault();
  };
  // リンク・検索・ログアウトで質問画面を離れる前に確認する。
  document.addEventListener('click', event => {
    if (event.defaultPrevented || event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
    const link = event.target.closest('a[href]');
    if (!link || link.hasAttribute('download') || (link.target && link.target !== '_self')) return;
    const href = link.getAttribute('href');
    if (href.startsWith('#')) return;
    const destination = new URL(link.href, window.location.href);
    if (!['http:', 'https:'].includes(destination.protocol)) return;
    if (destination.hash && destination.origin === window.location.origin &&
        destination.pathname === window.location.pathname && destination.search === window.location.search) return;
    confirmLeave(event);
  });
  document.addEventListener('submit', event => {
    if (event.defaultPrevented || event.target === form ||
        (event.target.target && event.target.target !== '_self')) return;
    confirmLeave(event);
  });
  // ブラウザの戻る・再読み込み・タブを閉じる操作には標準の離脱確認を使う。
  window.addEventListener('beforeunload', event => {
    if (submitted || leaving) return;
    event.preventDefault();
    event.returnValue = '';
  });

  // 質問ごとのラジオボタンの選択を、送信用の隠し入力（name=choice）に写す。
  // 未回答の隠し入力はdisabledにし、空の選択肢IDがサーバーへ届かないようにする。
  const syncAnswer = question => {
    const selected = question.querySelector('input[type="radio"]:checked');
    const answer = question.querySelector('input[type="hidden"][name="choice"]');
    answer.value = selected?.value || '';
    answer.disabled = !selected;
    return !!selected;
  };
  // 今の質問だけを表示し、何問目か・進捗・戻る/次へボタンの状態をそろえる。
  const render = (focus = false) => {
    questions.forEach((question, index) => {
      question.classList.toggle('active', index === step);
      question.hidden = index !== step;
    });
    position.textContent = `全${questions.length}問中 ${step + 1}問目`;
    progress.value = step + 1;
    back.disabled = step === 0 || submitted;
    next.disabled = submitted || !syncAnswer(questions[step]);
    next.textContent = step === questions.length - 1 ? '診断結果を見る' : '次へ →';
    if (focus) questions[step].querySelector('legend').focus({ preventScroll: true });
  };
  questions.forEach(question => {
    question.querySelectorAll('input[type="radio"]').forEach(radio => {
      radio.addEventListener('change', () => {
        syncAnswer(question);
        render();
      });
    });
  });
  back.addEventListener('click', () => {
    if (step === 0 || submitted) return;
    step--;
    render(true);
  });
  // 最後の質問までは送信を止めて次の質問へ進む。
  // 最後は全問回答済みか確認し、1回だけ回答一覧を送る。preventDefaultは通常の送信を止める命令。
  form.addEventListener('submit', event => {
    if (submitted) { event.preventDefault(); return; }
    if (!syncAnswer(questions[step])) { event.preventDefault(); render(); return; }
    if (step < questions.length - 1) {
      event.preventDefault();
      step++;
      render(true);
      return;
    }
    const incomplete = questions.findIndex(question => !syncAnswer(question));
    if (incomplete !== -1) {
      event.preventDefault();
      step = incomplete;
      render(true);
      return;
    }
    submitted = true;
    back.disabled = next.disabled = true;
    next.textContent = '結果を準備中…';
  });
  // 戻る操作や履歴からの復帰でも回答を保持する。
  window.addEventListener('pageshow', () => { submitted = false; leaving = false; render(); });
  render();
});
