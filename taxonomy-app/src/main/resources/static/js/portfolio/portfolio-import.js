(function () {
    'use strict';

    const pathMatch = window.location.pathname.match(/^\/projects\/(\d+)\/import$/);
    if (!pathMatch) return;
    const projectId = Number(pathMatch[1]);
    const locale = (new URLSearchParams(window.location.search).get('lang')
        || document.documentElement.lang || 'en').toLowerCase().startsWith('de') ? 'de' : 'en';
    const draftKey = `taxonomy.portfolio.importDraft.${projectId}`;
    const jobStorageKey = 'taxonomy.portfolio.analysisJobs.v2';
    const state = {
        project: null,
        account: null,
        existingRequirements: [],
        sourceArtifactId: null,
        sourceVersionId: null,
        fileName: null,
        mimeType: null,
        totalPages: null,
        warnings: [],
        candidates: [],
        nextCandidateId: 1,
        currentFile: null
    };

    const labels = {
        en: {
            title: 'Import requirements', portfolio: 'Portfolio', heading: 'Guided document import',
            document: 'Document', review: 'Review candidates', summary: 'Confirm import', choose: 'Choose PDF or DOCX',
            parse: 'Parse document', restore: 'Restore saved review', ai: 'Add AI candidates', manual: 'Add manually',
            filter: 'Filter candidates', decision: 'Decision', all: 'All', newRequirement: 'New requirement',
            newVersion: 'New version', merge: 'Merge', discard: 'Discard', saveDraft: 'Save review draft',
            back: 'Back', reviewSummary: 'Review import summary', confirm: 'Import requirements',
            analyze: 'Start separate analysis for every imported or versioned requirement',
            sourceTitle: 'Source title', sourceType: 'Source type', parsed: 'Parsed document', candidates: 'candidates',
            key: 'Requirement key', candidateTitle: 'Title', type: 'Type', priority: 'Priority', criticality: 'Criticality',
            text: 'Requirement text', action: 'Decision', targetRequirement: 'Existing requirement', mergeTarget: 'Merge into candidate',
            duplicateExact: 'Identical text already exists.', duplicateSimilar: 'Very similar to an existing requirement.',
            source: 'Source', page: 'Page', section: 'Section', confidence: 'AI confidence', rule: 'Rule-based', aiOrigin: 'AI-assisted', manualOrigin: 'Manual',
            saved: 'The review draft was saved in this browser.', restored: 'The review draft was restored.',
            imported: 'Requirements were imported successfully.', noCandidates: 'The document did not contain requirement candidates.',
            invalidMerge: 'Every merge decision requires another retained candidate as target.',
            invalidVersion: 'Every new-version decision requires an existing requirement.',
            duplicateKeys: 'New requirement keys must be unique and must not already exist in the project.',
            emptyText: 'Every retained candidate requires requirement text.', permission: 'Your role cannot import project requirements.',
            failed: 'The import operation failed.', aiUnavailable: 'Select the source file again before adding AI candidates.',
            newCount: 'New requirements', versionCount: 'New versions', mergeCount: 'Merged candidates', discardCount: 'Discarded candidates',
            analyzeQueued: 'The analysis job was queued and will appear in the portfolio job center.',
            draftExists: 'A saved review draft exists for this project.'
        },
        de: {
            title: 'Anforderungen importieren', portfolio: 'Portfolio', heading: 'Geführter Dokumentimport',
            document: 'Dokument', review: 'Kandidaten prüfen', summary: 'Import bestätigen', choose: 'PDF oder DOCX auswählen',
            parse: 'Dokument auslesen', restore: 'Gespeicherte Prüfung wiederherstellen', ai: 'KI-Kandidaten ergänzen', manual: 'Manuell ergänzen',
            filter: 'Kandidaten filtern', decision: 'Entscheidung', all: 'Alle', newRequirement: 'Neue Anforderung',
            newVersion: 'Neue Version', merge: 'Zusammenführen', discard: 'Verwerfen', saveDraft: 'Prüfentwurf speichern',
            back: 'Zurück', reviewSummary: 'Importzusammenfassung prüfen', confirm: 'Anforderungen importieren',
            analyze: 'Für jede importierte oder versionierte Anforderung eine getrennte Analyse starten',
            sourceTitle: 'Quelltitel', sourceType: 'Quellentyp', parsed: 'Dokument ausgelesen', candidates: 'Kandidaten',
            key: 'Anforderungsschlüssel', candidateTitle: 'Titel', type: 'Typ', priority: 'Priorität', criticality: 'Kritikalität',
            text: 'Anforderungstext', action: 'Entscheidung', targetRequirement: 'Bestehende Anforderung', mergeTarget: 'Mit Kandidat zusammenführen',
            duplicateExact: 'Ein identischer Text ist bereits vorhanden.', duplicateSimilar: 'Sehr ähnlich zu einer vorhandenen Anforderung.',
            source: 'Quelle', page: 'Seite', section: 'Abschnitt', confidence: 'KI-Konfidenz', rule: 'Regelbasiert', aiOrigin: 'KI-gestützt', manualOrigin: 'Manuell',
            saved: 'Der Prüfentwurf wurde in diesem Browser gespeichert.', restored: 'Der Prüfentwurf wurde wiederhergestellt.',
            imported: 'Die Anforderungen wurden erfolgreich importiert.', noCandidates: 'Das Dokument enthielt keine Anforderungskandidaten.',
            invalidMerge: 'Jede Zusammenführung benötigt einen anderen beibehaltenen Zielkandidaten.',
            invalidVersion: 'Jede neue Version benötigt eine vorhandene Zielanforderung.',
            duplicateKeys: 'Neue Anforderungsschlüssel müssen eindeutig sein und dürfen im Projekt noch nicht existieren.',
            emptyText: 'Jeder beibehaltene Kandidat benötigt einen Anforderungstext.', permission: 'Ihre Rolle darf keine Projektanforderungen importieren.',
            failed: 'Der Import ist fehlgeschlagen.', aiUnavailable: 'Wählen Sie die Quelldatei erneut aus, bevor Sie KI-Kandidaten ergänzen.',
            newCount: 'Neue Anforderungen', versionCount: 'Neue Versionen', mergeCount: 'Zusammengeführte Kandidaten', discardCount: 'Verworfene Kandidaten',
            analyzeQueued: 'Der Analysejob wurde eingereiht und erscheint im Job-Center des Portfolios.',
            draftExists: 'Für dieses Projekt ist ein gespeicherter Prüfentwurf vorhanden.'
        }
    };

    document.addEventListener('DOMContentLoaded', initialize);
    function l(key) { return labels[locale][key] || labels.en[key] || key; }

    async function initialize() {
        translateSurface();
        wireEvents();
        try {
            [state.project, state.existingRequirements, state.account] = await Promise.all([
                api(`/api/projects/${projectId}`),
                api(`/api/projects/${projectId}/requirements`),
                api('/api/account/me')
            ]);
            document.getElementById('importProject').textContent = `${state.project.projectKey} — ${state.project.title}`;
            document.getElementById('portfolioBack').href = `/projects?lang=${locale}`;
            const savedDraft = readDraft();
            if (savedDraft) {
                const restore = document.getElementById('restoreDraft');
                restore.classList.remove('d-none');
                restore.title = l('draftExists');
            }
            applyCapabilities();
        } catch (error) {
            showError(error);
        }
    }

    function translateSurface() {
        document.documentElement.lang = locale;
        document.title = `${l('title')} — Taxonomy`;
        document.querySelector('.skip-link').textContent = locale === 'de' ? 'Zum Anforderungsimport springen' : 'Skip to requirement import';
        document.getElementById('importPageTitle').textContent = l('title');
        document.getElementById('portfolioBack').textContent = l('portfolio');
        document.getElementById('importHeading').textContent = l('heading');
        document.getElementById('stepUpload').textContent = `1. ${l('document')}`;
        document.getElementById('stepReview').textContent = `2. ${l('review')}`;
        document.getElementById('stepSummary').textContent = `3. ${l('summary')}`;
        document.getElementById('uploadHeading').textContent = l('choose');
        document.querySelector('#uploadForm button[type="submit"]').textContent = l('parse');
        document.getElementById('restoreDraft').textContent = l('restore');
        document.getElementById('extractAi').textContent = l('ai');
        document.getElementById('addManualCandidate').textContent = l('manual');
        document.querySelector('label[for="candidateSearch"]').textContent = l('filter');
        document.querySelector('label[for="candidateStateFilter"]').textContent = l('decision');
        const decisionFilter = document.getElementById('candidateStateFilter');
        decisionFilter.options[0].textContent = l('all'); decisionFilter.options[1].textContent = l('newRequirement'); decisionFilter.options[2].textContent = l('newVersion'); decisionFilter.options[3].textContent = l('merge'); decisionFilter.options[4].textContent = l('discard');
        document.getElementById('saveDraft').textContent = l('saveDraft');
        document.getElementById('backToUpload').textContent = l('back');
        document.getElementById('reviewSummaryButton').textContent = l('reviewSummary');
        document.getElementById('backToReview').textContent = l('back');
        document.getElementById('confirmImport').textContent = l('confirm');
        document.querySelector('label[for="analyzeAfterImport"]').textContent = l('analyze');
        document.querySelector('label[for="documentTitle"]').textContent = l('sourceTitle');
        document.querySelector('label[for="sourceType"]').textContent = l('sourceType');
    }

    function wireEvents() {
        document.getElementById('uploadForm').addEventListener('submit', uploadDocument);
        document.getElementById('restoreDraft').addEventListener('click', restoreDraft);
        document.getElementById('extractAi').addEventListener('click', addAiCandidates);
        document.getElementById('addManualCandidate').addEventListener('click', () => { addCandidate({ origin: 'MANUAL', text: '', section: '', page: null }); renderCandidates(); });
        document.getElementById('candidateSearch').addEventListener('input', renderCandidates);
        document.getElementById('candidateStateFilter').addEventListener('change', renderCandidates);
        document.getElementById('candidateList').addEventListener('input', updateCandidateFromControl);
        document.getElementById('candidateList').addEventListener('change', updateCandidateFromControl);
        document.getElementById('saveDraft').addEventListener('click', saveDraft);
        document.getElementById('backToUpload').addEventListener('click', () => showStep('upload'));
        document.getElementById('reviewSummaryButton').addEventListener('click', reviewSummary);
        document.getElementById('backToReview').addEventListener('click', () => showStep('review'));
        document.getElementById('confirmImport').addEventListener('click', confirmImport);
        window.addEventListener('beforeunload', event => {
            if (state.candidates.length && !readDraft()) {
                event.preventDefault();
                event.returnValue = '';
            }
        });
    }

    async function uploadDocument(event) {
        event.preventDefault();
        const file = document.getElementById('documentFile').files[0];
        if (!file) return;
        state.currentFile = file;
        setBusy(true, l('parse'));
        try {
            const form = new FormData();
            form.append('file', file);
            form.append('title', document.getElementById('documentTitle').value || file.name);
            form.append('sourceType', document.getElementById('sourceType').value);
            const parsed = await multipart('/api/documents/upload', form);
            state.sourceArtifactId = parsed.sourceArtifactId;
            state.sourceVersionId = parsed.sourceVersionId;
            state.fileName = parsed.fileName || file.name;
            state.mimeType = parsed.mimeType;
            state.totalPages = parsed.totalPages;
            state.warnings = parsed.warnings || [];
            state.candidates = [];
            (parsed.candidates || []).forEach(candidate => addCandidate({
                origin: 'RULE',
                text: candidate.text,
                originalText: candidate.text,
                section: candidate.sectionHeading,
                page: candidate.pageNumber,
                selected: candidate.selected !== false
            }));
            if (!state.candidates.length && parsed.rawTextPreview) {
                addCandidate({ origin: 'RULE', text: parsed.rawTextPreview,
                    originalText: parsed.rawTextPreview, section: '', page: null });
            }
            enrichDuplicateSuggestions();
            renderReview();
            showStep('review');
            clearDraft();
        } catch (error) {
            showError(error);
        } finally {
            setBusy(false);
        }
    }

    async function addAiCandidates() {
        if (!state.currentFile) return showError(new Error(l('aiUnavailable')));
        setBusy(true, l('ai'));
        try {
            const form = new FormData();
            form.append('file', state.currentFile);
            form.append('sourceType', document.getElementById('sourceType').value);
            const result = await multipart('/api/documents/extract-ai', form);
            (result.aiCandidates || []).forEach(candidate => {
                if (state.candidates.some(existing => normalize(existing.text) === normalize(candidate.text))) return;
                addCandidate({
                    origin: 'AI', text: candidate.text, originalText: candidate.text,
                    section: candidate.sectionRef, page: null,
                    confidence: candidate.confidence,
                    type: normalizeRequirementType(candidate.type)
                });
            });
            enrichDuplicateSuggestions();
            renderReview();
        } catch (error) {
            showError(error);
        } finally {
            setBusy(false);
        }
    }

    function addCandidate(source) {
        const id = state.nextCandidateId++;
        const existingCount = state.existingRequirements.length + state.candidates.length + 1;
        const candidate = {
            id,
            origin: source.origin || 'MANUAL',
            originalText: source.originalText || source.text || '',
            section: source.section || '',
            page: source.page || null,
            confidence: source.confidence == null ? null : source.confidence,
            key: uniqueGeneratedKey(existingCount),
            title: deriveTitle(source.section, source.text, existingCount),
            text: source.text || '',
            type: source.type || 'FUNCTIONAL',
            priority: 50,
            criticality: 'MEDIUM',
            decision: source.selected === false ? 'DISCARD' : 'NEW',
            targetRequirementId: null,
            mergeTargetId: null,
            duplicate: null
        };
        state.candidates.push(candidate);
        return candidate;
    }

    function uniqueGeneratedKey(start) {
        const existing = new Set(state.existingRequirements.map(item => item.requirementKey.toUpperCase()));
        state.candidates.forEach(item => existing.add(String(item.key || '').toUpperCase()));
        let number = start;
        let key;
        do { key = `REQ-${String(number++).padStart(3, '0')}`; } while (existing.has(key));
        return key;
    }

    function deriveTitle(section, candidateText, number) {
        if (section && section.trim()) return section.trim().slice(0, 240);
        const text = String(candidateText || '').replace(/\s+/g, ' ').trim();
        return (text ? text.slice(0, 100) : `${l('newRequirement')} ${number}`).slice(0, 240);
    }

    function enrichDuplicateSuggestions() {
        state.candidates.forEach(candidate => {
            let best = null;
            state.existingRequirements.forEach(requirement => {
                const existingText = requirement.currentVersion && requirement.currentVersion.text || '';
                const exact = normalize(existingText) === normalize(candidate.text) && normalize(candidate.text);
                const similarity = exact ? 1 : jaccard(existingText, candidate.text);
                if (!best || similarity > best.similarity) best = { requirement, similarity, exact: Boolean(exact) };
            });
            candidate.duplicate = best && best.similarity >= 0.55 ? best : null;
            if (best && best.exact) {
                candidate.decision = 'VERSION';
                candidate.targetRequirementId = best.requirement.id;
            }
        });
    }

    function renderReview() {
        document.getElementById('parseSummary').textContent = `${l('parsed')}: ${state.fileName || '—'} · ${state.totalPages || 0} ${l('page')} · ${state.candidates.length} ${l('candidates')}`;
        const warnings = document.getElementById('candidateWarnings'); warnings.textContent = '';
        state.warnings.forEach(warning => { const alert = document.createElement('div'); alert.className = 'alert alert-warning py-2'; alert.textContent = warning; warnings.appendChild(alert); });
        renderCandidates();
    }

    function renderCandidates() {
        const target = document.getElementById('candidateList'); target.textContent = '';
        const search = document.getElementById('candidateSearch').value.trim().toLowerCase();
        const stateFilter = document.getElementById('candidateStateFilter').value;
        const candidates = state.candidates.filter(candidate => (!search || [candidate.key, candidate.title, candidate.text, candidate.section].join(' ').toLowerCase().includes(search)) && (!stateFilter || candidate.decision === stateFilter));
        if (!candidates.length) { const empty = document.createElement('p'); empty.className = 'text-body-secondary'; empty.textContent = l('noCandidates'); target.appendChild(empty); return; }
        candidates.forEach(candidate => target.appendChild(renderCandidate(candidate)));
    }

    function renderCandidate(candidate) {
        const article = document.createElement('article');
        article.className = 'card candidate-card'; article.dataset.candidateId = candidate.id;
        const duplicate = candidate.duplicate;
        article.innerHTML = `<div class="card-header d-flex flex-wrap justify-content-between gap-2"><div><strong>${escapeHtml(candidate.key)}</strong> · ${escapeHtml(originLabel(candidate.origin))}</div>`
            + `<span class="badge ${candidate.decision === 'DISCARD' ? 'text-bg-secondary' : candidate.decision === 'VERSION' ? 'text-bg-info' : candidate.decision === 'MERGE' ? 'text-bg-warning' : 'text-bg-primary'}">${escapeHtml(decisionLabel(candidate.decision))}</span></div>`
            + `<div class="card-body"><div class="row g-3">`
            + field('key', l('key'), `<input class="form-control candidate-key" maxlength="64" value="${escapeAttribute(candidate.key)}"/>`, 'col-md-3')
            + field('title', l('candidateTitle'), `<input class="form-control candidate-title" maxlength="240" value="${escapeAttribute(candidate.title)}"/>`, 'col-md-5')
            + field('type', l('type'), requirementTypeSelect(candidate.type), 'col-md-2')
            + field('priority', l('priority'), `<input type="number" min="0" max="100" class="form-control candidate-priority" value="${candidate.priority}"/>`, 'col-6 col-md-1')
            + field('criticality', l('criticality'), criticalitySelect(candidate.criticality), 'col-6 col-md-1')
            + `<div class="col-12"><label class="form-label">${escapeHtml(l('text'))}</label><textarea class="form-control candidate-text" rows="5" maxlength="100000">${escapeHtml(candidate.text)}</textarea></div>`
            + field('decision', l('action'), decisionSelect(candidate.decision), 'col-md-4')
            + `<div class="col-md-4 candidate-version-target ${candidate.decision === 'VERSION' ? '' : 'd-none'}"><label class="form-label">${escapeHtml(l('targetRequirement'))}</label>${requirementSelect(candidate.targetRequirementId)}</div>`
            + `<div class="col-md-4 candidate-merge-target ${candidate.decision === 'MERGE' ? '' : 'd-none'}"><label class="form-label">${escapeHtml(l('mergeTarget'))}</label>${mergeTargetSelect(candidate)}</div>`
            + `</div>`
            + (duplicate ? `<div class="alert ${duplicate.exact ? 'alert-danger' : 'alert-warning'} py-2 mt-3 mb-0"><strong>${escapeHtml(duplicate.exact ? l('duplicateExact') : l('duplicateSimilar'))}</strong> ${escapeHtml(duplicate.requirement.requirementKey + ' — ' + duplicate.requirement.title)} (${Math.round(duplicate.similarity * 100)}%)</div>` : '')
            + `<div class="small text-body-secondary mt-3">${escapeHtml(l('source'))}: ${escapeHtml(state.fileName || '—')} · ${escapeHtml(l('section'))}: ${escapeHtml(candidate.section || '—')} · ${escapeHtml(l('page'))}: ${escapeHtml(candidate.page || '—')}`
            + (candidate.confidence != null ? ` · ${escapeHtml(l('confidence'))}: ${Math.round(candidate.confidence * 100)}%` : '') + `</div></div>`;
        return article;
    }

    function field(name, label, control, cssClass) { return `<div class="${cssClass}"><label class="form-label">${escapeHtml(label)}</label>${control}</div>`; }
    function requirementTypeSelect(selected) { return `<select class="form-select candidate-type">${['FUNCTIONAL','NON_FUNCTIONAL','ORGANIZATIONAL','TECHNICAL','LEGAL','PROCESS','SECURITY','DATA','OTHER'].map(value => `<option value="${value}"${value === selected ? ' selected' : ''}>${humanize(value)}</option>`).join('')}</select>`; }
    function criticalitySelect(selected) { return `<select class="form-select candidate-criticality">${['LOW','MEDIUM','HIGH','CRITICAL'].map(value => `<option value="${value}"${value === selected ? ' selected' : ''}>${humanize(value)}</option>`).join('')}</select>`; }
    function decisionSelect(selected) { return `<select class="form-select candidate-decision"><option value="NEW"${selected === 'NEW' ? ' selected' : ''}>${escapeHtml(l('newRequirement'))}</option><option value="VERSION"${selected === 'VERSION' ? ' selected' : ''}>${escapeHtml(l('newVersion'))}</option><option value="MERGE"${selected === 'MERGE' ? ' selected' : ''}>${escapeHtml(l('merge'))}</option><option value="DISCARD"${selected === 'DISCARD' ? ' selected' : ''}>${escapeHtml(l('discard'))}</option></select>`; }
    function requirementSelect(selected) { return `<select class="form-select candidate-target-requirement"><option value="">—</option>${state.existingRequirements.map(requirement => `<option value="${requirement.id}"${Number(selected) === requirement.id ? ' selected' : ''}>${escapeHtml(requirement.requirementKey + ' — ' + requirement.title)}</option>`).join('')}</select>`; }
    function mergeTargetSelect(candidate) { return `<select class="form-select candidate-target-merge"><option value="">—</option>${state.candidates.filter(other => other.id !== candidate.id && other.decision !== 'DISCARD' && other.decision !== 'MERGE').map(other => `<option value="${other.id}"${Number(candidate.mergeTargetId) === other.id ? ' selected' : ''}>${escapeHtml(other.key + ' — ' + other.title)}</option>`).join('')}</select>`; }

    function updateCandidateFromControl(event) {
        const card = event.target.closest('[data-candidate-id]'); if (!card) return;
        const candidate = state.candidates.find(item => item.id === Number(card.dataset.candidateId)); if (!candidate) return;
        const classList = event.target.classList;
        if (classList.contains('candidate-key')) candidate.key = event.target.value;
        if (classList.contains('candidate-title')) candidate.title = event.target.value;
        if (classList.contains('candidate-text')) candidate.text = event.target.value;
        if (classList.contains('candidate-type')) candidate.type = event.target.value;
        if (classList.contains('candidate-priority')) candidate.priority = Number(event.target.value);
        if (classList.contains('candidate-criticality')) candidate.criticality = event.target.value;
        if (classList.contains('candidate-target-requirement')) candidate.targetRequirementId = Number(event.target.value) || null;
        if (classList.contains('candidate-target-merge')) candidate.mergeTargetId = Number(event.target.value) || null;
        if (classList.contains('candidate-decision')) {
            candidate.decision = event.target.value;
            if (candidate.decision !== 'VERSION') candidate.targetRequirementId = null;
            if (candidate.decision !== 'MERGE') candidate.mergeTargetId = null;
            renderCandidates();
        }
    }

    function reviewSummary() {
        try {
            const resolved = resolveReviewedCandidates();
            validateReviewedCandidates(resolved);
            const counts = countDecisions();
            const target = document.getElementById('importSummary');
            target.innerHTML = `<div class="row row-cols-2 row-cols-lg-4 g-2">${summaryMetric(l('newCount'), counts.NEW)}${summaryMetric(l('versionCount'), counts.VERSION)}${summaryMetric(l('mergeCount'), counts.MERGE)}${summaryMetric(l('discardCount'), counts.DISCARD)}</div>`
                + `<div class="table-responsive mt-3"><table class="table table-sm"><thead><tr><th>${escapeHtml(l('key'))}</th><th>${escapeHtml(l('candidateTitle'))}</th><th>${escapeHtml(l('action'))}</th><th>${escapeHtml(l('source'))}</th></tr></thead><tbody>`
                + resolved.map(candidate => `<tr><td><code>${escapeHtml(candidate.key || '—')}</code></td><td>${escapeHtml(candidate.title)}</td><td>${escapeHtml(decisionLabel(candidate.decision))}</td><td>${escapeHtml(candidate.section || state.fileName || '—')}</td></tr>`).join('') + `</tbody></table></div>`;
            showStep('summary');
        } catch (error) { showError(error); }
    }

    function resolveReviewedCandidates() {
        const retained = state.candidates.filter(candidate => candidate.decision !== 'DISCARD').map(candidate => ({ ...candidate }));
        const byId = new Map(retained.map(candidate => [candidate.id, candidate]));
        retained.filter(candidate => candidate.decision === 'MERGE').forEach(candidate => {
            const target = byId.get(Number(candidate.mergeTargetId));
            if (!target || target.decision === 'MERGE' || target.decision === 'DISCARD') throw new Error(l('invalidMerge'));
            target.text = [target.text, candidate.text].filter(Boolean).join('\n\n');
            target.originalText = [target.originalText, candidate.originalText].filter(Boolean).join('\n\n');
            target.section = [target.section, candidate.section].filter(Boolean).join('; ');
            byId.delete(candidate.id);
        });
        return Array.from(byId.values()).filter(candidate => candidate.decision !== 'MERGE');
    }

    function validateReviewedCandidates(candidates) {
        if (candidates.some(candidate => !candidate.text || !candidate.text.trim())) throw new Error(l('emptyText'));
        if (candidates.some(candidate => candidate.decision === 'VERSION' && !candidate.targetRequirementId)) throw new Error(l('invalidVersion'));
        const existingKeys = new Set(state.existingRequirements.map(requirement => requirement.requirementKey.toUpperCase()));
        const newKeys = candidates.filter(candidate => candidate.decision === 'NEW').map(candidate => String(candidate.key || '').trim().toUpperCase());
        if (newKeys.some(key => !key || existingKeys.has(key)) || new Set(newKeys).size !== newKeys.length) throw new Error(l('duplicateKeys'));
    }

    async function confirmImport() {
        setBusy(true, l('confirm'));
        try {
            const candidates = resolveReviewedCandidates(); validateReviewedCandidates(candidates);
            const request = {
                items: candidates.map(candidate => ({
                    decision: candidate.decision === 'VERSION' ? 'NEW_VERSION' : 'NEW_REQUIREMENT',
                    targetRequirementId: candidate.decision === 'VERSION' ? candidate.targetRequirementId : null,
                    requirementKey: candidate.decision === 'NEW' ? candidate.key.trim() : null,
                    title: candidate.title.trim(), text: candidate.text.trim(),
                    requirementType: candidate.type, priority: candidate.priority,
                    criticality: candidate.criticality,
                    source: {
                        sourceArtifactId: state.sourceArtifactId,
                        sourceVersionId: state.sourceVersionId,
                        sourceFragmentIds: [], sectionReference: candidate.section || null,
                        pageNumber: candidate.page || null,
                        originalText: candidate.originalText || candidate.text
                    }
                })),
                analyzeAfterImport: document.getElementById('analyzeAfterImport').checked,
                provider: null, maxArchitectureNodes: 25,
                idempotencyKey: `import-review:${projectId}:${state.sourceVersionId || 'draft'}:${Date.now()}`
            };
            const response = await apiResponse(`/api/projects/${projectId}/requirements/import-review`, { method: 'POST', body: request });
            const result = await response.json();
            if (result.analysisJob) registerAnalysisJob(response.headers.get('Location'), result.analysisJob);
            clearDraft();
            showInfo(result.analysisJob ? `${l('imported')} ${l('analyzeQueued')}` : l('imported'));
            window.setTimeout(() => { window.location.href = `/projects?lang=${locale}`; }, 1200);
        } catch (error) { showError(error); }
        finally { setBusy(false); }
    }

    function registerAnalysisJob(location, job) {
        if (!location || !job) return;
        const url = new URL(location, window.location.href).toString();
        let entries = [];
        try { entries = JSON.parse(localStorage.getItem(jobStorageKey) || '[]'); } catch (error) { entries = []; }
        entries = entries.filter(entry => entry && entry.url !== url);
        entries.unshift({ url, projectId, createdAt: Date.now(), updatedAt: Date.now(), expanded: false, job });
        localStorage.setItem(jobStorageKey, JSON.stringify(entries.slice(0, 20)));
    }

    function saveDraft() { localStorage.setItem(draftKey, JSON.stringify(serializableState())); showInfo(l('saved')); document.getElementById('restoreDraft').classList.remove('d-none'); }
    function restoreDraft() { const draft = readDraft(); if (!draft) return; Object.assign(state, draft); state.currentFile = null; state.nextCandidateId = Math.max(1, ...state.candidates.map(candidate => candidate.id + 1)); renderReview(); showStep('review'); showInfo(l('restored')); }
    function readDraft() { try { const value = JSON.parse(localStorage.getItem(draftKey) || 'null'); return value && value.projectId === projectId ? value : null; } catch (error) { return null; } }
    function clearDraft() { localStorage.removeItem(draftKey); document.getElementById('restoreDraft').classList.add('d-none'); }
    function serializableState() { return { projectId, sourceArtifactId: state.sourceArtifactId, sourceVersionId: state.sourceVersionId, fileName: state.fileName, mimeType: state.mimeType, totalPages: state.totalPages, warnings: state.warnings, candidates: state.candidates }; }

    function showStep(step) {
        const steps = { upload: 'uploadStep', review: 'reviewStep', summary: 'summaryStep' };
        Object.entries(steps).forEach(([name, id]) => document.getElementById(id).classList.toggle('d-none', name !== step));
        ['stepUpload', 'stepReview', 'stepSummary'].forEach(id => document.getElementById(id).classList.add('disabled'));
        const active = step === 'upload' ? 'stepUpload' : step === 'review' ? 'stepReview' : 'stepSummary';
        document.getElementById(active).classList.remove('disabled'); document.getElementById(active).classList.add('active');
        document.getElementById(steps[step]).scrollIntoView({ block: 'start' });
    }

    function applyCapabilities() {
        if (state.account && state.account.architectureMutationAllowed) return;
        document.querySelectorAll('button[type="submit"], #confirmImport, #extractAi, #addManualCandidate').forEach(button => { button.disabled = true; button.title = l('permission'); });
    }
    function countDecisions() { return state.candidates.reduce((counts, candidate) => { counts[candidate.decision] = (counts[candidate.decision] || 0) + 1; return counts; }, { NEW: 0, VERSION: 0, MERGE: 0, DISCARD: 0 }); }
    function summaryMetric(label, value) { return `<div><div class="card h-100"><div class="card-body"><div class="h3">${value}</div><div class="small text-body-secondary">${escapeHtml(label)}</div></div></div></div>`; }
    function originLabel(origin) { return origin === 'AI' ? l('aiOrigin') : origin === 'RULE' ? l('rule') : l('manualOrigin'); }
    function decisionLabel(decision) { return decision === 'VERSION' ? l('newVersion') : decision === 'MERGE' ? l('merge') : decision === 'DISCARD' ? l('discard') : l('newRequirement'); }
    function normalizeRequirementType(value) { const normalized = String(value || 'FUNCTIONAL').toUpperCase(); return ['FUNCTIONAL','NON_FUNCTIONAL','ORGANIZATIONAL','TECHNICAL','LEGAL','PROCESS','SECURITY','DATA','OTHER'].includes(normalized) ? normalized : 'OTHER'; }
    function normalize(value) { return String(value || '').toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim(); }
    function jaccard(left, right) { const a = new Set(normalize(left).split(' ').filter(Boolean)); const b = new Set(normalize(right).split(' ').filter(Boolean)); if (!a.size || !b.size) return 0; const intersection = [...a].filter(token => b.has(token)).length; return intersection / new Set([...a, ...b]).size; }
    function humanize(value) { return String(value || '').toLowerCase().replaceAll('_', ' ').replace(/\b\w/g, character => character.toUpperCase()); }

    async function multipart(path, form) { const headers = {}; addCsrf(headers); const response = await fetch(path, { method: 'POST', headers, credentials: 'same-origin', body: form }); if (!response.ok) throw await responseError(response); return response.json(); }
    async function api(path) { const response = await fetch(path, { headers: { Accept: 'application/json' }, credentials: 'same-origin' }); if (!response.ok) throw await responseError(response); return response.json(); }
    async function apiResponse(path, options) { const headers = { Accept: 'application/json', 'Content-Type': 'application/json' }; addCsrf(headers); const response = await fetch(path, { method: options.method, headers, credentials: 'same-origin', body: JSON.stringify(options.body) }); if (!response.ok) throw await responseError(response); return response; }
    async function responseError(response) { const payload = await response.json().catch(() => null); return new Error(payload?.detail || payload?.message || payload?.error || `${l('failed')} HTTP ${response.status}`); }
    function addCsrf(headers) { const token = document.querySelector('meta[name="_csrf"]')?.content; const name = document.querySelector('meta[name="_csrf_header"]')?.content || 'X-CSRF-TOKEN'; if (token) headers[name] = token; }
    function setBusy(active, message) { document.getElementById('importBusy').classList.toggle('d-none', !active); if (message) document.getElementById('importBusyText').textContent = message; }
    function showError(error) { const target = document.getElementById('importError'); target.textContent = error?.message || l('failed'); target.classList.remove('d-none'); target.focus(); }
    function showInfo(message) { const target = document.getElementById('importInfo'); target.textContent = message; target.classList.remove('d-none'); document.getElementById('importLive').textContent = message; }
    function escapeHtml(value) { return String(value == null ? '' : value).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;').replaceAll("'", '&#39;'); }
    function escapeAttribute(value) { return escapeHtml(value).replaceAll('`', '&#96;'); }
})();
