// 全画面共通のUI挙動をまとめたスクリプト。
// 出田担当の本番デザイン差し替え時も、th:src="@{/js/main.js}" として
// 各テンプレート(home/search/detail/diagnosis等)から共通で読み込まれる想定。
document.addEventListener('DOMContentLoaded', () => {
  // ---- ハンバーガーメニュー（画面幅が狭い時のナビゲーション開閉） ----
  const toggle = document.querySelector('.menu-toggle');
  const nav = document.querySelector('header nav');
  if (toggle && nav) {
    // クリックのたびに.openクラスを付け外しし、CSS側(@media(max-width:700px))で
    // nav.openの表示・非表示を切り替える仕組み
    toggle.addEventListener('click', () => nav.classList.toggle('open'));
  }

  // ---- マイページ：プロフィール画像の表示位置を即時プレビュー ----
  const profilePreview = document.querySelector('#profile-position-preview');
  const positionX = document.querySelector('#position-x');
  const positionY = document.querySelector('#position-y');
  const positionXValue = document.querySelector('#position-x-value');
  const positionYValue = document.querySelector('#position-y-value');
  if (profilePreview && positionX && positionY) {
    const previewPosition = () => {
      profilePreview.style.objectPosition = `${positionX.value}% ${positionY.value}%`;
      positionXValue.textContent = `${positionX.value}%`;
      positionYValue.textContent = `${positionY.value}%`;
    };
    positionX.addEventListener('input', previewPosition);
    positionY.addEventListener('input', previewPosition);
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
