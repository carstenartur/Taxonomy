/*
 * taxonomy-portfolio.js — Project → requirements → analyses → solutions → products.
 *
 * The page intentionally consumes only the public REST contracts. No result is
 * inferred from combined requirement text: every analysis request targets one
 * stable requirement identity or an explicitly selected batch of identities.
 */
(function () {
    'use strict';

    const translations = {
        en: {
            'page.title': 'Project Requirement Portfolio',
            'nav.analysis': 'Analysis workspace',
            'projects.title': 'Projects',
            'projects.new': 'New project',
            'projects.filter': 'Filter projects',
            'projects.empty': 'No projects in this workspace.',
            'project.select.title': 'Select or create a project',
            'project.select.help': 'Requirements, analyses, solutions and products remain traceable inside one project.',
            'action.refresh': 'Refresh',
            'action.analyze.all': 'Analyze all',
            'action.propose.solutions': 'Propose solutions',
            'action.detect.conflicts': 'Detect conflicts',
            'action.cancel': 'Cancel',
            'action.create': 'Create',
            'action.analyze': 'Analyze',
            'action.snapshots': 'Snapshots',
            'action.confirm': 'Confirm',
            'action.reject': 'Reject',
            'action.resolve': 'Resolve',
            'action.save': 'Save',
            'action.add': 'Add',
            'action.select': 'Select',
            'action.shortlist': 'Shortlist',
            'action.compare.previous': 'Compare with previous',
            'tab.requirements': 'Requirements',
            'tab.taxonomy': 'Taxonomy',
            'tab.solutions': 'Solutions',
            'tab.products': 'Products',
            'tab.conflicts': 'Conflicts',
            'tab.snapshots': 'Snapshots',
            'requirements.title': 'Project requirements',
            'requirements.help': 'Every row has a stable identity, immutable text versions and its own analysis history.',
            'requirements.new': 'New requirement',
            'requirements.empty': 'No requirements have been recorded.',
            'taxonomy.title': 'Requirement–taxonomy coverage',
            'taxonomy.help': "Values come only from each requirement's current immutable snapshot.",
            'solutions.title': 'Project solutions',
            'solutions.help': 'One reusable solution can cover several requirements; decisions remain reviewable.',
            'solutions.new': 'New solution',
            'products.title': 'Sourced product catalogue',
            'products.help': 'Product claims always show their source and verification date.',
            'products.new': 'New product',
            'conflicts.title': 'Requirement conflict hypotheses',
            'conflicts.help': 'Detection is evidence-backed but never auto-confirmed.',
            'snapshots.title': 'Analysis snapshot history',
            'snapshots.help': 'Select a requirement in the Requirements tab to inspect and compare snapshots.',
            'snapshots.none': 'No snapshot selected.',
            'column.key': 'Key',
            'column.requirement': 'Requirement',
            'column.version': 'Version',
            'column.analysis': 'Analysis',
            'column.review': 'Review',
            'column.actions': 'Actions',
            'field.project.key': 'Project key',
            'field.requirement.key': 'Requirement key',
            'field.solution.key': 'Solution key',
            'field.product.key': 'Product key',
            'field.title': 'Title',
            'field.description': 'Description',
            'field.type': 'Type',
            'field.requirement.text': 'Requirement text',
            'field.operating.model': 'Operating model',
            'field.manufacturer': 'Manufacturer',
            'field.product.name': 'Product name',
            'field.version': 'Version',
            'field.verified.at': 'Verified at',
            'field.source': 'Source',
            'status.working': 'Working…',
            'status.loaded': 'Portfolio refreshed.',
            'status.project.created': 'Project created.',
            'status.requirement.created': 'Requirement created as an independent identity.',
            'status.analysis.complete': 'Analysis job finished: {success} successful, {partial} partial, {failed} failed.',
            'status.solutions.proposed': '{count} reusable solution candidate(s) proposed.',
            'status.conflicts.detected': '{count} conflict hypothesis/hypotheses available for review.',
            'status.solution.created': 'Solution created and added to the project.',
            'status.product.created': 'Sourced product entry created.',
            'status.saved': 'Decision saved.',
            'metric.requirements': 'Requirements',
            'metric.analyzed': 'Analyzed',
            'metric.uncovered': 'Without confirmed solution',
            'metric.solutions': 'Solutions',
            'metric.products': 'Selected products',
            'metric.conflicts': 'Open conflicts',
            'label.no.analysis': 'Not analyzed',
            'label.current.snapshot': 'Current snapshot',
            'label.requirements': 'Requirements',
            'label.action': 'Action',
            'label.status': 'Status',
            'label.coverage': 'Coverage',
            'label.taxonomy.coverage': 'Taxonomy coverage',
            'label.product.candidates': 'Product candidates',
            'label.verified': 'Verified',
            'label.source': 'Source',
            'label.confidence': 'Confidence',
            'label.evidence': 'Evidence',
            'label.provider': 'Provider',
            'label.taxonomy.fingerprint': 'Taxonomy fingerprint',
            'label.prompt.fingerprint': 'Prompt fingerprint',
            'label.commit': 'Commit',
            'label.duration': 'Duration',
            'label.mappings': 'Taxonomy mappings',
            'label.warnings': 'Warnings',
            'label.score': 'Score',
            'label.relevance': 'Relevance',
            'label.origin': 'Origin',
            'label.review': 'Review',
            'label.requirement.solution': 'Requirement coverage',
            'label.add.taxonomy.coverage': 'Add taxonomy coverage',
            'label.add.product': 'Add product candidate',
            'empty.taxonomy': 'No current taxonomy mappings are available.',
            'empty.solutions': 'No project solutions have been recorded.',
            'empty.products': 'No sourced products have been recorded.',
            'empty.conflicts': 'No conflict hypotheses have been recorded.',
            'empty.snapshots': 'This requirement has no analysis snapshots.',
            'confirm.resolve.note': 'Resolution note',
            'error.generic': 'The operation failed.',
            'error.select.project': 'Select a project first.',
            'error.select.requirement': 'Select a requirement first.',
            'error.no.products': 'Create a sourced product before adding product candidates.'
        },
        de: {
            'page.title': 'Projekt-, Anforderungs- und Lösungsportfolio',
            'nav.analysis': 'Analysearbeitsbereich',
            'projects.title': 'Projekte',
            'projects.new': 'Neues Projekt',
            'projects.filter': 'Projekte filtern',
            'projects.empty': 'In diesem Workspace gibt es noch keine Projekte.',
            'project.select.title': 'Projekt auswählen oder anlegen',
            'project.select.help': 'Anforderungen, Analysen, Lösungen und Produkte bleiben innerhalb eines Projekts nachvollziehbar.',
            'action.refresh': 'Aktualisieren',
            'action.analyze.all': 'Alle analysieren',
            'action.propose.solutions': 'Lösungen vorschlagen',
            'action.detect.conflicts': 'Konflikte erkennen',
            'action.cancel': 'Abbrechen',
            'action.create': 'Anlegen',
            'action.analyze': 'Analysieren',
            'action.snapshots': 'Snapshots',
            'action.confirm': 'Bestätigen',
            'action.reject': 'Verwerfen',
            'action.resolve': 'Lösen',
            'action.save': 'Speichern',
            'action.add': 'Hinzufügen',
            'action.select': 'Auswählen',
            'action.shortlist': 'Vorauswahl',
            'action.compare.previous': 'Mit Vorgänger vergleichen',
            'tab.requirements': 'Anforderungen',
            'tab.taxonomy': 'Taxonomie',
            'tab.solutions': 'Lösungen',
            'tab.products': 'Produkte',
            'tab.conflicts': 'Konflikte',
            'tab.snapshots': 'Snapshots',
            'requirements.title': 'Projektanforderungen',
            'requirements.help': 'Jede Zeile besitzt eine stabile Identität, unveränderliche Textversionen und eine eigene Analysehistorie.',
            'requirements.new': 'Neue Anforderung',
            'requirements.empty': 'Es wurden noch keine Anforderungen erfasst.',
            'taxonomy.title': 'Anforderungs–Taxonomie-Abdeckung',
            'taxonomy.help': 'Die Werte stammen ausschließlich aus dem jeweils aktuellen unveränderlichen Snapshot jeder Anforderung.',
            'solutions.title': 'Projektlösungen',
            'solutions.help': 'Eine wiederverwendbare Lösung kann mehrere Anforderungen erfüllen; Entscheidungen bleiben prüfbar.',
            'solutions.new': 'Neue Lösung',
            'products.title': 'Quellengebundener Produktkatalog',
            'products.help': 'Produktaussagen zeigen immer Quelle und Verifikationsdatum.',
            'products.new': 'Neues Produkt',
            'conflicts.title': 'Konflikthypothesen zwischen Anforderungen',
            'conflicts.help': 'Die Erkennung ist evidenzbasiert, wird aber niemals automatisch bestätigt.',
            'snapshots.title': 'Historie der Analyse-Snapshots',
            'snapshots.help': 'Im Reiter Anforderungen eine Anforderung auswählen, um Snapshots zu prüfen und zu vergleichen.',
            'snapshots.none': 'Kein Snapshot ausgewählt.',
            'column.key': 'Schlüssel',
            'column.requirement': 'Anforderung',
            'column.version': 'Version',
            'column.analysis': 'Analyse',
            'column.review': 'Prüfung',
            'column.actions': 'Aktionen',
            'field.project.key': 'Projektschlüssel',
            'field.requirement.key': 'Anforderungsschlüssel',
            'field.solution.key': 'Lösungsschlüssel',
            'field.product.key': 'Produktschlüssel',
            'field.title': 'Titel',
            'field.description': 'Beschreibung',
            'field.type': 'Typ',
            'field.requirement.text': 'Anforderungstext',
            'field.operating.model': 'Betriebsmodell',
            'field.manufacturer': 'Hersteller',
            'field.product.name': 'Produktname',
            'field.version': 'Version',
            'field.verified.at': 'Verifiziert am',
            'field.source': 'Quelle',
            'status.working': 'Verarbeitung läuft…',
            'status.loaded': 'Portfolio aktualisiert.',
            'status.project.created': 'Projekt wurde angelegt.',
            'status.requirement.created': 'Anforderung wurde als eigenständige Identität angelegt.',
            'status.analysis.complete': 'Analysejob abgeschlossen: {success} erfolgreich, {partial} teilweise, {failed} fehlgeschlagen.',
            'status.solutions.proposed': '{count} wiederverwendbare Lösungskandidat(en) vorgeschlagen.',
            'status.conflicts.detected': '{count} Konflikthypothese(n) stehen zur Prüfung bereit.',
            'status.solution.created': 'Lösung wurde angelegt und dem Projekt hinzugefügt.',
            'status.product.created': 'Quellengebundener Produkteintrag wurde angelegt.',
            'status.saved': 'Entscheidung wurde gespeichert.',
            'metric.requirements': 'Anforderungen',
            'metric.analyzed': 'Analysiert',
            'metric.uncovered': 'Ohne bestätigte Lösung',
            'metric.solutions': 'Lösungen',
            'metric.products': 'Ausgewählte Produkte',
            'metric.conflicts': 'Offene Konflikte',
            'label.no.analysis': 'Nicht analysiert',
            'label.current.snapshot': 'Aktueller Snapshot',
            'label.requirements': 'Anforderungen',
            'label.action': 'Maßnahme',
            'label.status': 'Status',
            'label.coverage': 'Abdeckung',
            'label.taxonomy.coverage': 'Taxonomieabdeckung',
            'label.product.candidates': 'Produktkandidaten',
            'label.verified': 'Verifiziert',
            'label.source': 'Quelle',
            'label.confidence': 'Konfidenz',
            'label.evidence': 'Evidenz',
            'label.provider': 'Provider',
            'label.taxonomy.fingerprint': 'Taxonomie-Fingerprint',
            'label.prompt.fingerprint': 'Prompt-Fingerprint',
            'label.commit': 'Commit',
            'label.duration': 'Laufzeit',
            'label.mappings': 'Taxonomiezuordnungen',
            'label.warnings': 'Warnungen',
            'label.score': 'Score',
            'label.relevance': 'Relevanz',
            'label.origin': 'Herkunft',
            'label.review': 'Prüfung',
            'label.requirement.solution': 'Anforderungsabdeckung',
            'label.add.taxonomy.coverage': 'Taxonomieabdeckung ergänzen',
            'label.add.product': 'Produktkandidat ergänzen',
            'empty.taxonomy': 'Es liegen keine aktuellen Taxonomiezuordnungen vor.',
            'empty.solutions': 'Es wurden noch keine Projektlösungen erfasst.',
            'empty.products': 'Es wurden noch keine quellengebundenen Produkte erfasst.',
            'empty.conflicts': 'Es wurden keine Konflikthypothesen erfasst.',
            'empty.snapshots': 'Diese Anforderung besitzt noch keine Analyse-Snapshots.',
            'confirm.resolve.note': 'Lösungsnotiz',
            'error.generic': 'Die Operation ist fehlgeschlagen.',
            'error.select.project': 'Bitte zuerst ein Projekt auswählen.',
            'error.select.requirement': 'Bitte zuerst eine Anforderung auswählen.',
            'error.no.products': 'Vor dem Hinzufügen von Produktkandidaten muss ein quellengebundenes Produkt angelegt werden.'
        }
    };

    const locale = resolveLocale();
    const state = {
        projects: [],
        selectedProjectId: null,
        portfolio: null,
        products: [],
        selectedRequirementId: null,
        snapshots: [],
        selectedSnapshotId: null,
        busyCount: 0
    };

    document.addEventListener('DOMContentLoaded', initialize);

    async function initialize() {
        translatePage();
        wireStaticEvents();
        setDefaultVerifiedAt();
        await loadProjects();
    }

    function resolveLocale() {
        const requested = new URLSearchParams(window.location.search).get('lang');
        const htmlLanguage = document.documentElement.lang || 'en';
        const candidate = (requested || htmlLanguage).toLowerCase();
        return candidate.startsWith('de') ? 'de' : 'en';
    }

    function t(key, replacements) {
        let value = (translations[locale] && translations[locale][key])
            || translations.en[key]
            || key;
        if (replacements) {
            Object.keys(replacements).forEach(function (name) {
                value = value.replaceAll('{' + name + '}', String(replacements[name]));
            });
        }
        return value;
    }

    function translatePage() {
        document.documentElement.lang = locale;
        document.querySelectorAll('[data-i18n]').forEach(function (element) {
            const translated = t(element.dataset.i18n);
            if (translated !== element.dataset.i18n) element.textContent = translated;
        });
    }

    function wireStaticEvents() {
        document.getElementById('projectFilter').addEventListener('input', renderProjectList);
        document.getElementById('projectList').addEventListener('click', onProjectListClick);
        document.getElementById('projectForm').addEventListener('submit', createProject);
        document.getElementById('requirementForm').addEventListener('submit', createRequirement);
        document.getElementById('solutionForm').addEventListener('submit', createSolution);
        document.getElementById('productForm').addEventListener('submit', createProduct);
        document.getElementById('refreshPortfolioBtn').addEventListener('click', refreshSelectedProject);
        document.getElementById('analyzeAllBtn').addEventListener('click', analyzeAllRequirements);
        document.getElementById('proposeSolutionsBtn').addEventListener('click', proposeSolutions);
        document.getElementById('detectConflictsBtn').addEventListener('click', detectConflicts);
        document.querySelector('#requirementsTable tbody').addEventListener('click', onRequirementAction);
        document.getElementById('solutionsList').addEventListener('click', onSolutionAction);
        document.getElementById('productsList').addEventListener('click', onProductAction);
        document.getElementById('conflictsList').addEventListener('click', onConflictAction);
        document.getElementById('snapshotList').addEventListener('click', onSnapshotAction);
        document.getElementById('snapshotDetail').addEventListener('click', onSnapshotDetailAction);
    }

    async function api(path, options) {
        const config = Object.assign({ method: 'GET', headers: {} }, options || {});
        const method = String(config.method || 'GET').toUpperCase();
        config.headers.Accept = 'application/json';
        if (config.body !== undefined && !(config.body instanceof FormData)) {
            config.headers['Content-Type'] = 'application/json';
            if (typeof config.body !== 'string') config.body = JSON.stringify(config.body);
        }
        if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
            const token = document.querySelector('meta[name="_csrf"]')?.content;
            const header = document.querySelector('meta[name="_csrf_header"]')?.content || 'X-CSRF-TOKEN';
            if (token) config.headers[header] = token;
        }

        const response = await fetch(path, config);
        if (response.status === 204) return null;
        const contentType = response.headers.get('content-type') || '';
        const payload = contentType.includes('json')
            ? await response.json().catch(function () { return null; })
            : await response.text().catch(function () { return ''; });
        if (!response.ok) {
            const detail = payload && typeof payload === 'object'
                ? (payload.detail || payload.message || payload.title)
                : payload;
            const error = new Error(detail || (t('error.generic') + ' HTTP ' + response.status));
            error.status = response.status;
            throw error;
        }
        return payload;
    }

    async function withBusy(operation) {
        state.busyCount += 1;
        updateBusyState();
        clearMessage('portfolioError');
        try {
            return await operation();
        } catch (error) {
            showError(error);
            throw error;
        } finally {
            state.busyCount = Math.max(0, state.busyCount - 1);
            updateBusyState();
        }
    }

    function updateBusyState() {
        document.getElementById('portfolioBusy').classList.toggle('d-none', state.busyCount === 0);
        document.querySelectorAll('button[type="submit"], #analyzeAllBtn, #proposeSolutionsBtn, #detectConflictsBtn')
            .forEach(function (button) { button.disabled = state.busyCount > 0; });
    }

    function showError(error) {
        const target = document.getElementById('portfolioError');
        target.textContent = error && error.message ? error.message : t('error.generic');
        target.classList.remove('d-none');
        document.getElementById('portfolioAlert').textContent = target.textContent;
        target.scrollIntoView({ block: 'nearest' });
    }

    function showInfo(message) {
        const target = document.getElementById('portfolioInfo');
        target.textContent = message;
        target.classList.remove('d-none');
        document.getElementById('portfolioStatus').textContent = message;
        window.setTimeout(function () { target.classList.add('d-none'); }, 6000);
    }

    function clearMessage(id) {
        const target = document.getElementById(id);
        target.classList.add('d-none');
        target.textContent = '';
    }

    async function loadProjects(preferredProjectId) {
        await withBusy(async function () {
            state.projects = await api('/api/projects');
            renderProjectList();
            const stored = Number(window.localStorage.getItem('taxonomy.portfolio.projectId')) || null;
            const candidate = preferredProjectId || stored;
            const selected = state.projects.find(function (project) { return project.id === candidate; })
                || state.projects[0]
                || null;
            if (selected) await selectProject(selected.id);
            else showNoProjectSelected();
        }).catch(function () {});
    }

    function renderProjectList() {
        const target = document.getElementById('projectList');
        const filter = document.getElementById('projectFilter').value.trim().toLowerCase();
        const projects = state.projects.filter(function (project) {
            return !filter
                || project.projectKey.toLowerCase().includes(filter)
                || project.title.toLowerCase().includes(filter);
        });
        target.textContent = '';
        projects.forEach(function (project) {
            const button = document.createElement('button');
            button.type = 'button';
            button.className = 'list-group-item list-group-item-action project-select'
                + (project.id === state.selectedProjectId ? ' active' : '');
            button.dataset.projectId = project.id;
            button.setAttribute('role', 'option');
            button.setAttribute('aria-selected', String(project.id === state.selectedProjectId));

            const header = document.createElement('div');
            header.className = 'd-flex justify-content-between align-items-start gap-2';
            const title = document.createElement('strong');
            title.textContent = project.projectKey;
            const status = document.createElement('span');
            status.className = 'badge ' + statusBadgeClass(project.status);
            status.textContent = humanize(project.status);
            header.append(title, status);

            const name = document.createElement('div');
            name.className = 'small mt-1';
            name.textContent = project.title;
            const metrics = document.createElement('div');
            metrics.className = 'small opacity-75 mt-1';
            metrics.textContent = project.requirementCount + ' ' + t('metric.requirements').toLowerCase()
                + ' · ' + project.solutionCount + ' ' + t('metric.solutions').toLowerCase();
            button.append(header, name, metrics);
            target.appendChild(button);
        });
        document.getElementById('projectListEmpty').classList.toggle('d-none', projects.length > 0);
    }

    function onProjectListClick(event) {
        const button = event.target.closest('.project-select');
        if (!button) return;
        withBusy(function () { return selectProject(Number(button.dataset.projectId)); }).catch(function () {});
    }

    async function selectProject(projectId) {
        state.selectedProjectId = projectId;
        state.selectedRequirementId = null;
        state.snapshots = [];
        state.selectedSnapshotId = null;
        window.localStorage.setItem('taxonomy.portfolio.projectId', String(projectId));
        renderProjectList();
        await refreshSelectedProject(false);
        document.getElementById('noProjectSelected').classList.add('d-none');
        document.getElementById('projectWorkspace').classList.remove('d-none');
    }

    function showNoProjectSelected() {
        state.selectedProjectId = null;
        state.portfolio = null;
        document.getElementById('projectWorkspace').classList.add('d-none');
        document.getElementById('noProjectSelected').classList.remove('d-none');
    }

    async function refreshSelectedProject(showSuccess) {
        requireProject();
        const projectId = state.selectedProjectId;
        const results = await Promise.all([
            api('/api/projects/' + projectId + '/portfolio'),
            api('/api/products')
        ]);
        state.portfolio = results[0];
        state.products = results[1] || [];
        renderPortfolio();
        state.projects = await api('/api/projects');
        renderProjectList();
        if (showSuccess !== false) showInfo(t('status.loaded'));
    }

    function renderPortfolio() {
        if (!state.portfolio) return;
        const project = state.portfolio.project;
        document.getElementById('selectedProjectKey').textContent = project.projectKey;
        document.getElementById('selectedProjectStatus').textContent = humanize(project.status);
        document.getElementById('selectedProjectStatus').className = 'badge ' + statusBadgeClass(project.status);
        document.getElementById('selectedProjectTitle').textContent = project.title;
        document.getElementById('selectedProjectDescription').textContent = project.description || '';
        renderMetrics(state.portfolio.metrics);
        renderRequirements(state.portfolio.requirements || []);
        renderTaxonomy(state.portfolio.taxonomyNodes || []);
        renderSolutions(state.portfolio.solutions || []);
        renderProducts(state.products || []);
        renderConflicts(state.portfolio.conflicts || []);
        renderMatrix('requirementTaxonomyMatrix', state.portfolio.requirementTaxonomyMatrix);
        renderMatrix('requirementSolutionMatrix', state.portfolio.requirementSolutionMatrix);
        renderMatrix('solutionProductMatrix', state.portfolio.solutionProductMatrix);
        if (state.selectedRequirementId) {
            const stillExists = (state.portfolio.requirements || []).some(function (requirement) {
                return requirement.id === state.selectedRequirementId;
            });
            if (!stillExists) resetSnapshots();
        }
    }

    function renderMetrics(metrics) {
        const definitions = [
            ['metric.requirements', metrics.totalRequirements, 'text-bg-primary'],
            ['metric.analyzed', metrics.analyzedRequirements, 'text-bg-info'],
            ['metric.uncovered', metrics.requirementsWithoutConfirmedSolution,
                metrics.requirementsWithoutConfirmedSolution > 0 ? 'text-bg-warning' : 'text-bg-success'],
            ['metric.solutions', metrics.totalSolutions, 'text-bg-secondary'],
            ['metric.products', metrics.selectedProducts, 'text-bg-success'],
            ['metric.conflicts', metrics.openConflicts,
                metrics.openConflicts > 0 ? 'text-bg-danger' : 'text-bg-success']
        ];
        const target = document.getElementById('portfolioMetrics');
        target.textContent = '';
        definitions.forEach(function (definition) {
            const column = document.createElement('div');
            const card = document.createElement('div');
            card.className = 'card portfolio-metric shadow-sm h-100';
            const body = document.createElement('div');
            body.className = 'card-body';
            const value = document.createElement('div');
            value.className = 'metric-value';
            const badge = document.createElement('span');
            badge.className = 'badge rounded-pill ' + definition[2];
            badge.textContent = definition[1];
            value.appendChild(badge);
            const label = document.createElement('div');
            label.className = 'metric-label mt-2';
            label.textContent = t(definition[0]);
            body.append(value, label);
            card.appendChild(body);
            column.appendChild(card);
            target.appendChild(column);
        });
    }

    function renderRequirements(requirements) {
        const body = document.querySelector('#requirementsTable tbody');
        body.textContent = '';
        requirements.forEach(function (requirement) {
            const row = document.createElement('tr');
            const version = requirement.currentVersion;
            const analyzed = Boolean(requirement.currentAnalysisSnapshotId);
            row.innerHTML = '<td><code>' + escapeHtml(requirement.requirementKey) + '</code></td>'
                + '<td><strong>' + escapeHtml(requirement.title) + '</strong>'
                + '<div class="small text-body-secondary portfolio-requirement-text">'
                + escapeHtml(truncate(version ? version.text : '', 220)) + '</div></td>'
                + '<td><span class="badge text-bg-light border">v'
                + escapeHtml(version ? version.versionNumber : '—') + '</span>'
                + (version && version.source && version.source.sectionReference
                    ? '<div class="small text-body-secondary mt-1">'
                        + escapeHtml(version.source.sectionReference) + '</div>' : '') + '</td>'
                + '<td>' + (analyzed
                    ? '<span class="portfolio-status-dot success"></span><span class="small">'
                        + escapeHtml(t('label.current.snapshot')) + '</span>'
                    : '<span class="portfolio-status-dot pending"></span><span class="small">'
                        + escapeHtml(t('label.no.analysis')) + '</span>') + '</td>'
                + '<td><span class="badge ' + reviewBadgeClass(requirement.reviewStatus) + '">'
                + escapeHtml(humanize(requirement.reviewStatus)) + '</span></td>'
                + '<td class="text-end"><div class="btn-group btn-group-sm" role="group">'
                + '<button type="button" class="btn btn-outline-primary requirement-analyze" data-requirement-id="'
                + requirement.id + '">' + escapeHtml(t('action.analyze')) + '</button>'
                + '<button type="button" class="btn btn-outline-secondary requirement-snapshots" data-requirement-id="'
                + requirement.id + '">' + escapeHtml(t('action.snapshots')) + '</button>'
                + (requirement.reviewStatus !== 'CONFIRMED'
                    ? '<button type="button" class="btn btn-outline-success requirement-confirm" data-requirement-id="'
                        + requirement.id + '">' + escapeHtml(t('action.confirm')) + '</button>' : '')
                + '</div></td>';
            body.appendChild(row);
        });
        document.getElementById('requirementsEmpty').classList.toggle('d-none', requirements.length > 0);
        document.getElementById('requirementsTable').classList.toggle('d-none', requirements.length === 0);
    }

    async function onRequirementAction(event) {
        const analyze = event.target.closest('.requirement-analyze');
        const snapshots = event.target.closest('.requirement-snapshots');
        const confirm = event.target.closest('.requirement-confirm');
        if (!analyze && !snapshots && !confirm) return;
        const requirementId = Number((analyze || snapshots || confirm).dataset.requirementId);
        if (analyze) {
            await withBusy(async function () {
                const job = await api('/api/projects/' + state.selectedProjectId
                    + '/requirements/' + requirementId + '/analyses', {
                    method: 'POST',
                    body: {
                        provider: null,
                        maxArchitectureNodes: 25,
                        idempotencyKey: 'ui:req:' + requirementId + ':' + Date.now()
                    }
                });
                showAnalysisResult(job);
                await refreshSelectedProject(false);
            }).catch(function () {});
        } else if (snapshots) {
            await withBusy(function () { return loadSnapshots(requirementId); }).catch(function () {});
        } else {
            await withBusy(async function () {
                await api('/api/projects/' + state.selectedProjectId + '/requirements/' + requirementId, {
                    method: 'PATCH',
                    body: { reviewStatus: 'CONFIRMED' }
                });
                showInfo(t('status.saved'));
                await refreshSelectedProject(false);
            }).catch(function () {});
        }
    }

    function renderTaxonomy(nodes) {
        const target = document.getElementById('taxonomySummary');
        target.textContent = '';
        nodes.forEach(function (node) {
            const column = document.createElement('div');
            const actions = Object.entries(node.actionStatusCounts || {})
                .filter(function (entry) { return entry[1] > 0; })
                .map(function (entry) { return humanize(entry[0]) + ': ' + entry[1]; })
                .join(' · ');
            column.innerHTML = '<div class="card portfolio-taxonomy-card h-100">'
                + '<div class="card-body">'
                + '<div class="d-flex justify-content-between gap-2">'
                + '<code class="taxonomy-code">' + escapeHtml(node.nodeCode) + '</code>'
                + '<span class="badge text-bg-primary">' + node.requirementCount + '</span></div>'
                + '<h4 class="h6 mt-2">' + escapeHtml(node.title || node.nodeCode) + '</h4>'
                + '<div class="small text-body-secondary">'
                + escapeHtml(node.requirementKeys.join(', ')) + '</div>'
                + '<div class="small mt-2">' + escapeHtml(t('label.score')) + ': '
                + node.maximumDirectScore + '% · ' + escapeHtml(t('label.relevance')) + ': '
                + Math.round(node.averageRelevance * 100) + '%</div>'
                + (actions ? '<div class="small mt-1">' + escapeHtml(actions) + '</div>' : '')
                + '</div></div>';
            target.appendChild(column);
        });
        if (nodes.length === 0) renderEmpty(target, t('empty.taxonomy'));
    }

    function renderSolutions(solutions) {
        const target = document.getElementById('solutionsList');
        target.textContent = '';
        solutions.forEach(function (projectSolution) {
            const solution = projectSolution.solution;
            const column = document.createElement('div');
            column.className = 'col-12 col-xxl-6';
            const requirementLinks = (projectSolution.requirements || []).map(function (link) {
                return '<li class="list-group-item d-flex justify-content-between align-items-center gap-2">'
                    + '<span><code>' + escapeHtml(link.requirementKey) + '</code> · '
                    + link.coveragePercent + '%</span>'
                    + (link.reviewStatus !== 'CONFIRMED'
                        ? '<button type="button" class="btn btn-sm btn-outline-success confirm-requirement-link"'
                            + ' data-project-solution-id="' + projectSolution.id + '"'
                            + ' data-requirement-id="' + link.requirementId + '"'
                            + ' data-snapshot-id="' + escapeAttribute(link.snapshotId || '') + '"'
                            + ' data-coverage="' + link.coveragePercent + '">'
                            + escapeHtml(t('action.confirm')) + '</button>'
                        : '<span class="badge text-bg-success">' + escapeHtml(t('action.confirm')) + '</span>')
                    + '</li>';
            }).join('');
            const candidates = (projectSolution.productCandidates || []).map(function (candidate) {
                return '<li class="list-group-item">'
                    + '<div class="d-flex justify-content-between gap-2"><span><strong>'
                    + escapeHtml(candidate.product.productKey) + '</strong> · '
                    + escapeHtml(candidate.product.productName) + '</span><span>'
                    + candidate.coveragePercent + '%</span></div>'
                    + '<div class="small text-body-secondary">'
                    + escapeHtml(humanize(candidate.reviewStatus)) + ' · '
                    + escapeHtml(humanize(candidate.selectionStatus)) + '</div>'
                    + '<div class="btn-group btn-group-sm mt-2" role="group">'
                    + (candidate.reviewStatus !== 'CONFIRMED'
                        ? '<button type="button" class="btn btn-outline-success product-candidate-review"'
                            + ' data-project-solution-id="' + projectSolution.id + '"'
                            + ' data-candidate-id="' + candidate.id + '" data-status="SHORTLISTED">'
                            + escapeHtml(t('action.shortlist')) + '</button>' : '')
                    + (candidate.selectionStatus !== 'SELECTED'
                        ? '<button type="button" class="btn btn-outline-primary product-candidate-review"'
                            + ' data-project-solution-id="' + projectSolution.id + '"'
                            + ' data-candidate-id="' + candidate.id + '" data-status="SELECTED">'
                            + escapeHtml(t('action.select')) + '</button>' : '')
                    + '</div></li>';
            }).join('');
            const productOptions = state.products.map(function (product) {
                return '<option value="' + product.id + '">' + escapeHtml(product.productKey + ' — '
                    + product.manufacturer + ' ' + product.productName) + '</option>';
            }).join('');
            column.innerHTML = '<article class="card portfolio-solution-card shadow-sm">'
                + '<div class="card-header d-flex justify-content-between gap-2">'
                + '<span><code>' + escapeHtml(solution.solutionKey) + '</code> · <strong>'
                + escapeHtml(solution.title) + '</strong></span>'
                + '<span class="badge ' + statusBadgeClass(projectSolution.status) + '">'
                + escapeHtml(humanize(projectSolution.status)) + '</span></div>'
                + '<div class="card-body">'
                + '<p>' + escapeHtml(solution.description || '') + '</p>'
                + '<dl class="portfolio-card-meta"><dt>' + escapeHtml(t('label.action')) + '</dt><dd>'
                + '<div class="input-group input-group-sm"><select class="form-select solution-action-select"'
                + ' data-project-solution-id="' + projectSolution.id + '">'
                + actionOptions(projectSolution.actionStatus) + '</select>'
                + '<button type="button" class="btn btn-outline-primary save-solution-action" data-project-solution-id="'
                + projectSolution.id + '">' + escapeHtml(t('action.save')) + '</button></div></dd>'
                + '<dt>' + escapeHtml(t('label.taxonomy.coverage')) + '</dt><dd>'
                + escapeHtml((solution.taxonomyCoverage || []).map(function (coverage) {
                    return coverage.nodeCode + ' ' + coverage.coveragePercent + '% ('
                        + humanize(coverage.reviewStatus) + ')';
                }).join(', ') || '—') + '</dd></dl>'
                + '<h5 class="h6 mt-3">' + escapeHtml(t('label.requirement.solution')) + '</h5>'
                + '<ul class="list-group list-group-flush border rounded">'
                + (requirementLinks || '<li class="list-group-item text-body-secondary">—</li>') + '</ul>'
                + '<details class="mt-3"><summary class="fw-semibold">'
                + escapeHtml(t('label.add.taxonomy.coverage')) + '</summary>'
                + '<div class="row g-2 mt-1"><div class="col-4"><input class="form-control form-control-sm solution-node-code"'
                + ' data-project-solution-id="' + projectSolution.id + '" placeholder="CP-…"></div>'
                + '<div class="col-3"><input type="number" min="0" max="100" value="80"'
                + ' class="form-control form-control-sm solution-node-coverage" data-project-solution-id="'
                + projectSolution.id + '"></div><div class="col-3"><select class="form-select form-select-sm solution-node-review"'
                + ' data-project-solution-id="' + projectSolution.id + '"><option value="PROPOSED">Proposed</option>'
                + '<option value="CONFIRMED">Confirmed</option></select></div>'
                + '<div class="col-2"><button type="button" class="btn btn-sm btn-outline-primary w-100 add-solution-coverage"'
                + ' data-project-solution-id="' + projectSolution.id + '" data-solution-id="' + solution.id + '">+</button></div></div>'
                + '</details>'
                + '<h5 class="h6 mt-3">' + escapeHtml(t('label.product.candidates')) + '</h5>'
                + '<ul class="list-group list-group-flush border rounded">'
                + (candidates || '<li class="list-group-item text-body-secondary">—</li>') + '</ul>'
                + '<div class="input-group input-group-sm mt-2">'
                + '<select class="form-select solution-product-select" data-project-solution-id="'
                + projectSolution.id + '"><option value="">—</option>' + productOptions + '</select>'
                + '<input type="number" min="0" max="100" value="80" class="form-control solution-product-coverage"'
                + ' data-project-solution-id="' + projectSolution.id + '" aria-label="Coverage percent">'
                + '<button type="button" class="btn btn-outline-primary add-product-candidate" data-project-solution-id="'
                + projectSolution.id + '">' + escapeHtml(t('action.add')) + '</button></div>'
                + '</div></article>';
            target.appendChild(column);
        });
        if (solutions.length === 0) renderEmpty(target, t('empty.solutions'));
    }

    async function onSolutionAction(event) {
        const saveAction = event.target.closest('.save-solution-action');
        const confirmLink = event.target.closest('.confirm-requirement-link');
        const addCoverage = event.target.closest('.add-solution-coverage');
        const addProduct = event.target.closest('.add-product-candidate');
        const candidateReview = event.target.closest('.product-candidate-review');
        if (!saveAction && !confirmLink && !addCoverage && !addProduct && !candidateReview) return;
        await withBusy(async function () {
            if (saveAction) {
                const id = Number(saveAction.dataset.projectSolutionId);
                const select = document.querySelector('.solution-action-select[data-project-solution-id="' + id + '"]');
                await api('/api/projects/' + state.selectedProjectId + '/solutions/' + id, {
                    method: 'PATCH', body: { actionStatus: select.value }
                });
            } else if (confirmLink) {
                const id = Number(confirmLink.dataset.projectSolutionId);
                await api('/api/projects/' + state.selectedProjectId + '/solutions/' + id + '/requirements', {
                    method: 'POST',
                    body: {
                        requirementId: Number(confirmLink.dataset.requirementId),
                        snapshotId: confirmLink.dataset.snapshotId || null,
                        coveragePercent: Number(confirmLink.dataset.coverage),
                        role: 'USES',
                        reviewStatus: 'CONFIRMED',
                        evidence: 'Confirmed in the project portfolio workspace'
                    }
                });
            } else if (addCoverage) {
                const projectSolutionId = Number(addCoverage.dataset.projectSolutionId);
                const solutionId = Number(addCoverage.dataset.solutionId);
                const node = document.querySelector('.solution-node-code[data-project-solution-id="' + projectSolutionId + '"]').value;
                const coverage = document.querySelector('.solution-node-coverage[data-project-solution-id="' + projectSolutionId + '"]').value;
                const review = document.querySelector('.solution-node-review[data-project-solution-id="' + projectSolutionId + '"]').value;
                await api('/api/solutions/' + solutionId + '/taxonomy-coverage', {
                    method: 'POST',
                    body: {
                        nodeCode: node,
                        coveragePercent: Number(coverage),
                        evidence: 'Recorded in the project portfolio workspace',
                        reviewStatus: review
                    }
                });
            } else if (addProduct) {
                const projectSolutionId = Number(addProduct.dataset.projectSolutionId);
                const productId = Number(document.querySelector(
                    '.solution-product-select[data-project-solution-id="' + projectSolutionId + '"]').value);
                if (!productId) throw new Error(t('error.no.products'));
                const coverage = Number(document.querySelector(
                    '.solution-product-coverage[data-project-solution-id="' + projectSolutionId + '"]').value);
                await api('/api/projects/' + state.selectedProjectId + '/solutions/'
                    + projectSolutionId + '/products', {
                    method: 'POST',
                    body: {
                        productId: productId,
                        coveragePercent: coverage,
                        hardExclusions: null,
                        strengths: null,
                        weaknesses: null,
                        openEvidence: 'Human review required',
                        confidence: Math.max(0, Math.min(1, coverage / 100)),
                        reviewStatus: 'PROPOSED',
                        selectionStatus: 'CANDIDATE'
                    }
                });
            } else if (candidateReview) {
                const projectSolutionId = Number(candidateReview.dataset.projectSolutionId);
                const candidate = findProductCandidate(projectSolutionId, Number(candidateReview.dataset.candidateId));
                if (!candidate) throw new Error(t('error.generic'));
                const selectionStatus = candidateReview.dataset.status;
                await api('/api/projects/' + state.selectedProjectId + '/solutions/'
                    + projectSolutionId + '/products', {
                    method: 'POST',
                    body: {
                        productId: candidate.product.id,
                        coveragePercent: candidate.coveragePercent,
                        hardExclusions: candidate.hardExclusions,
                        strengths: candidate.strengths,
                        weaknesses: candidate.weaknesses,
                        openEvidence: candidate.openEvidence,
                        confidence: candidate.confidence,
                        reviewStatus: 'CONFIRMED',
                        selectionStatus: selectionStatus
                    }
                });
            }
            showInfo(t('status.saved'));
            await refreshSelectedProject(false);
        }).catch(function () {});
    }

    function renderProducts(products) {
        const target = document.getElementById('productsList');
        target.textContent = '';
        products.forEach(function (product) {
            const column = document.createElement('div');
            column.className = 'col-12 col-xl-6';
            const coverage = (product.taxonomyCoverage || []).map(function (item) {
                return item.nodeCode + ' ' + item.coveragePercent + '% (' + humanize(item.reviewStatus) + ')';
            }).join(', ') || '—';
            column.innerHTML = '<article class="card portfolio-product-card shadow-sm">'
                + '<div class="card-header d-flex justify-content-between gap-2"><span><code>'
                + escapeHtml(product.productKey) + '</code> · <strong>'
                + escapeHtml(product.manufacturer + ' ' + product.productName) + '</strong></span>'
                + '<span class="badge ' + statusBadgeClass(product.productStatus) + '">'
                + escapeHtml(humanize(product.productStatus)) + '</span></div>'
                + '<div class="card-body"><dl class="portfolio-card-meta">'
                + '<dt>' + escapeHtml(t('field.version')) + '</dt><dd>' + escapeHtml(product.editionVersion || '—') + '</dd>'
                + '<dt>' + escapeHtml(t('field.operating.model')) + '</dt><dd>' + escapeHtml(humanize(product.operatingModel)) + '</dd>'
                + '<dt>' + escapeHtml(t('label.verified')) + '</dt><dd>' + escapeHtml(formatDate(product.verifiedAt)) + '</dd>'
                + '<dt>' + escapeHtml(t('label.source')) + '</dt><dd class="portfolio-evidence">'
                + escapeHtml(product.sourceReference) + '</dd>'
                + '<dt>' + escapeHtml(t('label.taxonomy.coverage')) + '</dt><dd>' + escapeHtml(coverage) + '</dd></dl>'
                + '<details class="mt-3"><summary class="fw-semibold">'
                + escapeHtml(t('label.add.taxonomy.coverage')) + '</summary>'
                + '<div class="row g-2 mt-1"><div class="col-4"><input class="form-control form-control-sm product-node-code"'
                + ' data-product-id="' + product.id + '" placeholder="CP-…"></div>'
                + '<div class="col-3"><input type="number" min="0" max="100" value="80"'
                + ' class="form-control form-control-sm product-node-coverage" data-product-id="' + product.id + '"></div>'
                + '<div class="col-3"><select class="form-select form-select-sm product-node-review" data-product-id="'
                + product.id + '"><option value="PROPOSED">Proposed</option><option value="CONFIRMED">Confirmed</option></select></div>'
                + '<div class="col-2"><button type="button" class="btn btn-sm btn-outline-primary w-100 add-product-coverage"'
                + ' data-product-id="' + product.id + '">+</button></div></div></details>'
                + '</div></article>';
            target.appendChild(column);
        });
        if (products.length === 0) renderEmpty(target, t('empty.products'));
    }

    async function onProductAction(event) {
        const addCoverage = event.target.closest('.add-product-coverage');
        if (!addCoverage) return;
        const productId = Number(addCoverage.dataset.productId);
        await withBusy(async function () {
            const node = document.querySelector('.product-node-code[data-product-id="' + productId + '"]').value;
            const coverage = Number(document.querySelector(
                '.product-node-coverage[data-product-id="' + productId + '"]').value);
            const review = document.querySelector(
                '.product-node-review[data-product-id="' + productId + '"]').value;
            await api('/api/products/' + productId + '/taxonomy-coverage', {
                method: 'POST',
                body: {
                    nodeCode: node,
                    coveragePercent: coverage,
                    evidence: 'Recorded in the project portfolio workspace',
                    reviewStatus: review
                }
            });
            showInfo(t('status.saved'));
            await refreshSelectedProject(false);
        }).catch(function () {});
    }

    function renderConflicts(conflicts) {
        const target = document.getElementById('conflictsList');
        target.textContent = '';
        conflicts.forEach(function (conflict) {
            const card = document.createElement('article');
            card.className = 'card portfolio-conflict-card';
            card.innerHTML = '<div class="card-body">'
                + '<div class="d-flex justify-content-between gap-2 flex-wrap"><div>'
                + '<span class="badge text-bg-warning me-2">' + escapeHtml(humanize(conflict.conflictType)) + '</span>'
                + '<strong>' + escapeHtml(conflict.title) + '</strong></div>'
                + '<span class="badge ' + statusBadgeClass(conflict.status) + '">'
                + escapeHtml(humanize(conflict.status)) + '</span></div>'
                + '<div class="mt-2"><code>' + escapeHtml(conflict.requirementAKey) + '</code> ↔ <code>'
                + escapeHtml(conflict.requirementBKey) + '</code></div>'
                + '<p class="small portfolio-evidence mt-2 mb-2">' + escapeHtml(conflict.evidence) + '</p>'
                + '<div class="small text-body-secondary">' + escapeHtml(t('label.confidence')) + ': '
                + Math.round(conflict.confidence * 100) + '%</div>'
                + (conflict.resolutionNote ? '<div class="alert alert-info py-2 mt-2 mb-0">'
                    + escapeHtml(conflict.resolutionNote) + '</div>' : '')
                + (conflict.status === 'PROPOSED'
                    ? '<div class="btn-group btn-group-sm mt-3" role="group">'
                        + '<button type="button" class="btn btn-outline-success conflict-review" data-conflict-id="'
                        + conflict.id + '" data-status="CONFIRMED">' + escapeHtml(t('action.confirm')) + '</button>'
                        + '<button type="button" class="btn btn-outline-danger conflict-review" data-conflict-id="'
                        + conflict.id + '" data-status="REJECTED">' + escapeHtml(t('action.reject')) + '</button></div>'
                    : (conflict.status === 'CONFIRMED'
                        ? '<button type="button" class="btn btn-sm btn-outline-primary conflict-review mt-3" data-conflict-id="'
                            + conflict.id + '" data-status="RESOLVED">' + escapeHtml(t('action.resolve')) + '</button>' : ''))
                + '</div>';
            target.appendChild(card);
        });
        if (conflicts.length === 0) renderEmpty(target, t('empty.conflicts'));
    }

    async function onConflictAction(event) {
        const button = event.target.closest('.conflict-review');
        if (!button) return;
        let note = null;
        if (button.dataset.status === 'RESOLVED') {
            note = window.prompt(t('confirm.resolve.note'), '');
            if (note === null) return;
        }
        await withBusy(async function () {
            await api('/api/projects/' + state.selectedProjectId + '/conflicts/' + button.dataset.conflictId, {
                method: 'PATCH',
                body: { status: button.dataset.status, resolutionNote: note }
            });
            showInfo(t('status.saved'));
            await refreshSelectedProject(false);
        }).catch(function () {});
    }

    function renderMatrix(targetId, matrix) {
        const target = document.getElementById(targetId);
        target.textContent = '';
        if (!matrix || !matrix.rows || matrix.rows.length === 0 || !matrix.columns || matrix.columns.length === 0) {
            const empty = document.createElement('p');
            empty.className = 'p-3 text-body-secondary mb-0';
            empty.textContent = '—';
            target.appendChild(empty);
            return;
        }
        const table = document.createElement('table');
        table.className = 'table table-sm table-bordered align-middle';
        const thead = document.createElement('thead');
        const header = document.createElement('tr');
        const corner = document.createElement('th');
        corner.scope = 'col';
        corner.textContent = '';
        header.appendChild(corner);
        matrix.columns.forEach(function (column) {
            const th = document.createElement('th');
            th.scope = 'col';
            th.textContent = column;
            th.title = column;
            header.appendChild(th);
        });
        thead.appendChild(header);
        const tbody = document.createElement('tbody');
        matrix.rows.forEach(function (rowName) {
            const row = document.createElement('tr');
            const th = document.createElement('th');
            th.scope = 'row';
            th.textContent = rowName;
            row.appendChild(th);
            const rowValues = matrix.values && matrix.values[rowName] ? matrix.values[rowName] : {};
            matrix.columns.forEach(function (column) {
                const value = Number(rowValues[column] || 0);
                const cell = document.createElement('td');
                cell.className = 'matrix-cell';
                cell.dataset.value = String(value);
                cell.textContent = value ? value + '%' : '·';
                if (value) cell.setAttribute('aria-label', rowName + ', ' + column + ': ' + value + '%');
                row.appendChild(cell);
            });
            tbody.appendChild(row);
        });
        table.append(thead, tbody);
        target.appendChild(table);
    }

    async function loadSnapshots(requirementId) {
        requireProject();
        state.selectedRequirementId = requirementId;
        state.snapshots = await api('/api/projects/' + state.selectedProjectId
            + '/requirements/' + requirementId + '/snapshots');
        state.selectedSnapshotId = null;
        renderSnapshotList();
        renderSnapshotPlaceholder();
        bootstrap.Tab.getOrCreateInstance(document.getElementById('snapshots-tab')).show();
        if (state.snapshots.length > 0) await loadSnapshotDetail(state.snapshots[0].id);
    }

    function renderSnapshotList() {
        const target = document.getElementById('snapshotList');
        target.textContent = '';
        state.snapshots.forEach(function (snapshot) {
            const button = document.createElement('button');
            button.type = 'button';
            button.className = 'list-group-item list-group-item-action snapshot-select'
                + (snapshot.id === state.selectedSnapshotId ? ' active' : '');
            button.dataset.snapshotId = snapshot.id;
            button.innerHTML = '<div class="d-flex justify-content-between gap-2"><strong>v'
                + snapshot.requirementVersionNumber + '</strong><span class="badge '
                + statusBadgeClass(snapshot.status) + '">' + escapeHtml(humanize(snapshot.status)) + '</span></div>'
                + '<div class="small mt-1">' + escapeHtml(formatDate(snapshot.createdAt)) + '</div>'
                + '<code class="small portfolio-snapshot-code">' + escapeHtml(snapshot.id.slice(0, 8)) + '</code>';
            target.appendChild(button);
        });
        if (state.snapshots.length === 0) renderEmpty(target, t('empty.snapshots'));
    }

    async function onSnapshotAction(event) {
        const button = event.target.closest('.snapshot-select');
        if (!button) return;
        await withBusy(function () { return loadSnapshotDetail(button.dataset.snapshotId); }).catch(function () {});
    }

    async function loadSnapshotDetail(snapshotId) {
        state.selectedSnapshotId = snapshotId;
        renderSnapshotList();
        const detail = await api('/api/projects/' + state.selectedProjectId + '/snapshots/' + snapshotId);
        renderSnapshotDetail(detail);
    }

    function renderSnapshotDetail(detail) {
        const target = document.getElementById('snapshotDetail');
        const summary = detail.summary;
        const warnings = detail.analysis && detail.analysis.warnings ? detail.analysis.warnings : [];
        const mappings = detail.elementMappings || [];
        const mappingRows = mappings.map(function (mapping) {
            return '<tr><td><code>' + escapeHtml(mapping.nodeCode) + '</code><div class="small text-body-secondary">'
                + escapeHtml(mapping.nodeTitle || '') + '</div></td><td>' + mapping.directScore + '%</td><td>'
                + Math.round(mapping.relevance * 100) + '%</td><td>' + escapeHtml(humanize(mapping.mappingOrigin)) + '</td>'
                + '<td><div class="input-group input-group-sm"><select class="form-select mapping-action-select" data-mapping-id="'
                + mapping.id + '">' + actionOptions(mapping.actionStatus) + '</select>'
                + '<button type="button" class="btn btn-outline-success mapping-review" data-mapping-id="'
                + mapping.id + '">' + escapeHtml(t('action.confirm')) + '</button></div></td></tr>';
        }).join('');
        const currentIndex = state.snapshots.findIndex(function (snapshot) { return snapshot.id === summary.id; });
        const previous = currentIndex >= 0 ? state.snapshots[currentIndex + 1] : null;
        target.className = 'border rounded p-3 bg-body portfolio-snapshot-detail';
        target.innerHTML = '<div class="d-flex justify-content-between align-items-start gap-2 flex-wrap">'
            + '<div><h4 class="h5 mb-1">' + escapeHtml(summary.requirementKey) + ' · v'
            + summary.requirementVersionNumber + '</h4><code>' + escapeHtml(summary.id) + '</code></div>'
            + '<span class="badge ' + statusBadgeClass(summary.status) + '">' + escapeHtml(humanize(summary.status)) + '</span></div>'
            + '<dl class="portfolio-card-meta mt-3">'
            + '<dt>' + escapeHtml(t('label.provider')) + '</dt><dd>' + escapeHtml(summary.provider || '—') + '</dd>'
            + '<dt>' + escapeHtml(t('label.commit')) + '</dt><dd><code>' + escapeHtml(summary.commitSha || '—') + '</code></dd>'
            + '<dt>' + escapeHtml(t('label.duration')) + '</dt><dd>' + summary.durationMs + ' ms</dd>'
            + '<dt>' + escapeHtml(t('label.taxonomy.fingerprint')) + '</dt><dd><code>'
            + escapeHtml(summary.taxonomyFingerprint || '—') + '</code></dd>'
            + '<dt>' + escapeHtml(t('label.prompt.fingerprint')) + '</dt><dd><code>'
            + escapeHtml(summary.promptFingerprint || '—') + '</code></dd></dl>'
            + (previous ? '<button type="button" class="btn btn-sm btn-outline-secondary snapshot-diff mt-2"'
                + ' data-older-id="' + escapeAttribute(previous.id) + '" data-newer-id="' + escapeAttribute(summary.id) + '">'
                + escapeHtml(t('action.compare.previous')) + '</button>' : '')
            + (warnings.length ? '<div class="alert alert-warning mt-3"><strong>' + escapeHtml(t('label.warnings'))
                + '</strong><ul class="mb-0">' + warnings.map(function (warning) {
                    return '<li>' + escapeHtml(warning) + '</li>';
                }).join('') + '</ul></div>' : '')
            + '<h5 class="h6 mt-4">' + escapeHtml(t('label.mappings')) + '</h5>'
            + '<div class="table-responsive mapping-list"><table class="table table-sm align-middle"><thead><tr>'
            + '<th>Node</th><th>' + escapeHtml(t('label.score')) + '</th><th>' + escapeHtml(t('label.relevance'))
            + '</th><th>' + escapeHtml(t('label.origin')) + '</th><th>' + escapeHtml(t('label.action'))
            + '</th></tr></thead><tbody>' + mappingRows + '</tbody></table></div>'
            + '<div id="snapshotDiffResult" class="mt-3"></div>';
    }

    async function onSnapshotDetailAction(event) {
        const review = event.target.closest('.mapping-review');
        const diff = event.target.closest('.snapshot-diff');
        if (!review && !diff) return;
        await withBusy(async function () {
            if (review) {
                const mappingId = Number(review.dataset.mappingId);
                const action = document.querySelector('.mapping-action-select[data-mapping-id="' + mappingId + '"]').value;
                await api('/api/projects/' + state.selectedProjectId
                    + '/analysis-mappings/elements/' + mappingId, {
                    method: 'PATCH',
                    body: {
                        reviewStatus: 'CONFIRMED',
                        actionStatus: action,
                        actionEvidence: 'Reviewed in the project portfolio workspace',
                        comment: 'Human review completed'
                    }
                });
                showInfo(t('status.saved'));
                await loadSnapshotDetail(state.selectedSnapshotId);
                await refreshSelectedProject(false);
            } else {
                const result = await api('/api/projects/' + state.selectedProjectId + '/snapshots/diff?older='
                    + encodeURIComponent(diff.dataset.olderId) + '&newer=' + encodeURIComponent(diff.dataset.newerId));
                renderSnapshotDiff(result);
            }
        }).catch(function () {});
    }

    function renderSnapshotDiff(diff) {
        const target = document.getElementById('snapshotDiffResult');
        const scoreEntries = Object.entries(diff.scoreChanges || {});
        target.innerHTML = '<div class="card"><div class="card-header fw-semibold">Snapshot diff</div><div class="card-body">'
            + '<p class="small"><code>' + escapeHtml(diff.olderSnapshotId.slice(0, 8)) + '</code> → <code>'
            + escapeHtml(diff.newerSnapshotId.slice(0, 8)) + '</code></p>'
            + '<div class="row g-3"><div class="col-md-4"><strong>Added elements</strong><div class="small">'
            + escapeHtml((diff.addedElements || []).join(', ') || '—') + '</div></div>'
            + '<div class="col-md-4"><strong>Removed elements</strong><div class="small">'
            + escapeHtml((diff.removedElements || []).join(', ') || '—') + '</div></div>'
            + '<div class="col-md-4"><strong>Score changes</strong><div class="small">'
            + escapeHtml(scoreEntries.map(function (entry) {
                return entry[0] + ': ' + String(entry[1].oldScore ?? '—') + ' → ' + String(entry[1].newScore ?? '—');
            }).join(', ') || '—') + '</div></div></div>'
            + '<div class="small text-body-secondary mt-3">Taxonomy changed: '
            + diff.taxonomyFingerprintChanged + ' · Prompts changed: ' + diff.promptFingerprintChanged
            + ' · Provider changed: ' + diff.providerChanged + '</div></div></div>';
    }

    function renderSnapshotPlaceholder() {
        const target = document.getElementById('snapshotDetail');
        target.className = 'border rounded p-3 bg-body';
        target.innerHTML = '<p class="text-body-secondary mb-0">' + escapeHtml(t('snapshots.none')) + '</p>';
    }

    function resetSnapshots() {
        state.selectedRequirementId = null;
        state.snapshots = [];
        state.selectedSnapshotId = null;
        renderSnapshotList();
        renderSnapshotPlaceholder();
    }

    async function createProject(event) {
        event.preventDefault();
        const form = event.currentTarget;
        await withBusy(async function () {
            const data = formData(form);
            const project = await api('/api/projects', {
                method: 'POST',
                body: {
                    projectKey: data.projectKey,
                    title: data.title,
                    description: data.description || null,
                    status: 'ACTIVE'
                }
            });
            hideModal('projectModal');
            form.reset();
            showInfo(t('status.project.created'));
            await loadProjects(project.id);
        }).catch(function () {});
    }

    async function createRequirement(event) {
        event.preventDefault();
        requireProject();
        const form = event.currentTarget;
        await withBusy(async function () {
            const data = formData(form);
            await api('/api/projects/' + state.selectedProjectId + '/requirements', {
                method: 'POST',
                body: {
                    requirementKey: data.requirementKey,
                    title: data.title,
                    text: data.text,
                    status: 'DRAFT',
                    priority: 50,
                    criticality: 'MEDIUM',
                    requirementType: data.requirementType,
                    reviewStatus: 'PROPOSED',
                    changeReason: 'Initial version created in the portfolio workspace'
                }
            });
            hideModal('requirementModal');
            form.reset();
            showInfo(t('status.requirement.created'));
            await refreshSelectedProject(false);
        }).catch(function () {});
    }

    async function createSolution(event) {
        event.preventDefault();
        requireProject();
        const form = event.currentTarget;
        await withBusy(async function () {
            const data = formData(form);
            const solution = await api('/api/solutions', {
                method: 'POST',
                body: {
                    solutionKey: data.solutionKey,
                    title: data.title,
                    description: data.description || null,
                    solutionType: data.solutionType,
                    operatingModel: data.operatingModel,
                    lifecycleStatus: 'PLANNED',
                    maturityLevel: 0,
                    extensionAttributes: {}
                }
            });
            await api('/api/projects/' + state.selectedProjectId + '/solutions', {
                method: 'POST',
                body: {
                    solutionId: solution.id,
                    status: 'PROPOSED',
                    actionStatus: 'UNDECIDED',
                    priority: 50,
                    rationale: 'Added in the project portfolio workspace'
                }
            });
            hideModal('solutionModal');
            form.reset();
            showInfo(t('status.solution.created'));
            await refreshSelectedProject(false);
        }).catch(function () {});
    }

    async function createProduct(event) {
        event.preventDefault();
        const form = event.currentTarget;
        await withBusy(async function () {
            const data = formData(form);
            await api('/api/products', {
                method: 'POST',
                body: {
                    productKey: data.productKey,
                    manufacturer: data.manufacturer,
                    productName: data.productName,
                    editionVersion: data.editionVersion || null,
                    productStatus: 'CANDIDATE',
                    operatingModel: data.operatingModel,
                    sourceReference: data.sourceReference,
                    verifiedAt: new Date(data.verifiedAt).toISOString()
                }
            });
            hideModal('productModal');
            form.reset();
            setDefaultVerifiedAt();
            showInfo(t('status.product.created'));
            if (state.selectedProjectId) await refreshSelectedProject(false);
        }).catch(function () {});
    }

    async function analyzeAllRequirements() {
        requireProject();
        await withBusy(async function () {
            const job = await api('/api/projects/' + state.selectedProjectId + '/analyses', {
                method: 'POST',
                body: {
                    requirementIds: [],
                    all: true,
                    maxArchitectureNodes: 25,
                    idempotencyKey: 'ui:all:' + state.selectedProjectId + ':' + Date.now()
                }
            });
            showAnalysisResult(job);
            await refreshSelectedProject(false);
        }).catch(function () {});
    }

    function showAnalysisResult(job) {
        showInfo(t('status.analysis.complete', {
            success: job.successfulItems,
            partial: job.partialItems,
            failed: job.failedItems
        }));
    }

    async function proposeSolutions() {
        requireProject();
        await withBusy(async function () {
            const result = await api('/api/projects/' + state.selectedProjectId
                + '/solutions/propose-from-taxonomy', { method: 'POST' });
            showInfo(t('status.solutions.proposed', { count: (result || []).length }));
            await refreshSelectedProject(false);
        }).catch(function () {});
    }

    async function detectConflicts() {
        requireProject();
        await withBusy(async function () {
            const result = await api('/api/projects/' + state.selectedProjectId
                + '/conflicts/detect', { method: 'POST' });
            showInfo(t('status.conflicts.detected', { count: (result || []).length }));
            await refreshSelectedProject(false);
            bootstrap.Tab.getOrCreateInstance(document.getElementById('conflicts-tab')).show();
        }).catch(function () {});
    }

    function findProductCandidate(projectSolutionId, candidateId) {
        const solution = (state.portfolio.solutions || []).find(function (item) {
            return item.id === projectSolutionId;
        });
        return solution && (solution.productCandidates || []).find(function (candidate) {
            return candidate.id === candidateId;
        });
    }

    function actionOptions(selected) {
        const values = [
            'UNDECIDED', 'SATISFIED_AS_IS', 'REUSE', 'CHANGE', 'CREATE',
            'PROCURE', 'ORGANIZATIONAL', 'RETIRE_OR_REPLACE'
        ];
        return values.map(function (value) {
            return '<option value="' + value + '"' + (value === selected ? ' selected' : '') + '>'
                + escapeHtml(humanize(value)) + '</option>';
        }).join('');
    }

    function statusBadgeClass(status) {
        const normalized = String(status || '').toUpperCase();
        if (['SUCCESS', 'ACTIVE', 'SELECTED', 'IMPLEMENTED', 'CONFIRMED', 'COMPLETED'].includes(normalized)) {
            return 'text-bg-success';
        }
        if (['PARTIAL', 'PROPOSED', 'PLANNING', 'DRAFT', 'EVALUATED', 'SHORTLISTED', 'CANDIDATE'].includes(normalized)) {
            return 'text-bg-warning';
        }
        if (['FAILED', 'REJECTED', 'WITHDRAWN', 'END_OF_SUPPORT'].includes(normalized)) {
            return 'text-bg-danger';
        }
        return 'text-bg-secondary';
    }

    function reviewBadgeClass(status) {
        if (status === 'CONFIRMED') return 'text-bg-success';
        if (status === 'REJECTED') return 'text-bg-danger';
        return 'text-bg-warning';
    }

    function humanize(value) {
        if (value === null || value === undefined || value === '') return '—';
        return String(value).toLowerCase().replaceAll('_', ' ')
            .replace(/(^|\s)\S/g, function (letter) { return letter.toUpperCase(); });
    }

    function formatDate(value) {
        if (!value) return '—';
        const date = new Date(value);
        if (Number.isNaN(date.getTime())) return String(value);
        return new Intl.DateTimeFormat(locale === 'de' ? 'de-DE' : 'en-GB', {
            dateStyle: 'medium', timeStyle: 'short'
        }).format(date);
    }

    function formData(form) {
        return Object.fromEntries(new FormData(form).entries());
    }

    function hideModal(id) {
        bootstrap.Modal.getOrCreateInstance(document.getElementById(id)).hide();
    }

    function requireProject() {
        if (!state.selectedProjectId) throw new Error(t('error.select.project'));
    }

    function renderEmpty(target, message) {
        const empty = document.createElement('div');
        empty.className = 'portfolio-empty col-12';
        const icon = document.createElement('span');
        icon.setAttribute('aria-hidden', 'true');
        icon.textContent = '○';
        const text = document.createElement('p');
        text.className = 'mb-0';
        text.textContent = message;
        empty.append(icon, text);
        target.appendChild(empty);
    }

    function truncate(value, maximum) {
        const text = value || '';
        return text.length <= maximum ? text : text.slice(0, maximum - 1) + '…';
    }

    function escapeHtml(value) {
        return String(value === null || value === undefined ? '' : value)
            .replaceAll('&', '&amp;')
            .replaceAll('<', '&lt;')
            .replaceAll('>', '&gt;')
            .replaceAll('"', '&quot;')
            .replaceAll("'", '&#039;');
    }

    function escapeAttribute(value) {
        return escapeHtml(value).replaceAll('`', '&#096;');
    }

    function setDefaultVerifiedAt() {
        const input = document.getElementById('productVerifiedAt');
        if (!input || input.value) return;
        const now = new Date();
        now.setMinutes(now.getMinutes() - now.getTimezoneOffset());
        input.value = now.toISOString().slice(0, 16);
    }
})();
