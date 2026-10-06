// 詳細画面の紹介欄の高さをCSSへ渡す補助処理。現在のdetail.htmlからは読み込まれていない。
(() => {
  const intro = document.querySelector('.sake-intro');
  // 紹介欄の高さをCSS変数へ渡し、見出しに合わせたスクロール位置を調整できるようにする。
  const updateOffsets = () => {
    document.documentElement.style.setProperty('--sake-intro-height', `${intro?.getBoundingClientRect().height ?? 0}px`);
  };
  // 要素のサイズ変更を監視し、紹介文の折り返しなどで高さが変わったら再計算する。
  const observer = new ResizeObserver(updateOffsets);
  if (intro) observer.observe(intro);
  updateOffsets();
})();
