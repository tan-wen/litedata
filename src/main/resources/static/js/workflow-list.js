(() => {
    'use strict';
    const dialog = document.querySelector('[data-workflow-delete-dialog]');
    const confirm = dialog?.querySelector('[data-workflow-delete-confirm]');
    let pendingForm = null;
    dialog?.querySelector('[data-workflow-delete-cancel]').addEventListener('click', () => dialog.close());
    dialog?.addEventListener('close', () => {
        pendingForm = null;
    });
    confirm?.addEventListener('click', async () => {
        const form = pendingForm;
        if (!form) return;
        const button = form.querySelector('button[type="submit"]');
        dialog.close();
        window.platformFeedback.clear('workflow-delete');
        button.disabled = true;
        try {
            const response = await fetch(form.action, {
                method: 'POST',
                body: new URLSearchParams(new FormData(form)),
                headers: {Accept: 'application/json'},
                cache: 'no-store'
            });
            if (!response.ok || response.redirected && new URL(response.url).pathname.endsWith('/login')) await window.platformFeedback.readJson(response);
            location.assign(response.redirected ? response.url : location.href);
        } catch (failure) {
            window.platformFeedback.show(failure, {key: 'workflow-delete'});
            button.disabled = false;
        }
    });
    document.querySelectorAll('[data-workflow-delete-form]').forEach(form => {
        form.addEventListener('submit', event => {
            event.preventDefault();
            const button = form.querySelector('button[type="submit"]');
            if (button.disabled || !dialog) return;
            pendingForm = form;
            dialog.querySelector('[data-workflow-delete-name]').textContent = form.dataset.workflowName;
            dialog.showModal();
            dialog.querySelector('[data-workflow-delete-cancel]').focus();
        });
    });
})();
