(function () {
    'use strict';
    const button = document.getElementById('copyWebDavCredential');
    const secret = document.getElementById('webdavCredentialSecret');
    const live = document.getElementById('credentialLive');
    const form = document.getElementById('createWebDavCredentialForm');
    function draft() {
        return form ? JSON.stringify(Array.from(form.elements).filter(field => field.name && field.type !== 'hidden')
            .map(field => [field.name, field.type === 'checkbox' || field.type === 'radio' ? field.checked : field.value])) : '';
    }
    const initialDraft = draft();
    let submitting = false, copied = false;
    if (form) form.addEventListener('submit', function (event) {
        submitting = true;
        window.setTimeout(function () { if (event.defaultPrevented) submitting = false; }, 0);
    });
    window.addEventListener('beforeunload', function (event) {
        const committed = submitting;
        submitting = false;
        if ((!secret || copied) && (committed || draft() === initialDraft)) return;
        event.preventDefault();
        event.returnValue = '';
    });
    if (!button || !secret) return;

    button.addEventListener('click', async function () {
        const value = secret.textContent || '';
        try {
            if (navigator.clipboard && window.isSecureContext) {
                await navigator.clipboard.writeText(value);
            } else {
                const field = document.createElement('textarea');
                field.value = value;
                field.readOnly = true;
                field.className = 'position-fixed top-0 start-0 opacity-0';
                document.body.appendChild(field);
                try {
                    field.select();
                    if (!document.execCommand('copy')) throw new Error('Copy failed');
                } finally { field.remove(); }
            }
            copied = true;
            const message = document.documentElement.lang.toLowerCase().startsWith('de')
                ? 'Das WebDAV-Anwendungspasswort wurde kopiert.'
                : 'The WebDAV application password was copied.';
            button.textContent = message;
            if (live) live.textContent = message;
        } catch (error) {
            const message = document.documentElement.lang.toLowerCase().startsWith('de')
                ? 'Kopieren fehlgeschlagen. Markieren und kopieren Sie das Passwort manuell.'
                : 'Copy failed. Select and copy the password manually.';
            if (live) live.textContent = message;
            button.title = message;
        }
    });
}());
