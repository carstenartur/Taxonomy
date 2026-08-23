(function () {
    'use strict';
    const button = document.getElementById('copyWebDavCredential');
    const secret = document.getElementById('webdavCredentialSecret');
    const live = document.getElementById('credentialLive');
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
                field.select();
                if (!document.execCommand('copy')) {
                    throw new Error('Copy failed');
                }
                field.remove();
            }
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
