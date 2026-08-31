(function () {
    'use strict';

    const match = window.location.pathname.match(/^\/projects\/(\d+)\/requirements\/(\d+)$/);
    if (!match) return;
    const projectId = Number(match[1]);
    const requirementId = Number(match[2]);
    const locale = (new URLSearchParams(window.location.search).get('lang')
        || document.documentElement.lang || 'en').toLowerCase().startsWith('de') ? 'de' : 'en';
    const state = {
        project: null,
        requirement: null,
        versions: [],
        snapshots: [],
        selectedVersion: null,
        selectedSnapshot: null,
        snapshotDetail: null,
        portfolio: null,
        account: null,
        busy: 0
    };

    const text = {
        en: {
            loading: 'Loading…', portfolio: 'Portfolio', matrices: 'Matrices',
            detail: 'Requirement detail', analyze: 'Analyze current version',
            newVersion: 'New version', open: 'Open decisions', textSource: 'Text & source',
            versions: 'Versions', analyses: 'Analyses', architecture: 'Taxonomy & architecture',
            decisions: 'Decisions', solutions: 'Solutions & products', currentText: 'Current requirement text',
            source: 'Original source fragment', noSource: 'No source fragment is recorded.',
            noVersions: 'No versions are available.', noSnapshots: 'No analysis snapshots are available.',
            noMappings: 'No taxonomy mappings are available.', noRelations: 'No relation mappings are available.',
            noSolutions: 'No solution is currently linked to this requirement.',
            versionCreated: 'A new immutable version was created.', analysisQueued: 'Analysis was queued.',
            stale: 'The current requirement version has not been analysed.',
            unreviewed: 'Taxonomy mappings still require human review.',
            noConfirmedSolution: 'No confirmed solution covers this requirement.',
            sourceMissing: 'The current version has no source provenance.',
            allClear: 'No open decisions were derived from the current data.',
            provider: 'Provider', model: 'Model', created: 'Created', duration: 'Duration',
            baseline: 'Baseline', score: 'Score', relevance: 'Relevance', confidence: 'Confidence',
            origin: 'Origin', review: 'Review', action: 'Action', evidence: 'Evidence',
            node: 'Taxonomy node', relation: 'Relation', changeReason: 'Change reason',
            author: 'Author', page: 'Page', section: 'Section', sourceArtifact: 'Source artifact',
            snapshot: 'Snapshot', productCandidates: 'Product candidates', coverage: 'Coverage',
            decisionReport: 'Decision rationale report', reportDocx: 'Word (.docx)',
            reportHtml: 'HTML', reportJson: 'JSON',
            selected: 'Selected', retry: 'Retry failed analysis', comparePrevious: 'Compare with previous version',
            added: 'Added', removed: 'Removed', unchanged: 'Unchanged', permission: 'Your role cannot change this requirement.',
            failed: 'The operation failed.', resultOverview: 'Copilot result overview',
            mappedElements: 'Mapped elements', mappedRelations: 'Mapped relationships',
            openGaps: 'Open gaps', patternCoverage: 'Pattern coverage',
            recommendationConfidence: 'Recommendation confidence', confirmedElements: 'Confirmed elements',
            gapAnalysis: 'Gap analysis', anchorsEvaluated: 'Anchors evaluated',
            missingRelations: 'Missing relationships', coverageGaps: 'Coverage gaps',
            incompletePatterns: 'Incomplete patterns', highestPriority: 'Highest-priority findings',
            patternDetection: 'Detected patterns', matchedPatterns: 'Matched patterns',
            recommendation: 'Recommendation', proposedElements: 'Proposed elements',
            suggestedRelations: 'Suggested relationships', reasoning: 'Reasoning',
            technicalData: 'Technical snapshot data',
            technicalDataHint: 'Condensed provider-neutral diagnostics. Use the JSON report for the complete payload.',
            openWorkbench: 'Inspect diagram in architecture workbench', direct: 'Direct',
            resultReady: 'The immutable Copilot result is ready for review.'
        },
        de: {
            loading: 'Wird geladen…', portfolio: 'Portfolio', matrices: 'Matrizen',
            detail: 'Anforderungsdetails', analyze: 'Aktuelle Version analysieren',
            newVersion: 'Neue Version', open: 'Offene Entscheidungen', textSource: 'Text & Quelle',
            versions: 'Versionen', analyses: 'Analysen', architecture: 'Taxonomie & Architektur',
            decisions: 'Entscheidungen', solutions: 'Lösungen & Produkte', currentText: 'Aktueller Anforderungstext',
            source: 'Ursprüngliches Quellfragment', noSource: 'Es ist kein Quellfragment hinterlegt.',
            noVersions: 'Es sind keine Versionen verfügbar.', noSnapshots: 'Es sind keine Analyse-Snapshots verfügbar.',
            noMappings: 'Es sind keine Taxonomiezuordnungen verfügbar.', noRelations: 'Es sind keine Beziehungszuordnungen verfügbar.',
            noSolutions: 'Dieser Anforderung ist derzeit keine Lösung zugeordnet.',
            versionCreated: 'Eine neue unveränderliche Version wurde angelegt.', analysisQueued: 'Die Analyse wurde eingereiht.',
            stale: 'Die aktuelle Anforderungsversion wurde noch nicht analysiert.',
            unreviewed: 'Taxonomiezuordnungen benötigen noch eine menschliche Prüfung.',
            noConfirmedSolution: 'Keine bestätigte Lösung deckt diese Anforderung ab.',
            sourceMissing: 'Für die aktuelle Version fehlt die Quellenprovenienz.',
            allClear: 'Aus den aktuellen Daten wurden keine offenen Entscheidungen abgeleitet.',
            provider: 'Provider', model: 'Modell', created: 'Erstellt', duration: 'Laufzeit',
            baseline: 'Baseline', score: 'Score', relevance: 'Relevanz', confidence: 'Konfidenz',
            origin: 'Herkunft', review: 'Prüfung', action: 'Maßnahme', evidence: 'Evidenz',
            node: 'Taxonomieknoten', relation: 'Beziehung', changeReason: 'Änderungsgrund',
            author: 'Autor', page: 'Seite', section: 'Abschnitt', sourceArtifact: 'Quellartefakt',
            snapshot: 'Snapshot', productCandidates: 'Produktkandidaten', coverage: 'Abdeckung',
            decisionReport: 'Hierarchischer Entscheidungsbericht', reportDocx: 'Word (.docx)',
            reportHtml: 'HTML', reportJson: 'JSON',
            selected: 'Ausgewählt', retry: 'Fehlgeschlagene Analyse wiederholen', comparePrevious: 'Mit vorheriger Version vergleichen',
            added: 'Hinzugefügt', removed: 'Entfernt', unchanged: 'Unverändert', permission: 'Ihre Rolle darf diese Anforderung nicht ändern.',
            failed: 'Die Operation ist fehlgeschlagen.', resultOverview: 'Copilot-Ergebnisübersicht',
            mappedElements: 'Zugeordnete Elemente', mappedRelations: 'Zugeordnete Beziehungen',
            openGaps: 'Offene Lücken', patternCoverage: 'Musterabdeckung',
            recommendationConfidence: 'Konfidenz der Empfehlung', confirmedElements: 'Bestätigte Elemente',
            gapAnalysis: 'Lückenanalyse', anchorsEvaluated: 'Bewertete Anker',
            missingRelations: 'Fehlende Beziehungen', coverageGaps: 'Abdeckungslücken',
            incompletePatterns: 'Unvollständige Muster', highestPriority: 'Wichtigste Befunde',
            patternDetection: 'Erkannte Muster', matchedPatterns: 'Erfüllte Muster',
            recommendation: 'Empfehlung', proposedElements: 'Vorgeschlagene Elemente',
            suggestedRelations: 'Vorgeschlagene Beziehungen', reasoning: 'Begründung',
            technicalData: 'Technische Snapshot-Daten',
            technicalDataHint: 'Verdichtete anbieterneutrale Diagnosedaten. Der JSON-Bericht enthält die vollständigen Nutzdaten.',
            openWorkbench: 'Diagramm in der Architektur-Workbench untersuchen', direct: 'Direkt',
            resultReady: 'Das unveränderliche Copilot-Ergebnis ist zur Prüfung bereit.'
        }
    };

    document.addEventListener('DOMContentLoaded', initialize);

    function t(key) { return (text[locale] && text[locale][key]) || text.en[key] || key; }

    async function initialize() {
        translateSurface();
        wireEvents();
        await loadAll();
        const requestedSnapshot = new URLSearchParams(window.location.search).get('snapshot');
        if (requestedSnapshot) await selectSnapshot(requestedSnapshot);
        else if (state.snapshots.length) await selectSnapshot(state.snapshots[0].id);
    }

    function translateSurface() {
        document.documentElement.lang = locale;
        document.title = t('detail') + ' — Taxonomy';
        document.querySelector('.skip-link').textContent = locale === 'de'
            ? 'Zu den Anforderungsdetails springen' : 'Skip to requirement detail';
        document.getElementById('pageTitle').textContent = t('detail');
        document.getElementById('portfolioBack').textContent = t('portfolio');
        document.getElementById('matrixLink').textContent = t('matrices');
        document.getElementById('reanalyzeRequirement').textContent = t('analyze');
        document.getElementById('newVersionButton').textContent = t('newVersion');
        document.getElementById('tasksHeading').textContent = t('open');
        setTabText('text-tab', t('textSource'));
        setTabText('versions-tab', t('versions'));
        setTabText('analyses-tab', t('analyses'));
        setTabText('architecture-tab', t('architecture'));
        setTabText('decisions-tab', t('decisions'));
        setTabText('solutions-tab', t('solutions'));
        document.querySelector('#textPane h2').textContent = t('currentText');
        document.querySelectorAll('#textPane h2')[1].textContent = t('source');
    }

    function setTabText(id, value) { document.getElementById(id).textContent = value; }

    function wireEvents() {
        document.getElementById('newVersionForm').addEventListener('submit', createVersion);
        document.getElementById('reanalyzeRequirement').addEventListener('click', analyzeRequirement);
        document.getElementById('versionList').addEventListener('click', event => {
            const button = event.target.closest('[data-version-id]');
            if (button) selectVersion(Number(button.dataset.versionId));
        });
        document.getElementById('snapshotList').addEventListener('click', event => {
            const button = event.target.closest('[data-snapshot-id]');
            if (button) selectSnapshot(button.dataset.snapshotId);
        });
        document.getElementById('snapshotDetail').addEventListener('click', event => {
            const button = event.target.closest('[data-decision-report-format]');
            if (button) downloadDecisionReport(button.dataset.decisionReportFormat);
        });
    }

    async function loadAll() {
        setBusy(true);
        try {
            const [project, requirement, versions, snapshots, portfolio, account] = await Promise.all([
                api().getProject(projectId),
                api().getRequirement(projectId, requirementId),
                api().listRequirementVersions(projectId, requirementId),
                api().listRequirementSnapshots(projectId, requirementId),
                api().getProjectPortfolio(projectId),
                api().getAccount()
            ]);
            Object.assign(state, { project, requirement, versions, snapshots, portfolio, account });
            state.selectedVersion = requirement.currentVersion || versions[0] || null;
            renderAll();
        } catch (error) {
            showError(error);
        } finally {
            setBusy(false);
        }
    }

    function renderAll() {
        const requirement = state.requirement;
        document.getElementById('portfolioBack').href = `/projects?lang=${locale}`;
        document.getElementById('matrixLink').href = `/projects/${projectId}/matrices?lang=${locale}`;
        document.getElementById('requirementKey').textContent = requirement.requirementKey;
        document.getElementById('requirementStatus').textContent = humanize(requirement.status);
        document.getElementById('requirementReview').textContent = humanize(requirement.reviewStatus);
        document.getElementById('requirementHeading').textContent = requirement.title;
        document.getElementById('requirementMeta').textContent = [
            state.project.projectKey + ' — ' + state.project.title,
            humanize(requirement.requirementType),
            humanize(requirement.criticality),
            requirement.ownerUsername || '—'
        ].join(' · ');
        renderCurrentText();
        renderVersions();
        renderSnapshots();
        renderTasks();
        renderSolutions();
        applyCapabilities();
    }

    function renderCurrentText() {
        const version = state.requirement.currentVersion;
        document.getElementById('currentText').textContent = version ? version.text : '—';
        document.getElementById('versionText').value = version ? version.text : '';
        const source = version && version.source;
        const metadata = document.getElementById('sourceMetadata');
        metadata.textContent = '';
        if (!source) {
            document.getElementById('sourceText').textContent = t('noSource');
            return;
        }
        addDefinition(metadata, t('sourceArtifact'), source.sourceArtifactId || '—');
        addDefinition(metadata, t('section'), source.sectionReference || '—');
        addDefinition(metadata, t('page'), source.pageNumber || '—');
        document.getElementById('sourceText').textContent = source.originalText || t('noSource');
        document.getElementById('sourceSection').value = source.sectionReference || '';
        document.getElementById('sourcePage').value = source.pageNumber || '';
        document.getElementById('sourceOriginal').value = source.originalText || '';
    }

    function renderVersions() {
        const list = document.getElementById('versionList');
        list.textContent = '';
        if (!state.versions.length) return renderEmpty(list, t('noVersions'));
        state.versions.forEach(version => {
            const button = document.createElement('button');
            button.type = 'button';
            button.className = 'list-group-item list-group-item-action';
            button.dataset.versionId = version.id;
            button.innerHTML = `<div class="d-flex justify-content-between gap-2"><strong>v${version.versionNumber}</strong>`
                + `<span class="small">${escapeHtml(formatDate(version.createdAt))}</span></div>`
                + `<div class="small text-body-secondary">${escapeHtml(version.changeReason || '—')}</div>`;
            list.appendChild(button);
        });
        renderVersionDetail(state.selectedVersion);
    }

    function selectVersion(id) {
        state.selectedVersion = state.versions.find(version => version.id === id) || null;
        renderVersionDetail(state.selectedVersion);
    }

    function renderVersionDetail(version) {
        const target = document.getElementById('versionDetail');
        if (!version) return renderEmpty(target, t('noVersions'));
        const index = state.versions.findIndex(candidate => candidate.id === version.id);
        const previous = index >= 0 ? state.versions[index + 1] : null;
        target.innerHTML = `<div class="d-flex justify-content-between gap-2"><h2 class="h5">v${version.versionNumber}</h2>`
            + `<code>${escapeHtml(String(version.contentHash || '').slice(0, 16))}</code></div>`
            + `<dl class="portfolio-card-meta"><dt>${escapeHtml(t('author'))}</dt><dd>${escapeHtml(version.createdBy || '—')}</dd>`
            + `<dt>${escapeHtml(t('created'))}</dt><dd>${escapeHtml(formatDate(version.createdAt))}</dd>`
            + `<dt>${escapeHtml(t('changeReason'))}</dt><dd>${escapeHtml(version.changeReason || '—')}</dd></dl>`
            + `<pre class="border rounded p-3 bg-body-tertiary text-wrap mt-3">${escapeHtml(version.text)}</pre>`
            + (previous ? `<details class="mt-3"><summary>${escapeHtml(t('comparePrevious'))}</summary>`
                + `<div class="mt-2">${renderTextDiff(previous.text, version.text)}</div></details>` : '');
    }

    function renderTextDiff(older, newer) {
        const oldLines = new Set(String(older || '').split(/\r?\n/));
        const newLines = new Set(String(newer || '').split(/\r?\n/));
        const rows = [];
        oldLines.forEach(line => { if (!newLines.has(line)) rows.push(`<div class="text-danger">− ${escapeHtml(line)}</div>`); });
        newLines.forEach(line => rows.push(oldLines.has(line)
            ? `<div class="text-body-secondary">  ${escapeHtml(line)}</div>`
            : `<div class="text-success">+ ${escapeHtml(line)}</div>`));
        return `<div class="font-monospace small">${rows.join('')}</div>`;
    }

    function renderSnapshots() {
        const list = document.getElementById('snapshotList');
        list.textContent = '';
        if (!state.snapshots.length) {
            renderEmpty(list, t('noSnapshots'));
            renderEmpty(document.getElementById('snapshotDetail'), t('noSnapshots'));
            return;
        }
        state.snapshots.forEach(snapshot => {
            const selected = Boolean(state.selectedSnapshot
                && String(state.selectedSnapshot.id) === String(snapshot.id));
            const button = document.createElement('button');
            button.type = 'button';
            button.className = 'list-group-item list-group-item-action'
                + (selected ? ' active' : '');
            button.dataset.snapshotId = snapshot.id;
            if (selected) button.setAttribute('aria-current', 'true');
            button.innerHTML = `<div class="d-flex justify-content-between gap-2"><strong>v${snapshot.requirementVersionNumber}</strong>`
                + `<span class="badge ${statusClass(snapshot.status)}">${escapeHtml(humanize(snapshot.status))}</span></div>`
                + `<div class="small${selected ? '' : ' text-body-secondary'}">${escapeHtml(formatDate(snapshot.createdAt))}</div>`;
            list.appendChild(button);
        });
    }

    async function selectSnapshot(id) {
        setBusy(true);
        try {
            state.selectedSnapshot = state.snapshots.find(snapshot => String(snapshot.id) === String(id)) || null;
            state.snapshotDetail = await api().getSnapshot(projectId, id);
            const url = new URL(window.location.href);
            url.searchParams.set('snapshot', id);
            url.searchParams.set('lang', locale);
            history.replaceState(null, '', url);
            renderSnapshots();
            renderSnapshotDetail();
            renderArchitecture();
            renderDecisions();
            renderTasks();
        } catch (error) {
            showError(error);
        } finally {
            setBusy(false);
        }
    }

    function renderSnapshotDetail() {
        const target = document.getElementById('snapshotDetail');
        const detail = state.snapshotDetail;
        if (!detail) return renderEmpty(target, t('noSnapshots'));
        const summary = detail.summary;
        target.classList.add('portfolio-snapshot-detail');
        target.innerHTML = `<div class="d-flex flex-wrap justify-content-between gap-2 align-items-start">`
            + `<div><h2 class="h4 mb-1">${escapeHtml(t('snapshot'))} v${summary.requirementVersionNumber}</h2>`
            + `<p class="small text-body-secondary mb-0">${escapeHtml(t('resultReady'))}</p></div>`
            + `<span class="badge ${statusClass(summary.status)} fs-6">${escapeHtml(humanize(summary.status))}</span></div>`
            + `<dl class="portfolio-card-meta portfolio-snapshot-meta mt-3"><dt>${escapeHtml(t('provider'))}</dt><dd>${escapeHtml(summary.provider || '—')}</dd>`
            + `<dt>${escapeHtml(t('model'))}</dt><dd>${escapeHtml(summary.modelName || '—')}</dd>`
            + `<dt>${escapeHtml(t('created'))}</dt><dd>${escapeHtml(formatDate(summary.createdAt))}</dd>`
            + `<dt>${escapeHtml(t('duration'))}</dt><dd>${escapeHtml(formatDuration(summary.durationMs))}</dd>`
            + `<dt>${escapeHtml(t('baseline'))}</dt><dd><code>${escapeHtml(String(summary.taxonomyFingerprint || '').slice(0, 16))}</code> / <code>${escapeHtml(String(summary.promptFingerprint || '').slice(0, 16))}</code></dd></dl>`
            + renderDecisionReportActions(summary)
            + renderAnalysisSummary(detail);
    }

    function renderDecisionReportActions(summary) {
        if (!summary || !summary.id) return '';
        return `<div class="d-flex flex-wrap align-items-center gap-2 mt-3 p-3 border rounded bg-body-tertiary">`
            + `<strong class="me-2">${escapeHtml(t('decisionReport'))}</strong>`
            + `<button type="button" class="btn btn-sm btn-outline-primary" data-decision-report-format="docx">${escapeHtml(t('reportDocx'))}</button>`
            + `<button type="button" class="btn btn-sm btn-outline-primary" data-decision-report-format="html">${escapeHtml(t('reportHtml'))}</button>`
            + `<button type="button" class="btn btn-sm btn-outline-secondary" data-decision-report-format="json">${escapeHtml(t('reportJson'))}</button>`
            + `</div>`;
    }

    async function downloadDecisionReport(format) {
        const snapshot = state.selectedSnapshot;
        if (!snapshot || !snapshot.id || !format) return;
        setBusy(true);
        try {
            const response = await api().downloadDecisionReport(
                projectId, snapshot.id, format, locale);
            if (!response.ok) {
                let detail = `HTTP ${response.status}`;
                try {
                    const problem = await response.json();
                    detail = problem.detail || problem.message || detail;
                } catch (ignored) {
                    // A non-JSON error body still retains the HTTP status.
                }
                throw new Error(detail);
            }
            const blob = await response.blob();
            const disposition = response.headers.get('Content-Disposition') || '';
            const filenameMatch = disposition.match(/filename="?([^";]+)"?/i);
            const filename = filenameMatch ? filenameMatch[1]
                : `taxonomy-decision-rationale-report.${format}`;
            const url = URL.createObjectURL(blob);
            const link = document.createElement('a');
            link.href = url;
            link.download = filename;
            document.body.appendChild(link);
            link.click();
            link.remove();
            URL.revokeObjectURL(url);
        } catch (error) {
            showError(error);
        } finally {
            setBusy(false);
        }
    }

    function renderAnalysisSummary(detail) {
        const analysis = detail.analysis || {};
        const warnings = asArray(analysis.warnings);
        const gap = detail.gapAnalysis || {};
        const patterns = detail.patternDetection || {};
        const recommendation = detail.recommendation || {};
        const mappings = asArray(detail.elementMappings);
        const relations = asArray(detail.relationMappings);
        const missing = asArray(gap.missingRelations);
        const coverage = asArray(gap.coverageGaps);
        const incomplete = asArray(gap.incompletePatterns);
        const matched = asArray(patterns.matchedPatterns);
        const confirmed = asArray(recommendation.confirmedElements);
        const proposed = asArray(recommendation.proposedElements);
        const suggested = asArray(recommendation.suggestedRelations);
        const reasoning = asArray(recommendation.reasoning);
        const gapCount = safeNumber(gap.totalGaps, missing.length);
        const patternPercent = percentage(patterns.patternCoverage);
        const recommendationPercent = percentage(recommendation.confidence);

        const overview = `<section id="snapshotResultOverview" class="portfolio-result-overview mt-3" aria-labelledby="snapshotResultTitle">`
            + `<div class="d-flex flex-wrap justify-content-between gap-2 align-items-center mb-2">`
            + `<h3 id="snapshotResultTitle" class="h5 mb-0">${escapeHtml(t('resultOverview'))}</h3>`
            + `<a class="btn btn-sm btn-outline-success" href="${escapeHtml(architectureWorkbenchUrl())}">${escapeHtml(t('openWorkbench'))}</a></div>`
            + `<div class="portfolio-result-kpis">`
            + resultMetric(t('mappedElements'), mappings.length,
                mappings.filter(item => item.selectedForImpact).length + ' ' + t('direct'), 'primary')
            + resultMetric(t('mappedRelations'), relations.length, '', 'info')
            + resultMetric(t('openGaps'), gapCount,
                missing.length + ' ' + t('missingRelations'), gapCount > 0 ? 'warning' : 'success')
            + resultMetric(t('patternCoverage'), patternPercent + '%',
                matched.length + ' ' + t('matchedPatterns'), patternPercent >= 70 ? 'success' : 'warning')
            + resultMetric(t('recommendationConfidence'), recommendationPercent + '%',
                suggested.length + ' ' + t('suggestedRelations'), recommendationPercent >= 70 ? 'success' : 'warning')
            + resultMetric(t('confirmedElements'), confirmed.length,
                proposed.length + ' ' + t('proposedElements'), 'success')
            + `</div></section>`;

        const warningHtml = warnings.length
            ? `<div class="alert alert-warning mt-3"><ul class="mb-0">${warnings.map(warning => `<li>${escapeHtml(warning)}</li>`).join('')}</ul></div>`
            : '';
        const technical = {
            summary: detail.summary || {},
            analysis: {
                status: analysis.status,
                provider: analysis.provider,
                warnings: warnings,
                discrepancies: asArray(analysis.discrepancies),
                productCoverageGaps: asArray(analysis.productCoverageGaps),
                provisionalRelations: asArray(analysis.provisionalRelations),
                architectureView: compactArchitectureView(analysis.architectureView)
            },
            gapAnalysis: gap,
            patternDetection: patterns,
            recommendation: recommendation
        };
        const technicalHtml = `<details id="technicalSnapshotData" class="portfolio-technical-data mt-3">`
            + `<summary><span>${escapeHtml(t('technicalData'))}</span><small>${escapeHtml(t('technicalDataHint'))}</small></summary>`
            + `<pre>${escapeHtml(JSON.stringify(technical, null, 2))}</pre></details>`;

        return overview + warningHtml
            + `<div class="portfolio-result-grid mt-3">`
            + renderGapResult(gap, missing, coverage, incomplete)
            + renderPatternResult(patterns, matched)
            + renderRecommendationResult(recommendation, confirmed, proposed, suggested, reasoning)
            + `</div>` + technicalHtml;
    }

    function resultMetric(label, value, detail, variant) {
        return `<article class="portfolio-result-kpi border-${escapeHtml(variant || 'secondary')}">`
            + `<div class="portfolio-result-kpi-value">${escapeHtml(String(value))}</div>`
            + `<div class="portfolio-result-kpi-label">${escapeHtml(label)}</div>`
            + (detail ? `<div class="portfolio-result-kpi-detail">${escapeHtml(detail)}</div>` : '')
            + `</article>`;
    }

    function renderGapResult(gap, missing, coverage, incomplete) {
        const topMissing = missing.slice(0, 5);
        const findings = topMissing.length
            ? `<ol class="portfolio-finding-list">${topMissing.map(item => `<li><code>${escapeHtml(item.sourceNodeCode || '—')}</code> `
                + `<strong>${escapeHtml(humanize(item.expectedRelationType || 'RELATED_TO'))}</strong> `
                + `→ <span>${escapeHtml(item.expectedTargetRoot || '—')}</span>`
                + `<small>${escapeHtml(item.description || '')}</small></li>`).join('')}</ol>`
            : `<p class="text-body-secondary mb-0">—</p>`;
        return `<section id="gapResultSection" class="portfolio-result-panel" aria-labelledby="gapResultTitle">`
            + `<div class="portfolio-result-panel-header"><h3 id="gapResultTitle" class="h6 mb-0">${escapeHtml(t('gapAnalysis'))}</h3>`
            + `<div class="d-flex flex-wrap gap-1"><span class="badge text-bg-secondary">${safeNumber(gap.totalAnchors, 0)} ${escapeHtml(t('anchorsEvaluated'))}</span>`
            + `<span class="badge ${safeNumber(gap.totalGaps, 0) > 0 ? 'text-bg-warning' : 'text-bg-success'}">${safeNumber(gap.totalGaps, 0)} ${escapeHtml(t('openGaps'))}</span></div></div>`
            + `<h4 class="small fw-semibold mt-3">${escapeHtml(t('highestPriority'))}</h4>${findings}`
            + renderFindingDetails(t('missingRelations'), missing,
                ['sourceNodeCode', 'expectedRelationType', 'expectedTargetRoot', 'description'])
            + renderFindingDetails(t('coverageGaps'), coverage,
                ['nodeCode', 'coverageScore', 'gapDescription'])
            + renderFindingDetails(t('incompletePatterns'), incomplete,
                ['nodeCode', 'patternDescription', 'missingElement'])
            + `</section>`;
    }

    function renderPatternResult(patterns, matched) {
        const cards = matched.length ? matched.map(pattern => {
            const completeness = percentage(pattern.completeness);
            const expected = asArray(pattern.expectedSteps);
            const present = new Set(asArray(pattern.presentSteps));
            return `<article class="portfolio-pattern-card"><div class="d-flex justify-content-between gap-2 align-items-center">`
                + `<strong>${escapeHtml(pattern.patternName || t('patternDetection'))}</strong>`
                + `<span class="badge ${completeness >= 100 ? 'text-bg-success' : 'text-bg-warning'}">${completeness}%</span></div>`
                + `<div class="progress mt-2" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${completeness}">`
                + `<div class="progress-bar" style="width:${completeness}%"></div></div>`
                + `<ul class="portfolio-step-list">${expected.map(step => `<li class="${present.has(step) ? 'is-present' : 'is-missing'}">`
                    + `<span aria-hidden="true">${present.has(step) ? '✓' : '○'}</span>${escapeHtml(step)}</li>`).join('')}</ul></article>`;
        }).join('') : `<p class="text-body-secondary mb-0">—</p>`;
        return `<section id="patternResultSection" class="portfolio-result-panel" aria-labelledby="patternResultTitle">`
            + `<div class="portfolio-result-panel-header"><h3 id="patternResultTitle" class="h6 mb-0">${escapeHtml(t('patternDetection'))}</h3>`
            + `<span class="badge text-bg-success">${percentage(patterns.patternCoverage)}%</span></div>`
            + `<div class="vstack gap-2 mt-3">${cards}</div></section>`;
    }

    function renderRecommendationResult(recommendation, confirmed, proposed, suggested, reasoning) {
        const reasoningHtml = reasoning.length
            ? `<ul class="portfolio-reasoning-list">${reasoning.map(item => `<li>${escapeHtml(item)}</li>`).join('')}</ul>`
            : `<p class="text-body-secondary mb-0">—</p>`;
        const relationDetails = suggested.length
            ? `<details class="portfolio-finding-details"><summary>${escapeHtml(t('suggestedRelations'))} <span class="badge text-bg-secondary">${suggested.length}</span></summary>`
                + `<div class="portfolio-result-table portfolio-result-table-sm"><table class="table table-sm align-middle mb-0"><thead><tr>`
                + `<th>${escapeHtml(t('relation'))}</th><th>${escapeHtml(t('reasoning'))}</th></tr></thead><tbody>`
                + suggested.map(item => `<tr><td><code>${escapeHtml(item.sourceCode || '—')}</code> → <code>${escapeHtml(item.targetCode || '—')}</code>`
                    + `<div class="small fw-semibold">${escapeHtml(humanize(item.relationType || 'RELATED_TO'))}</div></td>`
                    + `<td>${escapeHtml(item.reasoning || '—')}</td></tr>`).join('')
                + `</tbody></table></div></details>`
            : '';
        return `<section id="recommendationResultSection" class="portfolio-result-panel portfolio-result-panel-wide" aria-labelledby="recommendationResultTitle">`
            + `<div class="portfolio-result-panel-header"><h3 id="recommendationResultTitle" class="h6 mb-0">${escapeHtml(t('recommendation'))}</h3>`
            + `<span class="badge ${percentage(recommendation.confidence) >= 70 ? 'text-bg-success' : 'text-bg-warning'}">${percentage(recommendation.confidence)}%</span></div>`
            + `<div class="d-flex flex-wrap gap-2 mt-3">`
            + `<span class="badge text-bg-success">${confirmed.length} ${escapeHtml(t('confirmedElements'))}</span>`
            + `<span class="badge text-bg-warning">${proposed.length} ${escapeHtml(t('proposedElements'))}</span>`
            + `<span class="badge text-bg-info">${suggested.length} ${escapeHtml(t('suggestedRelations'))}</span></div>`
            + `<h4 class="small fw-semibold mt-3">${escapeHtml(t('reasoning'))}</h4>${reasoningHtml}${relationDetails}</section>`;
    }

    function renderFindingDetails(label, items, fields) {
        if (!items.length) return '';
        return `<details class="portfolio-finding-details"><summary>${escapeHtml(label)} <span class="badge text-bg-secondary">${items.length}</span></summary>`
            + `<div class="portfolio-result-table portfolio-result-table-sm"><table class="table table-sm align-middle mb-0"><tbody>`
            + items.map(item => `<tr>${fields.map(field => `<td>${escapeHtml(formatFindingValue(field, item[field]))}</td>`).join('')}</tr>`).join('')
            + `</tbody></table></div></details>`;
    }

    function formatFindingValue(field, value) {
        if (value === null || value === undefined || value === '') return '—';
        if (field.toLowerCase().includes('score')) return percentage(value) + '%';
        return String(value);
    }

    function compactArchitectureView(view) {
        if (!view) return null;
        return {
            viewTitle: view.viewTitle,
            viewDescription: view.viewDescription,
            totalAnchors: view.totalAnchors,
            totalElements: view.totalElements,
            totalRelationships: view.totalRelationships,
            maxHopDistance: view.maxHopDistance,
            activeRules: view.activeRules,
            notes: view.notes
        };
    }

    function asArray(value) { return Array.isArray(value) ? value : []; }

    function safeNumber(value, fallback) {
        const parsed = Number(value);
        return Number.isFinite(parsed) ? parsed : (fallback ?? 0);
    }

    function percentage(value) {
        const parsed = safeNumber(value, 0);
        const normalized = Math.abs(parsed) <= 1 && parsed !== 0 ? parsed * 100 : parsed;
        return Math.max(0, Math.min(100, Math.round(normalized)));
    }

    function formatDuration(value) {
        const milliseconds = safeNumber(value, 0);
        if (milliseconds < 1000) return Math.round(milliseconds) + ' ms';
        if (milliseconds < 60000) return (milliseconds / 1000).toFixed(1) + ' s';
        return (milliseconds / 60000).toFixed(1) + ' min';
    }

    function architectureWorkbenchUrl() {
        return window.location.pathname.replace(/\/$/, '')
            + '/architecture?lang=' + encodeURIComponent(locale);
    }

    function renderArchitecture() {
        const detail = state.snapshotDetail;
        const mappings = detail && detail.elementMappings || [];
        const relations = detail && detail.relationMappings || [];
        const summary = document.getElementById('architectureSummary');
        summary.innerHTML = `<div class="row row-cols-2 row-cols-md-4 g-2">`
            + metric(t('node'), mappings.length) + metric(t('relation'), relations.length)
            + metric(t('review'), mappings.filter(m => m.reviewStatus === 'CONFIRMED').length)
            + metric(t('open'), mappings.filter(m => m.reviewStatus === 'PROPOSED').length) + '</div>';
        renderMappings(mappings);
        renderRelations(relations);
    }

    function metric(label, value) {
        return `<div><div class="card h-100"><div class="card-body"><div class="h3">${value}</div><div class="small text-body-secondary">${escapeHtml(label)}</div></div></div></div>`;
    }

    function renderMappings(mappings) {
        const target = document.getElementById('mappingTable');
        if (!mappings.length) return renderEmpty(target, t('noMappings'));
        target.innerHTML = `<table class="table table-sm align-middle"><caption>${escapeHtml(t('architecture'))}</caption>`
            + `<thead><tr><th>${escapeHtml(t('node'))}</th><th>${escapeHtml(t('score'))}</th><th>${escapeHtml(t('relevance'))}</th>`
            + `<th>${escapeHtml(t('confidence'))}</th><th>${escapeHtml(t('origin'))}</th><th>${escapeHtml(t('review'))}</th><th>${escapeHtml(t('action'))}</th></tr></thead>`
            + `<tbody>${mappings.map(mapping => `<tr><td><code>${escapeHtml(mapping.nodeCode)}</code><div class="small">${escapeHtml(mapping.nodeTitle || '')}</div><div class="small text-body-secondary">${escapeHtml(mapping.hierarchyPath || '')}</div></td>`
                + `<td>${mapping.directScore}%</td><td>${Math.round(mapping.relevance * 100)}%</td><td>${Math.round(mapping.confidence * 100)}%</td>`
                + `<td>${escapeHtml(humanize(mapping.mappingOrigin))}</td><td>${escapeHtml(humanize(mapping.reviewStatus))}</td><td>${escapeHtml(humanize(mapping.actionStatus))}</td></tr>`).join('')}</tbody></table>`;
    }

    function renderRelations(relations) {
        const target = document.getElementById('relationTable');
        if (!relations.length) return renderEmpty(target, t('noRelations'));
        target.innerHTML = `<table class="table table-sm align-middle"><caption>${escapeHtml(t('relation'))}</caption>`
            + `<thead><tr><th>${escapeHtml(t('relation'))}</th><th>${escapeHtml(t('relevance'))}</th><th>${escapeHtml(t('confidence'))}</th><th>${escapeHtml(t('review'))}</th></tr></thead>`
            + `<tbody>${relations.map(relation => `<tr><td><code>${escapeHtml(relation.sourceCode)}</code> → <code>${escapeHtml(relation.targetCode)}</code><div class="small">${escapeHtml(relation.relationType)}</div></td>`
                + `<td>${Math.round(relation.relevance * 100)}%</td><td>${Math.round(relation.confidence * 100)}%</td><td>${escapeHtml(humanize(relation.reviewStatus))}</td></tr>`).join('')}</tbody></table>`;
    }

    function renderDecisions() {
        const target = document.getElementById('decisionList');
        target.textContent = '';
        const mappings = state.snapshotDetail && state.snapshotDetail.elementMappings || [];
        if (!mappings.length) return renderEmpty(target, t('noMappings'));
        mappings.forEach(mapping => {
            const card = document.createElement('article');
            card.className = 'card';
            card.innerHTML = `<div class="card-body"><div class="d-flex justify-content-between gap-2"><div><code>${escapeHtml(mapping.nodeCode)}</code> · <strong>${escapeHtml(mapping.nodeTitle || '')}</strong></div>`
                + `<span class="badge ${mapping.reviewStatus === 'CONFIRMED' ? 'text-bg-success' : 'text-bg-warning'}">${escapeHtml(humanize(mapping.reviewStatus))}</span></div>`
                + `<dl class="portfolio-card-meta mt-2"><dt>${escapeHtml(t('action'))}</dt><dd>${escapeHtml(humanize(mapping.actionStatus))}</dd>`
                + `<dt>${escapeHtml(t('evidence'))}</dt><dd>${escapeHtml(mapping.actionEvidence || '—')}</dd>`
                + `<dt>${escapeHtml(t('author'))}</dt><dd>${escapeHtml(mapping.decisionBy || '—')}</dd>`
                + `<dt>${escapeHtml(t('created'))}</dt><dd>${escapeHtml(formatDate(mapping.decisionAt))}</dd></dl></div>`;
            target.appendChild(card);
        });
    }

    function renderSolutions() {
        const target = document.getElementById('solutionList');
        target.textContent = '';
        const solutions = (state.portfolio && state.portfolio.solutions || []).filter(projectSolution =>
            (projectSolution.requirements || []).some(link => link.requirementId === requirementId));
        if (!solutions.length) return renderEmpty(target, t('noSolutions'));
        solutions.forEach(projectSolution => {
            const link = projectSolution.requirements.find(item => item.requirementId === requirementId);
            const column = document.createElement('div');
            column.className = 'col-12 col-xl-6';
            column.innerHTML = `<article class="card h-100"><div class="card-header d-flex justify-content-between gap-2"><strong>${escapeHtml(projectSolution.solution.title)}</strong>`
                + `<span class="badge ${projectSolution.status === 'SELECTED' ? 'text-bg-success' : 'text-bg-secondary'}">${escapeHtml(humanize(projectSolution.status))}</span></div>`
                + `<div class="card-body"><p>${escapeHtml(projectSolution.solution.description || '')}</p>`
                + `<dl class="portfolio-card-meta"><dt>${escapeHtml(t('coverage'))}</dt><dd>${link.coveragePercent}%</dd>`
                + `<dt>${escapeHtml(t('review'))}</dt><dd>${escapeHtml(humanize(link.reviewStatus))}</dd>`
                + `<dt>${escapeHtml(t('evidence'))}</dt><dd>${escapeHtml(link.evidence || '—')}</dd></dl>`
                + `<h3 class="h6 mt-3">${escapeHtml(t('productCandidates'))}</h3>`
                + `<ul class="list-group list-group-flush border rounded">${(projectSolution.productCandidates || []).map(candidate => `<li class="list-group-item"><strong>${escapeHtml(candidate.product.manufacturer + ' ' + candidate.product.productName)}</strong>`
                    + `<div class="small">${candidate.coveragePercent}% · ${escapeHtml(humanize(candidate.selectionStatus))}</div>`
                    + `<div class="small text-body-secondary">${escapeHtml(candidate.product.sourceReference)}</div></li>`).join('') || '<li class="list-group-item">—</li>'}</ul></div></article>`;
            target.appendChild(column);
        });
    }

    function renderTasks() {
        const target = document.getElementById('taskList');
        target.textContent = '';
        const tasks = [];
        if (!state.requirement.currentAnalysisSnapshotId) tasks.push(t('stale'));
        const mappings = state.snapshotDetail && state.snapshotDetail.elementMappings || [];
        if (mappings.some(mapping => mapping.reviewStatus !== 'CONFIRMED')) tasks.push(t('unreviewed'));
        const links = (state.portfolio && state.portfolio.solutions || []).flatMap(solution => solution.requirements || [])
            .filter(link => link.requirementId === requirementId && link.reviewStatus === 'CONFIRMED');
        if (!links.length) tasks.push(t('noConfirmedSolution'));
        if (!(state.requirement.currentVersion && state.requirement.currentVersion.source)) tasks.push(t('sourceMissing'));
        if (!tasks.length) tasks.push(t('allClear'));
        tasks.forEach(task => {
            const item = document.createElement('div');
            item.className = 'list-group-item';
            item.textContent = task;
            target.appendChild(item);
        });
    }

    async function createVersion(event) {
        event.preventDefault();
        setBusy(true);
        try {
            const sourcePage = document.getElementById('sourcePage').value;
            await api().createRequirementVersion(projectId, requirementId, {
                text: document.getElementById('versionText').value,
                changeReason: document.getElementById('changeReason').value,
                source: {
                    sourceArtifactId: state.requirement.currentVersion?.source?.sourceArtifactId || null,
                    sourceVersionId: state.requirement.currentVersion?.source?.sourceVersionId || null,
                    sourceFragmentIds: state.requirement.currentVersion?.source?.sourceFragmentIds || [],
                    sectionReference: document.getElementById('sourceSection').value || null,
                    pageNumber: sourcePage ? Number(sourcePage) : null,
                    originalText: document.getElementById('sourceOriginal').value || null
                }
            });
            bootstrap.Modal.getOrCreateInstance(document.getElementById('newVersionModal')).hide();
            showInfo(t('versionCreated'));
            await loadAll();
        } catch (error) {
            showError(error);
        } finally {
            setBusy(false);
        }
    }

    async function analyzeRequirement() {
        setBusy(true);
        try {
            const response = await api().analyzeRequirement(projectId, requirementId, {
                provider: null,
                maxArchitectureNodes: 25,
                idempotencyKey: `detail:${requirementId}:${Date.now()}`
            });
            showInfo(t('analysisQueued'));
            const location = response.headers.get('Location');
            if (location) pollAnalysis(analysisJobId(location));
        } catch (error) {
            showError(error);
        } finally {
            setBusy(false);
        }
    }

    function analysisJobId(location) {
        const match = new URL(location, window.location.href).pathname
            .match(/\/analysis-jobs\/([^/]+)$/);
        return match ? decodeURIComponent(match[1]) : null;
    }

    async function pollAnalysis(jobId) {
        if (!jobId) return;
        for (;;) {
            await new Promise(resolve => setTimeout(resolve, 1500));
            try {
                const job = await api().getAnalysisJob(projectId, jobId);
                document.getElementById('requirementLive').textContent = humanize(job.status);
                if (['SUCCESS', 'PARTIAL', 'FAILED', 'CANCELLED'].includes(job.status)) {
                    await loadAll();
                    if (job.items && job.items[0] && job.items[0].snapshotId) {
                        await selectSnapshot(job.items[0].snapshotId);
                    }
                    return;
                }
            } catch (error) {
                showError(error);
                return;
            }
        }
    }

    function applyCapabilities() {
        if (state.account && state.account.architectureMutationAllowed) return;
        const versionButton = document.getElementById('newVersionButton');
        versionButton.disabled = true;
        versionButton.title = t('permission');
    }

    function api() {
        if (!window.TaxonomyPortfolioApi) {
            throw new Error('Portfolio API boundary is not available');
        }
        return window.TaxonomyPortfolioApi;
    }

    function addDefinition(target, term, value) {
        const dt = document.createElement('dt'); dt.textContent = term;
        const dd = document.createElement('dd'); dd.textContent = value;
        target.append(dt, dd);
    }

    function renderEmpty(target, message) {
        target.textContent = '';
        const paragraph = document.createElement('p');
        paragraph.className = 'text-body-secondary mb-0 p-3';
        paragraph.textContent = message;
        target.appendChild(paragraph);
    }

    function setBusy(active) {
        state.busy += active ? 1 : -1;
        state.busy = Math.max(0, state.busy);
        document.getElementById('detailBusy').classList.toggle('d-none', state.busy === 0);
    }

    function showError(error) {
        const target = document.getElementById('detailError');
        target.textContent = error?.message || t('failed');
        target.classList.remove('d-none');
        target.focus();
    }

    function showInfo(message) {
        const target = document.getElementById('detailInfo');
        target.textContent = message;
        target.classList.remove('d-none');
        document.getElementById('requirementLive').textContent = message;
    }

    function humanize(value) {
        return String(value || '—').toLowerCase().replaceAll('_', ' ')
            .replace(/\b\w/g, character => character.toUpperCase());
    }

    function statusClass(status) {
        if (['SUCCESS', 'CONFIRMED', 'ACTIVE', 'SELECTED'].includes(status)) return 'text-bg-success';
        if (['FAILED', 'REJECTED', 'CANCELLED'].includes(status)) return 'text-bg-danger';
        if (['PARTIAL', 'PROPOSED', 'DRAFT', 'PENDING'].includes(status)) return 'text-bg-warning';
        return 'text-bg-secondary';
    }

    function formatDate(value) {
        if (!value) return '—';
        const date = new Date(value);
        return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString(locale);
    }

    function escapeHtml(value) { return window.TaxonomyUtils.escapeHtml(value); }
})();
