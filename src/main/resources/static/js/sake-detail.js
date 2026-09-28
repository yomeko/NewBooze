// Keep anchor targets clear of the sticky product header.
(() => {
  const intro = document.querySelector('.sake-intro');
  const updateOffsets = () => {
    document.documentElement.style.setProperty('--sake-intro-height', `${intro?.getBoundingClientRect().height ?? 0}px`);
  };
  const observer = new ResizeObserver(updateOffsets);
  if (intro) observer.observe(intro);
  updateOffsets();
})();
