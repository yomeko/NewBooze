// チェックボックスの表示順ではなく、選んだ順でタグIDを送信する。
(() => {
  const panel = document.getElementById('tag-weights');
  if (!panel) return;

  const choices = Array.from(panel.querySelectorAll('[data-tag-choice]'));
  const inputs = document.getElementById('tag-order-inputs');
  const summary = document.getElementById('tag-weight-summary');
  const choiceById = new Map(choices.map(choice => [choice.value, choice]));
  // 入力エラーで戻った画面でも、サーバーが返した選択順を復元する。
  let selectedIds = Array.from(new Set(Array.from(inputs.querySelectorAll('input'))
    .map(input => input.value).filter(id => choiceById.has(id))));

  function render() {
    const fields = selectedIds.map(id => {
      const field = document.createElement('input');
      field.type = 'hidden';
      field.name = 'tagIds';
      field.value = id;
      return field;
    });
    inputs.replaceChildren(...fields);

    choices.forEach(choice => {
      const index = selectedIds.indexOf(choice.value);
      const badge = choice.closest('label').querySelector('[data-tag-score]');
      choice.checked = index !== -1;
      badge.hidden = index === -1;
      badge.textContent = index === -1 ? '' : `重み ${selectedIds.length - index}`;
    });
    const labels = selectedIds.map((id, index) => {
      const name = choiceById.get(id).closest('label').querySelector('[data-tag-name]').textContent;
      return `${name}（${selectedIds.length - index}）`;
    });
    summary.textContent = labels.length ? `選択順と重み：${labels.join(' → ')}` : 'タグは未選択です。';
  }

  choices.forEach(choice => choice.addEventListener('change', () => {
    selectedIds = selectedIds.filter(id => id !== choice.value);
    if (choice.checked) selectedIds.push(choice.value);
    render();
  }));
  render();
  choices.forEach(choice => { choice.disabled = false; });
})();
