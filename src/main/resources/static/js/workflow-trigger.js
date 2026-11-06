(() => {
    const mode = document.querySelector('[data-workflow-trigger-mode]'),
        source = document.querySelector('[data-workflow-event-source]'),
        select = document.querySelector('[data-workflow-trigger-source]'),
        cronSettings = document.querySelector('[data-workflow-cron-settings]'),
        cron = document.querySelector('[data-schedule-cron]');
    if (!mode || !source || !select || !cronSettings || !cron) return;
    const update = () => {
        const eventMode = mode.value === 'EVENT';
        source.hidden = !eventMode;
        select.disabled = !eventMode;
        select.required = eventMode;
        cronSettings.hidden = eventMode;
        cron.disabled = eventMode;
        cron.required = !eventMode;
        cron.dispatchEvent(new Event('change'));
    };
    mode.addEventListener('change', update);
    update();

    const form = document.querySelector('[data-workflow-form]');
    if (!form) return;
    const panels = Array.from(form.querySelectorAll('[data-wizard-step]')),
        buttons = Array.from(form.querySelectorAll('[data-wizard-target]'));
    let activeStep = 1;
    const showStep = step => {
        activeStep = step;
        panels.forEach(panel => { panel.hidden = Number(panel.dataset.wizardStep) !== step; });
        buttons.forEach(button => {
            const target = Number(button.dataset.wizardTarget);
            button.setAttribute('aria-current', target === step ? 'step' : 'false');
            button.classList.toggle('is-complete', target < step);
        });
    };
    const validate = step => {
        const panel = panels.find(item => Number(item.dataset.wizardStep) === step);
        const invalid = Array.from(panel.querySelectorAll('input, select, textarea'))
            .find(control => !control.disabled && !control.checkValidity());
        if (!invalid) return true;
        showStep(step);
        invalid.reportValidity();
        invalid.focus();
        return false;
    };
    const navigate = step => {
        if (step > activeStep && !validate(activeStep)) return;
        showStep(step);
        const panel = panels.find(item => Number(item.dataset.wizardStep) === step);
        panel.querySelector('input:not([disabled]), select:not([disabled]), textarea:not([disabled])')?.focus();
    };
    buttons.forEach(button => button.addEventListener('click', () => navigate(Number(button.dataset.wizardTarget))));
    form.querySelector('[data-wizard-next]').addEventListener('click', () => navigate(2));
    form.querySelector('[data-wizard-back]').addEventListener('click', () => navigate(1));
    form.addEventListener('submit', event => {
        if (!validate(1) || !validate(2)) {
            event.preventDefault();
            return;
        }
        if (activeStep === 1) {
            event.preventDefault();
            navigate(2);
        }
    });
    form.noValidate = true;
    form.classList.add('pipeline-wizard-enhanced');
    const invalidBasics = Array.from(panels[0].querySelectorAll('input, textarea')).some(control => !control.checkValidity());
    showStep(form.dataset.initialStep === '2' && !invalidBasics ? 2 : 1);
})();
