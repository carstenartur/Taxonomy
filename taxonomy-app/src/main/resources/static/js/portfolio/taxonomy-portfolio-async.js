/*
 * Non-blocking portfolio analysis jobs and cross-cutting GUI ergonomics.
 *
 * This adapter deliberately runs before taxonomy-portfolio.js. It keeps the
 * existing public REST contracts, but no longer blocks the entire page until a
 * queued analysis completes. It also adds capability-aware controls, accessible
 * job progress, guided evidence capture and translation of legacy hard-coded UI
 * fragments while the portfolio is incrementally decomposed into components.
 */
(function () {
    'use strict';

    const originalFetch = window.fetch.bind(window);
    const terminalStatuses = new Set(['SUCCESS', 'PARTIAL', 'FAILED', 'CANCELLED']);
    const pollIntervalMs = 1500;
    const storageKey = 'taxonomy.portfolio.analysisJobs.v2';
    const maximumHistory = 20;
    const maximumRenderedItems = 100;
    const jobs = new Map();
    let account = null;
    let locale = 'en';
    let dialogResolve = null;

    const messages = {
        en: {
            jobsTitle: 'Analysis jobs',
            jobsHelp: 'Analyses continue in the background. You can keep working and return after a reload.',
            jobsEmpty: 'No analysis jobs have been started in this browser.',
            filterAll: 'All statuses',
            retryFailed: 'Retry failed items',
            openDetails: 'Show details',
            hideDetails: 'Hide details',
            analysisStarted: 'Analysis job started. Progress is available below.',
            jobCompleted: 'Analysis job completed.',
            jobPartial: 'Analysis job completed with partial results.',
            jobFailed: 'Analysis job failed. Review the affected requirements below.',
            queued: 'Queued',
            running: 'Running',
            successful: 'Successful',
            partial: 'Partial',
            failed: 'Failed',
            cancelled: 'Cancelled',
            requirement: 'Requirement',
            status: 'Status',
            attempts: 'Attempts',
            result: 'Result',
            moreItems: 'More items are available through the job resource.',
            permissionDenied: 'Your role can view this information but cannot change it.',
            evidenceTitle: 'Document review decision',
            evidence: 'Evidence or rationale',
            comment: 'Comment',
            evidenceHelp: 'Record what you actually verified. The application does not invent human evidence.',
            save: 'Save decision',
            cancel: 'Cancel',
            resolutionTitle: 'Resolve conflict',
            resolutionNote: 'Resolution note',
            evidenceRequired: 'Evidence is required for a confirmed decision.',
            requestFailed: 'The operation failed.',
            projectsNavigation: 'Projects',
            language: 'Language',
            close: 'Close',
            skip: 'Skip to project portfolio',
            title: 'Project, requirement, solution and product portfolio',
            unknownError: 'No additional error information is available.'
        },
        de: {
            jobsTitle: 'Analysejobs',
            jobsHelp: 'Analysen laufen im Hintergrund weiter. Sie können weiterarbeiten und nach einem Neuladen zurückkehren.',
            jobsEmpty: 'In diesem Browser wurde noch kein Analysejob gestartet.',
            filterAll: 'Alle Status',
            retryFailed: 'Fehlgeschlagene Einträge wiederholen',
            openDetails: 'Details anzeigen',
            hideDetails: 'Details ausblenden',
            analysisStarted: 'Analysejob wurde gestartet. Der Fortschritt wird unten angezeigt.',
            jobCompleted: 'Analysejob wurde abgeschlossen.',
            jobPartial: 'Analysejob wurde mit Teilergebnissen abgeschlossen.',
            jobFailed: 'Analysejob ist fehlgeschlagen. Prüfen Sie unten die betroffenen Anforderungen.',
            queued: 'Wartend',
            running: 'Laufend',
            successful: 'Erfolgreich',
            partial: 'Teilweise',
            failed: 'Fehlgeschlagen',
            cancelled: 'Abgebrochen',
            requirement: 'Anforderung',
            status: 'Status',
            attempts: 'Versuche',
            result: 'Ergebnis',
            moreItems: 'Weitere Einträge sind über die Jobressource verfügbar.',
            permissionDenied: 'Ihre Rolle darf diese Information sehen, aber nicht verändern.',
            evidenceTitle: 'Prüfentscheidung dokumentieren',
            evidence: 'Evidenz oder Begründung',
            comment: 'Kommentar',
            evidenceHelp: 'Dokumentieren Sie, was Sie tatsächlich geprüft haben. Die Anwendung erfindet keine menschliche Evidenz.',
            save: 'Entscheidung speichern',
            cancel: 'Abbrechen',
            resolutionTitle: 'Konflikt lösen',
            resolutionNote: 'Lösungsnotiz',
            evidenceRequired: 'Für eine bestätigte Entscheidung ist eine Evidenz erforderlich.',
            requestFailed: 'Die Operation ist fehlgeschlagen.',
            projectsNavigation: 'Projekte',
            language: 'Sprache',
            close: 'Schließen',
            skip: 'Zum Projektportfolio springen',
            title: 'Projekt-, Anforderungs-, Lösungs- und Produktportfolio',
            unknownError: 'Es sind keine weiteren Fehlerinformationen verfügbar.'
        }
    };

    restoreJobs();
    installFetchAdapter();
    installGuidedReviewHandlers();
    document.addEventListener('DOMContentLoaded', initializeEnhancements);

    function m(key) {
        return (messages[locale] && messages[locale][key]) || messages.en[key] || key;
    }

    function installFetchAdapter() {
        window.fetch = async function portfolioAwareFetch(input, init) {
            const requestUrl = resolveUrl(input);
            const method = String((init && init.method)
                || (input instanceof Request ? input.method : 'GET')).toUpperCase();
            const sanitizedInit = sanitizeRequest(requestUrl, init);
            const response = await originalFetch(input, sanitizedInit);

            if (method !== 'POST' || response.status !== 202 || !isAnalysisSubmission(requestUrl)) {
                return response;
            }

            const initialJob = await response.clone().json().catch(function () { return null; });
            const location = response.headers.get('Location');
            if (!initialJob || !initialJob.id || !location) {
                return response;
            }

            const jobUrl = new URL(location, window.location.href).toString();
            registerJob(jobUrl, initialJob);
            queueMicrotask(function () {
                announce(m('analysisStarted'));
                replacePrematureCompletionMessage();
            });

            // Existing page code expects a parsed JSON response and refreshes the
            // portfolio immediately. Return the accepted job without waiting; the
            // browser network contract remains the original HTTP 202.
            const headers = new Headers(response.headers);
            headers.set('Content-Type', 'application/json');
            return new Response(JSON.stringify(initialJob), {
                status: 200,
                statusText: 'Accepted for background processing',
                headers: headers
            });
        };
    }

    function sanitizeRequest(url, init) {
        if (!init || typeof init.body !== 'string'
                || !String(init.headers && (init.headers['Content-Type']
                    || init.headers.get && init.headers.get('Content-Type')) || '')
                    .toLowerCase().includes('application/json')) {
            return init;
        }
        let payload;
        try {
            payload = JSON.parse(init.body);
        } catch (error) {
            return init;
        }
        let changed = false;
        const fakeEvidence = new Set([
            'Reviewed in the project portfolio workspace',
            'Confirmed in the project portfolio workspace',
            'Recorded in the project portfolio workspace'
        ]);
        if (fakeEvidence.has(payload.actionEvidence)) {
            payload.actionEvidence = null;
            changed = true;
        }
        if (fakeEvidence.has(payload.evidence)) {
            payload.evidence = null;
            changed = true;
        }
        if (payload.comment === 'Human review completed') {
            payload.comment = null;
            changed = true;
        }
        if (payload.openEvidence === 'Human review required') {
            payload.openEvidence = null;
            changed = true;
        }
        if (!changed) return init;
        return Object.assign({}, init, { body: JSON.stringify(payload) });
    }

    function initializeEnhancements() {
        locale = resolveLocale();
        translateStaticSurface();
        normalizeProjectNavigation();
        ensureDecisionDialog();
        ensureJobCenter();
        loadAccountCapabilities();
        renderJobs();
        pollActiveJobs();

        const observer = new MutationObserver(function () {
            translateLegacyFragments(document.body);
            normalizeProjectNavigation();
            applyCapabilities();
            replacePrematureCompletionMessage();
        });
        observer.observe(document.body, { childList: true, subtree: true, characterData: true });
    }

    function resolveLocale() {
        const requested = new URLSearchParams(window.location.search).get('lang');
        const language = requested || document.documentElement.lang || navigator.language || 'en';
        return String(language).toLowerCase().startsWith('de') ? 'de' : 'en';
    }

    function translateStaticSurface() {
        document.title = m('title') + ' — Taxonomy';
        const skip = document.querySelector('.skip-link');
        if (skip) skip.textContent = m('skip');
        const projectList = document.getElementById('projectList');
        if (projectList) projectList.setAttribute('aria-label', m('projectsNavigation'));
        document.querySelectorAll('[aria-label="Language"]').forEach(function (element) {
            element.setAttribute('aria-label', m('language'));
        });
        document.querySelectorAll('.btn-close').forEach(function (button) {
            button.setAttribute('aria-label', m('close'));
        });
        translateLegacyFragments(document.body);
    }

    function translateLegacyFragments(root) {
        if (locale !== 'de' || !root) return;
        const replacements = new Map([
            ['Snapshot diff', 'Snapshot-Vergleich'],
            ['Added elements', 'Hinzugekommene Elemente'],
            ['Removed elements', 'Entfallene Elemente'],
            ['Score changes', 'Score-Änderungen'],
            ['Taxonomy changed:', 'Taxonomie geändert:'],
            ['Prompts changed:', 'Prompts geändert:'],
            ['Provider changed:', 'Provider geändert:'],
            ['Node', 'Knoten'],
            ['Proposed', 'Vorgeschlagen'],
            ['Confirmed', 'Bestätigt'],
            ['Functional', 'Funktional'],
            ['Non-functional', 'Nichtfunktional'],
            ['Organizational', 'Organisatorisch'],
            ['Technical', 'Technisch'],
            ['Legal', 'Rechtlich'],
            ['Process', 'Prozess'],
            ['Security', 'Sicherheit'],
            ['Data', 'Daten'],
            ['Other', 'Sonstige'],
            ['Unspecified', 'Nicht festgelegt'],
            ['On premises', 'On-Premises'],
            ['Private cloud', 'Private Cloud'],
            ['Public cloud', 'Public Cloud']
        ]);
        const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
        const nodes = [];
        while (walker.nextNode()) nodes.push(walker.currentNode);
        nodes.forEach(function (node) {
            const trimmed = node.nodeValue.trim();
            if (replacements.has(trimmed)) {
                node.nodeValue = node.nodeValue.replace(trimmed, replacements.get(trimmed));
            }
        });
    }

    function normalizeProjectNavigation() {
        const projectList = document.getElementById('projectList');
        if (!projectList) return;
        projectList.removeAttribute('role');
        projectList.setAttribute('aria-label', m('projectsNavigation'));
        projectList.querySelectorAll('.project-select').forEach(function (button) {
            button.removeAttribute('role');
            button.removeAttribute('aria-selected');
            if (button.classList.contains('active')) button.setAttribute('aria-current', 'page');
            else button.removeAttribute('aria-current');
        });
    }

    async function loadAccountCapabilities() {
        try {
            const response = await originalFetch('/api/account/me', {
                headers: { Accept: 'application/json' },
                credentials: 'same-origin',
                cache: 'no-store'
            });
            account = response.ok ? await response.json() : null;
        } catch (error) {
            account = null;
        }
        applyCapabilities();
    }

    function applyCapabilities() {
        if (!account || account.architectureMutationAllowed) return;
        const selectors = [
            '[data-bs-target="#projectModal"]',
            '[data-bs-target="#requirementModal"]',
            '[data-bs-target="#solutionModal"]',
            '[data-bs-target="#productModal"]',
            '#proposeSolutionsBtn', '#detectConflictsBtn',
            '.requirement-confirm', '.mapping-review', '.save-solution-action',
            '.confirm-requirement-link', '.add-solution-coverage',
            '.add-product-candidate', '.product-candidate-review',
            '.add-product-coverage', '.conflict-review'
        ];
        document.querySelectorAll(selectors.join(',')).forEach(function (control) {
            control.disabled = true;
            control.setAttribute('aria-disabled', 'true');
            control.setAttribute('title', m('permissionDenied'));
        });
    }

    function ensureJobCenter() {
        if (document.getElementById('portfolioJobCenter')) return;
        const metrics = document.querySelector('[aria-labelledby="portfolioMetricsHeading"]');
        if (!metrics) return;
        const section = document.createElement('section');
        section.id = 'portfolioJobCenter';
        section.className = 'card shadow-sm mb-3 portfolio-job-center';
        section.setAttribute('aria-labelledby', 'portfolioJobCenterTitle');
        section.innerHTML = '<div class="card-header d-flex flex-wrap justify-content-between align-items-center gap-2">'
            + '<div><h2 id="portfolioJobCenterTitle" class="h5 mb-1"></h2>'
            + '<p id="portfolioJobCenterHelp" class="small text-body-secondary mb-0"></p></div>'
            + '<select id="portfolioJobFilter" class="form-select form-select-sm w-auto" '
            + 'aria-label="Job status filter"></select></div>'
            + '<div id="portfolioJobSummary" class="card-body border-bottom"></div>'
            + '<div id="portfolioJobList" class="list-group list-group-flush"></div>';
        metrics.insertAdjacentElement('afterend', section);
        document.getElementById('portfolioJobCenterTitle').textContent = m('jobsTitle');
        document.getElementById('portfolioJobCenterHelp').textContent = m('jobsHelp');
        const filter = document.getElementById('portfolioJobFilter');
        const options = [
            ['', m('filterAll')], ['PENDING', statusLabel('PENDING')],
            ['RUNNING', statusLabel('RUNNING')], ['SUCCESS', statusLabel('SUCCESS')],
            ['PARTIAL', statusLabel('PARTIAL')], ['FAILED', statusLabel('FAILED')],
            ['CANCELLED', statusLabel('CANCELLED')]
        ];
        options.forEach(function (option) {
            const element = document.createElement('option');
            element.value = option[0];
            element.textContent = option[1];
            filter.appendChild(element);
        });
        filter.addEventListener('change', renderJobs);
        document.getElementById('portfolioJobList').addEventListener('click', onJobAction);
    }

    function registerJob(url, job) {
        const projectMatch = new URL(url).pathname.match(/^\/api\/projects\/(\d+)\/analysis-jobs\//);
        const existing = jobs.get(url) || {};
        jobs.set(url, {
            url: url,
            projectId: projectMatch ? Number(projectMatch[1]) : existing.projectId || null,
            createdAt: existing.createdAt || Date.now(),
            updatedAt: Date.now(),
            expanded: existing.expanded || false,
            job: job
        });
        trimHistory();
        persistJobs();
        renderJobs();
        if (!terminalStatuses.has(job.status)) schedulePoll(url);
    }

    function restoreJobs() {
        try {
            const stored = JSON.parse(window.localStorage.getItem(storageKey) || '[]');
            if (!Array.isArray(stored)) return;
            stored.forEach(function (entry) {
                if (entry && entry.url && entry.job) jobs.set(entry.url, entry);
            });
        } catch (error) {
            window.localStorage.removeItem(storageKey);
        }
    }

    function persistJobs() {
        try {
            window.localStorage.setItem(storageKey, JSON.stringify(Array.from(jobs.values())));
        } catch (error) {
            // Storage availability must not prevent analysis.
        }
    }

    function trimHistory() {
        const ordered = Array.from(jobs.values()).sort(function (left, right) {
            return right.updatedAt - left.updatedAt;
        });
        ordered.slice(maximumHistory).forEach(function (entry) { jobs.delete(entry.url); });
    }

    function pollActiveJobs() {
        jobs.forEach(function (entry, url) {
            if (!terminalStatuses.has(entry.job.status)) schedulePoll(url, 50);
        });
    }

    function schedulePoll(url, delay) {
        const entry = jobs.get(url);
        if (!entry || entry.pollScheduled || terminalStatuses.has(entry.job.status)) return;
        entry.pollScheduled = true;
        window.setTimeout(async function () {
            const current = jobs.get(url);
            if (!current) return;
            current.pollScheduled = false;
            try {
                const response = await originalFetch(url, {
                    method: 'GET',
                    headers: { Accept: 'application/json' },
                    credentials: 'same-origin',
                    cache: 'no-store'
                });
                if (!response.ok) throw new Error('HTTP ' + response.status);
                const previousStatus = current.job.status;
                current.job = await response.json();
                current.updatedAt = Date.now();
                persistJobs();
                renderJobs();
                if (terminalStatuses.has(current.job.status)) {
                    announceTerminal(current.job);
                    refreshCurrentProject(current.projectId);
                } else {
                    schedulePoll(url);
                }
                if (previousStatus !== current.job.status) announce(statusLabel(current.job.status));
            } catch (error) {
                current.lastPollError = String(error && error.message || error);
                current.updatedAt = Date.now();
                persistJobs();
                renderJobs();
                schedulePoll(url, Math.min(10000, pollIntervalMs * 3));
            }
        }, delay == null ? pollIntervalMs : delay);
    }

    function renderJobs() {
        const list = document.getElementById('portfolioJobList');
        const summary = document.getElementById('portfolioJobSummary');
        if (!list || !summary) return;
        const filter = document.getElementById('portfolioJobFilter');
        const selectedStatus = filter ? filter.value : '';
        const entries = Array.from(jobs.values())
            .filter(function (entry) {
                return !selectedStatus || entry.job.status === selectedStatus;
            })
            .sort(function (left, right) { return right.createdAt - left.createdAt; });

        const totals = countStatuses(Array.from(jobs.values()).map(function (entry) { return entry.job; }));
        summary.innerHTML = statusPills(totals);
        list.textContent = '';
        if (entries.length === 0) {
            const empty = document.createElement('div');
            empty.className = 'p-4 text-center text-body-secondary';
            empty.textContent = m('jobsEmpty');
            list.appendChild(empty);
            return;
        }
        entries.forEach(function (entry) { list.appendChild(renderJob(entry)); });
    }

    function renderJob(entry) {
        const job = entry.job;
        const wrapper = document.createElement('article');
        wrapper.className = 'list-group-item portfolio-job';
        wrapper.dataset.jobUrl = entry.url;
        const headingId = 'job-' + String(job.id).replace(/[^A-Za-z0-9_-]/g, '-');
        wrapper.setAttribute('aria-labelledby', headingId);
        const counts = countItemStatuses(job.items || []);
        wrapper.innerHTML = '<div class="d-flex flex-wrap justify-content-between align-items-start gap-2">'
            + '<div><h3 id="' + headingId + '" class="h6 mb-1"><code>'
            + escapeHtml(String(job.id).slice(0, 12)) + '</code> · '
            + escapeHtml(statusLabel(job.status)) + '</h3>'
            + '<div class="small text-body-secondary">'
            + escapeHtml(formatDate(job.createdAt)) + ' · '
            + escapeHtml(job.provider || '—') + '</div></div>'
            + '<div class="d-flex flex-wrap gap-2">'
            + '<button type="button" class="btn btn-sm btn-outline-secondary job-toggle" '
            + 'aria-expanded="' + String(Boolean(entry.expanded)) + '">'
            + escapeHtml(entry.expanded ? m('hideDetails') : m('openDetails')) + '</button>'
            + (Number(job.failedItems || counts.FAILED || 0) > 0
                ? '<button type="button" class="btn btn-sm btn-outline-primary job-retry">'
                    + escapeHtml(m('retryFailed')) + '</button>' : '')
            + '</div></div>'
            + '<div class="mt-2">' + statusPills(counts) + '</div>'
            + '<div class="progress mt-2" role="progressbar" aria-valuemin="0" aria-valuemax="100" '
            + 'aria-valuenow="' + progressPercent(job) + '" aria-label="'
            + escapeHtml(m('jobsTitle')) + '"><div class="progress-bar" style="width:'
            + progressPercent(job) + '%">' + progressPercent(job) + '%</div></div>'
            + (entry.lastPollError ? '<div class="small text-warning mt-2">'
                + escapeHtml(entry.lastPollError) + '</div>' : '')
            + '<div class="job-details mt-3 ' + (entry.expanded ? '' : 'd-none') + '"></div>';
        if (entry.expanded) renderJobDetails(wrapper.querySelector('.job-details'), job);
        return wrapper;
    }

    function renderJobDetails(target, job) {
        const items = (job.items || []).slice(0, maximumRenderedItems);
        if (items.length === 0) {
            target.textContent = '—';
            return;
        }
        const table = document.createElement('div');
        table.className = 'table-responsive';
        table.innerHTML = '<table class="table table-sm align-middle mb-0"><caption class="visually-hidden">'
            + escapeHtml(m('jobsTitle')) + '</caption><thead><tr><th scope="col">'
            + escapeHtml(m('requirement')) + '</th><th scope="col">'
            + escapeHtml(m('status')) + '</th><th scope="col">'
            + escapeHtml(m('attempts')) + '</th><th scope="col">'
            + escapeHtml(m('result')) + '</th></tr></thead><tbody></tbody></table>';
        const body = table.querySelector('tbody');
        items.forEach(function (item) {
            const row = document.createElement('tr');
            row.innerHTML = '<td><code>' + escapeHtml(item.requirementKey || String(item.requirementId))
                + '</code></td><td>' + escapeHtml(statusLabel(item.status)) + '</td><td>'
                + escapeHtml(String(item.attempt == null ? '—' : item.attempt)) + '</td><td>'
                + (item.snapshotId ? '<code>' + escapeHtml(String(item.snapshotId).slice(0, 12)) + '</code>'
                    : escapeHtml(item.errorMessage || '—')) + '</td>';
            body.appendChild(row);
        });
        target.appendChild(table);
        if ((job.items || []).length > maximumRenderedItems) {
            const note = document.createElement('p');
            note.className = 'small text-body-secondary mt-2 mb-0';
            note.textContent = m('moreItems');
            target.appendChild(note);
        }
    }

    async function onJobAction(event) {
        const article = event.target.closest('.portfolio-job');
        if (!article) return;
        const entry = jobs.get(article.dataset.jobUrl);
        if (!entry) return;
        if (event.target.closest('.job-toggle')) {
            entry.expanded = !entry.expanded;
            persistJobs();
            renderJobs();
            return;
        }
        const retry = event.target.closest('.job-retry');
        if (!retry) return;
        retry.disabled = true;
        try {
            const response = await originalFetch(entry.url + '/retry-failed', {
                method: 'POST',
                headers: jsonHeaders(),
                credentials: 'same-origin',
                body: '{}'
            });
            if (!response.ok) throw await responseError(response);
            const job = await response.json();
            const location = response.headers.get('Location') || entry.url;
            registerJob(new URL(location, window.location.href).toString(), job);
        } catch (error) {
            showError(error);
        } finally {
            retry.disabled = false;
        }
    }

    function countStatuses(jobList) {
        const counts = { PENDING: 0, RUNNING: 0, SUCCESS: 0, PARTIAL: 0, FAILED: 0, CANCELLED: 0 };
        jobList.forEach(function (job) {
            const status = String(job.status || '').toUpperCase();
            if (Object.prototype.hasOwnProperty.call(counts, status)) counts[status] += 1;
        });
        return counts;
    }

    function countItemStatuses(items) {
        const counts = { PENDING: 0, RUNNING: 0, SUCCESS: 0, PARTIAL: 0, FAILED: 0, CANCELLED: 0 };
        items.forEach(function (item) {
            const status = String(item.status || '').toUpperCase();
            if (Object.prototype.hasOwnProperty.call(counts, status)) counts[status] += 1;
        });
        return counts;
    }

    function statusPills(counts) {
        return ['PENDING', 'RUNNING', 'SUCCESS', 'PARTIAL', 'FAILED', 'CANCELLED']
            .filter(function (status) { return Number(counts[status] || 0) > 0; })
            .map(function (status) {
                return '<span class="badge rounded-pill me-1 ' + statusClass(status) + '">'
                    + escapeHtml(statusLabel(status)) + ': ' + Number(counts[status]) + '</span>';
            }).join('') || '<span class="text-body-secondary">—</span>';
    }

    function progressPercent(job) {
        const total = Number(job.totalItems || (job.items || []).length || 0);
        if (total === 0) return terminalStatuses.has(job.status) ? 100 : 0;
        const complete = Number(job.successfulItems || 0) + Number(job.partialItems || 0)
            + Number(job.failedItems || 0)
            + (job.items || []).filter(function (item) { return item.status === 'CANCELLED'; }).length;
        return Math.max(0, Math.min(100, Math.round(complete * 100 / total)));
    }

    function statusClass(status) {
        switch (status) {
            case 'SUCCESS': return 'text-bg-success';
            case 'PARTIAL': case 'PENDING': return 'text-bg-warning';
            case 'FAILED': case 'CANCELLED': return 'text-bg-danger';
            case 'RUNNING': return 'text-bg-info';
            default: return 'text-bg-secondary';
        }
    }

    function statusLabel(status) {
        switch (String(status || '').toUpperCase()) {
            case 'PENDING': return m('queued');
            case 'RUNNING': return m('running');
            case 'SUCCESS': return m('successful');
            case 'PARTIAL': return m('partial');
            case 'FAILED': return m('failed');
            case 'CANCELLED': return m('cancelled');
            default: return String(status || '—');
        }
    }

    function announceTerminal(job) {
        if (job.status === 'SUCCESS') announce(m('jobCompleted'));
        else if (job.status === 'PARTIAL') announce(m('jobPartial'));
        else if (job.status === 'FAILED') announce(m('jobFailed'));
    }

    function announce(message) {
        const status = document.getElementById('portfolioStatus');
        if (status) status.textContent = message;
        const info = document.getElementById('portfolioInfo');
        if (info) {
            info.textContent = message;
            info.classList.remove('d-none');
        }
    }

    function replacePrematureCompletionMessage() {
        const info = document.getElementById('portfolioInfo');
        if (!info) return;
        const active = Array.from(jobs.values()).some(function (entry) {
            return !terminalStatuses.has(entry.job.status);
        });
        if (active && /(?:finished|abgeschlossen).*0/i.test(info.textContent || '')) {
            info.textContent = m('analysisStarted');
        }
    }

    function refreshCurrentProject(projectId) {
        const storedProject = Number(window.localStorage.getItem('taxonomy.portfolio.projectId')) || null;
        if (projectId && storedProject && projectId !== storedProject) return;
        const refresh = document.getElementById('refreshPortfolioBtn');
        if (refresh && !refresh.disabled) refresh.click();
    }

    function installGuidedReviewHandlers() {
        document.addEventListener('click', function (event) {
            const mapping = event.target.closest('.mapping-review');
            const requirementLink = event.target.closest('.confirm-requirement-link');
            const solutionCoverage = event.target.closest('.add-solution-coverage');
            const productCoverage = event.target.closest('.add-product-coverage');
            const conflict = event.target.closest('.conflict-review');
            if (!mapping && !requirementLink && !solutionCoverage && !productCoverage && !conflict) return;
            if (event.target.disabled) return;
            event.preventDefault();
            event.stopImmediatePropagation();
            if (mapping) reviewMapping(mapping);
            else if (requirementLink) confirmRequirementLink(requirementLink);
            else if (solutionCoverage) addSolutionCoverage(solutionCoverage);
            else if (productCoverage) addProductCoverage(productCoverage);
            else reviewConflict(conflict);
        }, true);
    }

    async function reviewMapping(button) {
        const mappingId = Number(button.dataset.mappingId);
        const action = document.querySelector(
            '.mapping-action-select[data-mapping-id="' + mappingId + '"]').value;
        const decision = await showDecisionDialog({
            title: m('evidenceTitle'),
            evidenceRequired: action !== 'UNDECIDED'
        });
        if (!decision) return;
        await guardedRequest(button,
            '/api/projects/' + currentProjectId()
                + '/analysis-mappings/elements/' + mappingId,
            'PATCH', {
                reviewStatus: 'CONFIRMED',
                actionStatus: action,
                actionEvidence: decision.evidence || null,
                comment: decision.comment || null
            });
    }

    async function confirmRequirementLink(button) {
        const decision = await showDecisionDialog({
            title: m('evidenceTitle'), evidenceRequired: true
        });
        if (!decision) return;
        await guardedRequest(button,
            '/api/projects/' + currentProjectId() + '/solutions/'
                + Number(button.dataset.projectSolutionId) + '/requirements',
            'POST', {
                requirementId: Number(button.dataset.requirementId),
                snapshotId: button.dataset.snapshotId || null,
                coveragePercent: Number(button.dataset.coverage),
                role: 'USES',
                reviewStatus: 'CONFIRMED',
                evidence: decision.evidence
            });
    }

    async function addSolutionCoverage(button) {
        const projectSolutionId = Number(button.dataset.projectSolutionId);
        const review = document.querySelector(
            '.solution-node-review[data-project-solution-id="' + projectSolutionId + '"]').value;
        const decision = await showDecisionDialog({
            title: m('evidenceTitle'), evidenceRequired: review === 'CONFIRMED'
        });
        if (!decision) return;
        await guardedRequest(button,
            '/api/solutions/' + Number(button.dataset.solutionId) + '/taxonomy-coverage',
            'POST', {
                nodeCode: document.querySelector(
                    '.solution-node-code[data-project-solution-id="' + projectSolutionId + '"]').value,
                coveragePercent: Number(document.querySelector(
                    '.solution-node-coverage[data-project-solution-id="' + projectSolutionId + '"]').value),
                evidence: decision.evidence || null,
                reviewStatus: review
            });
    }

    async function addProductCoverage(button) {
        const productId = Number(button.dataset.productId);
        const review = document.querySelector(
            '.product-node-review[data-product-id="' + productId + '"]').value;
        const decision = await showDecisionDialog({
            title: m('evidenceTitle'), evidenceRequired: review === 'CONFIRMED'
        });
        if (!decision) return;
        await guardedRequest(button,
            '/api/products/' + productId + '/taxonomy-coverage',
            'POST', {
                nodeCode: document.querySelector(
                    '.product-node-code[data-product-id="' + productId + '"]').value,
                coveragePercent: Number(document.querySelector(
                    '.product-node-coverage[data-product-id="' + productId + '"]').value),
                evidence: decision.evidence || null,
                reviewStatus: review
            });
    }

    async function reviewConflict(button) {
        const status = button.dataset.status;
        const decision = await showDecisionDialog({
            title: status === 'RESOLVED' ? m('resolutionTitle') : m('evidenceTitle'),
            evidenceLabel: status === 'RESOLVED' ? m('resolutionNote') : m('evidence'),
            evidenceRequired: status === 'RESOLVED'
        });
        if (!decision) return;
        await guardedRequest(button,
            '/api/projects/' + currentProjectId() + '/conflicts/' + button.dataset.conflictId,
            'PATCH', {
                status: status,
                resolutionNote: decision.evidence || decision.comment || null
            });
    }

    async function guardedRequest(button, path, method, body) {
        button.disabled = true;
        try {
            const response = await originalFetch(path, {
                method: method,
                headers: jsonHeaders(),
                credentials: 'same-origin',
                body: JSON.stringify(body)
            });
            if (!response.ok) throw await responseError(response);
            announce(locale === 'de' ? 'Entscheidung wurde gespeichert.' : 'Decision saved.');
            refreshCurrentProject(currentProjectId());
            window.setTimeout(function () {
                const selected = document.querySelector('.snapshot-select.active');
                if (selected) selected.click();
            }, 800);
        } catch (error) {
            showError(error);
        } finally {
            button.disabled = false;
        }
    }

    function ensureDecisionDialog() {
        if (document.getElementById('portfolioDecisionDialog')) return;
        const dialog = document.createElement('div');
        dialog.className = 'modal fade';
        dialog.id = 'portfolioDecisionDialog';
        dialog.tabIndex = -1;
        dialog.setAttribute('aria-labelledby', 'portfolioDecisionDialogTitle');
        dialog.setAttribute('aria-hidden', 'true');
        dialog.innerHTML = '<div class="modal-dialog"><form class="modal-content" id="portfolioDecisionForm">'
            + '<div class="modal-header"><h2 class="modal-title fs-5" id="portfolioDecisionDialogTitle"></h2>'
            + '<button type="button" class="btn-close" data-bs-dismiss="modal"></button></div>'
            + '<div class="modal-body"><div class="mb-3"><label class="form-label" '
            + 'for="portfolioDecisionEvidence" id="portfolioDecisionEvidenceLabel"></label>'
            + '<textarea id="portfolioDecisionEvidence" class="form-control" rows="4" maxlength="2000"></textarea>'
            + '<div class="form-text" id="portfolioDecisionHelp"></div>'
            + '<div class="invalid-feedback" id="portfolioDecisionEvidenceError"></div></div>'
            + '<div><label class="form-label" for="portfolioDecisionComment"></label>'
            + '<textarea id="portfolioDecisionComment" class="form-control" rows="2" maxlength="2000"></textarea></div>'
            + '</div><div class="modal-footer"><button type="button" class="btn btn-outline-secondary" '
            + 'data-bs-dismiss="modal" id="portfolioDecisionCancel"></button>'
            + '<button type="submit" class="btn btn-primary" id="portfolioDecisionSave"></button></div>'
            + '</form></div>';
        document.body.appendChild(dialog);
        dialog.querySelector('.btn-close').setAttribute('aria-label', m('close'));
        dialog.querySelector('label[for="portfolioDecisionComment"]').textContent = m('comment');
        dialog.querySelector('#portfolioDecisionCancel').textContent = m('cancel');
        dialog.querySelector('#portfolioDecisionSave').textContent = m('save');
        dialog.querySelector('#portfolioDecisionHelp').textContent = m('evidenceHelp');
        dialog.querySelector('#portfolioDecisionForm').addEventListener('submit', function (event) {
            event.preventDefault();
            const evidence = dialog.querySelector('#portfolioDecisionEvidence');
            const required = dialog.dataset.evidenceRequired === 'true';
            if (required && !evidence.value.trim()) {
                evidence.classList.add('is-invalid');
                dialog.querySelector('#portfolioDecisionEvidenceError').textContent = m('evidenceRequired');
                evidence.focus();
                return;
            }
            evidence.classList.remove('is-invalid');
            const result = {
                evidence: evidence.value.trim(),
                comment: dialog.querySelector('#portfolioDecisionComment').value.trim()
            };
            const resolver = dialogResolve;
            dialogResolve = null;
            window.bootstrap.Modal.getOrCreateInstance(dialog).hide();
            if (resolver) resolver(result);
        });
        dialog.addEventListener('hidden.bs.modal', function () {
            if (dialogResolve) {
                const resolver = dialogResolve;
                dialogResolve = null;
                resolver(null);
            }
        });
    }

    function showDecisionDialog(options) {
        ensureDecisionDialog();
        const dialog = document.getElementById('portfolioDecisionDialog');
        dialog.dataset.evidenceRequired = String(Boolean(options.evidenceRequired));
        dialog.querySelector('#portfolioDecisionDialogTitle').textContent = options.title || m('evidenceTitle');
        dialog.querySelector('#portfolioDecisionEvidenceLabel').textContent = options.evidenceLabel || m('evidence');
        dialog.querySelector('#portfolioDecisionEvidence').value = '';
        dialog.querySelector('#portfolioDecisionComment').value = '';
        dialog.querySelector('#portfolioDecisionEvidence').classList.remove('is-invalid');
        return new Promise(function (resolve) {
            dialogResolve = resolve;
            const modal = window.bootstrap.Modal.getOrCreateInstance(dialog);
            modal.show();
            dialog.addEventListener('shown.bs.modal', function focusEvidence() {
                dialog.removeEventListener('shown.bs.modal', focusEvidence);
                dialog.querySelector('#portfolioDecisionEvidence').focus();
            });
        });
    }

    function jsonHeaders() {
        const headers = { Accept: 'application/json', 'Content-Type': 'application/json' };
        const token = document.querySelector('meta[name="_csrf"]')?.content;
        const name = document.querySelector('meta[name="_csrf_header"]')?.content || 'X-CSRF-TOKEN';
        if (token) headers[name] = token;
        return headers;
    }

    async function responseError(response) {
        const type = response.headers.get('content-type') || '';
        const payload = type.includes('json')
            ? await response.json().catch(function () { return null; })
            : await response.text().catch(function () { return ''; });
        const detail = payload && typeof payload === 'object'
            ? (payload.detail || payload.message || payload.title || payload.error)
            : payload;
        return new Error(detail || m('requestFailed') + ' HTTP ' + response.status);
    }

    function showError(error) {
        const target = document.getElementById('portfolioError');
        const message = error && error.message ? error.message : m('unknownError');
        if (target) {
            target.textContent = message;
            target.classList.remove('d-none');
            target.tabIndex = -1;
            target.focus();
        }
        const alert = document.getElementById('portfolioAlert');
        if (alert) alert.textContent = message;
    }

    function currentProjectId() {
        return Number(window.localStorage.getItem('taxonomy.portfolio.projectId')) || null;
    }

    function isAnalysisSubmission(url) {
        return url.origin === window.location.origin
            && url.pathname.startsWith('/api/projects/')
            && (url.pathname.endsWith('/analyses') || url.pathname.endsWith('/retry-failed'));
    }

    function resolveUrl(input) {
        const value = input instanceof Request ? input.url : String(input);
        return new URL(value, window.location.href);
    }

    function formatDate(value) {
        if (!value) return '—';
        const date = new Date(value);
        return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString(locale);
    }

    function escapeHtml(value) {
        return String(value == null ? '' : value)
            .replaceAll('&', '&amp;')
            .replaceAll('<', '&lt;')
            .replaceAll('>', '&gt;')
            .replaceAll('"', '&quot;')
            .replaceAll("'", '&#39;');
    }
})();
