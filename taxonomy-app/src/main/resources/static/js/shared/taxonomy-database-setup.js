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
    var provisionPlan = null;
    var mutating = false;
    var needsInspection = false;

    function element(name) { return document.getElementById('databaseSetup' + name); }
    function text(name, value) { element(name).textContent = value; }
    function createMode() { return element('ModeCreate').checked; }

    function navigation() {
        element('Back').hidden = step === 0;
        element('Next').hidden = step === 2 || (step === 1 && createMode());
        text('Next', t(step === 1 ? 'database.setup.prepare' : 'database.setup.next'));
        text('Dismiss', t(step === 2 ? 'database.setup.done' : 'database.setup.continue'));
    }

    function prepareWelcome() {
        if (preparation) return preparation.then(function () { return Boolean(state && state.promptRequired); });
        administrator = false;
        state = null;
        var timeout;
        var loaded = false;
        var load = api.getAccountContext().then(function (account) {
            administrator = Boolean(account && account.administrator);
            if (!administrator) return null;
            // Installation guidance is independent of the selected repository.
            // Authentication, page lifetime and the deadline remain canonical.
            return api.getJson(base, { timeoutMillis: 4000, contextSensitive: false, workspaceScoped: false });
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
        element('TlsWarning').hidden = step === 0 || element('Tls').checked;
        element('Compatibility').hidden = step === 0 || element('Type').value === 'postgres';
        element('Dialog').querySelectorAll('[data-database-step]').forEach(function (section) {
            section.hidden = Number(section.dataset.databaseStep) !== step;
        });
        element('Dialog').querySelectorAll('[data-database-stage]').forEach(function (item) {
            if (Number(item.dataset.databaseStage) === step) item.setAttribute('aria-current', 'step');
            else item.removeAttribute('aria-current');
        });
        navigation();
        clearResult();
        provisionPlan = null;
        element('Review').hidden = true;
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
            element('AdminUsername').value = { postgres: 'postgres', mssql: 'sa', oracle: 'SYSTEM' }[type];
            element('Maintenance').value = type === 'mssql' ? 'master' : type === 'oracle' ? element('Name').value : 'postgres';
            if (['taxonomy_app', 'TAXONOMY_APP'].indexOf(element('Username').value) >= 0) {
                element('Username').value = type === 'oracle' ? 'TAXONOMY_APP' : 'taxonomy_app';
            }
            element('AdminPassword').value = '';
            element('Password').value = '';
        }
        var creating = createMode();
        text('Support', t(type === 'postgres' ? 'database.setup.support.postgres' : 'database.setup.support.evaluation'));
        text('CreateLabel', t('database.setup.mode.create.' + type));
        text('CreateHelp', t('database.setup.mode.create.help.' + type));
        text('NameLabel', t(type === 'oracle' ? 'database.setup.service' : 'database.setup.name'));
        text('UsernameLabel', t(type === 'oracle' ? 'database.setup.oracle.user' : type === 'mssql' ? 'database.setup.mssql.user' : 'database.setup.username'));
        text('NameHelp', t(creating ? 'database.setup.create.name.help.' + type : 'database.setup.name.help'));
        element('Name').maxLength = creating && type === 'postgres' ? 63 : 128;
        element('Name').pattern = creating && type !== 'oracle'
            ? (type === 'postgres' ? '[a-z_][a-z0-9_]{0,62}' : '[a-z_][a-z0-9_]{0,127}')
            : '[A-Za-z0-9_][A-Za-z0-9_.\\-]*';
        element('Username').maxLength = creating ? {postgres: 63, mssql: 128, oracle: 30}[type] : 128;
        if (creating) element('Username').pattern = {postgres: '[a-z_][a-z0-9_]{0,62}',
            mssql: '[a-z_][a-z0-9_]{0,127}', oracle: '[A-Za-z][A-Za-z0-9_]{0,29}'}[type];
        else element('Username').removeAttribute('pattern');
        element('Password').required = creating;
        element('Password').minLength = creating && type === 'mssql' ? 8 : 0;
        element('Password').maxLength = creating ? {postgres: 4096, mssql: 128, oracle: 30}[type] : 4096;
        if (creating && type === 'mssql') element('Password').pattern = "[^'\\x00-\\x1F\\x7F]{8,128}";
        else if (creating && type === 'oracle') element('Password').pattern = '[\\x20-\\x21\\x23-\\x7E]{1,30}';
        else element('Password').removeAttribute('pattern');
        text('PasswordLabel', t(creating ? 'database.setup.create.password' : 'database.setup.password'));
        text('PasswordHelp', t(creating ? 'database.setup.create.password.help.' + type : 'database.setup.password.help'));
        text('AdminHelp', t('database.setup.admin.help.' + type));
        element('MaintenanceGroup').hidden = type === 'oracle';
        element('Maintenance').readOnly = type === 'mssql';
        element('OracleStorage').hidden = type !== 'oracle';
        element('Compatibility').hidden = step === 0 || type === 'postgres';
        text('ReviewHelp', t('database.setup.review.help.' + type));
        text('ReviewDatabaseLabel', t(type === 'oracle' ? 'database.setup.service' : 'database.setup.name'));
        text('ReviewRoleLabel', t(type === 'oracle' ? 'database.setup.oracle.user' : type === 'mssql' ? 'database.setup.mssql.user' : 'database.setup.username'));
        element('ReviewStorageLabel').hidden = type !== 'oracle';
        element('ReviewStorage').hidden = type !== 'oracle';
        element('AdminFields').hidden = !creating;
        element('AdminFields').disabled = !creating || mutating;
        text('Check', t(creating ? (needsInspection ? 'database.setup.inspect.again' : 'database.setup.inspect') : 'database.setup.check'));
        text('CheckScope', t(creating ? 'database.setup.inspect.scope' : 'database.setup.check.scope'));
        var tls = element('Tls').checked;
        element('TlsWarning').hidden = step === 0 || tls;
        element('CaGroup').hidden = type !== 'postgres' || !tls;
        element('JvmTrust').hidden = type === 'postgres' || !tls;
        navigation();
    }

    function settings(includePassword) {
        return {
            type: element('Type').value,
            host: element('Host').value.trim(),
            port: Number(element('Port').value),
            database: element('Name').value.trim(),
            username: element('Type').value === 'oracle' && createMode()
                ? element('Username').value.trim().toUpperCase() : element('Username').value.trim(),
            password: includePassword ? element('Password').value : '',
            tls: element('Tls').checked,
            caCertificate: element('Type').value === 'postgres' && element('Tls').checked
                ? element('Ca').value.trim() : ''
        };
    }

    function validConnection() {
        return Array.from(element('Form').querySelectorAll('[data-database-step="1"] input'))
            .every(function (input) {
                return input.disabled || input.closest('fieldset[disabled]') || input.closest('[hidden]') || input.reportValidity();
            });
    }

    function busy(value) {
        element('Check').disabled = value;
        element('Next').disabled = value;
        element('Back').disabled = value;
        element('Provision').disabled = value || !provisionPlan;
        element('Close').disabled = value && mutating;
        element('Dismiss').disabled = value && mutating;
        element('Form').querySelectorAll('input, select').forEach(function (input) { input.disabled = value && mutating; });
        element('Form').setAttribute('aria-busy', String(value));
        element('AdminFields').disabled = !createMode() || (value && mutating);
    }

    function clearResult() {
        element('Result').hidden = true;
        element('Details').hidden = true;
        element('Details').open = false;
        element('Results').replaceChildren();
        text('Status', '');
        text('ResultMessage', '');
        text('ResultNext', '');
        text('ResultContext', '');
    }

    function invalidate() {
        if (mutating) return;
        generation += 1;
        if (request) request.abort();
        request = null;
        preview = null;
        provisionPlan = null;
        element('Review').hidden = true;
        busy(false);
        clearResult();
        text('Properties', '');
        text('Helm', '');
    }

    function findingText(finding) {
        var key = 'database.setup.finding.' + finding.key + '.' + finding.status;
        var translated = t(key);
        return translated === key ? finding.message : translated;
    }

    function findings(items) {
        var list = element('Results');
        list.replaceChildren();
        (items || []).forEach(function (finding) {
            if (finding.key.indexOf('database.connection') === 0 || finding.key === 'database.tls') return;
            var item = document.createElement('li');
            item.textContent = findingText(finding);
            list.appendChild(item);
        });
        element('Details').hidden = !list.children.length;
    }

    function showResult(status, title, message, next) {
        var result = element('Result');
        result.dataset.status = status;
        text('Status', t(title));
        text('ResultMessage', message || '');
        text('ResultNext', next ? t(next) : '');
        var target = settings(false);
        text('ResultContext', step === 0 ? '' : t('database.setup.result.context', target.host, target.port, target.database, target.username));
        result.hidden = false;
        result.scrollIntoView({ block: 'nearest' });
    }

    function connectionResult(result) {
        var items = result.findings || [];
        var outcome = items.find(function (finding) { return finding.key.indexOf('database.connection') === 0; });
        var ok = outcome && outcome.status === 'OK';
        var waiting = outcome && outcome.status === 'WARNING';
        var schemaConflict = items.some(function (finding) { return finding.key === 'database.schema-overrides'; });
        showResult(ok ? 'success' : waiting ? 'warning' : 'error',
            ok ? 'database.setup.check.success' : waiting ? 'database.setup.check.waiting' : 'database.setup.check.failed',
            outcome ? findingText(outcome) : t('database.setup.finding.database.connection.ERROR'),
            ok ? (schemaConflict ? 'database.setup.schema.conflict' : 'database.setup.check.next') : 'database.setup.check.retry');
        findings(items);
    }

    function requestMessage(error) {
        if (error.status === 400 || error.status === 413) return createMode() ? 'database.setup.create.invalid' : 'database.setup.invalid';
        if (error.status === 401 || error.status === 403) return 'database.setup.session.failed';
        if (error.status === 409 && error.responseBody && error.responseBody.code === 'DATABASE_SETUP_SCHEMA_CONFLICT') return 'database.setup.schema.conflict';
        return 'database.setup.request.failed';
    }

    function submit(check) {
        if (mutating) return;
        if (check && createMode()) { prepareDatabase(false); return; }
        if (!validConnection()) return;
        invalidate();
        var version = generation;
        request = new AbortController();
        busy(true);
        showResult('info', check ? 'database.setup.checking' : 'database.setup.preparing', '', '');
        api.sendJson(base + (check ? '/check' : '/preview'), settings(check), {
            signal: request.signal,
            timeoutMillis: check ? 15000 : 5000
        }).then(function (result) {
            if (version !== generation || !element('Dialog').open) return;
            if (check) connectionResult(result);
            else {
                preview = result;
                text('Properties', result.properties);
                text('Helm', result.helmValues);
                setStep(2);
                showResult('info', 'database.setup.preview.ready', t('database.setup.preview.message'), 'database.setup.preview.next');
                findings(result.findings);
                // Rendering the result above the deployment section can move its old focus
                // outside the mobile scroll area. Focus the visible outcome after layout.
                element('Status').focus();
            }
        }).catch(function (error) {
            if (version !== generation || !element('Dialog').open) return;
            showResult('error', 'database.setup.request.title', t(requestMessage(error)), 'database.setup.check.retry');
        }).finally(function () {
            if (version !== generation) return;
            request = null;
            busy(false);
        });
    }

    function resourceText(state) { return t('database.setup.resource.' + state); }

    function reviewPlan(result) {
        provisionPlan = result;
        var target = settings(false);
        text('ReviewServer', target.host + ':' + target.port);
        text('ReviewDatabase', target.database + ' — ' + resourceText(result.database));
        text('ReviewRole', target.username + ' — ' + resourceText(result.role));
        text('ReviewStorage', element('Tablespace').value.trim().toUpperCase() + ' · ' + element('Quota').value + ' MiB');
        text('ReviewTransport', t(target.tls ? 'database.setup.transport.tls' : 'database.setup.transport.plain'));
        text('Provision', t(result.role === 'REUSE' && result.database === 'REUSE' ? 'database.setup.provision.reuse' : 'database.setup.provision.' + target.type));
        element('Review').hidden = false;
        element('Review').scrollIntoView({ block: 'nearest' });
    }

    function withResponseDeadline(response, timeoutMillis) {
        var timer;
        // The shared transport timer ends at response headers. Bound JSON decoding too;
        // reaching this deadline does not cancel or roll back a server-side creation.
        var deadline = new Promise(function (resolve, reject) {
            timer = setTimeout(function () {
                var error = new Error('Database setup response timed out');
                error.name = 'TimeoutError';
                reject(error);
            }, timeoutMillis);
        });
        return Promise.race([response, deadline]).finally(function () { clearTimeout(timer); });
    }

    function prepareDatabase(create) {
        if (mutating || (create && !provisionPlan) || !validConnection()) return;
        var payload = { target: settings(true), maintenanceDatabase: element('Type').value === 'oracle' ? element('Name').value.trim() : element('Maintenance').value.trim(),
            adminUsername: element('AdminUsername').value.trim(), adminPassword: element('AdminPassword').value, confirmed: create,
            expectedRole: create ? provisionPlan.role : null, expectedDatabase: create ? provisionPlan.database : null };
        if (payload.target.type === 'oracle') {
            payload.tablespace = element('Tablespace').value.trim().toUpperCase();
            payload.quotaMiB = Number(element('Quota').value);
        }
        invalidate();
        var version = generation;
        mutating = create;
        request = create ? null : new AbortController();
        busy(true);
        showResult('info', create ? 'database.setup.provision.running' : 'database.setup.inspect.running', '', '');
        var timeoutMillis = create ? 75000 : 25000;
        var response = api.sendJson(base + (create ? '/provision' : '/inspect'), payload, {
            signal: request ? request.signal : undefined, timeoutMillis: timeoutMillis, retries: 0
        });
        withResponseDeadline(response, timeoutMillis).then(function (result) {
            if (version !== generation || !element('Dialog').open) return;
            var outcome = result.outcome;
            needsInspection = outcome !== 'READY' && outcome !== 'COMPLETE';
            if (outcome === 'COMPLETE') {
                element('AdminPassword').value = '';
                element('Username').value = payload.target.username;
                element('ModeExisting').checked = true;
                element('ModeCreate').checked = false;
            }
            var status = outcome === 'COMPLETE' ? 'success' : outcome === 'READY' ? 'info' : outcome === 'BUSY' ? 'warning' : 'error';
            var message = result.finding ? findingText(result.finding) : t('database.setup.request.failed');
            if (outcome !== 'READY') message += ' ' + t('database.setup.provision.resources.' + payload.target.type, resourceText(result.role), resourceText(result.database));
            showResult(status, 'database.setup.provision.outcome.' + outcome, message,
                outcome === 'COMPLETE' ? (payload.target.type === 'postgres' ? 'database.setup.check.next' : 'database.setup.compatibility.next') : outcome === 'READY' ? '' : 'database.setup.provision.retry');
            if (outcome === 'READY') reviewPlan(result);
            updateTarget(false);
        }).catch(function (error) {
            if (version !== generation || !element('Dialog').open) return;
            // A lost creation response says nothing about whether the database committed the operation.
            var uncertain = create && [400, 401, 403, 413, 415].indexOf(error.status) < 0;
            needsInspection = uncertain;
            showResult('error', uncertain ? 'database.setup.provision.outcome.UNKNOWN' : 'database.setup.request.title',
                uncertain ? t('database.setup.finding.database.provision.unknown.ERROR') : t(requestMessage(error)),
                uncertain ? 'database.setup.provision.retry' : 'database.setup.check.retry');
        }).finally(function () {
            if (version !== generation) return;
            mutating = false;
            request = null;
            busy(false);
            updateTarget(false);
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
        if (mutating) return;
        dismissPrompt();
        invalidate();
        element('Password').value = '';
        element('AdminPassword').value = '';
        element('Dialog').close();
    }

    function trapFocus(event) {
        if (event.key !== 'Tab') return;
        var controls = Array.from(element('Dialog').querySelectorAll('button:not([disabled]), input:not([disabled]), select:not([disabled]), summary, a[href], [tabindex="0"]'))
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
            if (mutating) return;
            invalidate();
            element('Password').value = '';
            element('AdminPassword').value = '';
            requestAnimationFrame(function () {
                if (element('Dialog').open) return;
                if (window.TaxonomyAppChrome) window.TaxonomyAppChrome.restoreFocus(returnFocus, { preventScroll: true });
                else if (returnFocus?.isConnected) returnFocus.focus({ preventScroll: true });
            });
        });
        element('Next').addEventListener('click', function () { if (step === 0) setStep(1); else submit(false); });
        element('Back').addEventListener('click', function () { setStep(Math.max(0, step - 1)); });
        element('Check').addEventListener('click', function () { submit(true); });
        element('Provision').addEventListener('click', function () { prepareDatabase(true); });
        element('Form').addEventListener('submit', function (event) { event.preventDefault(); if (step === 1) submit(true); });
        element('Form').addEventListener('input', function () { invalidate(); updateTarget(false); });
        element('Type').addEventListener('change', function () { updateTarget(true); });
        element('Tls').addEventListener('change', function () { updateTarget(false); });
        element('ModeExisting').addEventListener('change', function () { updateTarget(false); });
        element('ModeCreate').addEventListener('change', function () { updateTarget(false); });
        element('Dialog').querySelectorAll('[data-database-download]').forEach(function (button) {
            button.addEventListener('click', function () { download(button.dataset.databaseDownload); });
        });
        element('Dialog').querySelectorAll('[data-database-copy]').forEach(function (button) {
            button.addEventListener('click', function () { copy(button.dataset.databaseCopy); });
        });
    }

    function open() {
        var origin = document.activeElement;
        if (!element('Dialog')?.open) returnFocus = origin && origin !== document.body ? origin : document.getElementById('businessText');
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
            var menu = document.getElementById('applicationTools');
            if (menu) menu.open = false;
            invalidate();
            element('Password').value = '';
            element('AdminPassword').value = '';
            updateTarget(false);
            text('Current', t('database.setup.current', currentDatabase()));
            text('Persistence', t(state.inMemory ? 'database.setup.persistence.memory' : 'database.setup.persistence.body'));
            element('Dialog').showModal();
            setStep(0);
        });
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initialize);
    else initialize();
    return { prepareWelcome: prepareWelcome, decorateWelcome: decorateWelcome, dismissPrompt: dismissPrompt, open: open };
}());
