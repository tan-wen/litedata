(() => {
    const mode = document.querySelector('[data-workflow-trigger-mode]'),
        source = document.querySelector('[data-workflow-event-source]'),
        select = document.querySelector('[data-workflow-trigger-source]');
    if (!mode || !source || !select) return;
    const update = () => {
        const eventMode = mode.value === 'EVENT';
        source.hidden = !eventMode;
        select.disabled = !eventMode;
        select.required = eventMode;
    };
    mode.addEventListener('change', update);
    update();
})();
