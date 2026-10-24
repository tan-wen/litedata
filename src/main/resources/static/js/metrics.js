(() => {
  const form = document.querySelector('[data-metric-form]');
  if (!form) return;
  const type = form.querySelector('[data-metric-type]');
  if (!type) return;
  const sections = [...form.querySelectorAll('[data-metric-section]')];
  const dimensions = form.querySelector('[data-metric-dimensions]');
  const update = () => {
    const selected = type.value;
    sections.forEach(section => {
      const active = section.dataset.metricSection === selected;
      section.classList.toggle('d-none', !active);
      section.querySelectorAll('input, select, textarea').forEach(field => {
        field.disabled = !active;
        if (field.dataset.metricRequired) field.required = active;
      });
    });
    const inherited = selected === 'DERIVED' || selected === 'COMPOSITE';
    dimensions?.classList.toggle('d-none', inherited);
    const input = dimensions?.querySelector('input');
    if (input) {
      input.readOnly = inherited;
      input.disabled = inherited;
    }
  };
  type.addEventListener('change', update);
  update();
})();
