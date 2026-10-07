// JSが無効な場合は全トピックをそのまま読める。
document.addEventListener('DOMContentLoaded', () => {
  const root = document.querySelector('.topics');
  if (!root) return;
  const track = root.querySelector('.topic-slides');
  const slides = [...track.querySelectorAll('.topic-slide')];
  if (slides.length < 2) return;
  const controls = root.querySelector('.topic-controls');
  const position = root.querySelector('.topic-position');
  const pause = root.querySelector('[data-topic="pause"]');
  const previous = root.querySelector('[data-topic="previous"]');
  const next = root.querySelector('[data-topic="next"]');
  const dotGroup = root.querySelector('.topic-dots');
  const motion = window.matchMedia('(prefers-reduced-motion: reduce)');

  // 両端に複製を置き、最初・最後の記事でも左右の隣の記事を見せる。
  const lastClone = slides[slides.length - 1].cloneNode(true);
  const firstClone = slides[0].cloneNode(true);
  for (const clone of [lastClone, firstClone]) {
    clone.setAttribute('aria-hidden', 'true');
    clone.inert = true;
  }
  track.prepend(lastClone);
  track.append(firstClone);
  const panels = [lastClone, ...slides, firstClone];
  const dots = slides.map((_, i) => {
    const dot = document.createElement('button');
    dot.type = 'button';
    dot.className = 'topic-dot';
    dot.setAttribute('aria-label', `${i + 1}番目のトピックを表示`);
    dot.setAttribute('aria-controls', 'topic-slides');
    dot.addEventListener('click', () => select(i));
    dotGroup.append(dot);
    return dot;
  });

  // 初期状態は手動操作。再生ボタンを押すと7秒ごとに切り替える。
  let index = 0;
  let offset = 1;
  let paused = true;
  let timer;
  let touchStart;
  const render = () => {
    track.style.setProperty('--topic-offset', offset);
    panels.forEach((slide, i) => slide.classList.toggle('is-current', i === offset));
    slides.forEach((slide, i) => {
      slide.setAttribute('aria-hidden', String(i !== index));
      slide.inert = i !== index;
    });
    dots.forEach((dot, i) => dot.setAttribute('aria-current', String(i === index)));
    position.textContent = `${index + 1} / ${slides.length}`;
    pause.setAttribute('aria-label', paused ? '自動切り替えを開始' : '自動切り替えを停止');
    pause.setAttribute('aria-pressed', String(!paused));
    track.setAttribute('aria-live', paused ? 'polite' : 'off');
    position.setAttribute('aria-live', paused ? 'polite' : 'off');
  };
  // 複製へ移動した後は、同じ見た目の元記事へアニメーションなしで戻す。
  const settle = () => {
    if (offset > 0 && offset <= slides.length) return;
    track.classList.add('is-resetting');
    offset = index + 1;
    render();
    void track.offsetWidth;
    track.classList.remove('is-resetting');
  };
  // 次の自動切り替えを7秒後に予約する。停止中と別タブ表示中は予約しない。
  // 再生後のフォーカスやマウス位置では止めず、古い予約を消して二重実行を防ぐ。
  const schedule = () => {
    clearTimeout(timer);
    if (paused || document.hidden) return;
    timer = setTimeout(() => { move(1); }, 7000);
  };
  const move = delta => {
    settle();
    offset = index + 1 + delta;
    index = (index + delta + slides.length) % slides.length;
    render();
    if (motion.matches) settle();
    schedule();
  };
  const select = target => {
    settle();
    index = target;
    offset = index + 1;
    render();
    schedule();
  };
  track.addEventListener('transitionend', event => {
    if (event.target === track && event.propertyName === 'transform') settle();
  });
  previous.addEventListener('click', () => move(-1));
  next.addEventListener('click', () => move(1));
  pause.addEventListener('click', () => { paused = !paused; render(); schedule(); });
  root.addEventListener('keydown', event => {
    if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
    event.preventDefault();
    move(event.key === 'ArrowLeft' ? -1 : 1);
  });
  // 横スワイプで切り替え、縦方向には通常どおりページをスクロールできる。
  track.addEventListener('pointerdown', event => {
    if (event.pointerType === 'mouse' || !event.isPrimary) return;
    touchStart = { x: event.clientX, y: event.clientY, id: event.pointerId };
    track.setPointerCapture(event.pointerId);
  });
  track.addEventListener('pointerup', event => {
    if (!touchStart || touchStart.id !== event.pointerId) return;
    const dx = event.clientX - touchStart.x;
    const dy = event.clientY - touchStart.y;
    touchStart = undefined;
    if (Math.abs(dx) > 40 && Math.abs(dx) > Math.abs(dy) * 1.3) move(dx < 0 ? 1 : -1);
  });
  track.addEventListener('pointercancel', () => { touchStart = undefined; });
  document.addEventListener('visibilitychange', schedule);
  // 利用者が「動きを減らす」設定へ変えたら自動切り替えを停止する。
  motion.addEventListener('change', () => {
    if (motion.matches) paused = true;
    settle();
    render();
    schedule();
  });
  render();
  track.classList.add('is-resetting');
  root.classList.add('is-carousel');
  void track.offsetWidth;
  track.classList.remove('is-resetting');
  controls.hidden = false;
  previous.hidden = false;
  next.hidden = false;
  schedule();
});
