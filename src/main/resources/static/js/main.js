document.addEventListener('DOMContentLoaded', () => {
  const toggle = document.querySelector('.menu-toggle');
  const nav = document.querySelector('header nav');
  if (toggle && nav) toggle.addEventListener('click', () => nav.classList.toggle('open'));
  const questions = [...document.querySelectorAll('.question')];
  if (!questions.length) return;
  let step = 0;
  const previous = document.querySelector('#previous');
  const next = document.querySelector('#next');
  const submit = document.querySelector('#submit');
  const render = () => { questions.forEach((question, index) => question.classList.toggle('active', index === step)); previous.hidden = step === 0; next.hidden = step === questions.length - 1; submit.hidden = step !== questions.length - 1; };
  next.addEventListener('click', () => { if (!questions[step].querySelector('input:checked')) { alert('選択肢をひとつ選んでください。'); return; } step++; render(); });
  previous.addEventListener('click', () => { step--; render(); });
  render();
});
