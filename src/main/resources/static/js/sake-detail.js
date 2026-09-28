// Keep anchor targets and the last content clear of the fixed controls.
(() => {
  const intro = document.querySelector('.sake-intro');
  const purchaseBar = document.querySelector('.sake-purchase-bar');
  const updateOffsets = () => {
    document.documentElement.style.setProperty('--sake-intro-height', `${intro?.getBoundingClientRect().height ?? 0}px`);
    document.documentElement.style.setProperty('--purchase-bar-height', `${purchaseBar?.getBoundingClientRect().height ?? 0}px`);
  };
  const observer = new ResizeObserver(updateOffsets);
  if (intro) observer.observe(intro);
  if (purchaseBar) observer.observe(purchaseBar);
  updateOffsets();
})();
