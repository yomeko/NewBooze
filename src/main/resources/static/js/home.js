// JSが無効な場合は全トピックをそのまま読める。
// ホームの読み込み後、「日本酒のある暮らし」の記事を1件ずつ切り替える準備をする。
document.addEventListener('DOMContentLoaded', () => {
  // トピック欄のないページや記事が1件だけの場合は、切り替え処理を作らず終了する。
  const root = document.querySelector('.topics');
  if (!root) return;
  const slides = [...root.querySelectorAll('.topic-slide')];
  if (slides.length < 2) return;
  const controls = root.querySelector('.topic-controls');
  const position = root.querySelector('.topic-position');
  const pause = root.querySelector('[data-topic="pause"]');
  const motion = window.matchMedia('(prefers-reduced-motion: reduce)');
  // indexは表示中の記事番号（0始まり）。初期状態は手動操作で、自動切り替えは利用者が開始する。
  let index = 0;
  let paused = true;
  let hovered = false;
  let timer;
  // 現在の記事だけを見せ、記事番号と開始/停止ボタンの文言を更新する。
  const render = () => {
    slides.forEach((slide, i) => { slide.hidden = i !== index; });
    position.textContent = `${index + 1} / ${slides.length}`;
    pause.textContent = paused ? '自動切り替えを開始' : '自動切り替えを停止';
  };
  // 次の自動切り替えを7秒後に予約する。停止中、マウス操作中、別タブ表示中、
  // 欄内をキーボードで操作中は予約しない。先に古い予約を消して、二重に動くのを防ぐ。
  const schedule = () => {
    clearTimeout(timer);
    if (paused || hovered || document.hidden || root.contains(document.activeElement)) return;
    timer = setTimeout(() => { index = (index + 1) % slides.length; render(); schedule(); }, 7000);
  };
  // 前後の記事へ移る。余りを求める%を使い、最後の次は最初、最初の前は最後につなげる。
  const move = delta => { index = (index + delta + slides.length) % slides.length; render(); schedule(); };
  root.querySelector('[data-topic="previous"]').addEventListener('click', () => move(-1));
  root.querySelector('[data-topic="next"]').addEventListener('click', () => move(1));
  pause.addEventListener('click', () => { paused = !paused; render(); schedule(); });
  root.addEventListener('mouseenter', () => { hovered = true; schedule(); });
  root.addEventListener('mouseleave', () => { hovered = false; schedule(); });
  root.addEventListener('focusin', schedule);
  // フォーカスが次の要素へ移った後に、自動切り替えを再開できるか判断する。
  root.addEventListener('focusout', () => setTimeout(schedule, 0));
  document.addEventListener('visibilitychange', schedule);
  // 利用者が「動きを減らす」設定へ変えたら自動切り替えを停止する。
  motion.addEventListener('change', () => { if (motion.matches) paused = true; render(); schedule(); });
  controls.hidden = false;
  render();
  schedule();
});
