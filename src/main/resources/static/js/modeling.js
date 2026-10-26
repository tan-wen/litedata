(() => {
  const body = document.getElementById('model-fields');
  const add = document.getElementById('add-field');
  if (!body || !add) return;
  const layer = document.getElementById('layer');
  const dimensionSettings = document.getElementById('dimension-settings');
  function updateDimension() {
    const enabled = layer.value === 'DIM';
    dimensionSettings.hidden = !enabled;
    dimensionSettings.querySelectorAll('input, select').forEach(input => { input.disabled = !enabled; });
    document.getElementById('dimensionKeys').required = enabled;
    document.getElementById('tableName').placeholder = enabled ? 'dim_enterprise' : 'ods_orders';
    const suggestions = document.getElementById('dimension-fields');
    suggestions.replaceChildren(...[...body.querySelectorAll('[data-property="name"]')].filter(input => input.value.trim()
      && !input.closest('tr').querySelector('[data-property="keyColumn"]').checked).map(input => {
      const option = document.createElement('option'); option.value = input.value.trim(); return option;
    }));
  }
  layer.addEventListener('change', updateDimension);
  body.addEventListener('input', updateDimension);
  function renumber() {
    [...body.rows].forEach((row, index) => {
      row.querySelectorAll('[data-property]').forEach(input => {
        input.name = `fields[${index}].${input.dataset.property}`;
        input.id = `field-${index}-${input.dataset.property}`;
      });
    });
    add.disabled = body.rows.length >= 100;
    updateDimension();
  }
  add.addEventListener('click', () => {
    if (body.rows.length >= 100) return;
    body.append(document.getElementById('field-template').content.cloneNode(true));
    renumber();
    body.lastElementChild.querySelector('input').focus();
  });
  body.addEventListener('click', event => {
    const button = event.target.closest('button');
    if (!button) return;
    const row = button.closest('tr');
    if (button.hasAttribute('data-remove')) row.remove();
    if (button.dataset.move === 'up' && row.previousElementSibling) body.insertBefore(row, row.previousElementSibling);
    if (button.dataset.move === 'down' && row.nextElementSibling) body.insertBefore(row.nextElementSibling, row);
    renumber();
  });
  renumber();
})();
