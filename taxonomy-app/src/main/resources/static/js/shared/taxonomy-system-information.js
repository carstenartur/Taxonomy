/** Read-only system snapshot inside the existing administrator health panel. */
window.TaxonomySystemInformation = (function () {
    'use strict';
    var t = function (key) { return TaxonomyI18n.t(key); };

    function node(tag, text, className) {
        var result = document.createElement(tag);
        if (text !== undefined && text !== null) result.textContent = String(text);
        if (className) result.className = className;
        return result;
    }

    function size(bytes) {
        if (typeof bytes !== 'number' || !Number.isFinite(bytes) || bytes < 0) {
            return t('system.unavailable');
        }
        return (bytes / (1024 * 1024 * 1024)).toLocaleString(TaxonomyI18n.getLocale(),
            { maximumFractionDigits: 2 }) + ' GiB';
    }

    function row(list, key, value) {
        list.appendChild(node('dt', t(key), 'col-sm-5'));
        list.appendChild(node('dd', value === null || value === undefined || value === ''
            ? t('system.unavailable') : value, 'col-sm-7 text-break'));
    }

    function render(target, snapshot) {
        target.replaceChildren();
        var db = snapshot.database;
        db.warnings.forEach(function (warning) {
            var critical = warning.indexOf('IN_MEMORY_') === 0 || warning === 'DESTRUCTIVE_SCHEMA_ACTION';
            target.appendChild(node('p', t('system.warning.' + warning),
                'alert ' + (critical ? 'alert-danger' : 'alert-warning') + ' py-2 mb-2'));
        });
        var database = node('dl', null, 'row mb-2');
        row(database, 'system.database', db.product);
        row(database, 'system.version', db.version);
        row(database, 'system.versionSource', t('system.source.' + db.versionSource));
        row(database, 'system.storage', t('system.storage.' + db.storage));
        row(database, 'system.storageSource', t('system.source.' + db.storageSource));
        row(database, 'system.schemaAction', db.schemaAction);
        row(database, 'system.driver', [db.driver, db.driverVersion].filter(Boolean).join(' '));
        target.appendChild(database);

        var runtime = node('dl', null, 'row mb-2');
        row(runtime, 'system.applicationVersion', snapshot.applicationVersion);
        row(runtime, 'system.processors', snapshot.runtime.availableProcessors);
        row(runtime, 'system.java', snapshot.runtime.javaVersion + ' / ' + snapshot.runtime.javaVendor);
        row(runtime, 'system.os', snapshot.runtime.osName + ' / ' + snapshot.runtime.osArchitecture);
        row(runtime, 'system.started', new Date(snapshot.runtime.startTime).toLocaleString(TaxonomyI18n.getLocale()));
        row(runtime, 'system.uptime', Math.floor(snapshot.runtime.uptimeMillis / 1000).toLocaleString(
            TaxonomyI18n.getLocale()) + ' s');
        row(runtime, 'system.index', t('system.index.' + snapshot.indexStorage));
        target.appendChild(runtime);

        snapshot.disks.forEach(function (disk) {
            var label = disk.purposes.map(function (purpose) { return t('system.disk.' + purpose); }).join(' / ');
            var entry = node('dl', null, 'row mb-2');
            entry.appendChild(node('dt', label, 'col-sm-5'));
            entry.appendChild(node('dd', disk.status === 'AVAILABLE'
                ? t('system.usable') + ': ' + size(disk.usableBytes) + ' / '
                    + t('system.total') + ': ' + size(disk.totalBytes)
                : t('system.unavailable'), 'col-sm-7 text-break'));
            target.appendChild(entry);
        });
        target.appendChild(node('p', t('system.diskNote'), 'small text-muted mb-2'));
        target.appendChild(node('p', t('system.instance') + ': ' + snapshot.instanceId,
            'small text-muted text-break mb-1'));
        target.appendChild(node('p', t('system.measured') + ': '
            + new Date(snapshot.timestamp).toLocaleString(TaxonomyI18n.getLocale()), 'small text-muted mb-0'));
    }

    function init() {
        var health = document.getElementById('healthDashboard');
        if (!health || document.getElementById('systemInformation')) return;
        var panel = node('details', null, 'mt-3');
        panel.id = 'systemInformation';
        panel.appendChild(node('summary', t('system.title'), 'fw-semibold'));
        var refresh = node('button', t('system.refresh'), 'btn btn-sm btn-outline-secondary my-2');
        refresh.id = 'systemInformationRefresh';
        refresh.type = 'button';
        var content = node('div');
        content.id = 'systemInformationContent';
        var status = node('p', null, 'small text-muted');
        status.setAttribute('role', 'status');
        status.setAttribute('aria-live', 'polite');
        panel.appendChild(refresh);
        panel.appendChild(status);
        panel.appendChild(content);
        health.appendChild(panel);
        var busy = false;

        function load() {
            if (busy) return;
            busy = true;
            refresh.disabled = true;
            content.replaceChildren();
            content.setAttribute('aria-busy', 'true');
            status.textContent = t('system.loading');
            TaxonomyApiClient.getJson('/api/admin/system-information')
                .then(function (snapshot) {
                    render(content, snapshot);
                    status.textContent = t('system.loaded');
                })
                .catch(function () {
                    content.replaceChildren();
                    status.textContent = t('system.error');
                })
                .finally(function () {
                    busy = false;
                    refresh.disabled = false;
                    content.setAttribute('aria-busy', 'false');
                });
        }
        panel.addEventListener('toggle', function () { if (panel.open) load(); });
        refresh.addEventListener('click', load);
    }

    function ready() { TaxonomyI18n.ready().then(init); }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', ready);
    else ready();
    return { init: init };
}());
