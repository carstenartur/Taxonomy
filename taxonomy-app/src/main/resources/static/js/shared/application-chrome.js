/* Read-only shared navigation and entity presentation. Feature modules own all state reads and writes. */
(function () {
    'use strict';
    var state = {};
    var sourceContexts = {};
    var groupKeys = ['architecture', 'project', 'requirement', 'source'];
    var routes = {projects: '/projects', analysis: '/', architecture: '/', graph: '/', versions: '/', 'dsl-editor': '/', help: '/', admin: '/', preferences: '/', integrations: '/integrations'};
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
            : /^(FAILED|ERROR|CONFLICT|REJECTED|UNAVAILABLE|INVALID_EVIDENCE)$/.test(normalized) ? 'danger'
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
        technicalEntry(technical, 'app.context.current', present(architecture.modelStand)
            ? 'workbench.ui.commit' : 'app.context.lastObservedCommit', architecture.commit);
        technicalEntry(technical, 'app.context.current', present(architecture.modelStand)
            ? 'app.context.modelStand' : 'app.context.lastObservedRevision', architecture.revision);
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
        var summaryTitle = node('applicationContextSummaryTitle'), summarySelection = node('applicationContextSummarySelection');
        if (summaryTitle) summaryTitle.textContent = architectureName(architecture) || t('app.context.label');
        if (summarySelection) {
            var selected = [state.project?.key || state.project?.title,
                requirement.key || requirement.title,
                present(requirement.version) ? t('app.entity.version', requirement.version) : null].filter(present);
            summarySelection.textContent = selected.join(' · ');
        }
        var summaryStand = node('applicationContextSummaryStand');
        if (summaryStand) {
            var revision = present(architecture.revision) ? t('editor.revision', architecture.revision) : null;
            var selectedStand = present(architecture.modelStand)
                ? (revision && architecture.modelStand === revision ? revision
                    : architecture.commit?.slice(0, 12) || architecture.modelStand) : null;
            summaryStand.textContent = [architecture.branch, selectedStand].filter(present).join(' · ');
        }
        var summaryStates = node('applicationContextSummaryStates');
        if (summaryStates) {
            summaryStates.replaceChildren();
            if (state.editable === false) summaryStates.append(setStatus(create('span'), 'READ_ONLY'));
            ['publicationState', 'realizationStatus'].forEach(function (key) {
                var value = state[key];
                if (/^(CHECKPOINT_REQUIRED|CHECKPOINT_PENDING|PORTFOLIO_CHANGES_PENDING|VERSION_CHANGED|RECONCILIATION_REQUIRED|HISTORICAL|REVIEW_REQUIRED|PARTIAL|FAILED|ERROR|CONFLICT|UNRESOLVED|UNAVAILABLE|INVALID_EVIDENCE)$/.test(value || '')) {
                    summaryStates.append(setStatus(create('span'), value, t('app.context.' + key) + ': ' + statusLabel(value)));
                }
            });
        }
    }
    function initializeContextDisclosure() {
        var disclosure = node('applicationContextDisclosure');
        if (!disclosure) return;
        if (node('projectFilter')) {
            disclosure.dataset.compactContext = 'projects';
            disclosure.open = false;
        } else if (node('importMain') || node('matrixMain') || node('reportsMain')) {
            disclosure.dataset.compactContext = 'project';
            disclosure.open = false;
        } else if (node('architectureWorkspace') && node('tab-analyze')) {
            disclosure.dataset.compactContext = 'analysis';
            disclosure.open = false;
        }
    }
    function navigationUrl(route) {
        var target = new URL(window.TaxonomyI18n.resolveUrl(routes[route]), window.location.href);
        target.search = window.location.search;
        var scope = window.TaxonomyArchitectureWorkflow?.getScope?.() || {};
        Object.keys(scope).forEach(function (key) { if (present(scope[key]) || scope[key] === '') target.searchParams.set(key, scope[key]); });
        // Project subpages identify their project in the path, even before their data loads.
        var projectsPath = new URL(window.TaxonomyI18n.resolveUrl(routes.projects), window.location.href).pathname.replace(/\/+$/, '') + '/';
        var currentPath = window.location.pathname;
        var projectInPath = currentPath.startsWith(projectsPath) ? currentPath.slice(projectsPath.length).split('/')[0] : '';
        if (/^\d+$/.test(projectInPath)) target.searchParams.set('projectId', projectInPath);
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
        if (route !== 'projects' && route !== 'integrations') target.hash = route === 'analysis' ? 'analyze' : route;
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
    function activateContext(owner) {
        state = sourceContexts[owner] || {};
        renderContext(); refreshNavigation();
        return getContext();
    }
    function restoreFocus(element, options) {
        var menu = element?.closest('details');
        var candidates = [element, menu?.querySelector('summary'),
            node('applicationNavigationMenu')?.querySelector('summary'), node('applicationTools')?.querySelector('summary')];
        var target = candidates.find(function (candidate) {
            return candidate?.isConnected && !candidate.disabled && candidate.getClientRects().length
                && getComputedStyle(candidate).visibility !== 'hidden';
        });
        target?.focus(options);
    }
    function updateContext(value, owner) {
        owner = owner || 'analysis';
        var workspace = window.TaxonomyArchitectureWorkspace;
        var unified = Boolean(workspace || node('architectureWorkspace'));
        var previous = unified ? (sourceContexts[owner] || {}) : state;
        var next = Object.assign({}, previous);
        Object.keys(value || {}).forEach(function (key) {
            next[key] = groupKeys.includes(key) && value[key] ? Object.assign({}, previous[key] || {}, value[key]) : value[key];
        });
        sourceContexts[owner] = next;
        if (unified && workspace && owner !== (workspace.isActive() ? workspace.getSource().kind : 'analysis')) return JSON.parse(JSON.stringify(next));
        if (JSON.stringify(next) === JSON.stringify(state)) { refreshNavigation(); return getContext(); }
        state = next; renderContext(); refreshNavigation();
        document.dispatchEvent(new CustomEvent('taxonomy:entity-context-changed', {detail: getContext()}));
        return getContext();
    }
    function installCommandMenus() {
        var navigation = node('applicationNavigation');
        if (!navigation) return;
        function menus() { return Array.from(node('applicationMenuGroups').querySelectorAll(':scope > details.application-menu')); }
        function items(menu) {
            return Array.from(menu.querySelectorAll('.application-menu-content a[href], .application-menu-content button:not(:disabled), .application-menu-content select'))
                .filter(function (item) { return !item.hidden && item.getClientRects().length && item.getAttribute('aria-disabled') !== 'true'; });
        }
        function close(menu, restore) {
            menu.open = false;
            if (restore && menu.contains(document.activeElement)) menu.querySelector('summary').focus();
        }
        document.addEventListener('click', function (event) {
            var action = event.target.closest?.('a[href], button:not(:disabled)');
            menus().forEach(function (menu) {
                if (menu.open && (!menu.contains(event.target) || (action && menu.contains(action)))) close(menu, true);
            });
        });
        document.addEventListener('keydown', function (event) {
            if (event.defaultPrevented || event.ctrlKey || event.metaKey || event.altKey) return;
            var menu = event.target.closest?.('#applicationMenuGroups > details.application-menu');
            if (!menu) return;
            if (event.key === 'Escape' && menu.open) { event.preventDefault(); close(menu, true); return; }
            if (event.target.matches('input, textarea, select')) return;
            var group = menus(), summary = menu.querySelector('summary');
            if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
                event.preventDefault();
                var wasOpen = menu.open, next = group[(group.indexOf(menu) + (event.key === 'ArrowRight' ? 1 : group.length - 1)) % group.length];
                close(menu, false); next.querySelector('summary').focus(); next.open = wasOpen;
            } else if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
                event.preventDefault(); menu.open = true;
                window.TaxonomyAppCommands?.refresh();
                var options = items(menu), index = options.indexOf(document.activeElement);
                var nextIndex = event.key === 'End' ? options.length - 1 : event.key === 'Home' ? 0
                    : event.key === 'ArrowUp' ? (index <= 0 ? options.length - 1 : index - 1)
                    : (index + 1) % options.length;
                options[nextIndex]?.focus();
            }
        });
    }
    function installResponsiveMenu() {
        var menu = node('applicationNavigationMenu');
        if (!menu || !window.matchMedia) return;
        var content = node('applicationMenuGroups');
        var compact = window.matchMedia('(max-width: 767.98px)');
        var summary = menu.querySelector('summary');
        var previousFocus = document.activeElement;
        function positionMenu() {
            if (!compact.matches || !content) return;
            var bottom = node('applicationNavigation').getBoundingClientRect().bottom;
            var top = Math.max(0, Math.min(window.innerHeight - 44, bottom));
            content.style.setProperty('--app-menu-top', top + 'px');
        }
        function updateMode() {
            var focused = document.activeElement;
            // Hiding the compact launcher can move native focus to the body before the media event.
            if (focused === document.body && previousFocus === summary) focused = summary;
            if (compact.matches && content?.contains(focused)) summary.focus();
            menu.open = !compact.matches;
            if (!compact.matches && focused === summary && content) {
                var destination = content.querySelector('details.application-menu > summary');
                destination?.focus();
            }
            positionMenu();
        }
        updateMode();
        if (compact.addEventListener) compact.addEventListener('change', updateMode);
        else if (compact.addListener) compact.addListener(updateMode);
        window.addEventListener('resize', positionMenu);
        document.addEventListener('scroll', positionMenu, true);
        document.addEventListener('focusin', function (event) {
            previousFocus = event.target;
            if (compact.matches && menu.open && !menu.contains(event.target) && !content.contains(event.target)) {
                menu.open = false;
            }
        });
        document.addEventListener('click', function (event) {
            if (!compact.matches || !menu.open) return;
            var route = event.target.closest?.('a[href], button:not(:disabled)');
            if (route && content.contains(route) && event.isTrusted && event.button === 0
                    && !event.ctrlKey && !event.metaKey && !event.shiftKey && !event.altKey) {
                window.setTimeout(function () {
                    menu.open = false;
                    if (content.contains(document.activeElement)) summary.focus();
                }, 0);
            } else if (!menu.contains(event.target) && !content.contains(event.target)) menu.open = false;
        });
        document.addEventListener('keydown', function (event) {
            if (event.defaultPrevented || !compact.matches || !menu.open || event.key !== 'Escape'
                    || (!menu.contains(event.target) && !content.contains(event.target))) return;
            event.preventDefault(); menu.open = false; summary.focus();
        });
    }
    document.addEventListener('click', function (event) {
        var link = event.target.closest?.('[data-app-locale]');
        refreshNavigation();
        if (link && event.button === 0 && !event.ctrlKey && !event.metaKey && !event.shiftKey && !event.altKey) {
            event.preventDefault(); window.TaxonomyI18n.setLocale(link.dataset.appLocale);
        }
    }, true);
    document.addEventListener('focusin', function (event) {
        refreshNavigation();
        var link = event.target.closest?.('#applicationWorkspaces a');
        var row = link?.closest('#applicationWorkspaces');
        if (row && row.scrollWidth > row.clientWidth) {
            // Native focus may leave a partially visible destination clipped.
            link.scrollIntoView({block: 'nearest', inline: 'nearest'});
        }
    });
    document.addEventListener('auxclick', refreshNavigation, true);
    window.addEventListener('popstate', refreshNavigation);
    window.addEventListener('hashchange', refreshNavigation);
    document.addEventListener('taxonomy-i18n-loaded', function () { renderContext(); refreshNavigation(); });
    window.TaxonomyAppChrome = Object.freeze({updateContext: updateContext, getContext: getContext, activateContext:activateContext,
        refreshNavigation: refreshNavigation, restoreFocus: restoreFocus, renderEntity: renderEntity, renderDetails: renderDetails,
        setStatus: setStatus, statusLabel: statusLabel});
    installCommandMenus();
    installResponsiveMenu();
    initializeContextDisclosure();
    refreshNavigation();
}());
