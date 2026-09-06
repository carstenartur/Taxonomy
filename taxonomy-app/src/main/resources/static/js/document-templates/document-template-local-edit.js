/* A checkout is the immutable revision in the page URL, not the latest list entry. */
(function () {
    'use strict';
    const workspace = document.getElementById('documentTemplateLocalEdit');
    if (!workspace) return;
    const api = window.TaxonomyDocumentTemplatesApi;
    const form = document.getElementById('localTemplateForm');
    const fields = document.getElementById('localTemplateFields');
    const fileInput = document.getElementById('localTemplateFile');
    const message = document.getElementById('localTemplateMessage');
    const spinner = document.getElementById('localTemplateSpinner');
    const result = document.getElementById('localTemplateResult');
    const compare = document.getElementById('localTemplateCompare');
    const labels = document.getElementById('localTemplateLabels').dataset;
    const revision = workspace.dataset.revision;
    const maxBytes = Number(workspace.dataset.maxBytes);
    let busy = false;
    let saved = false;

    function notify(text, failure) {
        message.textContent = text;
        message.className = failure ? 'alert alert-danger' : 'alert alert-info';
        message.setAttribute('role', failure ? 'alert' : 'status');
        message.hidden = false;
        message.focus();
    }
    function setBusy(value) {
        busy = value;
        fields.disabled = value || saved;
        form.setAttribute('aria-busy', String(value));
        spinner.classList.toggle('d-none', !value);
    }
    if (!api || !/^[0-9a-f]{40}$/.test(revision || '')
            || !Number.isSafeInteger(maxBytes) || maxBytes <= 0) {
        notify(labels.failed, true);
        return;
    }
    // Fail closed: no current-head lookup, wildcard precondition or automatic retry.
    const headers = Object.freeze({
        Accept: 'application/json',
        'Content-Type': 'application/vnd.openxmlformats-officedocument.wordprocessingml.template',
        'If-Match': '"' + revision + '"'
    });
    const uploadUrl = new URL(workspace.dataset.uploadUrl, window.location.href);
    if (uploadUrl.origin !== window.location.origin) {
        notify(labels.failed, true);
        return;
    }
    fields.disabled = false;
    fileInput.addEventListener('change', function () { fileInput.setCustomValidity(''); });
    form.addEventListener('submit', async function (event) {
        event.preventDefault();
        if (busy || saved) return;
        const file = fileInput.files && fileInput.files[0];
        fileInput.setCustomValidity('');
        if (!file || !file.name.toLowerCase().endsWith('.dotx') || file.size === 0) {
            fileInput.setCustomValidity(labels.invalid);
        } else if (file.size > maxBytes) {
            fileInput.setCustomValidity(labels.tooLarge);
        }
        if (!form.reportValidity()) return;
        setBusy(true);
        notify(labels.busy, false);
        try {
            const response = await api.upload(uploadUrl.href, file, headers, labels.failed);
            if (!response || response.templateId !== workspace.dataset.templateId
                    || !/^[0-9a-f]{40}$/.test(response.headCommit || '')) {
                throw new Error(labels.uncertain);
            }
            // Prevent a second submit, including when updating the result UI fails.
            saved = true;
            const url = new URL(compare.href, window.location.href);
            url.searchParams.set('from', revision);
            url.searchParams.set('to', response.headCommit);
            compare.href = url.href;
            document.getElementById('localTemplateSavedRevision').textContent = response.headCommit;
            result.hidden = false;
            notify(labels.saved, false);
        } catch (error) {
            if (error.status === 409 || error.status === 412) {
                notify(labels.conflict, true);
            } else if (error.status === 401 || error.status === 403) {
                notify(labels.authentication, true);
            } else if (error.status === 400 || error.status === 413 || error.status === 422) {
                notify(labels.invalid + ' ' + (error.message || ''), true);
            } else {
                // A timeout/lost response can occur after a successful commit.
                notify(labels.uncertain, true);
            }
        } finally {
            setBusy(false);
        }
    });
}());
