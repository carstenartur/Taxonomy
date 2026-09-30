(function () {
    'use strict';
    var api = window.BackupApi, i18n = window.TaxonomyI18n;
    var list = document.getElementById('backupJobList'), notice = document.getElementById('backupNotice');
    if (!list) return;
    var jobs = [], page = 0, pageSize = 10, busy = false, generation = 0;
    var rows = new Map(), active = ['QUEUED', 'CAPTURING', 'WRITING', 'VERIFYING'];
    function t(key) { return i18n.t('backup.' + key); }
    function element(tag, className, text) {
        var node = document.createElement(tag); if (className) node.className = className;
        if (text !== undefined) node.textContent = text; return node;
    }
    function visible() { return jobs.slice(page * pageSize, (page + 1) * pageSize); }
    function paint(job) {
        var row = rows.get(job.id); if (!row) return;
        row.title.textContent = t('profile.' + job.profile);
        row.state.textContent = t('state.' + job.state);
        row.progress.textContent = t('processed') + ': ' + new Intl.NumberFormat(i18n.getLocale()).format(job.progressBytes) + ' B';
        row.expiry.textContent = t('expires') + ': ' + new Date(job.expiresAt).toLocaleString(i18n.getLocale());
        row.error.textContent = job.failure === 'NONE' ? '' : t('failure.' + job.failure);
        row.cancel.hidden = !active.includes(job.state);
        row.cancel.disabled = job.cancellationRequested;
        row.cancel.textContent = t(job.cancellationRequested ? 'cancelling' : 'cancel');
        row.download.hidden = !job.downloadAvailable;
        if (job.downloadAvailable) row.download.href = api.downloadUrl(job.id); else row.download.removeAttribute('href');
        row.protection.textContent = job.encrypted ? t('encrypted') : '';
    }
    function render() {
        generation++; rows.clear(); list.replaceChildren();
        visible().forEach(function (job) {
            var item = element('li', 'col-12 col-lg-6'), card = element('article', 'card h-100');
            var body = element('div', 'card-body'), title = element('h2', 'h5'), state = element('p', 'fw-semibold');
            state.setAttribute('role', 'status');
            var progress = element('p'), expiry = element('p', 'small'), error = element('p', 'text-danger');
            var protection = element('p', 'small'), actions = element('div', 'd-flex flex-wrap gap-2');
            var cancel = element('button', 'btn btn-outline-secondary', t('cancel'));
            cancel.type = 'button';
            cancel.addEventListener('click', async function () {
                cancel.disabled = true;
                try { await api.cancel(job.id); var updated = await api.status(job.id); Object.assign(job, updated); paint(job); }
                catch (failure) { notice.textContent = t('error'); cancel.disabled = false; }
            });
            var download = element('a', 'btn btn-primary', t('download'));
            // Native browser download streams directly to disk; no archive-sized Blob in JavaScript.
            actions.append(cancel, download); body.append(title, state, progress, expiry, error, protection, actions);
            card.append(body); item.append(card); list.append(item);
            rows.set(job.id, {title: title, state: state, progress: progress, expiry: expiry, error: error,
                protection: protection, cancel: cancel, download: download}); paint(job);
        });
        document.getElementById('backupPrevious').disabled = page === 0;
        document.getElementById('backupNext').disabled = (page + 1) * pageSize >= jobs.length;
        document.getElementById('backupPage').textContent = jobs.length ? (page + 1) + ' / ' + Math.ceil(jobs.length / pageSize) : '';
        notice.textContent = jobs.length ? '' : t('empty');
    }
    async function refresh() {
        if (busy) return; busy = true;
        try { jobs = (await api.list()).slice(0, 50); page = Math.min(page, Math.max(0, Math.ceil(jobs.length / pageSize) - 1)); render(); }
        catch (failure) { jobs = []; render(); notice.textContent = t('error'); }
        finally { busy = false; }
    }
    async function poll() {
        if (document.hidden || busy) return;
        busy = true; var started = generation;
        try {
            for (var job of visible()) {
                if (document.hidden || started !== generation) break;
                try { var current = await api.status(job.id); if (started === generation) { Object.assign(job, current); paint(job); } }
                catch (failure) {
                    if (failure.status === 401 || failure.status === 403) { jobs = jobs.filter(function (value) { return value.id !== job.id; }); render(); break; }
                    notice.textContent = t('error');
                }
            }
        } finally { busy = false; }
    }
    document.getElementById('backupRefresh').addEventListener('click', refresh);
    document.getElementById('backupPrevious').addEventListener('click', function () { if (page > 0) { page--; render(); } });
    document.getElementById('backupNext').addEventListener('click', function () { if ((page + 1) * pageSize < jobs.length) { page++; render(); } });
    document.addEventListener('visibilitychange', function () { if (!document.hidden) poll(); });
    i18n.ready().then(function () { refresh(); window.setInterval(poll, 3000); });
}());
