// JSが無効な場合は全トピックをそのまま読める。
document.addEventListener('DOMContentLoaded', () => {
  const root = document.querySelector('.topics');
  if (!root) return;
  const slides = [...root.querySelectorAll('.topic-slide')];
  if (slides.length < 2) return;
  const controls = root.querySelector('.topic-controls');
  const position = root.querySelector('.topic-position');
  const pause = root.querySelector('[data-topic="pause"]');
  const motion = window.matchMedia('(prefers-reduced-motion: reduce)');
  let index = 0;
  let paused = motion.matches;
  let hovered = false;
  let timer;
  const render = () => {
    slides.forEach((slide, i) => { slide.hidden = i !== index; });
    position.textContent = `${index + 1} / ${slides.length}`;
    pause.textContent = paused ? '自動切り替えを再開' : '自動切り替えを停止';
  };
  const schedule = () => {
    clearTimeout(timer);
    if (paused || hovered || document.hidden || root.contains(document.activeElement)) return;
    timer = setTimeout(() => { index = (index + 1) % slides.length; render(); schedule(); }, 7000);
  };
  const move = delta => { index = (index + delta + slides.length) % slides.length; render(); schedule(); };
  root.querySelector('[data-topic="previous"]').addEventListener('click', () => move(-1));
  root.querySelector('[data-topic="next"]').addEventListener('click', () => move(1));
  pause.addEventListener('click', () => { paused = !paused; render(); schedule(); });
  root.addEventListener('mouseenter', () => { hovered = true; schedule(); });
  root.addEventListener('mouseleave', () => { hovered = false; schedule(); });
  root.addEventListener('focusin', schedule);
  root.addEventListener('focusout', () => setTimeout(schedule, 0));
  document.addEventListener('visibilitychange', schedule);
  motion.addEventListener('change', () => { paused = motion.matches; render(); schedule(); });
  controls.hidden = false;
  render();
  schedule();
});
