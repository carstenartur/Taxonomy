(function () {
    'use strict';

    const workspace = document.getElementById('documentTemplateWorkspace');
    if (!workspace) {
        return;
    }

    const links = window.TaxonomyDocumentTemplateLinks;
    if (!links) {
        throw new Error('Document template Word link support was not loaded');
    }

    const apiBase = String(workspace.dataset.apiBase || '').replace(/\/+$/, '');
    const webDavBase = String(workspace.dataset.webdavBase || '');
    const labelsElement = document.getElementById('documentTemplateLabels');
    const labels = labelsElement ? labelsElement.dataset : {};
    const german = (document.documentElement.lang || '').toLowerCase().startsWith('de');
    const text = german ? {
        loadFailed: 'Dokumentvorlagen konnten nicht geladen werden.',
        uploadFailed: 'Die Dokumentvorlage konnte nicht gespeichert werden.',
        uploaded: 'Die Vorlage „{0}“ wurde als neue Git-Version gespeichert.',
        copied: 'Die WebDAV-Adresse wurde kopiert.',
        copyFailed: 'Die WebDAV-Adresse konnte nicht kopiert werden.',
        historyFailed: 'Die Vorlagenhistorie konnte nicht geladen werden.',
        templates: '{0} Vorlagen geladen',
        versions: '{0} Versionen',
        current: 'Aktuell',
        unknown: 'Unbekannt',
        loading: 'Wird geladen…',
        invalidDotx: 'Bitte wählen Sie eine Datei mit der Endung .dotx.',
        wordHint: 'Word kann beim ersten Aufruf um Erlaubnis zum Öffnen der Anwendung bitten.',
        insecureWord: 'Direkte Word-Bearbeitung ist außerhalb der lokalen Entwicklung nur über HTTPS verfügbar.',
        downloadRevision: 'Diese Version herunterladen'
    } : {
        loadFailed: 'Document templates could not be loaded.',
        uploadFailed: 'The document template could not be saved.',
        uploaded: 'Template “{0}” was saved as a new Git version.',
        copied: 'The WebDAV address was copied.',
        copyFailed: 'The WebDAV address could not be copied.',
        historyFailed: 'The template history could not be loaded.',
        templates: '{0} templates loaded',
        versions: '{0} versions',
        current: 'Current',
        unknown: 'Unknown',
        loading: 'Loading…',
        invalidDotx: 'Choose a file with the .dotx extension.',
        wordHint: 'Word may ask for permission to open the application on first use.',
        insecureWord: 'Direct Word editing is available only over HTTPS outside local development.',
        downloadRevision: 'Download this version'
    };

    const rows = document.getElementById('documentTemplateRows');
    const emptyState = document.getElementById('documentTemplateEmpty');
    const refreshButton = document.getElementById('documentTemplateRefresh');
    const uploadForm = document.getElementById('documentTemplateUploadForm');
    const uploadButton = document.getElementById('documentTemplateUploadButton');
    const uploadSpinner = document.getElementById('documentTemplateUploadSpinner');
    const errorAlert = document.getElementById('documentTemplateError');
    const successAlert = document.getElementById('documentTemplateSuccess');
    const liveRegion = document.getElementById('documentTemplateLive');
    const historyBody = document.getElementById('documentTemplateHistoryBody');
    const historyTitle = document.getElementById('documentTemplateHistoryTitle');
    const historyModalElement = document.getElementById('documentTemplateHistoryModal');
    const templatesById = new Map();

    function format(pattern, value) {
        return String(pattern).replace('{0}', String(value));
    }

    function announce(message) {
        if (liveRegion) {
            liveRegion.textContent = '';
            window.setTimeout(function () {
                liveRegion.textContent = message;
            }, 0);
        }
    }

    function hideAlerts() {
        errorAlert.classList.add('d-none');
        successAlert.classList.add('d-none');
        errorAlert.textContent = '';
        successAlert.textContent = '';
    }

    function showError(message) {
        successAlert.classList.add('d-none');
        errorAlert.textContent = message;
        errorAlert.classList.remove('d-none');
        errorAlert.focus({ preventScroll: false });
        announce(message);
    }

    function showSuccess(message) {
        errorAlert.classList.add('d-none');
        successAlert.textContent = message;
        successAlert.classList.remove('d-none');
        announce(message);
    }

    function csrfHeaders() {
        const token = document.querySelector('meta[name="_csrf"]')?.content;
        const header = document.querySelector('meta[name="_csrf_header"]')?.content;
        return token && header ? { [header]: token } : {};
    }

    async function responseError(response, fallback) {
        const type = response.headers.get('content-type') || '';
        try {
            if (type.includes('application/json')) {
                const payload = await response.json();
                return payload.error || payload.message || fallback;
            }
            const body = await response.text();
            return body.trim() || fallback;
        } catch (ignored) {
            return fallback;
        }
    }

    async function fetchJson(url, options, fallback) {
        const response = await fetch(url, Object.assign({
            credentials: 'same-origin',
            cache: 'no-store',
            headers: { Accept: 'application/json' }
        }, options || {}));
        if (!response.ok) {
            throw new Error(await responseError(response, fallback));
        }
        return response.status === 204 ? null : response.json();
    }

    function apiUrl(path) {
        return new URL(apiBase + path, window.location.href).href;
    }

    function templateWebDavUrl(template) {
        const base = new URL(webDavBase, window.location.href);
        if (!base.pathname.endsWith('/')) {
            base.pathname += '/';
        }
        return new URL(encodeURIComponent(template.fileName), base).href;
    }

    function templateDownloadUrl(templateId, revision) {
        const url = new URL(apiBase + '/' + encodeURIComponent(templateId) + '/download',
            window.location.href);
        if (revision) {
            url.searchParams.set('revision', revision);
        }
        return url.href;
    }

    function formatBytes(value) {
        const bytes = Number(value);
        if (!Number.isFinite(bytes) || bytes < 0) {
            return text.unknown;
        }
        const units = ['B', 'KiB', 'MiB', 'GiB'];
        let amount = bytes;
        let unit = 0;
        while (amount >= 1024 && unit < units.length - 1) {
            amount /= 1024;
            unit += 1;
        }
        return (unit === 0 ? amount.toFixed(0) : amount.toFixed(1)) + ' ' + units[unit];
    }

    function formatDate(value) {
        if (!value) {
            return text.unknown;
        }
        const date = new Date(value);
        return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
    }

    function element(name, className, content) {
        const node = document.createElement(name);
        if (className) {
            node.className = className;
        }
        if (content !== undefined && content !== null) {
            node.textContent = content;
        }
        return node;
    }

    function actionLink(label, className) {
        const anchor = element('a', className, label);
        anchor.setAttribute('role', 'button');
        return anchor;
    }

    function disableWordLink(anchor) {
        anchor.removeAttribute('href');
        anchor.removeAttribute('target');
        anchor.classList.add('disabled');
        anchor.setAttribute('aria-disabled', 'true');
        anchor.setAttribute('tabindex', '-1');
        anchor.title = text.insecureWord;
    }

    function renderTemplate(template) {
        const row = document.createElement('tr');
        row.dataset.templateId = template.templateId;

        const identityCell = document.createElement('td');
        identityCell.append(
            element('div', 'fw-semibold', template.displayName),
            element('code', 'small d-block', template.templateId),
            element('span', 'small text-body-secondary',
                template.fileName + ' · ' + formatBytes(template.uncompressedSize)
                + ' · ' + template.partCount + ' OOXML parts')
        );

        const versionCell = document.createElement('td');
        const commit = element('code', 'small d-block', String(template.headCommit).slice(0, 12));
        commit.title = template.headCommit;
        versionCell.append(
            commit,
            element('span', 'small d-block', formatDate(template.updatedAt)),
            element('span', 'small text-body-secondary', template.updatedBy || text.unknown)
        );

        const wordCell = document.createElement('td');
        const wordActions = element('div', 'd-flex flex-wrap gap-2');
        const webDavUrl = templateWebDavUrl(template);
        const editLink = actionLink(labels.edit || 'Edit template in Word', 'btn btn-sm btn-primary');
        const newLink = actionLink(labels.new || 'New document from template',
            'btn btn-sm btn-outline-primary');
        if (links.directWordLinkAllowed(webDavUrl)) {
            links.configureWordLink(editLink, webDavUrl, 'edit');
            links.configureWordLink(newLink, webDavUrl, 'new');
            editLink.title = text.wordHint;
            newLink.title = text.wordHint;
        } else {
            disableWordLink(editLink);
            disableWordLink(newLink);
        }
        wordActions.append(editLink, newLink);
        const address = element('code', 'small text-break d-block mt-2', webDavUrl);
        wordCell.append(wordActions, address);

        const otherCell = document.createElement('td');
        const otherActions = element('div', 'd-flex flex-wrap gap-2');
        const download = actionLink(labels.download || 'Download', 'btn btn-sm btn-outline-secondary');
        download.href = templateDownloadUrl(template.templateId);
        download.setAttribute('download', template.fileName);
        download.setAttribute('type', links.DOTX_MEDIA_TYPE);
        const copy = element('button', 'btn btn-sm btn-outline-secondary', labels.copy || 'Copy WebDAV address');
        copy.type = 'button';
        copy.addEventListener('click', function () {
            copyAddress(webDavUrl);
        });
        const history = element('button', 'btn btn-sm btn-outline-secondary', labels.history || 'History');
        history.type = 'button';
        history.addEventListener('click', function () {
            showHistory(template);
        });
        otherActions.append(download, copy, history);
        otherCell.append(otherActions);

        row.append(identityCell, versionCell, wordCell, otherCell);
        return row;
    }

    function renderTemplates(templates) {
        templatesById.clear();
        rows.replaceChildren();
        const sorted = Array.from(templates || []).sort(function (left, right) {
            return String(left.displayName).localeCompare(String(right.displayName));
        });
        sorted.forEach(function (template) {
            templatesById.set(template.templateId, template);
            rows.appendChild(renderTemplate(template));
        });
        emptyState.classList.toggle('d-none', sorted.length !== 0);
    }

    async function loadTemplates() {
        hideAlerts();
        refreshButton.disabled = true;
        try {
            const templates = await fetchJson(apiUrl(''), null, text.loadFailed);
            renderTemplates(templates);
            announce(format(text.templates, templates.length));
        } catch (error) {
            renderTemplates([]);
            showError(error.message || text.loadFailed);
        } finally {
            refreshButton.disabled = false;
        }
    }

    async function copyAddress(value) {
        try {
            if (navigator.clipboard && window.isSecureContext) {
                await navigator.clipboard.writeText(value);
            } else {
                const temporary = document.createElement('textarea');
                temporary.value = value;
                temporary.setAttribute('readonly', '');
                temporary.className = 'position-fixed top-0 start-0 opacity-0';
                document.body.appendChild(temporary);
                temporary.select();
                const copied = document.execCommand('copy');
                temporary.remove();
                if (!copied) {
                    throw new Error(text.copyFailed);
                }
            }
            showSuccess(text.copied);
        } catch (error) {
            showError(error.message || text.copyFailed);
        }
    }

    function setUploadBusy(busy) {
        uploadButton.disabled = busy;
        uploadSpinner.classList.toggle('d-none', !busy);
        uploadForm.setAttribute('aria-busy', busy ? 'true' : 'false');
    }

    uploadForm.addEventListener('submit', async function (event) {
        event.preventDefault();
        hideAlerts();

        const idInput = document.getElementById('documentTemplateId');
        const displayNameInput = document.getElementById('documentTemplateDisplayName');
        const fileInput = document.getElementById('documentTemplateFile');
        idInput.value = idInput.value.trim().toLowerCase();
        displayNameInput.value = displayNameInput.value.trim();
        fileInput.setCustomValidity('');

        const file = fileInput.files && fileInput.files[0];
        if (file && !file.name.toLowerCase().endsWith('.dotx')) {
            fileInput.setCustomValidity(text.invalidDotx);
        }
        if (!uploadForm.reportValidity()) {
            return;
        }

        const templateId = idInput.value;
        const existing = templatesById.get(templateId);
        const url = new URL(apiBase + '/' + encodeURIComponent(templateId), window.location.href);
        url.searchParams.set('displayName', displayNameInput.value);
        const headers = Object.assign({
            Accept: 'application/json',
            'Content-Type': links.DOTX_MEDIA_TYPE
        }, csrfHeaders());
        if (existing && existing.headCommit) {
            headers['If-Match'] = '"' + existing.headCommit + '"';
        }

        setUploadBusy(true);
        try {
            const saved = await fetchJson(url.href, {
                method: 'PUT',
                headers: headers,
                body: file
            }, text.uploadFailed);
            uploadForm.reset();
            showSuccess(format(text.uploaded, saved.displayName));
            await loadTemplates();
            showSuccess(format(text.uploaded, saved.displayName));
        } catch (error) {
            showError(error.message || text.uploadFailed);
        } finally {
            setUploadBusy(false);
        }
    });

    async function showHistory(template) {
        const modal = bootstrap.Modal.getOrCreateInstance(historyModalElement);
        historyTitle.textContent = (labels.history || 'History') + ' — ' + template.displayName;
        historyBody.replaceChildren(element('p', 'text-body-secondary', text.loading));
        modal.show();
        try {
            const revisions = await fetchJson(
                apiUrl('/' + encodeURIComponent(template.templateId) + '/history'),
                null,
                text.historyFailed);
            historyBody.replaceChildren();
            if (!revisions.length) {
                historyBody.appendChild(element('p', 'text-body-secondary',
                    labels.historyEmpty || 'No versions are available.'));
                return;
            }
            const list = element('div', 'list-group');
            revisions.forEach(function (revision, index) {
                const item = element('div', 'list-group-item');
                const heading = element('div', 'd-flex flex-wrap justify-content-between gap-2');
                const commit = element('code', '', String(revision.commitId).slice(0, 12));
                commit.title = revision.commitId;
                const badge = element('span', index === 0
                    ? 'badge text-bg-success' : 'badge text-bg-secondary',
                    index === 0 ? text.current : formatDate(revision.committedAt));
                heading.append(commit, badge);
                const message = element('div', 'mt-2', revision.message || text.unknown);
                const author = element('div', 'small text-body-secondary',
                    (revision.author || text.unknown) + ' · ' + formatDate(revision.committedAt));
                const historicalDownload = actionLink(text.downloadRevision,
                    'btn btn-sm btn-outline-secondary mt-2');
                historicalDownload.href = templateDownloadUrl(template.templateId, revision.commitId);
                historicalDownload.setAttribute('download', template.fileName);
                historicalDownload.setAttribute('type', links.DOTX_MEDIA_TYPE);
                item.append(heading, message, author, historicalDownload);
                list.appendChild(item);
            });
            historyBody.appendChild(list);
        } catch (error) {
            historyBody.replaceChildren(element('div', 'alert alert-danger',
                error.message || text.historyFailed));
        }
    }

    refreshButton.addEventListener('click', loadTemplates);

    const backLink = document.getElementById('documentTemplateBack');
    if (backLink && workspace.dataset.homeUrl) {
        const home = new URL(workspace.dataset.homeUrl, window.location.href);
        home.hash = 'admin';
        backLink.href = home.href;
    }

    const httpsWarning = document.getElementById('documentTemplateHttpsWarning');
    const loopback = ['localhost', '127.0.0.1', '::1'].includes(window.location.hostname);
    if (window.location.protocol !== 'https:' && !loopback) {
        httpsWarning.classList.remove('d-none');
    }

    loadTemplates();
}());
