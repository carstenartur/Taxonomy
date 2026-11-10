/* taxonomy-analysis.js – Gap Analysis, Pattern Detection, Recommendation & Copilot UI */

(function () {
    'use strict';
    var t = TaxonomyI18n.t;

    // ── Helpers ────────────────────────────────────────────────────────────────

    var escapeHtml = TaxonomyUtils.escapeHtml;
    var panelRequests = Object.create(null);
    function beginPanelRequest(panelId) {
        var sequence = (panelRequests[panelId] || 0) + 1;
        panelRequests[panelId] = sequence;
        var context = window.TaxonomyApiClient.captureContext();
        return function () { return panelRequests[panelId] === sequence && context(); };
    }

    // Layer label lookup for human-readable display of 2-letter taxonomy roots
    var LAYER_LABELS = {
        'CP': 'Capabilities', 'BP': 'Business Processes', 'BR': 'Business Roles',
        'CI': 'COI Services', 'CO': 'Communications Services', 'CR': 'Core Services',
        'IP': 'Information Products', 'UA': 'User Applications'
    };
    function layerLabel(code) { return LAYER_LABELS[code] || code || ''; }

    function getBusinessText() {
        var el = document.getElementById('businessText');
        return el ? el.value.trim() : '';
    }

    function getCurrentScores() {
        // TaxonomyState owns the current analysis scores.
        var stateScores = window.TaxonomyState && window.TaxonomyState.currentScores;
        return stateScores && typeof stateScores === 'object'
            ? stateScores : {};
    }

    function hasScores() {
        var scores = getCurrentScores();
        return scores && Object.keys(scores).length > 0;
    }

    function warnAboutOpenAssessments(contentId) {
        if (window.TaxonomyAnalysisScope?.restrictsGlobalAnalysis(window.TaxonomyState?.lastAnalysisScope)) {
            var scopeMessage = t('analysis.scope.global.required');
            if (contentId === 'copilotContent') renderPartialCopilot(scopeMessage);
            else showPanelError(contentId, scopeMessage);
            return true;
        }
        var recovery = window.TaxonomyAnalysisRecovery;
        if (window.TaxonomyState?.lastAnalysisStatus !== 'PARTIAL'
                && !recovery?.hasOpenEvaluations()
                && !window.TaxonomyState?.analysisCoverage?.failedOrBlockedNodes) return false;
        var message = recovery?.partialResultMessage?.() || t('relation.search.partial');
        if (contentId === 'copilotContent') renderPartialCopilot(message);
        else showPanelError(contentId, message);
        // Imported evidence has no owned continuation dialog. The inline warning is
        // therefore necessary even when open() cannot offer a recovery action.
        recovery?.open();
        return true;
    }

    function displayPercent(value) {
        var numeric = Number(value);
        if (!Number.isFinite(numeric)) return '0';
        return Math.max(0, Math.min(100, numeric)).toFixed(0);
    }

    function showPanelLoading(contentId) {
        var el = document.getElementById(contentId);
        if (el) {
            el.innerHTML =
                '<div class="text-center text-muted py-2">' +
                '<div class="spinner-border spinner-border-sm me-1" role="status"></div> ' +
                t('analyze.panel.analyzing') + '</div>';
        }
    }

    function showPanelError(contentId, msg) {
        var el = document.getElementById(contentId);
        if (el) {
            el.innerHTML = '<div class="alert alert-warning py-1 px-2 small mb-0">' +
                escapeHtml(msg) + '</div>';
        }
    }

    // ── Gap Analysis ──────────────────────────────────────────────────────────

    var assessmentProfiles = null;
    var assessmentTickets = {gap:0, recommendation:0, pattern:0, copilot:0};
    var liveAssessments = {gap:null, recommendation:null, pattern:null, copilot:null};
    var assessmentPanels = {gap:'gapAnalysisContent', recommendation:'recommendationContent', pattern:'patternDetectionContent', copilot:'copilotContent'};
    async function loadAssessmentProfiles() {
        if (!assessmentProfiles) assessmentProfiles = window.AnalysisApi.gapProfiles().catch(function (error) {
            assessmentProfiles = null; throw error;
        });
        var profiles = await assessmentProfiles, select = document.getElementById('gapProfile');
        if (select && !select.dataset.loaded) {
            var chosen = select.value || 'compatibility';
            select.replaceChildren();
            profiles.forEach(function (profile) {
                var option = document.createElement('option');
                option.value = profile.id; option.textContent = profile.description + ' (' + profile.version + ')';
                select.append(option);
            });
            select.value = chosen; select.dataset.loaded = 'true';
        }
        return profiles;
    }
    async function assessmentInput(extra) {
        var selected = document.getElementById('gapProfile')?.value || 'compatibility';
        var body = Object.assign({businessText:getBusinessText(), profileId:selected,
            evidenceCompleteness:document.getElementById('gapEvidenceComplete')?.checked ? 'COMPLETE' : 'INCOMPLETE'}, extra || {});
        if (body.scores) body.scores = Object.assign({}, body.scores);
        var values = await Promise.all([window.AnalysisApi.captureContext(), loadAssessmentProfiles()]);
        var profile = values[1].find(function (item) { return item.id === selected; });
        if (!profile) throw new Error(t('analysis.evidence.selectProfile'));
        body.profileVersion = profile.version;
        return {context:values[0], body:body};
    }
    async function assessmentCurrent(input) {
        if (!input?.context) return false;
        var context;
        try { context = await window.AnalysisApi.captureContext(); }
        catch (_) { return false; }
        return context && ['repositoryId','workspaceScopeKey','branch','commit'].every(function (key) {
            return context[key] === input.context[key];
        }) && input.body.businessText === getBusinessText()
            && (!input.body.profileId || input.body.profileId === (document.getElementById('gapProfile')?.value || 'compatibility'))
            && (!input.body.evidenceCompleteness || input.body.evidenceCompleteness === (document.getElementById('gapEvidenceComplete')?.checked ? 'COMPLETE' : 'INCOMPLETE'))
            && (!input.body.scores || JSON.stringify(input.body.scores) === JSON.stringify(getCurrentScores()));
    }
    function matchesAssessmentSource(source, evidence) {
        return evidence && !Array.isArray(evidence)
            && evidence.repositoryId === source.repositoryId && evidence.workspaceScopeKey === source.workspaceScopeKey
            && evidence.branch === source.branch && evidence.commitId === source.commit
            && evidence.evidenceAuthority === 'CANONICAL_SNAPSHOT'
            && (!Object.hasOwn(evidence, 'workspaceId')
                || (evidence.workspaceId === null || typeof evidence.workspaceId === 'string')
                    && (evidence.workspaceId || '__shared__') === source.workspaceScopeKey);
    }
    function requireAssessmentProof(input, evidence) {
        var body = input.body;
        if (!matchesAssessmentSource(input.context, evidence)
                || evidence.profileId !== body.profileId || evidence.profileVersion !== body.profileVersion
                || evidence.businessText !== body.businessText
                || typeof evidence.metamodelVersion !== 'string' || !evidence.metamodelVersion
                || !['COMPLETE','INCOMPLETE'].includes(evidence.evidenceCompleteness)
                || body.evidenceCompleteness === 'INCOMPLETE' && evidence.evidenceCompleteness !== 'INCOMPLETE') {
            throw new Error(t('analysis.evidence.invalidResponse'));
        }
    }
    function requirePatternProof(input, evidence) {
        if (!matchesAssessmentSource(input.context, evidence)
                || evidence.readinessState !== 'READY' || typeof evidence.readAuthority !== 'string' || !evidence.readAuthority
                || typeof evidence.templateVersion !== 'string' || !evidence.templateVersion
                || evidence.minimumScore !== input.body.minScore
                || !Number.isInteger(evidence.eligibleAnchors) || evidence.eligibleAnchors < 0
                || !['COMPLETE_WITHIN_PATTERN_TEMPLATES','NOT_EVALUATED_NO_ANCHORS','INCOMPLETE_CANONICAL_EVIDENCE'].includes(evidence.completeness)) {
            throw new Error(t('analysis.evidence.invalidResponse'));
        }
    }
    function invalidateLiveAssessments() {
        Object.keys(assessmentTickets).forEach(function (kind) {
            assessmentTickets[kind]++;
            if (liveAssessments[kind]) {
                showPanelError(assessmentPanels[kind], t('analysis.evidence.selectionChanged'));
                if (kind === 'copilot') showPanelError('patternDetectionContent', t('analysis.evidence.selectionChanged'));
                liveAssessments[kind] = null;
            }
        });
    }
    async function updateAssessmentSourceLabel() {
        var label = document.getElementById('gapModelSource'); if (!label) return;
        try {
            var source = await window.AnalysisApi.captureContext();
            label.textContent = source.repositoryId + ' · ' + source.branch + ' · ' + source.commit.substring(0,12);
        } catch (_) { label.textContent = t('analysis.evidence.selectVersion'); }
    }
    async function runGapAnalysis() {
        var isCurrent = beginPanelRequest('gapAnalysisContent');
        var ticket = ++assessmentTickets.gap;
        liveAssessments.gap = {pending:true};
        showPanelLoading('gapAnalysisContent');
        var panel = document.getElementById('gapAnalysisPanel'); if (panel) panel.open = true;
        var input;
        try {
            input = await assessmentInput();
            if (!await assessmentCurrent(input) || !isCurrent() || ticket !== assessmentTickets.gap) throw new Error(t('analysis.evidence.selectionChanged'));
            liveAssessments.gap = input;
            var data = await window.AnalysisApi.analyzeGap(input.body, input.context);
            var current = await assessmentCurrent(input);
            if (isCurrent() && ticket === assessmentTickets.gap) {
                if (current) { requireAssessmentProof(input, data); renderGapAnalysis(data, {live:input}); }
                else showPanelError('gapAnalysisContent', t('analysis.evidence.selectionChanged'));
            }
        } catch (error) {
            if (!isCurrent() || error.code === 'ABORTED') return;
            var current = !input || await assessmentCurrent(input);
            if (isCurrent() && ticket === assessmentTickets.gap)
                showPanelError('gapAnalysisContent', current
                    ? t('analyze.gap.failed', error.message) : t('analysis.evidence.selectionChanged'));
        }
    }

    function renderGapAnalysis(data, options) {
        liveAssessments.gap = options?.live || null;
        var el = document.getElementById('gapAnalysisContent'); if (!el) return;
        var judgments = Array.isArray(data.judgments) ? data.judgments : [];
        var violated = judgments.filter(function (item) { return item.status === 'VIOLATED'; });
        var open = judgments.filter(function (item) { return item.status === 'INCONCLUSIVE'; });
        var html = '<div class="d-flex gap-3 mb-2 flex-wrap">'
            + (options?.retained ? '<span class="badge bg-secondary">' + t('analysis.evidence.retained') + '</span>' : '')
            + '<span class="badge bg-secondary">' + escapeHtml(data.profileId || '') + ' · ' + escapeHtml(data.profileVersion || '') + '</span>'
            + '<span class="badge bg-primary">' + t('analyze.gap.anchors', data.totalAnchors || 0) + '</span>'
            + '<span class="badge ' + (violated.length ? 'bg-danger' : 'bg-secondary') + '">' + t('analysis.evidence.violated', violated.length) + '</span>'
            + '<span class="badge ' + (open.length ? 'bg-warning text-dark' : 'bg-secondary') + '">' + t('analysis.evidence.openCount', open.length) + '</span></div>';
        if (!judgments.length) html += '<p class="text-muted">' + t('analysis.evidence.noObligations') + '</p>';
        else {
            html += '<div class="table-responsive"><table class="table table-sm"><thead><tr>'
                + '<th>' + t('analyze.table.source.node') + '</th><th>' + t('analysis.evidence.rule') + '</th>'
                + '<th>' + t('analysis.evidence.cardinality') + '</th><th>' + t('analysis.evidence.result') + '</th></tr></thead><tbody>';
            judgments.forEach(function (item) {
                var knownStatus = ['SATISFIED','VIOLATED','INCONCLUSIVE'].includes(item.status) ? item.status : 'INCONCLUSIVE';
                var label = {SATISFIED:'satisfied',VIOLATED:'violatedLabel',INCONCLUSIVE:'inconclusive'}[knownStatus];
                var color = {SATISFIED:'success',VIOLATED:'danger',INCONCLUSIVE:'warning text-dark'}[knownStatus];
                html += '<tr data-assessment-status="' + knownStatus + '"><td><code>' + escapeHtml(item.sourceNodeCode) + '</code></td>'
                    + '<td>' + escapeHtml(item.ruleId) + '<small class="d-block">' + escapeHtml(item.relationType)
                    + ' → ' + escapeHtml((item.targetRoots || []).join(' / ')) + '</small></td>'
                    + '<td>' + escapeHtml(item.observedCount) + ' / ' + escapeHtml(item.minCount) + '–'
                    + (item.maxCount == null ? '∞' : escapeHtml(item.maxCount)) + '</td>'
                    + '<td><span class="badge bg-' + color + '">' + t('analysis.evidence.' + label)
                    + '</span><small class="d-block">' + escapeHtml(item.description || '') + '</small></td></tr>';
            });
            html += '</tbody></table></div>';
        }
        if (data.commitId) html += '<p class="small text-muted">' + t('analysis.evidence.source') + ' '
            + escapeHtml(data.repositoryId) + ' · ' + escapeHtml(data.branch) + ' · <code>' + escapeHtml(data.commitId.substring(0,12)) + '</code></p>';
        if (data.notes?.length) html += '<div class="alert alert-info small py-1 px-2">' + data.notes.map(escapeHtml).join('<br>') + '</div>';
        el.innerHTML = html;
    }

    // ── Pattern Detection ─────────────────────────────────────────────────────

    async function runPatternDetection() {
        if (warnAboutOpenAssessments('patternDetectionContent')) return;
        if (!hasScores()) {
            showPanelError('patternDetectionContent', t('analyze.scores.required'));
            return;
        }
        var isCurrent = beginPanelRequest('patternDetectionContent');
        var ticket = ++assessmentTickets.pattern, input;
        liveAssessments.pattern = {pending:true};
        showPanelLoading('patternDetectionContent');
        var panel = document.getElementById('patternDetectionPanel');
        if (panel) panel.open = true;

        try {
            var body = {scores:Object.assign({}, getCurrentScores()), minScore:50, businessText:getBusinessText()};
            input = {body:body, context:await window.AnalysisApi.captureContext()};
            if (!await assessmentCurrent(input) || !isCurrent() || ticket !== assessmentTickets.pattern) throw new Error(t('analysis.evidence.selectionChanged'));
            liveAssessments.pattern = input;
            var data = await window.AnalysisApi.detectPatterns({scores:body.scores, minScore:body.minScore}, input.context);
            var current = await assessmentCurrent(input);
            if (isCurrent() && ticket === assessmentTickets.pattern) {
                if (current) { requirePatternProof(input, data?.evidence); renderPatternDetection(data, {live:input}); }
                else showPanelError('patternDetectionContent', t('analysis.evidence.selectionChanged'));
            }
        } catch (error) {
            if (!isCurrent() || error.code === 'ABORTED') return;
            var current = !input || await assessmentCurrent(input);
            if (isCurrent() && ticket === assessmentTickets.pattern)
                showPanelError('patternDetectionContent', current
                    ? t('analyze.patterns.failed', error.message) : t('analysis.evidence.selectionChanged'));
        }
    }

    function renderPatternDetection(data, options) {
        liveAssessments.pattern = options?.live || null;
        var el = document.getElementById('patternDetectionContent');
        if (!el) return;
        var html = '';

        // Summary
        html += '<div class="d-flex gap-3 mb-2 flex-wrap">';
        if (options?.retained) html += '<span class="badge bg-secondary">' + t('analysis.evidence.retained') + '</span>';
        html += '<span class="badge bg-success">' + t('analyze.patterns.complete', (data.matchedPatterns ? data.matchedPatterns.length : 0)) + '</span>';
        html += '<span class="badge bg-warning text-dark">' + t('analyze.patterns.incomplete.count', (data.incompletePatterns ? data.incompletePatterns.length : 0)) + '</span>';
        var coverage = Number.isFinite(data.patternCoverage) ? displayPercent(data.patternCoverage) + '%' : t('analysis.evidence.notAssessed');
        html += '<span class="badge bg-secondary">' + t('analyze.patterns.coverage', coverage) + '</span>';
        html += '</div>';
        if (data.evidence) {
            var evidence = data.evidence;
            html += '<p class="small text-muted">' + t('analysis.evidence.source') + ' '
                + escapeHtml(evidence.repositoryId) + ' · ' + escapeHtml(evidence.branch) + ' · <code>'
                + escapeHtml(evidence.commitId.substring(0,12)) + '</code></p>';
            html += '<p class="small text-muted">' + t('analysis.patterns.policy', escapeHtml(evidence.templateVersion), evidence.minimumScore)
                + ' · ' + t('analysis.patterns.' + evidence.completeness) + '</p>';
        }

        // Matched patterns
        if (data.matchedPatterns && data.matchedPatterns.length > 0) {
            html += '<h6 class="small fw-semibold mt-2">' + t('analyze.patterns.complete.heading') + '</h6>';
            data.matchedPatterns.forEach(function (p) {
                html += renderPatternCard(p, 'success', options);
            });
        }

        // Incomplete patterns
        if (data.incompletePatterns && data.incompletePatterns.length > 0) {
            html += '<h6 class="small fw-semibold mt-2">' + t('analyze.patterns.incomplete.heading') + '</h6>';
            data.incompletePatterns.forEach(function (p) {
                html += renderPatternCard(p, 'warning', options);
            });
        }

        // Notes
        if (data.notes && data.notes.length > 0) {
            html += '<div class="alert alert-info py-1 px-2 small mb-0 mt-2">';
            html += data.notes.map(function (n) { return escapeHtml(n); }).join('<br>');
            html += '</div>';
        }

        if ((!data.matchedPatterns || data.matchedPatterns.length === 0) &&
            (!data.incompletePatterns || data.incompletePatterns.length === 0)) {
            html += '<div class="text-muted small">' + t('analyze.patterns.none') + '</div>';
        }

        el.innerHTML = html;
    }

    function renderPatternCard(pattern, color, options) {
        var pct = displayPercent(pattern.completeness);
        var html = '<div class="card mb-2 border-' + color + '">';
        html += '<div class="card-body py-2 px-3">';
        html += '<div class="d-flex justify-content-between align-items-center">';
        html += '<strong class="small">' + escapeHtml(pattern.patternName) + '</strong>';
        html += '<span class="badge bg-' + color + (color === 'warning' ? ' text-dark' : '') + '">' + pct + '%</span>';
        html += '</div>';

        // Progress bar
        html += '<div class="progress mt-1" style="height:6px;">';
        html += '<div class="progress-bar bg-' + color + '" style="width:' + pct + '%;"></div>';
        html += '</div>';

        // Steps
        if (options?.retained) {
            // Retained steps name concrete endpoints and preserve applicability qualifiers.
            // Their strings cannot be compared with the root-level pattern template.
            html += '<ul class="small mt-2 mb-0">';
            (pattern.presentSteps || []).forEach(function (step) {
                html += '<li>' + escapeHtml(step) + '</li>';
            });
            (pattern.missingSteps || []).forEach(function (step) {
                html += '<li class="text-warning-emphasis">' + escapeHtml(step) + '</li>';
            });
            html += '</ul>';
        } else if (pattern.expectedSteps && pattern.expectedSteps.length > 0) {
            html += '<div class="mt-1" style="font-size:0.78em;">';
            pattern.expectedSteps.forEach(function (step) {
                var isPresent = pattern.presentSteps && pattern.presentSteps.indexOf(step) >= 0;
                var icon = isPresent ? '\u2705' : '\u274C';
                html += '<span class="me-1">' + icon + ' ' + escapeHtml(step) + '</span>';
            });
            html += '</div>';
        }

        html += '</div></div>';
        return html;
    }

    // ── Architecture Recommendation ───────────────────────────────────────────

    async function runRecommendation() {
        if (warnAboutOpenAssessments('recommendationContent')) return;
        if (!hasScores()) { showPanelError('recommendationContent', t('analyze.scores.required')); return; }
        var isCurrent = beginPanelRequest('recommendationContent');
        var ticket = ++assessmentTickets.recommendation, input;
        liveAssessments.recommendation = {pending:true};
        showPanelLoading('recommendationContent');
        var panel = document.getElementById('recommendationPanel'); if (panel) panel.open = true;
        try {
            input = await assessmentInput({scores:getCurrentScores(), minScore:50});
            if (!await assessmentCurrent(input) || !isCurrent() || ticket !== assessmentTickets.recommendation) throw new Error(t('analysis.evidence.selectionChanged'));
            liveAssessments.recommendation = input;
            var data = await window.AnalysisApi.recommend(input.body, input.context);
            var current = await assessmentCurrent(input);
            if (isCurrent() && ticket === assessmentTickets.recommendation) {
                if (current) { requireAssessmentProof(input, data?.gapAnalysis); renderRecommendation(data, {live:input}); }
                else showPanelError('recommendationContent', t('analysis.evidence.selectionChanged'));
            }
        } catch (error) {
            if (!isCurrent() || error.code === 'ABORTED') return;
            var current = !input || await assessmentCurrent(input);
            if (isCurrent() && ticket === assessmentTickets.recommendation)
                showPanelError('recommendationContent', current
                    ? t('analyze.recommendation.failed', error.message) : t('analysis.evidence.selectionChanged'));
        }
    }

    function renderRecommendation(data, options) {
        liveAssessments.recommendation = options?.live || null;
        var el = document.getElementById('recommendationContent');
        if (!el) return;
        var html = '';

        var mean = Number.isFinite(data.meanRelevance) ? data.meanRelevance.toFixed(0) + '%' : t('analysis.evidence.notAssessed');
        html += '<div class="d-flex gap-3 mb-2 flex-wrap align-items-center">';
        if (options?.retained) html += '<span class="badge bg-secondary">' + t('analysis.evidence.retained') + '</span>';
        html += '<span class="badge bg-secondary">' + t('analysis.recommendation.meanRelevance', mean) + '</span>';
        html += '<span class="badge bg-info text-dark">' + t('analysis.recommendation.matches', (data.matchedElements || []).length) + '</span>';
        html += '<span class="badge bg-secondary">' + t('analysis.recommendation.accepted', (data.acceptedElements || []).length) + '</span>';
        html += '<span class="badge bg-info text-dark">' + t('analyze.recommendation.proposed', (data.proposedElements || []).length) + '</span>';
        html += '<span class="badge bg-secondary">' + t('analyze.recommendation.relations.count', (data.suggestedRelations || []).length) + '</span></div>';

        // Reasoning
        if (data.reasoning && data.reasoning.length > 0) {
            html += '<div class="alert alert-light py-1 px-2 small mb-2 border">';
            html += '<strong>' + t('analyze.recommendation.reasoning') + '</strong><ul class="mb-0 ps-3">';
            data.reasoning.forEach(function (r) {
                html += '<li>' + escapeHtml(r) + '</li>';
            });
            html += '</ul></div>';
        }

        // Search relevance is distinct from membership in the selected model.
        if (data.matchedElements && data.matchedElements.length > 0) {
            html += '<h6 class="small fw-semibold mt-2">' + t('analysis.recommendation.matchesHeading') + '</h6>';
            html += renderRecommendedElementsTable(data.matchedElements, 'info');
        }

        if (data.acceptedElements && data.acceptedElements.length > 0) {
            html += '<h6 class="small fw-semibold mt-2">' + t('analysis.recommendation.acceptedHeading') + '</h6>';
            html += renderRecommendedElementsTable(data.acceptedElements, 'secondary', false);
        }

        // Proposed Elements
        if (data.proposedElements && data.proposedElements.length > 0) {
            html += '<h6 class="small fw-semibold mt-2">' + t('analyze.recommendation.proposed.heading') + '</h6>';
            html += renderRecommendedElementsTable(data.proposedElements, 'info');
        }

        // Suggested Relations
        if (data.suggestedRelations && data.suggestedRelations.length > 0) {
            html += '<h6 class="small fw-semibold mt-2">' + t('analyze.recommendation.suggested.heading') + '</h6>';
            html += '<div class="table-responsive"><table class="table table-sm table-hover mb-2" style="font-size:0.82em;">';
            html += '<thead><tr><th>' + t('analyze.table.source') + '</th><th></th><th>' + t('analyze.table.target') + '</th><th>' + t('analyze.table.type') + '</th><th>' + t('analyze.table.reasoning') + '</th></tr></thead><tbody>';
            data.suggestedRelations.forEach(function (sr) {
                html += '<tr>';
                html += '<td><code>' + escapeHtml(sr.sourceCode) + '</code></td>';
                html += '<td class="text-center">\u2192</td>';
                html += '<td><code>' + escapeHtml(sr.targetCode) + '</code></td>';
                html += '<td><span class="badge bg-info text-dark">' + escapeHtml(sr.relationType) + '</span></td>';
                html += '<td class="small text-muted">' + escapeHtml(sr.reasoning || '') + '</td>';
                html += '</tr>';
            });
            html += '</tbody></table></div>';
        }

        // Notes
        if (data.notes && data.notes.length > 0) {
            html += '<div class="alert alert-info py-1 px-2 small mb-0 mt-2">';
            html += data.notes.map(function (n) { return escapeHtml(n); }).join('<br>');
            html += '</div>';
        }

        el.innerHTML = html;
    }

    function renderRecommendedElementsTable(elements, color, showScore) {
        showScore = showScore !== false;
        var html = '<div class="table-responsive"><table class="table table-sm table-hover mb-2" style="font-size:0.82em;">';
        html += '<thead><tr><th>' + t('analyze.table.code') + '</th><th>' + t('analyze.table.title') + '</th><th>' + t('analyze.table.root') + '</th>'
            + (showScore ? '<th>' + t('analyze.table.score') + '</th>' : '') + '<th>' + t('analyze.table.reasoning') + '</th></tr></thead><tbody>';
        elements.forEach(function (e) {
            html += '<tr>';
            html += '<td><code>' + escapeHtml(e.nodeCode) + '</code></td>';
            html += '<td>' + escapeHtml(e.title || '') + '</td>';
            html += '<td><span class="badge bg-light text-dark border">' + escapeHtml(layerLabel(e.taxonomyRoot)) + '</span></td>';
            if (showScore) html += '<td><span class="badge bg-' + color + (color === 'warning' ? ' text-dark' : '') + '">' + escapeHtml(e.score) + '</span></td>';
            html += '<td class="small text-muted">' + escapeHtml(e.reasoning || '') + '</td>';
            html += '</tr>';
        });
        html += '</tbody></table></div>';
        return html;
    }

    // ── Enriched Failure Impact ───────────────────────────────────────────────

    function runEnrichedFailureImpact() {
        var input = document.getElementById('graphNodeInput');
        var code = input ? input.value.trim() : '';
        var graph = window.TaxonomyGraph;
        var sequence = graph.beginResult('node', code, 'enriched');
        if (!code) {
            var content = document.getElementById('graphResultsContent');
            if (content) {
                content.innerHTML = '<div class="alert alert-warning py-1 px-2 small mb-0">' + t('analyze.graph.enter.code') + '</div>';
            }
            return;
        }

        var maxHopsSelect = document.getElementById('graphMaxHops');
        var maxHops = maxHopsSelect ? parseInt(maxHopsSelect.value, 10) : 3;

        var area = document.getElementById('graphResultsArea');
        var content = document.getElementById('graphResultsContent');
        if (area) area.style.display = '';
        if (content) {
            content.innerHTML =
                '<div class="text-center text-muted py-2">' +
                '<div class="spinner-border spinner-border-sm me-1" role="status"></div> ' +
                t('analyze.graph.enriched.analyzing') + '</div>';
        }

        var isCurrent = beginPanelRequest('graphResultsContent');
        var selectedContext;
        window.AnalysisApi.captureContext().then(function (context) {
                if (!isCurrent() || !graph.isCurrentResult(sequence)) { var stale = new Error('Analysis context changed'); stale.code = 'ABORTED'; throw stale; }
                selectedContext = context;
                return window.AnalysisApi.enrichedFailureImpact(code, maxHops, context);
            })
            .then(async function (data) {
                var current = await graph.isCurrentEvidence(selectedContext, data.evidence);
                if (content && isCurrent() && graph.isCurrentResult(sequence)) {
                    content.innerHTML = current ? renderEnrichedFailureImpact(data)
                        : '<div class="alert alert-warning py-1 px-2 small mb-0">' + t('graph.evidence.changed') + '</div>';
                }
            })
            .catch(function (err) {
                if (err.code === 'ABORTED') return;
                if (content && isCurrent() && graph.isCurrentResult(sequence)) {
                    content.innerHTML = '<div class="alert alert-warning py-1 px-2 small mb-0">' + t('analyze.graph.enriched.failed', escapeHtml(err.message)) + '</div>';
                }
            });
    }

    function renderEnrichedFailureImpact(data) {
        var html = window.TaxonomyGraph?.renderEvidence?.(data.evidence) || '';

        // Summary stats
        html += '<div class="d-flex gap-2 mb-2 flex-wrap">';
        html += '<span class="badge bg-danger">' + t('analyze.graph.failed.node', escapeHtml(data.failedNodeCode)) + '</span>';
        html += '<span class="badge bg-danger">' + t('analyze.graph.direct', (data.directlyAffected ? data.directlyAffected.length : 0)) + '</span>';
        html += '<span class="badge bg-warning text-dark">' + t('analyze.graph.indirect', (data.indirectlyAffected ? data.indirectlyAffected.length : 0)) + '</span>';
        html += '<span class="badge bg-dark">' + t('analyze.graph.total', data.totalAffected) + '</span>';
        var riskKnown = Number.isFinite(data.riskScore);
        var riskColor = !riskKnown ? 'secondary' : data.riskScore > 5 ? 'danger' : (data.riskScore > 2 ? 'warning' : 'success');
        html += '<span class="badge bg-' + riskColor + (riskColor === 'warning' ? ' text-dark' : '') + '">' + t('analyze.graph.risk', riskKnown ? data.riskScore.toFixed(2) : t('analysis.evidence.notAssessed')) + '</span>';
        html += '</div>';
        if (data.uncorrelatedElementIds?.length) {
            html += '<div class="alert alert-warning py-1 px-2 small">' + t('analysis.evidence.correlationOpen', data.uncorrelatedElementIds.length)
                + '<div>' + data.uncorrelatedElementIds.map(escapeHtml).join(', ') + '</div></div>';
        }

        // Affected Requirements
        if (data.affectedRequirements && data.affectedRequirements.length > 0) {
            html += '<div class="alert alert-danger py-1 px-2 small mb-2">';
            html += '<strong>' + t('analyze.graph.affected.requirements', data.affectedRequirements.length) + '</strong> ';
            html += data.affectedRequirements.map(function (r) { return escapeHtml(r); }).join(', ');
            html += '</div>';
        }

        // Directly Affected
        if (data.directlyAffected && data.directlyAffected.length > 0) {
            html += '<h6 class="small fw-semibold">' + t('analyze.graph.directly.affected') + '</h6>';
            html += renderEnrichedElementsTable(data.directlyAffected);
        }

        // Indirectly Affected
        if (data.indirectlyAffected && data.indirectlyAffected.length > 0) {
            html += '<h6 class="small fw-semibold mt-2">' + t('analyze.graph.indirectly.affected') + '</h6>';
            html += renderEnrichedElementsTable(data.indirectlyAffected);
        }

        // Notes
        if (data.notes && data.notes.length > 0) {
            html += '<div class="alert alert-info py-1 px-2 small mb-0 mt-2">';
            html += data.notes.map(function (n) { return escapeHtml(n); }).join('<br>');
            html += '</div>';
        }

        return html;
    }

    function renderEnrichedElementsTable(elements) {
        var html = '<div class="table-responsive"><table class="table table-sm table-hover mb-2" style="font-size:0.82em;">';
        html += '<thead><tr><th>' + t('analyze.table.code') + '</th><th>' + t('analyze.table.title') + '</th><th>' + t('analyze.table.sheet') + '</th><th>' + t('analyze.table.relevance') + '</th><th>' + t('analyze.table.hop') + '</th><th>' + t('analyze.table.requirements') + '</th></tr></thead><tbody>';
        elements.forEach(function (e) {
            html += '<tr>';
            html += '<td><code>' + escapeHtml(e.nodeCode) + '</code></td>';
            html += '<td>' + escapeHtml(e.title || '') + '</td>';
            html += '<td><span class="badge bg-light text-dark border">' + escapeHtml(layerLabel(e.taxonomySheet)) + '</span></td>';
            html += '<td>' + (e.relevance * 100).toFixed(0) + '%</td>';
            html += '<td>' + e.hopDistance + '</td>';
            html += '<td>';
            if (e.coveredByRequirements && e.coveredByRequirements.length > 0) {
                html += '<span class="badge bg-danger">' + e.requirementCount + ' req(s)</span> ';
                html += '<span class="small text-muted">' + e.coveredByRequirements.map(function (r) { return escapeHtml(r); }).join(', ') + '</span>';
            } else {
                html += '<span class="text-muted small">' + t('analysis.evidence.notAssessed') + '</span>';
            }
            html += '</td>';
            html += '</tr>';
        });
        html += '</tbody></table></div>';
        return html;
    }

    // ── ArchiMate Import ──────────────────────────────────────────────────────

    function runArchiMateImport() {
        var fileInput = document.getElementById('archiMateImportFile');
        if (!fileInput) return;
        fileInput.value = '';
        fileInput.click();
    }

    function handleArchiMateFileSelect(e) {
        var file = e.target.files && e.target.files[0];
        if (!file) return;

        var formData = new FormData();
        formData.append('file', file);

        var statusEl = document.getElementById('archiMateImportStatus');
        if (statusEl) {
            statusEl.innerHTML =
                '<div class="text-center text-muted py-1">' +
                '<div class="spinner-border spinner-border-sm me-1" role="status"></div> ' +
                t('analyze.import.importing', escapeHtml(file.name)) + '</div>';
            statusEl.style.display = '';
        }

        var isCurrent = beginPanelRequest('archiMateImportStatus');
        window.ImportApi.importArchiMate(formData)
        .then(function (data) {
            if (statusEl && isCurrent()) {
                var html = '<div class="alert alert-success py-1 px-2 small mb-0">';
                html += t('analyze.import.success', (data.elementsImported || 0), (data.relationsImported || 0));
                if (data.notes && data.notes.length > 0) {
                    html += '<br><small>' + data.notes.map(function (n) { return escapeHtml(n); }).join('; ') + '</small>';
                }
                html += '</div>';
                statusEl.innerHTML = html;
            }
        })
        .catch(function (err) {
            if (statusEl && isCurrent()) {
                statusEl.innerHTML = '<div class="alert alert-danger py-1 px-2 small mb-0">' + t('analyze.import.failed', escapeHtml(err.message)) + '</div>';
            }
        });
    }

    // ── Copilot One-Click Flow ────────────────────────────────────────────────

    function renderCopilotResult(copilot) {
        Object.keys(assessmentTickets).forEach(function (kind) { assessmentTickets[kind]++; liveAssessments[kind] = null; });
        var panel = document.getElementById('copilotPanel'); if (panel) panel.style.display = '';
        if (!copilot) {
            renderPartialCopilot((document.documentElement?.lang || 'en').startsWith('de')
                ? 'Für dieses historische Ergebnis sind keine gespeicherten Copilot-Berichte vorhanden.'
                : 'This historical result has no saved Copilot reports.');
            return;
        }
        if (copilot.schemaVersion !== 2) {
            renderPartialCopilot(t('analysis.evidence.unsupportedVersion'));
            return;
        }
        if (copilot.state === 'SKIPPED_PARTIAL' || copilot.state === 'SKIPPED_SCOPE') {
            renderPartialCopilot((copilot.notes || []).join(' ') || ((document.documentElement?.lang || 'en').startsWith('de')
                ? 'Für diesen Analyseumfang sind globale Copilot-Aussagen offen.'
                : 'Global Copilot findings remain open for this analysis scope.'));
            return;
        }
        [['gaps', renderGapAnalysis, 'gapAnalysisPanel'], ['patterns', renderPatternDetection, 'patternDetectionPanel'],
            ['recommendation', renderRecommendation, 'recommendationPanel']].forEach(function (entry) {
            if (copilot[entry[0]]) {
                entry[1](copilot[entry[0]], { retained: true });
                var section = document.getElementById(entry[2]); if (section) section.open = true;
            } else {
                showPanelError({ gaps: 'gapAnalysisContent', patterns: 'patternDetectionContent',
                    recommendation: 'recommendationContent' }[entry[0]], (copilot.notes || []).join(' '));
            }
        });
        if (copilot.state === 'COMPLETED') renderCopilotSummary(copilot, { retained: true });
        else showCopilotStatus('warning', (copilot.notes || []).join(' ') || ((document.documentElement?.lang || 'en').startsWith('de')
            ? 'Copilot-Teilergebnis: Offene Beziehungen bleiben ungeklärt.'
            : 'Partial Copilot result: open relationships remain unresolved.'));
        resetCopilotBtn();
    }
    function runCopilotFlow() {
        var state = window.TaxonomyState || {};
        if (state.analysisOperationId || state.analysisRecovery || window.TaxonomyAnalysisRecovery?.isManaged?.()) {
            if (!state.currentCopilot && warnAboutOpenAssessments('copilotContent')) return Promise.resolve();
            renderCopilotResult(state.currentCopilot || null);
            return Promise.resolve(state.currentCopilot || null);
        }
        if (warnAboutOpenAssessments('copilotContent')) return;
        var bt = getBusinessText();
        if (!bt) {
            showCopilotStatus('warning', t('analyze.copilot.enter.requirement'));
            return;
        }

        var copilotBtn = document.getElementById('copilotBtn');
        var copilotSpinner = document.getElementById('copilotSpinner');
        if (copilotBtn) copilotBtn.disabled = true;
        if (copilotSpinner) copilotSpinner.classList.remove('d-none');

        var copilotPanel = document.getElementById('copilotPanel');
        if (copilotPanel) copilotPanel.style.display = '';
        var copilotContent = document.getElementById('copilotContent');
        if (copilotContent) {
            copilotContent.innerHTML =
                '<div class="text-center text-muted py-2">' +
                '<div class="spinner-border spinner-border-sm me-1" role="status"></div> ' +
                '<span id="copilotStepLabel">' + t('analyze.copilot.step1') + '</span></div>';
        }

        // The operation coordinator owns the complete scoring lifecycle and invokes
        // this flow only after an authoritative SUCCESS. Do not start a second
        // analysis or infer completion from a browser timer/score side effect here.
        if (!hasScores()) {
            showCopilotStatus('danger', t('analyze.copilot.cannot.start'));
            resetCopilotBtn();
            return;
        }
        return continueCopilotFlow();
    }

    async function continueCopilotFlow() {
        var results = {};
        var isCurrent = beginPanelRequest('copilotContent');
        var ticket = ++assessmentTickets.copilot;
        liveAssessments.copilot = {pending:true};
        var recovery = window.TaxonomyAnalysisRecovery;
        var input;
        async function step(key, label, request, render, panelId) {
            showCopilotStep(label);
            var execute = async function () {
                if (!isCurrent()) { var stale = new Error('Analysis context changed'); stale.code = 'ABORTED'; throw stale; }
                if (!await assessmentCurrent(input) || !isCurrent() || ticket !== assessmentTickets.copilot) throw new Error(t('analysis.evidence.selectionChanged'));
                var result = await request();
                if (!result || typeof result !== 'object' || Array.isArray(result)) {
                    throw new Error('Invalid Copilot response');
                }
                return result;
            };
            var data = recovery ? await recovery.stage(key, execute, label) : await execute();
            if (!isCurrent()) { var stale = new Error('Analysis context changed'); stale.code = 'ABORTED'; throw stale; }
            if (!await assessmentCurrent(input) || !isCurrent() || ticket !== assessmentTickets.copilot) throw new Error(t('analysis.evidence.selectionChanged'));
            if (key === 'gap') requireAssessmentProof(input, data);
            if (key === 'patterns') requirePatternProof(input, data.evidence);
            if (key === 'recommendation') requireAssessmentProof(input, data?.gapAnalysis);
            render(data, {live:input});
            var panel = document.getElementById(panelId); if (panel) panel.open = true;
            return data;
        }
        try {
            input = await assessmentInput({scores:getCurrentScores(), minScore:50});
            liveAssessments.copilot = input;
            results.gaps = await step('gap', t('analyze.copilot.step2'), function () {
                var body = Object.assign({}, input.body); delete body.scores; delete body.minScore;
                return window.AnalysisApi.analyzeGap(body, input.context);
            }, renderGapAnalysis, 'gapAnalysisPanel');
            results.patterns = await step('patterns', t('analyze.copilot.step3'), function () {
                return window.AnalysisApi.detectPatterns({scores:input.body.scores, minScore:input.body.minScore}, input.context);
            }, renderPatternDetection, 'patternDetectionPanel');
            results.recommendation = await step('recommendation', t('analyze.copilot.step4'), function () {
                return window.AnalysisApi.recommend(input.body, input.context);
            }, renderRecommendation, 'recommendationPanel');
            renderCopilotSummary(results, {live:input});
            return results;
        } catch (err) {
            if (isCurrent() && ticket === assessmentTickets.copilot && err.code !== 'ABORTED') showCopilotStatus('danger', t('analyze.copilot.failed', err.message));
            if (recovery && recovery.isManaged()) throw err;
        } finally { if (isCurrent()) resetCopilotBtn(); }
    }

    function renderPartialCopilot(message) {
        var panel = document.getElementById('copilotPanel'); if (panel) panel.style.display = '';
        showCopilotStatus('warning', message);
        ['gapAnalysisContent', 'patternDetectionContent', 'recommendationContent'].forEach(function (id) {
            showPanelError(id, message);
        });
        resetCopilotBtn();
    }

    function showCopilotStep(text) {
        var label = document.getElementById('copilotStepLabel');
        if (label) label.textContent = text;
    }

    function showCopilotStatus(type, msg) {
        var el = document.getElementById('copilotContent');
        if (el) {
            el.innerHTML = '<div class="alert alert-' + type + ' py-1 px-2 small mb-0">' + escapeHtml(msg) + '</div>';
        }
    }

    function resetCopilotBtn() {
        var copilotBtn = document.getElementById('copilotBtn');
        var copilotSpinner = document.getElementById('copilotSpinner');
        if (copilotBtn) copilotBtn.disabled = false;
        if (copilotSpinner) copilotSpinner.classList.add('d-none');
    }

    function renderCopilotSummary(results, options) {
        liveAssessments.copilot = options?.live || null;
        var retained = options?.retained === true;
        var el = document.getElementById('copilotContent');
        if (!el) return;
        var html = '';

        html += '<div class="alert alert-info py-2 px-3 mb-2" data-assessment-state="COMPLETED">';
        html += '<strong>' + t('analyze.copilot.complete') + '</strong>';
        if (options?.retained) html += ' · ' + t('analysis.evidence.retained');
        html += '</div>';

        html += '<div class="row g-2">';

        // Gaps summary card
        var judgments = results.gaps?.judgments || [];
        var gapCount = judgments.filter(function (item) { return item.status === 'VIOLATED'; }).length;
        var openCount = judgments.filter(function (item) { return item.status === 'INCONCLUSIVE'; }).length;
        var gapColor = gapCount ? 'danger' : openCount ? 'warning' : 'secondary';
        html += '<div class="col-4">';
        html += '<div class="card text-center border-' + gapColor + '" data-assessment-summary="obligations">';
        html += '<div class="card-body py-2">';
        html += '<div class="fs-4">\uD83D\uDD0E</div>';
        html += '<div class="fw-bold">' + (judgments.length ? t('analysis.evidence.violated', gapCount) : t('analysis.evidence.noObligations')) + '</div>';
        if (openCount) html += '<div class="small">' + t('analysis.evidence.openCount', openCount) + '</div>';
        html += '<div class="small text-muted">' + t('analyze.copilot.anchors.analyzed', (results.gaps ? results.gaps.totalAnchors : 0)) + '</div>';
        html += '</div></div></div>';

        // Patterns summary card
        var patternKnown = Number.isFinite(results.patterns?.patternCoverage);
        var patternPct = patternKnown ? displayPercent(results.patterns.patternCoverage) + '%' : t('analysis.evidence.notAssessed');
        var patternColor = 'secondary';
        html += '<div class="col-4">';
        html += '<div class="card text-center border-' + patternColor + '">';
        html += '<div class="card-body py-2">';
        html += '<div class="fs-4">\uD83E\uDDE9</div>';
        html += '<div class="fw-bold">' + t('analyze.patterns.coverage', patternPct) + '</div>';
        html += '<div class="small text-muted">' + t('analyze.copilot.patterns.complete', (results.patterns && results.patterns.matchedPatterns ? results.patterns.matchedPatterns.length : 0)) + '</div>';
        html += '</div></div></div>';

        // Recommendation summary card
        var conf = results.recommendation && Number.isFinite(results.recommendation.meanRelevance)
            ? results.recommendation.meanRelevance.toFixed(0) + '%' : t('analysis.evidence.notAssessed');
        var confColor = 'secondary';
        html += '<div class="col-4">';
        html += '<div class="card text-center border-' + confColor + '">';
        html += '<div class="card-body py-2">';
        html += '<div class="fs-4">\uD83D\uDCA1</div>';
        html += '<div class="fw-bold">' + t('analysis.recommendation.meanRelevance', conf) + '</div>';
        html += '<div class="small text-muted">' + t('analyze.copilot.proposals', (results.recommendation && results.recommendation.proposedElements ? results.recommendation.proposedElements.length : 0)) + '</div>';
        html += '</div></div></div>';

        html += '</div>';

        if (retained && results.notes?.length) {
            html += '<div class="alert alert-info py-1 px-2 small mt-2">'
                + results.notes.map(escapeHtml).join('<br>') + '</div>';
        }
        html += '<div class="small text-muted mt-2">' + t('analyze.copilot.scroll.hint') + '</div>';

        el.innerHTML = html;
    }

    // ── Event binding ─────────────────────────────────────────────────────────

    ['taxonomy:lifecycle-changed','taxonomy:architecture-reconciled','taxonomy:architecture-source-changed'].forEach(function (event) {
        document.addEventListener(event, function () { invalidateLiveAssessments(); updateAssessmentSourceLabel(); });
    });
    document.addEventListener('taxonomy:entity-context-changed', async function () {
        for (var kind of Object.keys(liveAssessments)) {
            var input = liveAssessments[kind];
            if (input && !await assessmentCurrent(input) && liveAssessments[kind] === input) {
                invalidateLiveAssessments(); break;
            }
        }
        updateAssessmentSourceLabel();
    });
    ['input','change'].forEach(function (event) {
        document.addEventListener(event, function (value) {
            if (['gapProfile','gapEvidenceComplete','businessText'].includes(value.target?.id)) invalidateLiveAssessments();
        });
    });

    document.addEventListener('DOMContentLoaded', function () {
        updateAssessmentSourceLabel();
        document.getElementById('gapSelectModel')?.addEventListener('click', async function () {
            try {
                var selected = await window.TaxonomyArchitectureWorkspace?.useSelectedModel?.();
                if (selected) await updateAssessmentSourceLabel();
                else showPanelError('gapAnalysisContent', t('analysis.evidence.selectSavedModel'));
            } catch (error) { showPanelError('gapAnalysisContent', error.message); }
        });
        if (window.TaxonomyState?.currentCopilot) renderCopilotResult(window.TaxonomyState.currentCopilot);
        if (document.getElementById('gapProfile')) loadAssessmentProfiles().catch(function (error) {
            showPanelError('gapAnalysisContent', error.message);
        });
        var gapBtn = document.getElementById('gapAnalyzeBtn');
        if (gapBtn) gapBtn.addEventListener('click', runGapAnalysis);

        var patternBtn = document.getElementById('patternDetectBtn');
        if (patternBtn) patternBtn.addEventListener('click', runPatternDetection);

        var recBtn = document.getElementById('recommendBtn');
        if (recBtn) recBtn.addEventListener('click', runRecommendation);

        var enrichedBtn = document.getElementById('graphEnrichedFailureBtn');
        if (enrichedBtn) enrichedBtn.addEventListener('click', runEnrichedFailureImpact);

        var copilotBtn = document.getElementById('copilotBtn');
        if (copilotBtn) copilotBtn.addEventListener('click', runCopilotFlow);

        var importBtn = document.getElementById('archiMateImportBtn');
        if (importBtn) importBtn.addEventListener('click', runArchiMateImport);

        var importFile = document.getElementById('archiMateImportFile');
        if (importFile) importFile.addEventListener('change', handleArchiMateFileSelect);
    });

    // ── Public API ────────────────────────────────────────────────────────────
    window.TaxonomyAnalysis = {
        runGapAnalysis: runGapAnalysis,
        runPatternDetection: runPatternDetection,
        runRecommendation: runRecommendation,
        runEnrichedFailureImpact: runEnrichedFailureImpact,
        runCopilotFlow: runCopilotFlow,
        renderCopilotResult: renderCopilotResult,
        renderPartialCopilot: renderPartialCopilot,
        runArchiMateImport: runArchiMateImport
    };

})();
