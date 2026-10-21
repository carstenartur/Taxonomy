/* Optional administrator guidance; deployment configuration remains authoritative. */
window.TaxonomyDatabaseSetup = (function () {
    'use strict';
    var api = window.TaxonomyApiClient;
    var t = window.TaxonomyI18n.t;
    var base = '/api/admin/database-setup';
    var state = null;
    var administrator = false;
    var preparation = null;
    var initialized = false;
    var step = 0;
    var generation = 0;
    var request = null;
    var preview = null;
    var returnFocus = null;

    function element(name) { return document.getElementById('databaseSetup' + name); }
    function text(name, value) { element(name).textContent = value; }

    function prepareWelcome() {
        if (preparation) return preparation.then(function () { return Boolean(state && state.promptRequired); });
        administrator = false;
        state = null;
        var timeout;
        var loaded = false;
        var load = api.getAccountContext().then(function (account) {
            administrator = Boolean(account && account.administrator);
            if (!administrator) return null;
            return api.getJson(base, { timeoutMillis: 4000 });
        }).then(function (result) {
            state = result;
            loaded = true;
            return Boolean(state && state.promptRequired);
        });
        // A slow/unavailable settings endpoint must not hold up normal work.
        preparation = Promise.race([
            load,
            new Promise(function (resolve) { timeout = setTimeout(function () { resolve(false); }, 6000); })
        ]).catch(function () { return false; }).finally(function () {
            clearTimeout(timeout);
            if (!loaded) preparation = null;
        });
        return preparation;
    }

    function dismissPrompt() {
        if (!state || !state.promptRequired) return;
        state.promptRequired = false;
        api.sendJson(base + '/dismiss', {}, { timeoutMillis: 5000 }).catch(function () {
            var notice = element('Notice');
            notice.textContent = t('database.setup.dismiss.failed');
            notice.hidden = false;
        });
    }

    function decorateWelcome(dialog) {
        if (!state || !state.promptRequired) return;
        dialog.dataset.databaseSetupPrompt = 'true';
        var card = dialog.querySelector('.onboarding-card');
        var close = document.createElement('button');
        close.type = 'button';
        close.className = 'btn-close database-setup-welcome-close';
        close.setAttribute('aria-label', t('database.setup.close'));
        close.addEventListener('click', function () { window.TaxonomyOnboarding.dismiss(); });
        card.prepend(close);
        var invitation = document.createElement('section');
        invitation.className = 'database-setup-invitation';
        var title = document.createElement('h3');
        title.textContent = t('database.setup.prompt.title');
        var description = document.createElement('p');
        description.textContent = t(state.inMemory ? 'database.setup.prompt.memory' : 'database.setup.prompt.body');
        var start = document.createElement('button');
        start.id = 'databaseSetupStart';
        start.type = 'button';
        start.className = 'btn btn-outline-primary';
        start.textContent = t('database.setup.prompt.start');
        start.addEventListener('click', function () {
            window.TaxonomyOnboarding.dismiss();
            requestAnimationFrame(function () { open(); });
        });
        invitation.append(title, description, start);
        card.insertBefore(invitation, card.querySelector('.onboarding-actions'));
        dialog.querySelector('#onboardingDismiss').textContent = t('database.setup.continue');
    }

    function currentDatabase() {
        if (!state) return t('database.setup.current.unknown');
        return t('database.setup.current.' + (state.inMemory ? 'memory' : state.database));
    }

    function setStep(value) {
        step = value;
        element('Dialog').querySelectorAll('[data-database-step]').forEach(function (section) {
            section.hidden = Number(section.dataset.databaseStep) !== step;
        });
        element('Dialog').querySelectorAll('[data-database-stage]').forEach(function (item) {
            if (Number(item.dataset.databaseStage) === step) item.setAttribute('aria-current', 'step');
            else item.removeAttribute('aria-current');
        });
        element('Back').hidden = step === 0;
        element('Next').hidden = step === 2;
        text('Next', t(step === 1 ? 'database.setup.prepare' : 'database.setup.next'));
        text('Status', '');
        element('Results').hidden = true;
        var focus = step === 0 ? element('Type') : step === 1 ? element('Host') : element('ApplyTitle');
        element('Dialog').querySelector('.database-setup-body').scrollTop = 0;
        focus.focus();
    }

    function updateTarget(resetDefaults) {
        var type = element('Type').value;
        if (resetDefaults) {
            element('Port').value = { postgres: 5432, mssql: 1433, oracle: 1521 }[type];
            if (['taxonomy', 'FREEPDB1'].indexOf(element('Name').value) >= 0) {
                element('Name').value = type === 'oracle' ? 'FREEPDB1' : 'taxonomy';
            }
            element('Ca').value = '';
        }
        text('Support', t(type === 'postgres' ? 'database.setup.support.postgres' : 'database.setup.support.evaluation'));
        text('NameLabel', t(type === 'oracle' ? 'database.setup.service' : 'database.setup.name'));
        var tls = element('Tls').checked;
        element('TlsWarning').hidden = tls;
        element('CaGroup').hidden = type !== 'postgres' || !tls;
        element('JvmTrust').hidden = type === 'postgres' || !tls;
    }

    function settings(includePassword) {
        return {
            type: element('Type').value,
            host: element('Host').value.trim(),
            port: Number(element('Port').value),
            database: element('Name').value.trim(),
            username: element('Username').value.trim(),
            password: includePassword ? element('Password').value : '',
            tls: element('Tls').checked,
            caCertificate: element('Type').value === 'postgres' && element('Tls').checked
                ? element('Ca').value.trim() : ''
        };
    }

    function validConnection() {
        return Array.from(element('Form').querySelectorAll('[data-database-step="1"] input'))
            .every(function (input) { return input.reportValidity(); });
    }

    function busy(value) {
        element('Check').disabled = value;
        element('Next').disabled = value;
        element('Back').disabled = value;
        element('Form').setAttribute('aria-busy', String(value));
    }

    function invalidate() {
        generation += 1;
        if (request) request.abort();
        request = null;
        preview = null;
        busy(false);
        element('Results').replaceChildren();
        element('Results').hidden = true;
        text('Status', '');
        text('Properties', '');
        text('Helm', '');
    }

    function findings(items) {
        var list = element('Results');
        list.replaceChildren();
        (items || []).forEach(function (finding) {
            var item = document.createElement('li');
            var badge = document.createElement('span');
            var colors = { OK: 'success', WARNING: 'warning text-dark', ERROR: 'danger', NOT_CHECKED: 'secondary' };
            badge.className = 'badge bg-' + (colors[finding.status] || 'secondary');
            badge.textContent = t('database.setup.status.' + finding.status);
            var key = 'database.setup.finding.' + finding.key + '.' + finding.status;
            var translated = t(key);
            item.append(badge, document.createTextNode(translated === key ? finding.message : translated));
            list.appendChild(item);
        });
        list.hidden = !list.children.length;
    }

    function submit(check) {
        if (!validConnection()) return;
        invalidate();
        var version = generation;
        request = new AbortController();
        busy(true);
        text('Status', t(check ? 'database.setup.checking' : 'database.setup.preparing'));
        api.sendJson(base + (check ? '/check' : '/preview'), settings(check), {
            signal: request.signal,
            timeoutMillis: check ? 15000 : 5000
        }).then(function (result) {
            if (version !== generation || !element('Dialog').open) return;
            text('Status', t(check ? 'database.setup.check.finished' : 'database.setup.preview.ready'));
            if (!check) {
                preview = result;
                text('Properties', result.properties);
                text('Helm', result.helmValues);
                setStep(2);
                text('Status', t('database.setup.preview.ready'));
            }
            findings(result.findings);
        }).catch(function (error) {
            if (version !== generation || !element('Dialog').open) return;
            var message = 'database.setup.request.failed';
            if (error.status === 400 || error.status === 413) message = 'database.setup.invalid';
            if (error.status === 409 && error.responseBody
                    && error.responseBody.code === 'DATABASE_SETUP_SCHEMA_CONFLICT') {
                message = 'database.setup.schema.conflict';
            }
            text('Status', t(message));
        }).finally(function () {
            if (version !== generation) return;
            request = null;
            busy(false);
        });
    }

    function download(kind) {
        if (!preview) return;
        var url = URL.createObjectURL(new Blob([preview[kind]], { type: 'text/plain;charset=utf-8' }));
        var link = document.createElement('a');
        link.href = url;
        link.download = kind === 'properties' ? 'taxonomy-database.properties' : 'taxonomy-database-values.yaml';
        document.body.appendChild(link);
        link.click();
        link.remove();
        setTimeout(function () { URL.revokeObjectURL(url); }, 1000);
    }

    function copy(kind) {
        if (!preview) return;
        if (!navigator.clipboard) {
            text('Status', t('database.setup.copy.failed'));
            return;
        }
        navigator.clipboard.writeText(preview[kind]).then(function () {
            text('Status', t('database.setup.copied'));
        }).catch(function () { text('Status', t('database.setup.copy.failed')); });
    }

    function close() {
        dismissPrompt();
        invalidate();
        element('Password').value = '';
        element('Dialog').close();
    }

    function trapFocus(event) {
        if (event.key !== 'Tab') return;
        var controls = Array.from(element('Dialog').querySelectorAll('button:not([disabled]), input, select, a[href], [tabindex="0"]'))
            .filter(function (control) { return control.getClientRects().length > 0; });
        var first = controls[0];
        var last = controls[controls.length - 1];
        if (event.shiftKey && document.activeElement === first) {
            event.preventDefault(); last.focus();
        } else if (!event.shiftKey && document.activeElement === last) {
            event.preventDefault(); first.focus();
        }
    }

    function initialize() {
        if (initialized || !element('Dialog')) return;
        initialized = true;
        element('MenuAction').addEventListener('click', open);
        element('Close').addEventListener('click', close);
        element('Dismiss').addEventListener('click', close);
        element('Dialog').addEventListener('cancel', function (event) { event.preventDefault(); close(); });
        element('Dialog').addEventListener('keydown', trapFocus);
        element('Dialog').addEventListener('close', function () {
            invalidate();
            element('Password').value = '';
            if (returnFocus && returnFocus.isConnected) {
                requestAnimationFrame(function () { if (!element('Dialog').open) returnFocus.focus({ preventScroll: true }); });
            }
        });
        element('Next').addEventListener('click', function () { if (step === 0) setStep(1); else submit(false); });
        element('Back').addEventListener('click', function () { setStep(Math.max(0, step - 1)); });
        element('Check').addEventListener('click', function () { submit(true); });
        element('Form').addEventListener('submit', function (event) { event.preventDefault(); if (step === 1) submit(true); });
        element('Form').addEventListener('input', invalidate);
        element('Type').addEventListener('change', function () { updateTarget(true); });
        element('Tls').addEventListener('change', function () { updateTarget(false); });
        element('Dialog').querySelectorAll('[data-database-download]').forEach(function (button) {
            button.addEventListener('click', function () { download(button.dataset.databaseDownload); });
        });
        element('Dialog').querySelectorAll('[data-database-copy]').forEach(function (button) {
            button.addEventListener('click', function () { copy(button.dataset.databaseCopy); });
        });
    }

    function open() {
        if (window.TaxonomyOnboarding) window.TaxonomyOnboarding.dismiss();
        return prepareWelcome().then(function () {
            if (!administrator || !state) {
                text('Notice', t('database.setup.request.failed'));
                element('Notice').hidden = false;
                element('Notice').scrollIntoView({ block: 'center' });
                return;
            }
            initialize();
            if (element('Dialog').open) return;
            element('Notice').hidden = true;
            var menu = document.getElementById('applicationSettings');
            returnFocus = menu && menu.contains(document.activeElement)
                ? menu.querySelector('summary') : document.getElementById('businessText');
            if (menu) menu.open = false;
            element('Form').reset();
            invalidate();
            updateTarget(true);
            text('Current', t('database.setup.current', currentDatabase()));
            element('Dialog').showModal();
            setStep(0);
        });
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initialize);
    else initialize();
    return { prepareWelcome: prepareWelcome, decorateWelcome: decorateWelcome, dismissPrompt: dismissPrompt, open: open };
}());
