/* Read-only shared navigation and entity presentation. Feature modules own all state reads and writes. */
(function () {
    'use strict';
    var state = {};
    var groupKeys = ['architecture', 'project', 'requirement', 'source'];
    var routes = {projects: '/projects', analysis: '/', architecture: '/architecture/editor'};
    function node(value) { return typeof value === 'string' ? document.getElementById(value) : value; }
    function t(key) { return window.TaxonomyI18n.t.apply(null, arguments); }
    function present(value) { return value !== null && value !== undefined && value !== ''; }
    function create(tag, text, className) {
        var result = document.createElement(tag);
        if (present(text)) result.textContent = String(text);
        if (className) result.className = className;
        return result;
    }
    function statusLabel(value) {
        var key = 'app.status.' + String(value || '').toUpperCase(), label = t(key);
        return label === key ? window.TaxonomyI18n.formatEnum(String(value || '').toUpperCase()) : label;
    }
    function setStatus(target, value, label) {
        target = node(target); if (!target) return null;
        Array.from(target.classList).forEach(function (name) {
            if (name === 'badge' || /^(?:text-bg|bg)-(?:primary|secondary|success|warning|danger|info|light|dark)$/.test(name)) target.classList.remove(name);
        });
        target.classList.add('taxonomy-status', 'taxonomy-entity-status');
        var normalized = String(value || '').toUpperCase();
        target.dataset.state = normalized;
        target.dataset.tone = /^(COMPLETE|COMPLETED|CONFIRMED|CHECKPOINTED|SUCCESS|ACTIVE)$/.test(normalized) ? 'positive'
            : /^(REVIEW_REQUIRED|PARTIAL|CHECKPOINT_REQUIRED|CHECKPOINT_PENDING|PORTFOLIO_CHANGES_PENDING|VERSION_CHANGED|PROPOSED)$/.test(normalized) ? 'warning'
            : /^(FAILED|ERROR|CONFLICT|REJECTED)$/.test(normalized) ? 'danger'
            : /^(ARCHIVED|SUPERSEDED|HISTORICAL|READ_ONLY)$/.test(normalized) ? 'muted' : 'neutral';
        target.textContent = label || statusLabel(value);
        target.hidden = !present(value) && !present(label);
        return target;
    }
    function renderDetails(target, rows) {
        target = node(target); if (!target) return null;
        var list = target.tagName === 'DL' ? target : create('dl');
        list.classList.add('taxonomy-entity-details');
        list.replaceChildren();
        (rows || []).forEach(function (row) {
            var label = Array.isArray(row) ? row[0] : row.label;
            var value = Array.isArray(row) ? row[1] : row.value;
            list.append(create('dt', label), create('dd', present(value) ? value : '—'));
        });
        if (list !== target) target.replaceChildren(list);
        return list;
    }
    function renderEntity(target, value) {
        target = node(target); if (!target) return null;
        value = value || {}; target.classList.add('taxonomy-entity'); target.replaceChildren();
        if (value.kind) target.append(create('p', t('app.entity.' + value.kind), 'taxonomy-entity-kind'));
        var badges = create('div', null, 'taxonomy-entity-badges');
        if (present(value.key)) badges.append(create('span', value.key, 'taxonomy-entity-key'));
        if (present(value.version)) badges.append(create('span', t('app.entity.version', value.version), 'taxonomy-entity-version'));
        if (present(value.status)) badges.append(setStatus(create('span'), value.status, value.statusLabel));
        if (badges.childElementCount) target.append(badges);
        if (present(value.title)) target.append(create('h' + (/^[1-6]$/.test(value.headingLevel) ? value.headingLevel : 2), value.title, 'taxonomy-entity-title'));
        if (present(value.description)) target.append(create('p', value.description, 'taxonomy-entity-description'));
        if (value.details?.length) { var list = create('dl'); renderDetails(list, value.details); target.append(list); }
        return target;
    }
    function getContext() { return JSON.parse(JSON.stringify(state)); }
    function identity(value) { return [value?.key || value?.id, value?.title].filter(present).join(' — '); }
    function entry(rows, key, value) { if (present(value)) rows.push({label: t(key), value: value}); }
    function opaqueIdentity(value) {
        return /^(?:[a-f\d]{40}|[a-f\d]{8}(?:-[a-f\d]{4}){3}-[a-f\d]{12})$/i.test(String(value || ''));
    }
    function shortIdentity(value) {
        return present(value) && (opaqueIdentity(value) || String(value).length > 24)
            ? String(value).slice(0, 12) + '…' : value;
    }
    function architectureName(value) {
        if (present(value.title)) return value.title;
        if (!present(value.repositoryId)) return null;
        return opaqueIdentity(value.repositoryId) ? t('app.entity.architecture') : value.repositoryId;
    }
    function versionedIdentity(value, version) {
        return [identity(value), present(version) ? t('app.entity.version', version) : null].filter(present).join(' · ');
    }
    function renderContextDetails(target, rows) {
        target = node(target); if (!target) return;
        target.replaceChildren();
        rows.forEach(function (row) {
            var field = create('div', null, 'taxonomy-app-context-field');
            field.append(create('dt', row.label), create('dd', row.value));
            target.append(field);
        });
    }
    function technicalEntry(rows, group, key, value, id) {
        if (present(value)) rows.push({label: t(group) + ' · ' + t(key) + (id ? ' ID' : ''), value: value});
    }
    function renderContext() {
        var root = node('applicationContext'); if (!root) return;
        var current = [], source = [], technical = [];
        var architecture = state.architecture || {}, requirement = state.requirement || {};
        entry(current, 'app.entity.architecture', architectureName(architecture));
        entry(current, 'app.entity.project', identity(state.project));
        entry(current, 'app.entity.requirement', versionedIdentity(requirement, requirement.version));
        entry(current, 'app.context.modelStand', [architecture.branch, shortIdentity(architecture.modelStand)].filter(present).join(' · '));
        technicalEntry(technical, 'app.context.current', 'app.entity.architecture', architecture.repositoryId, true);
        technicalEntry(technical, 'app.context.current', 'app.context.workspace', architecture.workspaceScopeKey);
        technicalEntry(technical, 'app.context.current', 'app.context.branch', architecture.branch);
        technicalEntry(technical, 'app.context.current', 'app.context.modelStand', architecture.modelStand);
        technicalEntry(technical, 'app.context.current', 'workbench.ui.commit', architecture.commit);
        technicalEntry(technical, 'app.context.current', 'app.context.modelStand', architecture.revision);
        technicalEntry(technical, 'app.context.current', 'app.entity.project', state.project?.id, true);
        technicalEntry(technical, 'app.context.current', 'app.entity.requirement', requirement.id, true);
        technicalEntry(technical, 'app.context.current', 'app.context.currentTextVersionId', requirement.versionId);
        if (state.source) {
            var evidence = state.source;
            entry(source, 'app.entity.project', identity({key: evidence.projectKey || evidence.projectId, title: evidence.projectTitle}));
            entry(source, 'app.entity.requirement', versionedIdentity({key: evidence.requirementKey || evidence.requirementId,
                title: evidence.requirementTitle}, evidence.requirementVersion));
            entry(source, 'app.context.snapshot', shortIdentity(evidence.snapshotId));
            technicalEntry(technical, 'app.context.source', 'app.entity.architecture', evidence.repositoryId, true);
            technicalEntry(technical, 'app.context.source', 'app.context.workspace', evidence.workspaceScopeKey);
            technicalEntry(technical, 'app.context.source', 'app.context.branch', evidence.branch);
            technicalEntry(technical, 'app.context.source', 'app.entity.project', evidence.projectId, true);
            technicalEntry(technical, 'app.context.source', 'app.entity.requirement', evidence.requirementId, true);
            technicalEntry(technical, 'app.context.source', 'app.context.evidenceVersionId', evidence.requirementVersionId);
            technicalEntry(technical, 'app.context.source', 'app.context.snapshot', evidence.snapshotId);
        }
        technical = technical.concat(state.details || []);
        renderContextDetails('applicationCurrentDetails', current);
        renderContextDetails('applicationSourceDetails', source);
        var statuses = node('applicationContextStates'); statuses.replaceChildren();
        if (typeof state.editable === 'boolean') statuses.append(setStatus(create('span'), state.editable ? 'EDITABLE' : 'READ_ONLY'));
        ['realizationStatus', 'publicationState'].forEach(function (key) {
            if (present(state[key])) statuses.append(setStatus(create('span'), state[key], t('app.context.' + key) + ': ' + statusLabel(state[key])));
        });
        node('applicationCurrentContext').hidden = !current.length && !statuses.childElementCount;
        node('applicationSourceContext').hidden = !source.length;
        node('applicationTechnicalContext').hidden = !technical.length;
        renderDetails('applicationTechnicalDetails', technical);
        root.hidden = !current.length && !source.length && !statuses.childElementCount && !technical.length;
    }
    function navigationUrl(route) {
        var target = new URL(window.TaxonomyI18n.resolveUrl(routes[route]), window.location.href);
        target.search = window.location.search;
        var scope = window.TaxonomyArchitectureWorkflow?.getScope?.() || {};
        Object.keys(scope).forEach(function (key) { if (present(scope[key]) || scope[key] === '') target.searchParams.set(key, scope[key]); });
        if (Object.prototype.hasOwnProperty.call(state, 'project')) {
            if (state.project?.id) target.searchParams.set('projectId', state.project.id); else target.searchParams.delete('projectId');
        }
        if (Object.prototype.hasOwnProperty.call(state, 'requirement')) {
            if (state.requirement?.id) target.searchParams.set('requirementId', state.requirement.id); else target.searchParams.delete('requirementId');
            if (state.requirement?.versionId) target.searchParams.set('requirementVersionId', state.requirement.versionId); else target.searchParams.delete('requirementVersionId');
        }
        if (Object.prototype.hasOwnProperty.call(state, 'source')) {
            ['projectId', 'requirementId', 'requirementVersionId', 'snapshotId', 'repositoryId', 'workspaceScopeKey', 'branch'].forEach(function (key) {
                var name = 'source' + key.charAt(0).toUpperCase() + key.slice(1), value = state.source?.[key];
                if (present(value)) target.searchParams.set(name, value); else target.searchParams.delete(name);
            });
            if (state.source?.workspaceScopeKey) target.searchParams.set('sourceWorkspaceId', state.source.workspaceScopeKey === '__shared__' ? '' : state.source.workspaceScopeKey);
            else if (state.source?.workspaceId !== undefined) target.searchParams.set('sourceWorkspaceId', state.source.workspaceId || '');
            else target.searchParams.delete('sourceWorkspaceId');
        }
        target.searchParams.set('lang', window.TaxonomyI18n.getLocale());
        if (route === 'analysis') target.hash = 'analyze';
        return target.pathname + target.search + target.hash;
    }
    function refreshNavigation() {
        document.querySelectorAll('[data-app-route]').forEach(function (link) {
            var href = navigationUrl(link.dataset.appRoute);
            if (link.getAttribute('href') !== href) link.setAttribute('href', href);
        });
        document.querySelectorAll('[data-app-locale]').forEach(function (link) {
            var target = new URL(window.location.href); target.searchParams.set('lang', link.dataset.appLocale);
            var href = target.pathname + target.search + target.hash;
            if (link.getAttribute('href') !== href) link.setAttribute('href', href);
            if (link.dataset.appLocale === window.TaxonomyI18n.getLocale()) link.setAttribute('aria-current', 'true'); else link.removeAttribute('aria-current');
        });
    }
    function updateContext(value) {
        var next = Object.assign({}, state);
        Object.keys(value || {}).forEach(function (key) {
            next[key] = groupKeys.includes(key) && value[key] ? Object.assign({}, state[key] || {}, value[key]) : value[key];
        });
        if (JSON.stringify(next) === JSON.stringify(state)) { refreshNavigation(); return getContext(); }
        state = next; renderContext(); refreshNavigation();
        document.dispatchEvent(new CustomEvent('taxonomy:entity-context-changed', {detail: getContext()}));
        return getContext();
    }
    document.addEventListener('click', function (event) {
        var link = event.target.closest?.('[data-app-locale]');
        refreshNavigation();
        if (link && event.button === 0 && !event.ctrlKey && !event.metaKey && !event.shiftKey && !event.altKey) {
            event.preventDefault(); window.TaxonomyI18n.setLocale(link.dataset.appLocale);
        }
    }, true);
    document.addEventListener('focusin', refreshNavigation);
    document.addEventListener('auxclick', refreshNavigation, true);
    window.addEventListener('popstate', refreshNavigation);
    window.addEventListener('hashchange', refreshNavigation);
    document.addEventListener('taxonomy-i18n-loaded', function () { renderContext(); refreshNavigation(); });
    window.TaxonomyAppChrome = Object.freeze({updateContext: updateContext, getContext: getContext,
        refreshNavigation: refreshNavigation, renderEntity: renderEntity, renderDetails: renderDetails,
        setStatus: setStatus, statusLabel: statusLabel});
    refreshNavigation();
}());
