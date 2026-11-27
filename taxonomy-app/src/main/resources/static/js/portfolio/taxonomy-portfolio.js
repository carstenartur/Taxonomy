/*
 * taxonomy-portfolio.js — Project → requirements → analyses → solutions → products.
 *
 * The page intentionally consumes only the public REST contracts. No result is
 * inferred from combined requirement text: every analysis request targets one
 * stable requirement identity or an explicitly selected batch of identities.
 */
(function () {
    'use strict';

    const applicationUrl = window.TaxonomyI18n?.resolveUrl || (path => path);
    const translations = {
        en: {
            'page.title': 'Project Requirement Portfolio',
            'nav.analysis': 'Analysis workspace',
            'nav.project.tools': 'Project tools',
            'nav.project.import': 'Import requirements',
            'nav.project.matrices': 'Matrices',
            'nav.project.reports': 'Reports',
            'nav.project.versioning': 'Versioning',
            'requirements.details': 'Open details for {key}: {title}',
            'projects.title': 'Projects',
            'projects.new': 'New project',
            'projects.filter': 'Filter projects',
            'projects.empty': 'No projects in this workspace.',
            'projects.requirements.one': '{count} requirement',
            'projects.requirements.other': '{count} requirements',
            'projects.solutions.one': '{count} solution',
            'projects.solutions.other': '{count} solutions',
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
            'conflicts.rule.weight': 'Rule weight',
            'conflicts.rule.help': 'Fixed priority assigned to this detection rule; not a measured probability. Review the evidence before confirming or rejecting the conflict.',
            'conflicts.rule.unknown': 'The score basis or a valid weight is unavailable. Review the evidence to decide whether the requirements conflict.',
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
            'error.analyze.empty': 'Add a requirement before analyzing this project.',
            'error.coverage.percent': 'Enter a whole coverage percentage from 0 to 100.',
            'error.coverage.node': 'Enter a taxonomy node code.',
            'error.select.product': 'Select a sourced product to add as a candidate.',
            'error.no.products': 'Create a sourced product before adding product candidates.',
            'diff.title': 'Snapshot comparison',
            'diff.added': 'Added elements',
            'diff.removed': 'Removed elements',
            'diff.scores': 'Score changes',
            'diff.taxonomy': 'Taxonomy changed',
            'diff.prompts': 'Prompts changed',
            'diff.provider': 'Provider changed',
            'diff.no.previous': 'There is no earlier snapshot to compare with.',
            'boolean.yes': 'Yes',
            'boolean.no': 'No'
        },
        de: {
            'page.title': 'Projekt-, Anforderungs- und Lösungsportfolio',
            'nav.analysis': 'Analysearbeitsbereich',
            'nav.project.tools': 'Projektwerkzeuge',
            'nav.project.import': 'Anforderungen importieren',
            'nav.project.matrices': 'Matrizen',
            'nav.project.reports': 'Berichte',
            'nav.project.versioning': 'Versionierung',
            'requirements.details': 'Details zu {key}: {title} öffnen',
            'projects.title': 'Projekte',
            'projects.new': 'Neues Projekt',
            'projects.filter': 'Projekte filtern',
            'projects.empty': 'In diesem Workspace gibt es noch keine Projekte.',
            'projects.requirements.one': '{count} Anforderung',
            'projects.requirements.other': '{count} Anforderungen',
            'projects.solutions.one': '{count} Lösung',
            'projects.solutions.other': '{count} Lösungen',
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
            'conflicts.rule.weight': 'Regelgewicht',
            'conflicts.rule.help': 'Feste Priorität dieser Erkennungsregel; keine gemessene Wahrscheinlichkeit. Prüfen Sie die Belege, bevor Sie den Konflikt bestätigen oder verwerfen.',
            'conflicts.rule.unknown': 'Die Bewertungsgrundlage oder ein gültiges Regelgewicht fehlt. Prüfen Sie anhand der Belege, ob sich die Anforderungen widersprechen.',
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
            'error.analyze.empty': 'Fügen Sie eine Anforderung hinzu, bevor Sie dieses Projekt analysieren.',
            'error.coverage.percent': 'Geben Sie einen ganzzahligen Abdeckungsgrad von 0 bis 100 Prozent ein.',
            'error.coverage.node': 'Geben Sie einen Taxonomieknotencode ein.',
            'error.select.product': 'Wählen Sie ein quellengebundenes Produkt als Kandidaten aus.',
            'error.no.products': 'Vor dem Hinzufügen von Produktkandidaten muss ein quellengebundenes Produkt angelegt werden.',
            'diff.title': 'Snapshot-Vergleich',
            'diff.added': 'Hinzugefügte Elemente',
            'diff.removed': 'Entfernte Elemente',
            'diff.scores': 'Score-Änderungen',
            'diff.taxonomy': 'Taxonomie geändert',
            'diff.prompts': 'Prompts geändert',
            'diff.provider': 'Anbieter geändert',
            'diff.no.previous': 'Es ist kein früherer Snapshot für einen Vergleich vorhanden.',
            'boolean.yes': 'Ja',
            'boolean.no': 'Nein'
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
    let initialSourceReady = null;
    let sourceInitialized = false;

    document.addEventListener('DOMContentLoaded', initialize);

    async function initialize() {
        translatePage();
        wireStaticEvents();
        setDefaultVerifiedAt();
        return initializeSource();
    }

    function initializeSource(options) {
        if (initialSourceReady) return initialSourceReady;
        // Lifecycle hydration can intentionally publish a new source. Every network
        // read retains its own selection fence while that publication is in progress.
        const activePage = window.TaxonomyApiClient.captureContext({contextSensitive: false});
        const sourceUrl = function () { return window.location.pathname + window.location.search; };
        const selectedUrl = sourceUrl();
        let departed = false;
        const scope = window.TaxonomyArchitectureWorkflow?.getScope?.();
        const workspace = window.TaxonomyWorkspaceApi;
        const lifecycle = window.TaxonomyArchitectureLifecycle;
        const needsWorkspaceConfirmation = workspace
            && scope?.workspaceId === undefined && scope?.workspaceScopeKey === undefined;
        const retry = document.getElementById('portfolioSourceRetry');
        const observeSelection = function () { departed ||= sourceUrl() !== selectedUrl; };
        window.addEventListener('popstate', observeSelection);
        const stopObservingStand = lifecycle?.onStandChange?.(observeSelection);
        const isSelected = function () {
            observeSelection();
            if (activePage() && !departed) return true;
            if (activePage() && retry) retry.hidden = false;
            return false;
        };
        if (retry) retry.hidden = true;
        state.busyCount += 1;
        updateBusyState();
        initialSourceReady = Promise.resolve(options?.refresh && !needsWorkspaceConfirmation
            ? lifecycle?.refresh?.(scope) : lifecycle?.ready?.()).then(async function (overview) {
            if (!isSelected()) return false;
            if (needsWorkspaceConfirmation) {
                const currentSource = window.TaxonomyApiClient.captureContext();
                const confirmed = await workspace.waitForCurrent(workspace.current);
                if (!currentSource()) {
                    if (activePage() && retry) retry.hidden = false;
                    return false;
                }
                const selected = confirmed?.workspaceId ? {...scope, workspaceId: confirmed.workspaceId} : scope;
                if (lifecycle) overview = await lifecycle.refresh(selected);
            }
            if (!isSelected()) return false;
            if (lifecycle && !overview) {
                throw new Error(window.TaxonomyI18n.t('lifecycle.intake.contextUnavailable'));
            }
            await loadProjects(undefined, {sourceBootstrap: true});
            if (!isSelected()) return false;
            sourceInitialized = true;
            return true;
        }).catch(function (error) {
            if (activePage() && error.code !== 'ABORTED') showError(error);
            if (activePage() && retry) retry.hidden = false;
            return false;
        }).finally(function () {
            window.removeEventListener('popstate', observeSelection);
            stopObservingStand?.();
            initialSourceReady = null;
            state.busyCount = Math.max(0, state.busyCount - 1);
            updateBusyState();
        });
        return initialSourceReady;
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
        document.getElementById('portfolioSourceRetry')?.addEventListener('click', function () {
            return initializeSource({refresh: true});
        });
        document.getElementById('requirementForm').addEventListener('submit', createRequirement);
        window.TaxonomyRequirementIntake?.attach(document.getElementById('requirementForm'), {textElement:document.querySelector('#requirementForm [name="text"]'), projectId:function () { return state.selectedProjectId; }});
        document.getElementById('solutionForm').addEventListener('submit', createSolution);
        document.getElementById('productForm').addEventListener('submit', createProduct);
        document.getElementById('refreshPortfolioBtn').addEventListener('click', refreshSelectedProject);
        document.getElementById('analyzeAllBtn').addEventListener('click', analyzeAllRequirements);
        document.getElementById('proposeSolutionsBtn').addEventListener('click', proposeSolutions);
        document.getElementById('detectConflictsBtn').addEventListener('click', detectConflicts);
        document.querySelector('#requirementsTable tbody').addEventListener('click', onRequirementAction);
        document.getElementById('solutionsList').addEventListener('click', onSolutionAction);
        document.getElementById('solutionsList').addEventListener('submit', onPortfolioFormSubmit);
        document.getElementById('productsList').addEventListener('submit', onPortfolioFormSubmit);
        document.addEventListener('taxonomy:portfolio-mutation-access-changed', updateBusyState);
        document.getElementById('conflictsList').addEventListener('click', onConflictAction);
        document.getElementById('snapshotList').addEventListener('click', onSnapshotAction);
        document.getElementById('snapshotDetail').addEventListener('click', onSnapshotDetailAction);
    }

    async function withBusy(operation, options) {
        let context = window.TaxonomyApiClient.captureContext();
        let projectId = state.selectedProjectId;
        const isCurrent = () => context() && state.selectedProjectId === projectId;
        const preparingSource = !sourceInitialized && !options?.sourceBootstrap && initialSourceReady;
        const activePage = preparingSource && window.TaxonomyApiClient.captureContext({contextSensitive:false});
        state.busyCount += 1;
        updateBusyState();
        clearMessage('portfolioError');
        try {
            if (preparingSource) {
                if (!await preparingSource || !activePage()) return;
                context = window.TaxonomyApiClient.captureContext();
                projectId = state.selectedProjectId;
            }
            const pending = operation(isCurrent);
            // Selection handlers change their target synchronously before issuing reads.
            projectId = state.selectedProjectId;
            context = window.TaxonomyApiClient.captureContext();
            return await pending;
        } catch (error) {
            if (isCurrent() && error.code !== 'ABORTED') showError(error);
            throw error;
        } finally {
            state.busyCount = Math.max(0, state.busyCount - 1);
            updateBusyState();
        }
    }

    function updateBusyState() {
        const busy = state.busyCount > 0;
        const disabled = busy || !sourceInitialized;
        document.getElementById('portfolioBusy').classList.toggle('d-none', !busy);
        document.querySelectorAll('button[type="submit"], #proposeSolutionsBtn, #detectConflictsBtn, .product-candidate-review, .save-solution-action')
            .forEach(function (button) {
                const missingProducts = button.closest('.add-product-candidate') && !state.products.length;
                button.disabled = disabled || button.getAttribute?.('aria-disabled') === 'true' || Boolean(missingProducts);
                if (missingProducts) {
                    button.dataset.productUnavailable = 'true';
                    button.setAttribute('title', t('error.no.products'));
                } else if (button.dataset.productUnavailable) {
                    delete button.dataset.productUnavailable;
                    if (button.getAttribute('aria-disabled') !== 'true') button.removeAttribute('title');
                }
            });
        const analyzeAll = document.getElementById('analyzeAllBtn');
        if (!analyzeAll) return;
        const hasCurrentPortfolio = state.portfolio?.project.id === state.selectedProjectId;
        const hasRequirements = hasCurrentPortfolio && state.portfolio.requirements?.length > 0;
        analyzeAll.disabled = disabled || !hasRequirements;
        const reason = document.getElementById('analyzeAllUnavailable');
        if (reason) {
            reason.hidden = !hasCurrentPortfolio || hasRequirements;
            if (reason.hidden) analyzeAll.removeAttribute?.('aria-describedby');
            else analyzeAll.setAttribute('aria-describedby', reason.id);
        }
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

    async function loadProjects(preferredProjectId, options) {
        await withBusy(async function (isCurrent) {
            const [projects] = await Promise.all([
                window.TaxonomyPortfolioApi.listProjects(),
                window.TaxonomyI18n.ready()
            ]);
            state.projects = projects;
            renderProjectList();
            const stored = Number(window.localStorage.getItem('taxonomy.portfolio.projectId')) || null;
            const candidate = preferredProjectId || stored;
            const selected = window.TaxonomyArchitectureWorkflow
                ? window.TaxonomyArchitectureWorkflow.preferredProject(state.projects, preferredProjectId, stored)
                : state.projects.find(function (project) { return project.id === candidate; })
                || state.projects[0]
                || null;
            if (selected) await selectProject(selected.id);
            else {
                showNoProjectSelected();
                if (new URLSearchParams(window.location.search).has('projectId')) {
                    showError(new Error(window.TaxonomyI18n.t('workflow.projectUnavailable')));
                }
            }
        }, options).catch(function (error) { if (options?.sourceBootstrap) throw error; });
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
            if (project.id === state.selectedProjectId) {
                button.setAttribute('aria-current', 'page');
            }

            const header = document.createElement('div');
            header.className = 'd-flex flex-wrap justify-content-between align-items-start gap-2';
            const title = document.createElement('strong');
            title.textContent = project.projectKey;
            const status = document.createElement('span');
            status.className = 'badge ' + statusBadgeClass(project.status);
            status.textContent = humanize(project.status);
            window.TaxonomyAppChrome?.setStatus(status,project.status);
            title.classList.add('taxonomy-entity-key');
            header.append(title, status);

            const name = document.createElement('div');
            name.className = 'small mt-1';
            name.textContent = project.title;
            const metrics = document.createElement('div');
            metrics.className = 'small opacity-75 mt-1';
            metrics.textContent = t('projects.requirements.' + (project.requirementCount === 1 ? 'one' : 'other'),
                { count: project.requirementCount })
                + ' · ' + t('projects.solutions.' + (project.solutionCount === 1 ? 'one' : 'other'),
                    { count: project.solutionCount });
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
        const hadSelection = state.selectedProjectId !== null;
        const workspace = document.getElementById('projectWorkspace');
        renderProjectTools(null);
        state.selectedProjectId = projectId;
        workspace.dataset.projectId = String(projectId);
        state.selectedRequirementId = null;
        state.snapshots = [];
        state.selectedSnapshotId = null;
        document.getElementById('snapshotDetail').replaceChildren();
        renderSnapshotList();
        window.TaxonomyAppChrome?.updateContext(hadSelection ? {project:null,requirement:null,source:null} : {project:null,requirement:null});
        window.localStorage.setItem('taxonomy.portfolio.projectId', String(projectId));
        document.dispatchEvent(new CustomEvent('taxonomy:portfolio-project-selecting', {
            detail: { projectId: projectId }
        }));
        renderProjectList();
        await refreshSelectedProject(false);
        if (state.selectedProjectId !== projectId) return;
        document.getElementById('noProjectSelected').classList.add('d-none');
        workspace.classList.remove('d-none');
        document.dispatchEvent(new CustomEvent('taxonomy:portfolio-project-selected', {
            detail: { projectId: projectId }
        }));
    }

    function showNoProjectSelected() {
        const hadSelection = state.selectedProjectId !== null;
        state.selectedProjectId = null;
        state.portfolio = null;
        window.TaxonomyAppChrome?.updateContext(hadSelection ? {project:null,requirement:null,source:null} : {project:null,requirement:null});
        renderProjectTools(null);
        const workspace = document.getElementById('projectWorkspace');
        delete workspace.dataset.projectId;
        workspace.classList.add('d-none');
        document.getElementById('noProjectSelected').classList.remove('d-none');
    }

    async function refreshSelectedProject(showSuccess) {
        requireProject();
        const projectId = state.selectedProjectId;
        const results = await Promise.all([
            window.TaxonomyPortfolioApi.getProjectPortfolio(projectId),
            window.TaxonomyPortfolioApi.listProducts()
        ]);
        if (state.selectedProjectId !== projectId) return;
        state.portfolio = results[0];
        state.products = results[1] || [];
        renderPortfolio();
        const projects = await window.TaxonomyPortfolioApi.listProjects();
        if (state.selectedProjectId !== projectId) return;
        state.projects = projects;
        renderProjectList();
        if (showSuccess !== false) showInfo(t('status.loaded'));
    }

    function renderPortfolio() {
        if (!state.portfolio) return;
        const project = state.portfolio.project;
        publishSelection();
        renderProjectTools(project.id);
        document.getElementById('selectedProjectKey').textContent = project.projectKey;
        document.getElementById('selectedProjectStatus').textContent = humanize(project.status);
        document.getElementById('selectedProjectStatus').className = 'badge ' + statusBadgeClass(project.status);
        window.TaxonomyAppChrome?.setStatus('selectedProjectStatus', project.status);
        document.getElementById('selectedProjectKey').className = 'taxonomy-entity-key';
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
            if (!stillExists) { state.selectedRequirementId = null; state.selectedSnapshotId = null; state.snapshots = []; publishSelection(null); }
        }
        updateBusyState();
    }

    function renderProjectTools(projectId) {
        const selected = Number.isSafeInteger(projectId) && projectId > 0;
        document.getElementById('projectTools').classList.toggle('d-none', !selected);
        [['Import', 'import'], ['Matrices', 'matrices'], ['Reports', 'reports'], ['Versioning', 'versioning']]
            .forEach(function ([name, path]) {
                const link = document.getElementById('project' + name + 'Link');
                if (selected) link.href = applicationUrl('/projects/' + projectId + '/' + path + '?lang=' + locale);
                else link.removeAttribute('href');
            });
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
            const detailUrl = applicationUrl('/projects/' + state.portfolio.project.id
                + '/requirements/' + requirement.id + '?lang=' + locale);
            row.innerHTML = '<td><code>' + escapeHtml(requirement.requirementKey) + '</code></td>'
                + '<td><strong><a href="' + escapeHtml(detailUrl) + '" aria-label="'
                + escapeHtml(t('requirements.details', { key: requirement.requirementKey, title: requirement.title }))
                + '">' + escapeHtml(requirement.title) + '</a></strong>'
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
            window.TaxonomyAppChrome?.setStatus(row.querySelector('td:nth-child(5) span'), requirement.reviewStatus);
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
            await withBusy(async function (isCurrent) {
                const job = await window.TaxonomyPortfolioApi.analyzeRequirementJob(state.selectedProjectId, requirementId, {
                        provider: null,
                        analysisLanguage: window.TaxonomyContentLanguage?.analysis(),
                        maxArchitectureNodes: 25,
                        idempotencyKey: 'ui:req:' + requirementId + ':' + Date.now()
                    });
                if (!isCurrent()) return;
                showAnalysisResult(job);
                await refreshSelectedProject(false);
            }).catch(function () {});
        } else if (snapshots) {
            await withBusy(function () { return loadSnapshots(requirementId); }).catch(function () {});
        } else {
            await withBusy(async function (isCurrent) {
                await window.TaxonomyPortfolioApi.updateRequirement(state.selectedProjectId, requirementId, { reviewStatus: 'CONFIRMED' });
                if (!isCurrent()) return;
                showInfo(t('status.saved'));
                await refreshSelectedProject(false);
            }).catch(function () {});
        }
    }

    async function analyzeAllRequirements() {
        if (!state.selectedProjectId) {
            showError(new Error(t('error.select.project')));
            return;
        }
        if (state.busyCount > 0 || !sourceInitialized) return;
        const requirements = state.portfolio?.project.id === state.selectedProjectId ? state.portfolio.requirements : [];
        if (!requirements?.length) {
            showError(new Error(t('error.analyze.empty')));
            return;
        }
        const requirementIds = requirements.map(function (requirement) { return requirement.id; });
        await withBusy(async function (isCurrent) {
            const job = await window.TaxonomyPortfolioApi.analyzeProject(state.selectedProjectId, {
                    requirementIds: requirementIds,
                    analysisLanguage: window.TaxonomyContentLanguage?.analysis(),
                    provider: null,
                    maxArchitectureNodes: 25,
                    idempotencyKey: 'ui:project:' + state.selectedProjectId + ':' + Date.now()
                });
            if (!isCurrent()) return;
            showAnalysisResult(job);
            await refreshSelectedProject(false);
        }).catch(function () {});
    }

    function showAnalysisResult(job) {
        showInfo(t('status.analysis.complete', {
            success: job.successfulItems || 0,
            partial: job.partialItems || 0,
            failed: job.failedItems || 0
        }));
    }

    async function proposeSolutions() {
        requireProject();
        await withBusy(async function (isCurrent) {
            const solutions = await window.TaxonomyPortfolioApi.proposeSolutions(state.selectedProjectId, {});
            if (!isCurrent()) return;
            showInfo(t('status.solutions.proposed', { count: (solutions || []).length }));
            await refreshSelectedProject(false);
        }).catch(function () {});
    }

    async function detectConflicts() {
        requireProject();
        await withBusy(async function (isCurrent) {
            const conflicts = await window.TaxonomyPortfolioApi.detectConflicts(state.selectedProjectId, {});
            if (!isCurrent()) return;
            showInfo(t('status.conflicts.detected', { count: (conflicts || []).length }));
            await refreshSelectedProject(false);
        }).catch(function () {});
    }

    async function createProject(event) {
        event.preventDefault();
        // withBusy retains a submit while the current source is being prepared.
        // A failed preparation still requires the explicit source retry.
        if (!sourceInitialized && !initialSourceReady) return;
        const form = event.currentTarget;
        const payload = formDataObject(form);
        payload.status = 'PLANNING';
        await withBusy(async function (isCurrent) {
            const project = await window.TaxonomyPortfolioApi.createProject(payload);
            if (!isCurrent()) return;
            window.TaxonomyUtils.hideModal(document.getElementById('projectModal'));
            form.reset();
            showInfo(t('status.project.created'));
            await loadProjects(project.id);
        }).catch(function () {});
    }

    async function createRequirement(event) {
        event.preventDefault();
        if (state.requirementCreateInFlight) return;
        state.requirementCreateInFlight = true;
        const current = window.TaxonomyApiClient.captureContext();
        const activePage = window.TaxonomyApiClient.captureContext({contextSensitive:false});
        const projectId = state.selectedProjectId;
        const form = event.currentTarget;
        try {
            requireProject();
            const payload = formDataObject(form);
            payload.text = form.querySelector('[name="text"]').value;
            payload.status = 'DRAFT';
            payload.priority = 50;
            payload.criticality = 'MEDIUM';
            payload.reviewStatus = 'PROPOSED';
            payload.changeReason = 'Created in the project portfolio';
            const command = await window.TaxonomyRequirementIntake.prepare(form, payload, state.selectedProjectId);
            if (!command || !current() || state.selectedProjectId !== projectId) return;
            await withBusy(async function () {
                const saved = await window.TaxonomyArchitectureLifecycle.saveRequirement(command);
                if (!activePage() || state.selectedProjectId !== projectId
                        || !window.TaxonomyArchitectureLifecycle.isCurrentRequirementPublication(saved)) return;
                const pendingPublication = ['CHECKPOINT_PENDING','RECONCILIATION_REQUIRED'].includes(saved.publication?.state);
                if (!window.TaxonomyRequirementIntake.isCurrent(form)) {
                    if (!pendingPublication) showInfo(command.choice === 'LINK'
                        ? window.TaxonomyI18n.t('lifecycle.linked') : t('status.requirement.created'));
                    return;
                }
                if (pendingPublication) {
                    window.TaxonomyArchitectureLifecycle.showPublicationRecovery(form,saved); return;
                }
                form.querySelector('[data-lifecycle-publication-recovery]')?.remove();
                if (command.choice === 'LINK') { showInfo(window.TaxonomyI18n.t('lifecycle.linked')); return; }
                const requirement = saved.requirement;
                window.TaxonomyUtils.hideModal(document.getElementById('requirementModal'));
                form.reset();
                state.selectedRequirementId = requirement.id;
                showInfo(t('status.requirement.created'));
                await refreshSelectedProject(false);
            }).catch(function () {});
        } catch (error) {
            if (current() && state.selectedProjectId === projectId && error.code !== 'ABORTED') showError(error);
        } finally {
            state.requirementCreateInFlight = false;
        }
    }

    async function createSolution(event) {
        event.preventDefault();
        requireProject();
        const form = event.currentTarget;
        await withBusy(async function (isCurrent) {
            const payload = formDataObject(form);
            payload.lifecycleStatus = 'PLANNED';
            payload.maturityLevel = 0;
            payload.reviewStatus = 'PROPOSED';
            const solution = await window.TaxonomyPortfolioApi.createSolution(payload);
            if (!isCurrent()) return;
            await window.TaxonomyPortfolioApi.addProjectSolution(state.selectedProjectId, {
                    solutionId: solution.id,
                    actionStatus: 'UNDECIDED',
                    reviewStatus: 'PROPOSED',
                    status: 'PROPOSED'
                });
            if (!isCurrent()) return;
            window.TaxonomyUtils.hideModal(document.getElementById('solutionModal'));
            form.reset();
            showInfo(t('status.solution.created'));
            await refreshSelectedProject(false);
        }).catch(function () {});
    }

    async function createProduct(event) {
        event.preventDefault();
        const form = event.currentTarget;
        await withBusy(async function (isCurrent) {
            const payload = formDataObject(form);
            payload.productStatus = 'CANDIDATE';
            payload.verifiedAt = localDateTimeToInstant(payload.verifiedAt);
            const product = await window.TaxonomyPortfolioApi.createProduct(payload);
            if (!isCurrent()) return;
            window.TaxonomyUtils.hideModal(document.getElementById('productModal'));
            form.reset();
            setDefaultVerifiedAt();
            showInfo(t('status.product.created'));
            state.products.push(product);
            renderProducts(state.products);
            refreshProductCandidateOptions();
        }).catch(function () {});
    }

    function renderTaxonomy(nodes) {
        const target = document.getElementById('taxonomySummary');
        target.textContent = '';
        nodes.forEach(function (node) {
            const column = document.createElement('div');
            const card = document.createElement('div');
            card.className = 'card h-100';
            card.innerHTML = '<div class="card-body"><div class="d-flex justify-content-between gap-2">'
                + '<code>' + escapeHtml(node.nodeCode) + '</code><span class="badge text-bg-primary">'
                + node.requirementCount + '</span></div><strong class="d-block mt-2">'
                + escapeHtml(node.nodeTitle) + '</strong><div class="small text-body-secondary">'
                + escapeHtml(node.taxonomyRoot) + ' · ' + formatPercent(node.averageRelevance)
                + ' · ' + escapeHtml(t('label.score')) + ' ' + node.maximumDirectScore + '</div></div>';
            column.appendChild(card);
            target.appendChild(column);
        });
    }

    function renderSolutions(solutions) {
        const target = document.getElementById('solutionsList');
        target.textContent = '';
        solutions.forEach(function (projectSolution) {
            const solution = projectSolution.solution;
            const taxonomyCoverage = solution.taxonomyCoverage || [];
            const column = document.createElement('div');
            column.className = 'col-12';
            const card = document.createElement('article');
            card.className = 'card portfolio-solution-card';
            card.dataset.projectId = projectSolution.projectId;
            card.dataset.projectSolutionId = projectSolution.id;
            card.innerHTML = '<div class="card-body"><div class="d-flex flex-column flex-lg-row justify-content-between gap-3">'
                + '<div><div class="d-flex align-items-center gap-2 flex-wrap"><code>'
                + escapeHtml(solution.solutionKey) + '</code><span class="badge '
                + reviewBadgeClass(projectSolution.reviewStatus) + '">'
                + escapeHtml(humanize(projectSolution.reviewStatus)) + '</span></div><h4 class="h5 mt-2">'
                + escapeHtml(solution.title) + '</h4><p class="text-body-secondary">'
                + escapeHtml(solution.description || '') + '</p></div><div class="portfolio-card-meta">'
                + '<div><span>' + escapeHtml(t('label.action')) + '</span><strong>'
                + escapeHtml(humanize(projectSolution.actionStatus)) + '</strong></div><div><span>'
                + escapeHtml(t('label.status')) + '</span><strong>'
                + escapeHtml(humanize(projectSolution.status)) + '</strong></div></div></div>'
                + '<details class="mt-3"><summary>' + escapeHtml(t('label.taxonomy.coverage')) + '</summary>'
                + '<div class="table-responsive mt-2"><table class="table table-sm align-middle"><thead><tr>'
                + '<th>' + escapeHtml(t('label.taxonomy.coverage')) + '</th><th>'
                + escapeHtml(t('label.coverage')) + '</th><th>' + escapeHtml(t('label.review')) + '</th></tr>'
                + '</thead><tbody>' + taxonomyCoverage.map(function (coverage) {
                    return '<tr><td><code>' + escapeHtml(coverage.nodeCode) + '</code> '
                        + escapeHtml(coverage.nodeTitle || '') + '</td><td>' + coverage.coveragePercent
                        + '%</td><td><span class="badge ' + reviewBadgeClass(coverage.reviewStatus) + '">'
                        + escapeHtml(humanize(coverage.reviewStatus)) + '</span></td></tr>';
                }).join('') + '</tbody></table></div>'
                + '<form class="row g-2 mt-2 add-solution-coverage" data-solution-id="'
                + solution.id + '"><label class="col-md-5 small">' + escapeHtml(t('label.taxonomy.coverage'))
                + '<input class="form-control form-control-sm solution-node-code" '
                + 'placeholder="CP-…" required></label><label class="col-md-3 small">' + escapeHtml(t('label.coverage'))
                + '<input type="number" min="0" max="100" '
                + 'class="form-control form-control-sm solution-coverage-percent" value="100" required></label>'
                + '<div class="col-md-4"><button type="submit" class="btn btn-sm btn-outline-primary w-100">'
                + escapeHtml(t('action.add')) + '</button></div></form></details>'
                + '<div class="mt-3"><label class="form-label small fw-semibold" for="solutionAction' + projectSolution.id + '">'
                + escapeHtml(t('label.action')) + '</label><div class="input-group input-group-sm">'
                + '<select id="solutionAction' + projectSolution.id + '" class="form-select solution-action-select" data-project-solution-id="'
                + projectSolution.id + '">' + actionOptions(projectSolution.actionStatus) + '</select>'
                + '<button class="btn btn-outline-success save-solution-action" type="button" data-project-solution-id="'
                + projectSolution.id + '">' + escapeHtml(t('action.save')) + '</button></div></div>'
                + '<details class="mt-3"><summary>' + escapeHtml(t('label.requirement.solution')) + '</summary><div class="mt-2">'
                + (projectSolution.requirementLinks || []).map(function (link) {
                    return '<div class="d-flex align-items-center justify-content-between border-bottom py-2">'
                        + '<span><code>' + escapeHtml(link.requirementKey) + '</code> '
                        + escapeHtml(link.requirementTitle) + '</span><span>' + link.coveragePercent + '% · '
                        + escapeHtml(humanize(link.reviewStatus)) + '</span></div>';
                }).join('') + '</div></details>'
                + '<details class="mt-3"><summary>' + escapeHtml(t('label.product.candidates')) + '</summary><div class="mt-2">'
                + (projectSolution.productCandidates || []).map(function (candidate) {
                    return '<div class="border rounded p-2 mb-2"><div class="d-flex justify-content-between gap-2">'
                        + '<strong>' + escapeHtml(candidate.product.manufacturer + ' '
                        + candidate.product.productName) + '</strong><span class="badge '
                        + reviewBadgeClass(candidate.reviewStatus) + '">' + escapeHtml(humanize(candidate.reviewStatus))
                        + '</span></div><div class="small text-body-secondary">'
                        + candidate.coveragePercent + '% · ' + escapeHtml(humanize(candidate.selectionStatus))
                        + '</div><div class="btn-group btn-group-sm mt-2"><button type="button" '
                        + 'class="btn btn-outline-success product-candidate-review" data-candidate-id="'
                        + candidate.id + '" data-status="CONFIRMED">' + escapeHtml(t('action.confirm'))
                        + '</button><button type="button" class="btn btn-outline-danger product-candidate-review" '
                        + 'data-candidate-id="' + candidate.id + '" data-status="REJECTED">'
                        + escapeHtml(t('action.reject')) + '</button></div></div>';
                }).join('') + '</div><form class="row g-2 mt-2 add-product-candidate" data-project-solution-id="'
                + projectSolution.id + '"><label class="col-md-5 small">' + escapeHtml(t('field.product.name'))
                + '<select class="form-select form-select-sm candidate-product-id" required>'
                + productOptions() + '</select></label><label class="col-md-3 small">' + escapeHtml(t('label.coverage'))
                + '<input type="number" min="0" max="100" '
                + 'class="form-control form-control-sm candidate-coverage-percent" value="100" required></label>'
                + '<div class="col-md-4"><button type="submit" class="btn btn-sm btn-outline-primary w-100">'
                + escapeHtml(t('action.add')) + '</button></div></form></details></div>';
            const badges = Array.from(card.querySelectorAll('.badge'));
            const statuses = [projectSolution.reviewStatus].concat(taxonomyCoverage.map(function (value) { return value.reviewStatus; }),
                (projectSolution.productCandidates || []).map(function (value) { return value.reviewStatus; }));
            badges.forEach(function (badge,index) { window.TaxonomyAppChrome?.setStatus(badge,statuses[index]); });
            column.appendChild(card);
            target.appendChild(column);
        });
        document.getElementById('solutionsList').classList.toggle('d-none', solutions.length === 0);
    }

    function renderProducts(products) {
        const target = document.getElementById('productsList');
        target.textContent = '';
        products.forEach(function (product) {
            const column = document.createElement('div');
            column.className = 'col-12';
            const card = document.createElement('article');
            card.className = 'card portfolio-product-card';
            card.innerHTML = '<div class="card-body"><div class="d-flex flex-column flex-lg-row justify-content-between gap-3">'
                + '<div><div class="d-flex gap-2 align-items-center flex-wrap"><code>'
                + escapeHtml(product.productKey) + '</code><span class="badge '
                + statusBadgeClass(product.productStatus) + '">' + escapeHtml(humanize(product.productStatus))
                + '</span></div><h4 class="h5 mt-2">' + escapeHtml(product.manufacturer + ' '
                + product.productName) + '</h4><div class="text-body-secondary">'
                + escapeHtml(product.productFamily || '') + ' ' + escapeHtml(product.editionVersion || '')
                + '</div></div><div class="portfolio-card-meta"><div><span>' + escapeHtml(t('field.operating.model'))
                + '</span><strong>' + escapeHtml(humanize(product.operatingModel)) + '</strong></div><div><span>'
                + escapeHtml(t('label.verified')) + '</span><strong>' + escapeHtml(formatDate(product.verifiedAt))
                + '</strong></div></div></div><div class="portfolio-evidence mt-3"><strong>'
                + escapeHtml(t('label.source')) + ':</strong> ' + escapeHtml(product.sourceReference) + '</div>'
                + '<details class="mt-3"><summary>' + escapeHtml(t('label.taxonomy.coverage')) + '</summary><div class="mt-2">'
                + (product.taxonomyCoverage || []).map(function (coverage) {
                    return '<div class="d-flex justify-content-between border-bottom py-2"><span><code>'
                        + escapeHtml(coverage.nodeCode) + '</code> ' + escapeHtml(coverage.nodeTitle || '')
                        + '</span><span>' + coverage.coveragePercent + '% · '
                        + escapeHtml(humanize(coverage.reviewStatus)) + '</span></div>';
                }).join('') + '</div><form class="row g-2 mt-2 add-product-coverage" data-product-id="'
                + product.id + '"><label class="col-md-5 small">' + escapeHtml(t('label.taxonomy.coverage'))
                + '<input class="form-control form-control-sm product-node-code" '
                + 'placeholder="CP-…" required></label><label class="col-md-3 small">' + escapeHtml(t('label.coverage'))
                + '<input type="number" min="0" max="100" '
                + 'class="form-control form-control-sm product-coverage-percent" value="100" required></label>'
                + '<div class="col-md-4"><button type="submit" class="btn btn-sm btn-outline-primary w-100">'
                + escapeHtml(t('action.add')) + '</button></div></form></details></div>';
            window.TaxonomyAppChrome?.setStatus(card.querySelector('.badge'),product.productStatus);
            column.appendChild(card);
            target.appendChild(column);
        });
    }

    function renderConflicts(conflicts) {
        const target = document.getElementById('conflictsList');
        target.textContent = '';
        conflicts.forEach(function (conflict) {
            const card = document.createElement('article');
            card.className = 'card portfolio-conflict-card';
            card.innerHTML = '<div class="card-body"><div class="d-flex justify-content-between gap-2">'
                + '<div><div class="d-flex align-items-center gap-2 flex-wrap"><span class="badge text-bg-warning">'
                + escapeHtml(humanize(conflict.conflictType)) + '</span><span class="badge '
                + statusBadgeClass(conflict.status) + '">' + escapeHtml(humanize(conflict.status))
                + '</span></div><strong class="d-block mt-2">' + escapeHtml(conflict.title)
                + '</strong><div><code>' + escapeHtml(conflict.requirementAKey) + '</code> ↔ <code>'
                + escapeHtml(conflict.requirementBKey) + '</code></div></div></div>'
                + conflictAssessmentHtml(conflict) + '<div class="portfolio-evidence mt-3">'
                + escapeHtml(conflict.evidence) + '</div><div class="btn-group btn-group-sm mt-3">'
                + '<button class="btn btn-outline-success conflict-review" data-conflict-id="' + conflict.id
                + '" data-status="CONFIRMED">' + escapeHtml(t('action.confirm')) + '</button>'
                + '<button class="btn btn-outline-danger conflict-review" data-conflict-id="' + conflict.id
                + '" data-status="REJECTED">' + escapeHtml(t('action.reject')) + '</button>'
                + '<button class="btn btn-outline-primary conflict-review" data-conflict-id="' + conflict.id
                + '" data-status="RESOLVED">' + escapeHtml(t('action.resolve')) + '</button></div>'
                + (conflict.resolutionNote ? '<div class="alert alert-light border mt-3 mb-0">'
                    + escapeHtml(conflict.resolutionNote) + '</div>' : '') + '</div>';
            const badges = card.querySelectorAll('.badge');
            window.TaxonomyAppChrome?.setStatus(badges[0],conflict.conflictType);
            window.TaxonomyAppChrome?.setStatus(badges[1],conflict.status);
            target.appendChild(card);
        });
        if (conflicts.length === 0) target.innerHTML = '<div class="portfolio-empty"><span>✓</span><p>'
            + escapeHtml(t('empty.conflicts')) + '</p></div>';
    }

    function conflictAssessmentHtml(conflict) {
        const known = conflict.confidenceBasis === 'UNCALIBRATED_RULE_WEIGHT'
            && typeof conflict.confidence === 'number'
            && Number.isFinite(conflict.confidence)
            && conflict.confidence >= 0 && conflict.confidence <= 1;
        const weight = known
            ? new Intl.NumberFormat(locale, { maximumFractionDigits: 1 }).format(conflict.confidence * 100) + ' / 100'
            : '—';
        return '<div class="conflict-assessment small border rounded p-2 mt-3"><span>'
            + escapeHtml(t('conflicts.rule.weight')) + ': </span><strong>' + escapeHtml(weight)
            + '</strong>\n<p class="text-body-secondary mb-0 mt-1">'
            + escapeHtml(t(known ? 'conflicts.rule.help' : 'conflicts.rule.unknown')) + '</p></div>';
    }

    function renderMatrix(targetId, matrix) {
        const target = document.getElementById(targetId);
        if (!matrix || matrix.rows.length === 0 || matrix.columns.length === 0) {
            target.innerHTML = '<div class="portfolio-empty"><span>↔</span><p>—</p></div>';
            return;
        }
        let html = '<table class="table table-sm table-bordered align-middle"><thead><tr><th></th>';
        matrix.columns.forEach(function (column) { html += '<th scope="col"><code>' + escapeHtml(column) + '</code></th>'; });
        html += '</tr></thead><tbody>';
        matrix.rows.forEach(function (row) {
            html += '<tr><th scope="row"><code>' + escapeHtml(row) + '</code></th>';
            matrix.columns.forEach(function (column) {
                const value = matrix.values[row] && matrix.values[row][column];
                html += '<td class="text-center ' + matrixCellClass(value) + '">'
                    + (value === undefined || value === null ? '—' : escapeHtml(value)) + '</td>';
            });
            html += '</tr>';
        });
        target.innerHTML = html + '</tbody></table>';
    }

    async function onSolutionAction(event) {
        const saveAction = event.target.closest('.save-solution-action');
        const reviewCandidate = event.target.closest('.product-candidate-review');
        if (saveAction) {
            if (!canSubmitPortfolioMutation() || saveAction.disabled || !saveAction.isConnected) return;
            const projectSolutionId = Number(saveAction.dataset.projectSolutionId);
            const card = saveAction.closest('.portfolio-solution-card');
            if (Number(card?.dataset.projectId) !== state.selectedProjectId
                    || !state.portfolio.solutions?.some(item => item.id === projectSolutionId)) return;
            await withBusy(async function (isCurrent) {
                const select = document.querySelector('.solution-action-select[data-project-solution-id="'
                    + projectSolutionId + '"]');
                await window.TaxonomyPortfolioApi.updateProjectSolution(state.selectedProjectId, projectSolutionId, { actionStatus: select.value });
                if (!isCurrent()) return;
                showInfo(t('status.saved'));
                await refreshSelectedProject(false);
            }).catch(function () {});
        } else if (reviewCandidate) {
            if (!canSubmitPortfolioMutation() || reviewCandidate.disabled || !reviewCandidate.isConnected) return;
            const projectId = state.selectedProjectId;
            const candidateId = Number(reviewCandidate.dataset.candidateId);
            const reviewStatus = reviewCandidate.dataset.status;
            const card = reviewCandidate.closest('.portfolio-solution-card');
            if (Number(card?.dataset.projectId) !== projectId || !['CONFIRMED', 'REJECTED'].includes(reviewStatus)
                    || !Number.isSafeInteger(candidateId) || candidateId <= 0) return;
            const projectSolutionId = Number(card.dataset.projectSolutionId);
            const solution = state.portfolio.solutions?.find(item => item.id === projectSolutionId);
            if (!solution?.productCandidates?.some(candidate => candidate.id === candidateId)) return;
            await withBusy(async function (isCurrent) {
                await window.TaxonomyPortfolioApi.updateProductCandidate(projectId, candidateId, {
                    reviewStatus,
                    selectionStatus: reviewStatus === 'CONFIRMED' ? 'SHORTLISTED' : 'REJECTED'
                });
                if (!isCurrent()) return;
                showInfo(t('status.saved'));
                await refreshSelectedProject(false);
            }).catch(function () {});
        }
    }

    function canSubmitPortfolioMutation() {
        return sourceInitialized && state.busyCount === 0 && state.portfolio?.project.id === state.selectedProjectId
            && document.getElementById('portfolioMain').dataset.mutationAccess === 'allowed';
    }

    async function onPortfolioFormSubmit(event) {
        const form = event.target;
        if (!form.matches('form.add-solution-coverage, form.add-product-coverage, form.add-product-candidate')) return;
        event.preventDefault();
        const projectId = state.selectedProjectId;
        if (!canSubmitPortfolioMutation() || !form.isConnected
                || form.getAttribute('aria-disabled') === 'true' || event.submitter?.disabled) return;

        const candidate = form.matches('.add-product-candidate');
        const solutionCoverage = form.matches('.add-solution-coverage');
        const recordId = Number(candidate ? form.dataset.projectSolutionId
            : solutionCoverage ? form.dataset.solutionId : form.dataset.productId);
        if (!Number.isSafeInteger(recordId) || recordId <= 0) {
            showError(new Error(t('error.generic')));
            return;
        }
        const percentField = form.querySelector('input[type="number"]');
        const coveragePercent = Number(percentField.value);
        if (!percentField.value.trim() || !Number.isInteger(coveragePercent) || coveragePercent < 0 || coveragePercent > 100) {
            showError(new Error(t('error.coverage.percent')));
            percentField.focus();
            return;
        }

        const api = window.TaxonomyPortfolioApi;
        let save;
        if (candidate) {
            const productField = form.querySelector('.candidate-product-id');
            const productId = Number(productField.value);
            if (!Number.isSafeInteger(productId) || productId <= 0 || !state.products.some(product => product.id === productId)) {
                showError(new Error(t('error.select.product')));
                productField.focus();
                return;
            }
            save = () => api.addProductCandidate(projectId, recordId, {
                productId, coveragePercent, confidence: 0, reviewStatus: 'PROPOSED', selectionStatus: 'CANDIDATE'
            });
        } else {
            const codeField = form.querySelector(solutionCoverage ? '.solution-node-code' : '.product-node-code');
            const nodeCode = codeField.value.trim();
            if (!nodeCode) {
                showError(new Error(t('error.coverage.node')));
                codeField.focus();
                return;
            }
            const request = { nodeCode, coveragePercent, reviewStatus: 'PROPOSED', evidence: null };
            save = solutionCoverage ? () => api.addCatalogSolutionCoverage(recordId, request)
                : () => api.addProductCoverage(recordId, request);
        }
        await withBusy(async function (isCurrent) {
            await save();
            if (!isCurrent()) return;
            showInfo(t('status.saved'));
            await refreshSelectedProject(false);
        }).catch(function () {});
    }

    async function onConflictAction(event) {
        const button = event.target.closest('.conflict-review');
        if (!button) return;
        const status = button.dataset.status;
        let resolutionNote = null;
        if (status === 'RESOLVED') {
            resolutionNote = window.prompt(t('confirm.resolve.note'));
            if (resolutionNote === null) return;
        }
        await withBusy(async function (isCurrent) {
            await window.TaxonomyPortfolioApi.updateConflict(state.selectedProjectId, button.dataset.conflictId, { status: status, resolutionNote: resolutionNote });
            if (!isCurrent()) return;
            showInfo(t('status.saved'));
            await refreshSelectedProject(false);
        }).catch(function () {});
    }

    function publishSelection(source) {
        const project = state.portfolio?.project;
        const requirement = (state.portfolio?.requirements || []).find(function (item) { return item.id === state.selectedRequirementId; });
        const facts = {project:project ? {id:project.id,key:project.projectKey,title:project.title} : null,
            requirement:requirement ? {id:requirement.id,key:requirement.requirementKey,title:requirement.title,
                version:requirement.currentVersion?.versionNumber,versionId:requirement.currentVersion?.id || requirement.currentVersionId} : null};
        if (arguments.length) facts.source = source;
        window.TaxonomyAppChrome?.updateContext(facts);
    }
    function snapshotSource(snapshot) {
        const summary = snapshot.summary || snapshot;
        const current = window.TaxonomyArchitectureWorkflow?.getScope() || {};
        return {projectId:summary.projectId || state.selectedProjectId,requirementId:summary.requirementId || state.selectedRequirementId,
            projectKey:state.portfolio?.project?.projectKey,requirementKey:summary.requirementKey,
            snapshotId:summary.id || state.selectedSnapshotId,requirementVersionId:summary.requirementVersionId,
            requirementVersion:summary.requirementVersionNumber,repositoryId:snapshot.exportProvenance?.repositoryId || current.repositoryId,
            workspaceId:summary.workspaceId ?? '',workspaceScopeKey:summary.workspaceId || '__shared__',branch:summary.branchName};
    }
    async function loadSnapshots(requirementId) {
        state.selectedRequirementId = requirementId;
        state.selectedSnapshotId = null; publishSelection(null);
        document.getElementById('snapshotDetail').replaceChildren();
        const selectedProject = state.selectedProjectId;
        const loaded = await window.TaxonomyPortfolioApi.listRequirementSnapshots(selectedProject, requirementId);
        if (state.selectedProjectId !== selectedProject || state.selectedRequirementId !== requirementId) return;
        state.snapshots = loaded;
        renderSnapshotList();
        const tab = bootstrap.Tab.getOrCreateInstance(document.getElementById('snapshots-tab'));
        tab.show();
        if (state.snapshots.length > 0) await showSnapshot(state.snapshots[0].id);
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
            button.innerHTML = '<div class="d-flex justify-content-between gap-2"><strong>'
                + escapeHtml(formatDate(snapshot.createdAt)) + '</strong><span class="badge '
                + statusBadgeClass(snapshot.status) + '">' + escapeHtml(humanize(snapshot.status))
                + '</span></div><div class="small text-body-secondary">'
                + escapeHtml(snapshot.provider || '—') + ' · ' + escapeHtml(t('label.warnings')) + ': '
                + escapeHtml(snapshot.warningCount ?? 0) + '</div>';
            target.appendChild(button);
        });
        if (state.snapshots.length === 0) target.innerHTML = '<div class="portfolio-empty"><span>🕒</span><p>'
            + escapeHtml(t('empty.snapshots')) + '</p></div>';
    }

    async function onSnapshotAction(event) {
        const button = event.target.closest('.snapshot-select');
        if (!button) return;
        await withBusy(function () { return showSnapshot(button.dataset.snapshotId); }).catch(function () {});
    }

    async function showSnapshot(snapshotId) {
        state.selectedSnapshotId = snapshotId;
        publishSelection(null);
        const selectedProject = state.selectedProjectId, selectedRequirement = state.selectedRequirementId;
        const target = document.getElementById('snapshotDetail');
        target.replaceChildren();
        renderSnapshotList();
        const snapshot = await window.TaxonomyPortfolioApi.getSnapshot(selectedProject, snapshotId);
        if (state.selectedProjectId !== selectedProject || state.selectedRequirementId !== selectedRequirement || state.selectedSnapshotId !== snapshotId) return;
        const summary = snapshot.summary;
        if (summary.id !== snapshotId || summary.projectId !== selectedProject || summary.requirementId !== selectedRequirement) {
            throw new Error(t('error.generic'));
        }
        publishSelection(snapshotSource(snapshot));
        const warnings = snapshot.analysis?.warnings || [];
        target.innerHTML = '<div class="d-flex justify-content-between gap-2"><div><h4 class="h5">'
            + escapeHtml(formatDate(summary.createdAt)) + '</h4><div class="text-body-secondary">'
            + escapeHtml(summary.provider || '—') + '</div></div><span class="badge '
            + statusBadgeClass(summary.status) + '">' + escapeHtml(humanize(summary.status))
            + '</span></div><dl class="portfolio-card-meta mt-3"><dt>' + escapeHtml(t('label.taxonomy.fingerprint'))
            + '</dt><dd><code>' + escapeHtml(summary.taxonomyFingerprint || '—') + '</code></dd><dt>'
            + escapeHtml(t('label.prompt.fingerprint')) + '</dt><dd><code>'
            + escapeHtml(summary.promptFingerprint || '—') + '</code></dd><dt>'
            + escapeHtml(t('label.commit')) + '</dt><dd><code>' + escapeHtml(summary.commitSha || '—')
            + '</code></dd><dt>' + escapeHtml(t('label.duration')) + '</dt><dd>'
            + escapeHtml(formatDuration(summary.durationMs)) + '</dd></dl><h5 class="h6 mt-4">'
            + escapeHtml(t('label.mappings')) + '</h5><div class="table-responsive"><table class="table table-sm">'
            + '<thead><tr><th>' + escapeHtml(t('label.taxonomy.coverage')) + '</th><th>'
            + escapeHtml(t('label.score')) + '</th><th>' + escapeHtml(t('label.relevance')) + '</th><th>'
            + escapeHtml(t('label.origin')) + '</th><th>' + escapeHtml(t('label.review')) + '</th></tr></thead><tbody>'
            + (snapshot.elementMappings || []).filter(mapping => mapping.snapshotId === snapshotId).map(function (mapping) {
                const identity = ' data-mapping-id="' + escapeHtml(mapping.id) + '" data-snapshot-id="'
                    + escapeHtml(mapping.snapshotId) + '" data-project-id="' + selectedProject + '"';
                return '<tr><td><code>' + escapeHtml(mapping.nodeCode) + '</code> '
                    + escapeHtml(mapping.nodeTitle || '') + '</td><td>' + mapping.directScore + '</td><td>'
                    + formatPercent(mapping.relevance) + '</td><td>' + escapeHtml(humanize(mapping.mappingOrigin))
                    + '</td><td><span class="badge me-2 ' + reviewBadgeClass(mapping.reviewStatus) + '">'
                    + escapeHtml(humanize(mapping.reviewStatus)) + '</span><div class="btn-group btn-group-sm">'
                    + '<button type="button" class="btn btn-outline-success mapping-review"' + identity
                    + ' data-status="CONFIRMED">' + escapeHtml(t('action.confirm')) + '</button>'
                    + '<button type="button" class="btn btn-outline-danger mapping-review"' + identity + ' data-status="REJECTED">'
                    + escapeHtml(t('action.reject')) + '</button></div></td></tr>';
            }).join('') + '</tbody></table></div>' + (warnings.length
                ? '<div class="alert alert-warning mt-3"><strong>' + escapeHtml(t('label.warnings')) + ':</strong><ul>'
                    + warnings.map(function (warning) { return '<li>' + escapeHtml(warning) + '</li>'; }).join('')
                    + '</ul></div>' : '') + '<div class="mt-3"><button class="btn btn-sm btn-outline-secondary snapshot-compare" '
            + 'type="button">' + escapeHtml(t('action.compare.previous')) + '</button></div><div id="snapshotDiff" class="mt-3"></div>';
        if (window.TaxonomyInventoryComparison) {
            const inventory = document.createElement('div');
            inventory.id = 'inventoryComparison';
            target.append(inventory);
            window.TaxonomyInventoryComparison.mount(inventory, {projectId: state.selectedProjectId,
                snapshot, solutions: state.portfolio?.solutions || []});
        }
    }

    async function onSnapshotDetailAction(event) {
        const compare = event.target.closest('.snapshot-compare');
        if (compare) {
            await compareWithPreviousSnapshot();
        }
    }

    async function compareWithPreviousSnapshot() {
        const index = state.snapshots.findIndex(function (snapshot) { return snapshot.id === state.selectedSnapshotId; });
        const previous = state.snapshots[index + 1];
        const target = document.getElementById('snapshotDiff');
        if (!previous) {
            target.innerHTML = '<div class="alert alert-info">' + escapeHtml(t('diff.no.previous')) + '</div>';
            return;
        }
        const isCurrent = window.TaxonomyApiClient.captureContext();
        const selectedSnapshot = state.selectedSnapshotId;
        const projectId = state.selectedProjectId;
        const requirementId = state.selectedRequirementId;
        const isSelected = () => isCurrent() && target.isConnected && state.selectedProjectId === projectId
            && state.selectedRequirementId === requirementId && state.selectedSnapshotId === selectedSnapshot;
        try {
            const diff = await window.TaxonomyPortfolioApi.compareRequirementAnalyses(projectId, requirementId, selectedSnapshot, previous.id);
            if (!isSelected()) return;
            const flag = value => value === true ? t('boolean.yes') : value === false ? t('boolean.no') : String(value ?? '—');
            target.innerHTML = '<div class="card"><div class="card-body"><h5 class="h6">' + escapeHtml(t('diff.title')) + '</h5><div class="row g-2">'
                + metricCard(t('diff.added'), diff.addedElements.length)
                + metricCard(t('diff.removed'), diff.removedElements.length)
                + metricCard(t('diff.scores'), Object.keys(diff.scoreChanges).length) + '</div><ul class="small mt-3">'
                + '<li>' + escapeHtml(t('diff.taxonomy')) + ': ' + escapeHtml(flag(diff.taxonomyFingerprintChanged)) + '</li>'
                + '<li>' + escapeHtml(t('diff.prompts')) + ': ' + escapeHtml(flag(diff.promptFingerprintChanged)) + '</li>'
                + '<li>' + escapeHtml(t('diff.provider')) + ': ' + escapeHtml(flag(diff.providerChanged)) + '</li></ul></div></div>';
        } catch (error) {
            if (isSelected() && error.code !== 'ABORTED') showError(error);
        }
    }

    function metricCard(label, value) {
        return '<div class="col-4"><div class="border rounded p-2 text-center"><strong class="d-block fs-4">'
            + value + '</strong><span class="small">' + escapeHtml(label) + '</span></div></div>';
    }

    function productOptions() {
        if (!state.products.length) return '<option value="">' + escapeHtml(t('error.no.products')) + '</option>';
        return '<option value="">—</option>' + state.products.map(function (product) {
            return '<option value="' + product.id + '">' + escapeHtml(product.productKey + ' — '
                + product.manufacturer + ' ' + product.productName) + '</option>';
        }).join('');
    }

    function actionOptions(selected) {
        return ['UNDECIDED', 'SATISFIED_AS_IS', 'REUSE', 'CHANGE', 'CREATE', 'PROCURE',
            'ORGANIZATIONAL', 'RETIRE_OR_REPLACE'].map(function (value) {
            return '<option value="' + value + '"' + (value === selected ? ' selected' : '') + '>'
                + escapeHtml(humanize(value)) + '</option>';
        }).join('');
    }

    function refreshProductCandidateOptions() {
        document.querySelectorAll('.candidate-product-id').forEach(function (select) {
            const selected = select.value;
            select.innerHTML = productOptions();
            select.value = state.products.some(product => String(product.id) === selected) ? selected : '';
        });
    }

    function formDataObject(form) {
        const object = {};
        new FormData(form).forEach(function (value, key) {
            object[key] = typeof value === 'string' ? value.trim() : value;
        });
        return object;
    }

    function requireProject() {
        if (!state.selectedProjectId) throw new Error(t('error.select.project'));
    }

    /**
     * datetime-local controls return a local date and time without an offset,
     * while the product REST contract uses java.time.Instant. Convert here, in
     * the form layer that owns the control, rather than in a network wrapper.
     */
    function localDateTimeToInstant(value) {
        if (!value || /(?:z|[+-]\d{2}:\d{2})$/i.test(String(value))) return value;
        const parsed = new Date(value);
        return Number.isNaN(parsed.getTime()) ? value : parsed.toISOString();
    }

    function setDefaultVerifiedAt() {
        const input = document.getElementById('productVerifiedAt');
        if (!input || input.value) return;
        const now = new Date();
        now.setMinutes(now.getMinutes() - now.getTimezoneOffset());
        input.value = now.toISOString().slice(0, 16);
    }

    function matrixCellClass(value) {
        if (value === undefined || value === null) return 'matrix-empty';
        if (value >= 80) return 'matrix-high';
        if (value >= 50) return 'matrix-medium';
        if (value > 0) return 'matrix-low';
        return 'matrix-zero';
    }

    function statusBadgeClass(status) {
        switch (status) {
            case 'ACTIVE': case 'APPROVED': case 'SATISFIED': case 'SELECTED': case 'RESOLVED':
            case 'SUCCESS': return 'text-bg-success';
            case 'PLANNING': case 'DRAFT': case 'PROPOSED': case 'CANDIDATE': case 'SHORTLISTED':
            case 'PENDING': return 'text-bg-secondary';
            case 'IMPLEMENTING': case 'PARTIAL': return 'text-bg-info';
            case 'ON_HOLD': case 'DEPRECATED': case 'RUNNING': return 'text-bg-warning';
            case 'REJECTED': case 'FAILED': return 'text-bg-danger';
            default: return 'text-bg-light';
        }
    }

    function reviewBadgeClass(status) {
        if (status === 'CONFIRMED') return 'text-bg-success';
        if (status === 'REJECTED') return 'text-bg-danger';
        return 'text-bg-secondary';
    }

    function matrixCellClass(value) {
        if (value === undefined || value === null) return 'matrix-empty';
        if (value >= 80) return 'matrix-high';
        if (value >= 50) return 'matrix-medium';
        if (value > 0) return 'matrix-low';
        return 'matrix-zero';
    }

    function humanize(value) {
        return window.TaxonomyI18n.formatEnum(value);
    }

    function formatPercent(value) {
        return new Intl.NumberFormat(locale, { style: 'percent', maximumFractionDigits: 1 }).format(value || 0);
    }

    function formatDate(value) {
        if (!value) return '—';
        return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value));
    }

    function formatDuration(value) {
        if (value === null || value === undefined) return '—';
        return value < 1000 ? value + ' ms' : (value / 1000).toFixed(1) + ' s';
    }

    function truncate(value, maxLength) {
        const normalized = String(value || '').replace(/\s+/g, ' ').trim();
        return normalized.length <= maxLength ? normalized : normalized.slice(0, maxLength - 1) + '…';
    }

    function escapeHtml(value) {
        return String(value === null || value === undefined ? '' : value)
            .replaceAll('&', '&amp;')
            .replaceAll('<', '&lt;')
            .replaceAll('>', '&gt;')
            .replaceAll('"', '&quot;')
            .replaceAll("'", '&#039;');
    }
})();
