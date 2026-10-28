/* taxonomy-scoring.js – Analysis, Scoring & Streaming logic for the Taxonomy Browser */

(function () {
    'use strict';

    var t = TaxonomyI18n.t;
    var S = window.TaxonomyState;
    var recoverySelection = 0;
    // B (browse functions) resolved lazily — script may load before taxonomy-browse.js
    function B() { return window.TaxonomyBrowse || {}; }

    // ── Architecture Summary View ─────────────────────────────────────────────
    var LAYER_CONFIG = {
        // Full sheet names (used by fallback HTML and future backend changes)
        'Capabilities':           { order: 1, cls: 'layer-cap',  icon: '◆', label: 'Capabilities' },
        'Business Processes':     { order: 2, cls: 'layer-proc', icon: '▰', label: 'Business Processes' },
        'Business Roles':         { order: 2, cls: 'layer-proc', icon: '▰', label: 'Business Roles' },
        'Services':               { order: 3, cls: 'layer-svc',  icon: '⬡', label: 'Services' },
        'COI Services':           { order: 3, cls: 'layer-svc',  icon: '⬡', label: 'COI Services' },
        'Core Services':          { order: 3, cls: 'layer-svc',  icon: '⬡', label: 'Core Services' },
        'Applications':           { order: 4, cls: 'layer-app',  icon: '▣', label: 'Applications' },
        'User Applications':      { order: 4, cls: 'layer-app',  icon: '▣', label: 'User Applications' },
        'Information Products':   { order: 5, cls: 'layer-info', icon: '◇', label: 'Information Products' },
        'Communications Services':{ order: 6, cls: 'layer-comm', icon: '↔', label: 'Communications Services' },
        // 2-letter taxonomy-root prefixes (returned by RequirementArchitectureViewService)
        'CP':                     { order: 1, cls: 'layer-cap',  icon: '◆', label: 'Capabilities' },
        'BP':                     { order: 2, cls: 'layer-proc', icon: '▰', label: 'Business Processes' },
        'BR':                     { order: 2, cls: 'layer-proc', icon: '▰', label: 'Business Roles' },
        'CI':                     { order: 3, cls: 'layer-svc',  icon: '⬡', label: 'COI Services' },
        'CO':                     { order: 6, cls: 'layer-comm', icon: '↔', label: 'Communications Services' },
        'CR':                     { order: 3, cls: 'layer-svc',  icon: '⬡', label: 'Core Services' },
        'IP':                     { order: 5, cls: 'layer-info', icon: '◇', label: 'Information Products' },
        'UA':                     { order: 4, cls: 'layer-app',  icon: '▣', label: 'User Applications' }
    };

    // Minimum number of elements required to render the D3 force graph (otherwise show swimlane)
    var MIN_NODES_FOR_GRAPH = 3;

    // ── Utility ───────────────────────────────────────────────────────────────
    var escapeHtml = TaxonomyUtils.escapeHtml;

    function isProductScore(detail) {
        return detail && detail.kind === 'PRODUCT_SUITABILITY';
    }

    function clampScore(value) {
        // Do not coerce null/booleans/strings to measured evidence or round decimals.
        return typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= 100
            ? value : NaN;
    }

    function requireScore(value) {
        var score = clampScore(value);
        if (!Number.isFinite(score)) throw new Error('Invalid numeric percentage; this assessment remains open.');
        return score;
    }

    function scoreText(value) {
        var score = clampScore(value);
        if (!Number.isFinite(score)) return '—';
        if (score > 0 && score < 0.000001) return '<0.000001';
        return String(Number(score.toFixed(6)));
    }

    function percentageProduct(parent, suitability) {
        parent = requireScore(parent); suitability = requireScore(suitability);
        // Use decimal coefficients like the server. 0.1 * 0.2 / 100 must not
        // acquire an extra binary rounding digit in a subsequently saved score.
        function decimal(value) {
            var parts = String(value).split(/[eE]/), digits = parts[0].split('.');
            return { coefficient: BigInt(digits.join('')),
                exponent: Number(parts[1] || 0) - (digits[1] || '').length };
        }
        var a = decimal(parent), b = decimal(suitability);
        var value = Number(String(a.coefficient * b.coefficient) + 'e' + (a.exponent + b.exponent - 2));
        if (parent > 0 && suitability > 0 && value === 0)
            throw new Error('Unsupported percentage precision; this assessment remains open.');
        return requireScore(value);
    }

    function productParentScore(detail) {
        var parentScore = detail && detail.parentCode
            ? (S.currentRawScores || {})[detail.parentCode] : null;
        // Null, blank text and booleans are not measured zeroes.
        if (Number.isFinite(parentScore)) return parentScore;
        return detail && Number.isFinite(detail.parentScore) ? detail.parentScore : null;
    }

    function productRelevancePending(detail) {
        return isProductScore(detail) && productParentScore(detail) === null
            && !Number.isFinite(detail.effectiveRelevance);
    }

    function effectiveProductScore(rawScore, detail) {
        var parentScore = productParentScore(detail);
        if (parentScore === null) return 0;
        return percentageProduct(parentScore, rawScore);
    }

    function effectiveIncrementalScores(rawScores, details) {
        var effective = {};
        Object.entries(rawScores || {}).forEach(function ([code, raw]) {
            var detail = details && details[code] ? details[code] : null;
            effective[code] = isProductScore(detail)
                ? effectiveProductScore(raw, detail)
                : requireScore(raw);
        });
        return effective;
    }

    function isGermanLocale() {
        var locale = window.TaxonomyI18n && typeof window.TaxonomyI18n.getLocale === 'function'
            ? window.TaxonomyI18n.getLocale()
            : document.documentElement.lang;
        return String(locale || '').toLowerCase().startsWith('de');
    }

    function scoreLabel(code, effective, raw, detail) {
        if (isProductScore(detail)) {
            if (productRelevancePending(detail)) {
                return isGermanLocale()
                    ? 'Eignung ' + scoreText(raw) + '% · effektive Relevanz ausstehend'
                    : 'Suitability ' + scoreText(raw) + '% · effective relevance pending';
            }
            return isGermanLocale()
                ? 'Eignung ' + scoreText(raw) + '% · effektiv ' + scoreText(effective) + '/100'
                : 'Suitability ' + scoreText(raw) + '% · effective ' + scoreText(effective) + '/100';
        }
        return scoreText(effective) + '%';
    }

    function scoreAriaLabel(code, effective, raw, detail) {
        if (isProductScore(detail)) {
            if (productRelevancePending(detail)) {
                return isGermanLocale()
                    ? 'Produkteignung: ' + scoreText(raw) + ' Prozent, effektive Relevanz ausstehend'
                    : 'Product suitability: ' + scoreText(raw) + ' percent, effective relevance pending';
            }
            return isGermanLocale()
                ? 'Produkteignung: ' + scoreText(raw)
                    + ' Prozent, effektive Relevanz: ' + scoreText(effective) + ' von 100'
                : 'Product suitability: ' + scoreText(raw)
                    + ' percent, effective relevance: ' + scoreText(effective) + ' of 100';
        }
        return isGermanLocale()
            ? 'Bewertung: ' + scoreText(effective) + ' Prozent'
            : 'Score: ' + scoreText(effective) + ' percent';
    }

    function applyScoreEnvelope(envelope) {
        envelope = envelope || {};
        S.currentRawScores = envelope.rawScores || envelope.scores || {};
        S.currentEffectiveScores = envelope.effectiveScores || envelope.scores || {};
        S.currentScoreDetails = envelope.scoreDetails || {};
        S.currentProductSuitabilityScores = envelope.productSuitabilityScores || {};
        S.scoreSemanticsVersion = envelope.scoreSemanticsVersion || 0;
        S.currentScoreSemanticsWarnings = envelope.scoreSemanticsWarnings || [];
        S.currentScores = S.currentEffectiveScores;
        S.lastAnalysisDurationMillis = Number.isSafeInteger(envelope.analysisDurationMillis)
            && envelope.analysisDurationMillis >= 0 ? envelope.analysisDurationMillis : null;
        if (typeof B().refreshAnalysisDuration === 'function') B().refreshAnalysisDuration();
        refreshScoreStatistics();
    }

    function refreshScoreStatistics() {
        var statistics = window.TaxonomyResultStatistics;
        if (statistics && typeof statistics.refresh === 'function') statistics.refresh();
    }

    function describeScore(code, effective) {
        var detail = (S.currentScoreDetails || {})[code];
        var raw = (S.currentRawScores || {})[code] ?? effective;
        return {
            visible: Number.isFinite(effective) && (effective > 0 || isProductScore(detail)),
            label: scoreLabel(code, effective, raw, detail),
            ariaLabel: scoreAriaLabel(code, effective, raw, detail),
            tooltip: t(isProductScore(detail)
                ? 'scoring.score.tooltip.product' : 'scoring.score.tooltip.relevance')
        };
    }

    // Explicit compatibility boundary for interactive provider batches and expert-entered raw
    // scores. All browser consumers receive comparable relevance, including report requests and
    // saved drafts. Resolve the role/parent once from the loaded catalogue, never from percentages.
    function applyLocalRawScores(scores, replace, allowUnresolvedProviderKeys, livePreview) {
        var raw = replace ? {} : Object.assign({}, S.currentRawScores || {});
        Object.entries(scores || {}).forEach(function ([code, value]) {
            raw[code] = requireScore(value);
        });
        var contexts = new Map();
        var visited = new Set();
        function index(nodes, parentCode) {
            (nodes || []).forEach(function (node) {
                var code = String(node.code || '').trim();
                if (!code || visited.has(node) || contexts.has(code)) {
                    throw new Error('Cannot interpret scores from an ambiguous taxonomy.');
                }
                visited.add(node);
                contexts.set(code, {
                    parentCode: String(node.parentCode || '').trim() || parentCode || null,
                    role: String(node.analysisRole || 'CATEGORY').trim().toUpperCase()
                });
                index(node.children, code);
            });
        }
        index(S.taxonomyData, null);
        var effective = {}, details = {}, suitability = {}, warnings = [];
        Object.entries(raw).forEach(function ([code, value]) {
            var context = contexts.get(code);
            if (!context) {
                if (!allowUnresolvedProviderKeys && Object.prototype.hasOwnProperty.call(scores || {}, code)) {
                    throw new Error('Cannot resolve taxonomy score ' + code + '.');
                }
                // Retained unresolved provider evidence must not prevent a valid local edit.
                // Only newly entered keys are subject to the strict expert-input boundary.
                // Match AnalysisScoreSemantics at the provider boundary without inventing a catalogue node.
                // Expert-entered local scores retain the strict identity check above.
                effective[code] = value;
                details[code] = { nodeCode: code, kind: 'HIERARCHICAL_RELEVANCE',
                    rawScore: value, effectiveRelevance: value, parentCode: null, parentScore: null };
                if (warnings.length < 100) warnings.push('Score semantics could not resolve taxonomy node ' + code
                    + '; treating its value as hierarchical relevance.');
                else if (warnings.length === 100) warnings.push('Additional score-semantics warnings were suppressed.');
                return;
            }
            var parentScore = context.parentCode && Number.isFinite(raw[context.parentCode])
                ? raw[context.parentCode] : null;
            var product = context.role === 'PRODUCT';
            var pending = product && parentScore === null && livePreview === true;
            var relevance = product
                ? (parentScore === null ? 0 : percentageProduct(parentScore, value)) : value;
            if (product) {
                suitability[code] = value;
                if (parentScore === null && warnings.length < 100) {
                    warnings.push('Concrete product ' + code
                        + ' has no evaluated direct family score; its effective relevance is '
                        + (pending ? 'pending.' : '0.'));
                }
            }
            effective[code] = relevance;
            details[code] = {
                nodeCode: code,
                kind: product ? 'PRODUCT_SUITABILITY'
                    : (context.parentCode ? 'HIERARCHICAL_RELEVANCE' : 'ROOT_RELEVANCE'),
                // Keep the numeric preview placeholder separate from authoritative zero evidence.
                rawScore: value, effectiveRelevance: pending ? null : relevance,
                parentCode: context.parentCode, parentScore: parentScore
            };
        });
        applyScoreEnvelope({ rawScores: raw, effectiveScores: effective, scoreDetails: details,
            productSuitabilityScores: suitability, scoreSemanticsVersion: 2,
            scoreSemanticsWarnings: warnings });
        return effective;
    }

    // ── UI helpers ────────────────────────────────────────────────────────────
    function setAnalyzing(on) {
        const btn = document.getElementById('analyzeBtn');
        if (!btn) { return; }

        let spinner = document.getElementById('analyzeSpinner');
        if (!spinner) {
            spinner = document.createElement('span');
            spinner.id = 'analyzeSpinner';
            spinner.className = 'spinner-border spinner-border-sm me-1';
            spinner.setAttribute('role', 'status');
            spinner.setAttribute('aria-hidden', 'true');
        }

        btn.disabled = on;
        spinner.classList.toggle('d-none', !on);
        const label = on ? t('scoring.analyzing') : t('scoring.analyze.btn');
        btn.replaceChildren(spinner, document.createTextNode(' ' + label));
    }

    function clearAnalysisLog(preserveArchitecture) {
        const log = document.getElementById('analysisLog');
        if (log) { log.style.display = 'none'; }
        const logContent = document.getElementById('analysisLogContent');
        if (logContent) { logContent.innerHTML = ''; }
        if (!preserveArchitecture) {
            const archPanel = document.getElementById('architectureViewPanel');
            if (archPanel) { archPanel.style.display = 'none'; }
        }
        // Also hide suggested relations
        const sugPanel = document.getElementById('suggestedRelationsPanel');
        if (sugPanel) { sugPanel.style.display = 'none'; }
    }

    function updateAnalysisLog(info) {
        const logEl = document.getElementById('analysisLog');
        const logContent = document.getElementById('analysisLogContent');
        if (!logEl || !logContent) { return; }

        const timeStr = info.timestamp.toLocaleTimeString();
        const matchedList = info.matchedEntries.length > 0
            ? info.matchedEntries.map(([k, v]) => {
                var detail = S.currentScoreDetails ? S.currentScoreDetails[k] : null;
                var raw = S.currentRawScores && S.currentRawScores[k] !== undefined
                    ? S.currentRawScores[k] : v;
                return escapeHtml(k) + ': ' + escapeHtml(scoreLabel(k, v, raw, detail));
            }).join(', ')
            : '(none)';
        const warnHtml = info.warnings.length > 0
            ? '<div class="text-warning mt-1"><strong>Warnings:</strong><ul class="mb-0 ps-3">' +
              info.warnings.map(w => '<li>' + escapeHtml(w) + '</li>').join('') + '</ul></div>'
            : '';

        logContent.innerHTML =
            '<div><strong>Time:</strong> ' + timeStr + '</div>' +
            '<div><strong>Nodes evaluated:</strong> ' + info.totalNodes + '</div>' +
            '<div><strong>Status:</strong> ' + escapeHtml(info.status || 'unknown') + '</div>' +
            '<div><strong>' + (isGermanLocale() ? 'Analysedauer:' : 'Analysis duration:') + '</strong> '
                + (Number.isSafeInteger(S.lastAnalysisDurationMillis) && S.lastAnalysisDurationMillis >= 0
                    ? Math.floor(S.lastAnalysisDurationMillis / 60000) + ' min '
                        + String(Math.floor(S.lastAnalysisDurationMillis / 1000) % 60).padStart(2, '0') + ' s'
                    : (isGermanLocale() ? 'Nicht aufgezeichnet' : 'Not recorded')) + '</div>' +
            '<div><strong>Matched codes (' + info.matchedEntries.length + '):</strong> ' + matchedList + '</div>' +
            warnHtml;
        logEl.style.display = '';
    }

    function llmLogDiagnosticField(label, value, className) {
        const section = document.createElement('div');
        section.className = 'mt-1';

        const heading = document.createElement('div');
        heading.className = 'd-flex align-items-center justify-content-between gap-2';
        const title = document.createElement('strong');
        title.textContent = label;
        const copy = document.createElement('button');
        copy.type = 'button';
        copy.className = 'btn btn-sm btn-outline-secondary ms-2 llm-log-copy';
        copy.textContent = isGermanLocale() ? 'Kopieren' : 'Copy';
        copy.setAttribute('aria-label', (isGermanLocale() ? 'Kopieren: ' : 'Copy: ') + label);
        copy.disabled = !value;
        copy.addEventListener('click', async function () {
            if (!value) return;
            try {
                await TaxonomyUtils.copyText(value);
                copy.textContent = isGermanLocale() ? 'Kopiert' : 'Copied';
            } catch (_) {
                copy.textContent = isGermanLocale() ? 'Kopieren fehlgeschlagen' : 'Copy failed';
            }
        });
        heading.appendChild(title);
        heading.appendChild(copy);

        const text = document.createElement('div');
        text.className = className;
        text.textContent = value;
        section.appendChild(heading);
        section.appendChild(text);
        return section;
    }

    function appendLlmLogEntry(parentCode, scores, detail) {
        const content = document.getElementById('llmCommLogContent');
        if (!content) { return; }

        // Remove the placeholder message on first entry
        const placeholder = content.querySelector('.text-muted.p-2');
        if (placeholder) { placeholder.remove(); }

        const now = new Date();
        const timeStr = now.toLocaleTimeString();
        const nodeCount = scores ? Object.keys(scores).length : 0;
        const matchCount = scores ? Object.values(scores).filter(v => v > 0).length : 0;
        const provider = (detail && detail.provider) ? detail.provider.toUpperCase() : 'UNKNOWN';
        const durationSec = (detail && detail.durationMs) ? (detail.durationMs / 1000).toFixed(1) : '?';
        const prompt = (detail && detail.prompt) ? detail.prompt : '';
        const rawResponse = (detail && detail.rawResponse) ? detail.rawResponse : '';
        const errorMsg = (detail && detail.error) ? detail.error : null;
        const rootClarification = typeof errorMsg === 'string' && errorMsg.startsWith('ROOT_UNRESOLVED:');
        const reasons = (detail && detail.reasons) ? detail.reasons : {};

        const entry = document.createElement('details');
        entry.className = 'llm-log-entry' + (errorMsg && !rootClarification ? ' llm-log-error' : '');

        const summary = document.createElement('summary');
        summary.style.cursor = 'pointer';
        const statusBadge = rootClarification
            ? '<span>' + escapeHtml(t('browse.container.area.clarification')) + '</span>' : errorMsg
            ? '<span class="llm-log-error-badge">&#10060; ERROR</span>'
            : '<span class="text-success">&#10003; ' + matchCount + ' match' + (matchCount !== 1 ? 'es' : '') + '</span>';
        summary.innerHTML =
            '&#128100; <strong>' + escapeHtml(timeStr) + '</strong> — ' +
            '<code>' + escapeHtml(parentCode) + '</code> ' +
            '(' + nodeCount + ' nodes) via <strong>' + escapeHtml(provider) + '</strong> ' +
            '[' + durationSec + 's] ' +
            statusBadge;
        entry.appendChild(summary);

        const body = document.createElement('div');
        body.className = 'px-2 pb-2';

        const errorHtml = rootClarification
            ? '<div class="mt-1"><strong>' + escapeHtml(t('browse.container.area.clarification')) + ':</strong> '
                + escapeHtml(errorMsg.substring('ROOT_UNRESOLVED:'.length).trim()) + '</div>' : errorMsg
            ? '<div class="mt-1 llm-log-error-detail"><strong>&#9888; ERROR:</strong> ' + escapeHtml(errorMsg) + '</div>'
            : '';

        // Build reasons HTML if any reasons were returned
        const reasonEntries = Object.entries(reasons).filter(([, r]) => r);
        let reasonsHtml = '';
        if (reasonEntries.length > 0) {
            const reasonLines = reasonEntries.map(([code, reason]) => {
                const raw = scores && scores[code];
                const scoreDetail = detail && detail.scoreDetails
                    ? detail.scoreDetails[code] : (S.currentScoreDetails || {})[code];
                const effective = !Number.isFinite(raw) ? null : S.currentScores && S.currentScores[code] !== undefined
                    ? S.currentScores[code]
                    : (isProductScore(scoreDetail)
                        ? effectiveProductScore(raw, scoreDetail) : raw);
                const unresolvedRoot = detail && detail.rootAssessments && detail.rootAssessments[code]
                    && detail.rootAssessments[code].status === 'UNRESOLVED';
                const label = Number.isFinite(raw) ? scoreLabel(code, effective, raw, scoreDetail)
                    : t(unresolvedRoot ? 'browse.container.area.clarification' : 'browse.container.area.unassessed');
                return '<div class="llm-log-reason-entry"><code>' + escapeHtml(code) + '</code> '
                    + '(' + escapeHtml(label) + '): '
                    + escapeHtml(reason) + '</div>';
            }).join('');
            reasonsHtml = '<div class="mt-1"><strong>&#128172; REASONS:</strong>'
                + '<div class="llm-log-reasons">' + reasonLines + '</div></div>';
        }

        body.innerHTML = errorHtml;
        body.appendChild(llmLogDiagnosticField('\u{1F4E4} PROMPT:', prompt, 'llm-log-prompt'));
        body.appendChild(llmLogDiagnosticField('\u{1F4E5} RESPONSE:', rawResponse, 'llm-log-response'));
        if (reasonsHtml) {
            const reasonsContainer = document.createElement('div');
            reasonsContainer.innerHTML = reasonsHtml;
            body.appendChild(reasonsContainer);
        }

        entry.appendChild(body);

        // Prepend so newest entries appear at top
        content.insertBefore(entry, content.firstChild);
    }

    // A live preview can omit an explanation; absence does not erase saved evidence.
    function mergeAnalysisReasons(reasons) {
        var changed = [];
        S.currentReasons = S.currentReasons || {};
        Object.entries(reasons || {}).forEach(function ([code, reason]) {
            if (typeof reason !== 'string' || !reason.trim() || S.currentReasons[code] === reason) return;
            S.currentReasons[code] = reason;
            changed.push(code);
        });
        return changed;
    }

    // A single setting describes the reasons only while every contributing batch
    // records that same setting. Do not relabel older or mixed interactive evidence.
    function acceptAnalysisReasonLength(result, replace) {
        var language = ['de', 'en'].includes(result?.analysisLanguage) ? result.analysisLanguage : null;
        if (replace || Object.keys(S.currentReasons || {}).length === 0) {
            S.lastAnalysisLanguage = language;
        } else if (Object.keys(result?.reasons || {}).length > 0 && S.lastAnalysisLanguage !== language) {
            S.lastAnalysisLanguage = null;
        }
        if (language) S.activeAnalysisLanguage = language;
        var value = ['short', 'standard', 'detailed'].includes(result?.analysisReasonLength)
            ? result.analysisReasonLength : null;
        if (replace || Object.keys(S.currentReasons || {}).length === 0) {
            S.lastAnalysisReasonLength = value;
        } else if (Object.keys(result?.reasons || {}).length > 0
                && S.lastAnalysisReasonLength !== value) {
            S.lastAnalysisReasonLength = null;
        }
    }

    function applyAnalysisResult(result, settings) {
        var text = settings.text, requestedScope = settings.requestedScope, provider = settings.provider;
        var options = settings.options || {}, analysisStart = settings.analysisStart || new Date();
        setAnalyzing(false);
        if (window.__TaxonomyAnalysisSessionContext) window.__TaxonomyAnalysisSessionContext.runtime.preserveRestoredEvidence = false;
        if (Array.isArray(result.tree) && result.tree.length) S.taxonomyData = result.tree;
        applyScoreEnvelope(result);
        S.analysisCoverage = result.analysisCoverage || null;
        S.lastAnalysisScope = result.analysisScope || requestedScope || null;
        window.TaxonomyAnalysisScope?.acceptResult?.(S.lastAnalysisScope);
        S.analysisRecovery = result.recovery || null;
        S.currentCopilot = result.copilot || null;
        acceptAnalysisReasonLength(result, true);
        S.currentReasons = result.reasons || {};
        S.currentDiscrepancies = result.discrepancies || [];
        S.currentProductCoverageGaps = result.productCoverageGaps || [];
        S.lastAnalysisProvider = result.provider || provider || null;
        S.analysisProviderId = result.analysisProviderId || provider || null;
        S.analysisOperationId = result.analysisOperationId || settings.operationId || null;
        S.lastAnalysisStatus = result.status || 'UNKNOWN';
        S.storedBusinessText = text;
        S.lastAnalyzedText = text;
        B().renderView(S.taxonomyData, S.currentScores);

        console.log('[Taxonomy] Analysis result:', result);
        console.log('[Taxonomy] Scores:', result.scores);
        const matchedEntries = Object.entries(result.scores).filter(([k, v]) => v > 0);
        console.log('[Taxonomy] Matched nodes:', matchedEntries);

        const matchedCount = Object.values(result.scores).filter(v => v > 0).length;

        if (result.status === 'SUCCESS') {
            if (matchedCount === 0) {
                B().showStatus('warning', t('analyze.zero.matches'));
            } else {
                B().showStatus('success', t('analyze.complete', matchedCount));
            }
        } else if (result.status === 'PARTIAL') {
            B().showStatus('warning',
                t('analyze.partial', (result.errorMessage || t('analyze.incomplete')), matchedCount));
        } else if (result.status === 'CANCELLED') {
            B().showStatus('warning', result.errorMessage || 'Analysis cancelled; valid partial results retained.');
        } else if (result.status === 'ERROR') {
            B().showStatus('danger',
                '❌ ' + t('scoring.analysis.failed', result.errorMessage || 'Unknown error.'));
        } else {
            if (matchedCount === 0) {
                B().showStatus('warning', t('analyze.zero.matches'));
            } else {
                B().showStatus('success', t('analyze.complete', matchedCount));
            }
        }

        if (result.warnings && result.warnings.length > 0) {
            const warningList = result.warnings
                .map(w => '<li>' + escapeHtml(w) + '</li>')
                .join('');
            document.getElementById('statusArea').innerHTML +=
                '<ul class="mb-0 mt-1 ps-3" style="font-size:0.9em">' + warningList + '</ul>';
        }

        const analysisFindings = [];
        if (S.currentDiscrepancies.length > 0) {
            analysisFindings.push(t(
                'analyze.discrepancies', S.currentDiscrepancies.length));
        }
        if (S.currentProductCoverageGaps.length > 0) {
            analysisFindings.push(t(
                'analyze.product.coverage.gaps',
                S.currentProductCoverageGaps.length));
        }
        if (analysisFindings.length > 0) {
            document.getElementById('statusArea').innerHTML +=
                '<div class="mt-1">' + analysisFindings
                    .map(escapeHtml).join(' ') + '</div>';
        }

        // Update analysis log panel
        updateAnalysisLog({
            timestamp: analysisStart,
            totalNodes: Object.keys(result.scores).length,
            matchedEntries: matchedEntries,
            warnings: result.warnings || [],
            status: result.status
        });

        // Relation evidence is a result in its own right, not an optional diagram attachment.
        var hasRelationEvidence = Object.prototype.hasOwnProperty.call(result, 'relationSearchReport')
            || (result.architectureView && Object.prototype.hasOwnProperty.call(
                result.architectureView, 'relationSearchReport'));
        if (!options.recoveryManaged || hasRelationEvidence) {
            acceptRelationSearchReport(result);
        }

        // Render architecture view if present
        if (result.architectureView) {
            renderArchitectureView(result.architectureView);
        } else if (!options.recoveryManaged) {
            renderArchitectureView(null);
        }
        // Render suggested relationships from provisional relations
        renderSuggestedRelations(result.provisionalRelations);

        // Render ViewContext provenance info
        if (window.TaxonomyViewContext) {
            window.TaxonomyViewContext.renderFromResponse('analyzeViewContext', result);
        }

        // Store architecture view and show summary button
        if (result.architectureView) {
            S.currentArchView = result.architectureView;
            var summaryBtn = document.getElementById('viewSummary');
            if (summaryBtn) summaryBtn.style.display = '';
            // Keep the user's diagram selection and viewport during recovery.
            if (!options.recoveryManaged) B().switchView('summary');
            // Add quick action to navigate to Architecture tab
            var statusArea = document.getElementById('statusArea');
            if (statusArea && window.navigateToPage) {
                var archLink = document.createElement('div');
                archLink.className = 'mt-2';
                var archBtn = document.createElement('button');
                archBtn.className = 'btn btn-sm btn-outline-primary';
                archBtn.textContent = '\uD83C\uDFDB\uFE0F ' + t('scoring.view.architecture');
                archBtn.addEventListener('click', function () {
                    window.navigateToPage('architecture');
                });
                archLink.appendChild(archBtn);
                statusArea.appendChild(archLink);
            }
        }
        window.TaxonomyAnalysis?.renderCopilotResult?.(S.currentCopilot);
        window.__TaxonomyAnalysisSessionContext?.retainAnalysisReference?.(result, { businessText: text, durable: settings.durable === true });
        return result;
    }

    // ── Analysis ──────────────────────────────────────────────────────────────
    function runAnalysis(options) {
        options = options || {};
        const text = document.getElementById('businessText').value.trim();
        if (!text) {
            B().showStatus('warning', t('scoring.enter.requirement'));
            return;
        }

        // Dynamic lifecycle modules install observation, routing and workspace guards together.
        // A premature click must not clear prior results or silently start an unobservable analysis.
        const session = window.TaxonomyAnalysisSession;
        const sessionState = session && typeof session.state === 'function' ? session.state() : null;
        if (!sessionState || sessionState.ready !== true || window.__taxonomyAnalysisSessionLoading
                || !window.TaxonomyAnalysisProgress
                || typeof window.TaxonomyAnalysisProgress.start !== 'function') {
            B().showStatus('warning', t('scoring.lifecycle.not.ready'));
            return;
        }

        // Analysis produces workspace-scoped evidence. Central read-only context may be
        // observed, but cannot enter the isolated-workspace analysis endpoints.
        if (typeof sessionState.workspaceId !== 'string' || !sessionState.workspaceId.trim()) {
            B().showStatus('warning', t('guard.blocked.readonly'));
            return;
        }

        var requestedScope;
        try {
            requestedScope = options.continuation ? options.continuation.analysisScope
                : window.TaxonomyAnalysisScope?.read();
        } catch (invalidScope) {
            B().showStatus('warning', invalidScope.message);
            return;
        }

        if (!options.continuation && requestedScope?.mode === 'TAXONOMIES_ONLY'
                && window.TaxonomyAnalysisRecovery?.startManagedAnalysis) {
            return window.TaxonomyAnalysisRecovery.startManagedAnalysis();
        }

        recoverySelection++;
        console.log('[Taxonomy] Starting analysis with text:', text.substring(0, 100) + '...');
        const analysisStart = new Date();
        var operationId;
        try {
            if (typeof crypto !== 'undefined' && crypto && typeof crypto.randomUUID === 'function') {
                operationId = crypto.randomUUID();
            } else if (typeof crypto !== 'undefined' && crypto && typeof crypto.getRandomValues === 'function') {
                operationId = '10000000-1000-4000-8000-100000000000'.replace(/[018]/g, function (c) {
                    return (Number(c) ^ crypto.getRandomValues(new Uint8Array(1))[0] & 15 >> Number(c) / 4).toString(16);
                });
            } else {
                B().showStatus('danger', t('scoring.secure.id.unavailable'));
                return;
            }
        } catch (error) {
            // Disabled browser cryptography must not erase existing results or start untracked work.
            B().showStatus('danger', t('scoring.secure.id.unavailable'));
            return;
        }
        if (!options.continuation || options.newOperation === true) {
        B().resetDiagramContext?.();
        S.currentReasons = {};
        S.currentDiscrepancies = [];
        S.currentProductCoverageGaps = [];
        clearDerivedAnalysisView();
        S.evaluatedNodes = new Set();
        S.storedBusinessText = null;
        S.lastAnalyzedText = null;
        S.pendingProposalNodeCode = null;
        S.lastAnalysisProvider = null;
        S.analysisProviderId = null;
        S.analysisOperationId = null;
        S.lastAnalysisScope = null;
        S.lastAnalysisReasonLength = null;
        S.lastAnalysisLanguage = null;
        S.activeAnalysisLanguage = null;
        window.TaxonomyAnalysisScope?.acceptResult?.(null);
        window._currentProvisionalRelations = [];
        S.lastAnalysisDurationMillis = null;
        S.lastAnalysisStatus = 'IN_PROGRESS';
        applyLocalRawScores({}, true);
        var lifecycle = window.__TaxonomyAnalysisSessionContext;
        if (lifecycle && typeof lifecycle.clearDerivedUi === 'function') lifecycle.clearDerivedUi();
        B().renderView(S.taxonomyData, S.currentScores);
        S.analysisCoverage = null;
        if (!options.recoveryManaged) { S.analysisRecovery = null; S.recoveryContext = null; }
        }
        var resultHandled = false, acceptedResult, clusterObserved = false, resolveRecovered;
        var recoveredResult = new Promise(function (resolve) { resolveRecovered = resolve; });
        function acceptResult(result) {
            if (resultHandled) return acceptedResult;
            // Polling may have stopped after observing completion, but only the latest
            // in-scope operation owns this full response and may update the application.
            if (progress && !progress.acceptsResult()) return;
            if (options.recoveryManaged && document.getElementById('businessText').value.trim() !== text) {
                if (progress) progress.finish(result.status, false);
                setAnalyzing(false); return result;
            }
            if (progress) progress.finish(result.status, true, result.analysisDurationMillis);
            acceptedResult = applyAnalysisResult(result, { text: text, requestedScope: requestedScope, provider: provider,
                options: options, analysisStart: analysisStart, operationId: operationId, durable: clusterObserved });
            resultHandled = true;
            return acceptedResult;
        }
        var progress = window.TaxonomyAnalysisProgress.start(operationId, function (snapshot) {
                if (resultHandled || progress && !progress.acceptsResult()) return;
                clusterObserved = snapshot.transport === 'artemis' || clusterObserved;
                var previous = S.currentEffectiveScores || {};
                var previousRaw = S.currentRawScores || {};
                var previousDetails = S.currentScoreDetails || {};
                applyLocalRawScores(options.continuation
                    ? Object.assign({}, previousRaw, snapshot.rawScores || {}) : snapshot.rawScores || {}, true, true, true);
                var reasonChanges = mergeAnalysisReasons(snapshot.reasons);
                var changed = Array.from(new Set(Object.keys(S.currentScores || {}).filter(function (code) {
                    var before = previousDetails[code] || {}, after = S.currentScoreDetails[code] || {};
                    return previous[code] !== S.currentScores[code] || previousRaw[code] !== S.currentRawScores[code]
                        || before.effectiveRelevance !== after.effectiveRelevance || before.parentScore !== after.parentScore;
                }).concat(reasonChanges)));
                if (!changed.length) return;
                if ((S.currentView === 'list' || S.currentView === 'tabs') && !B().isRelevanceFilterActive?.()) {
                    withScoreNodeUpdates(function () {
                        changed.forEach(function (code) {
                            applyScoreToNode(code, S.currentScores[code], S.currentReasons[code],
                                S.currentScoreDetails[code], S.currentRawScores[code], true);
                        });
                    });
                    if (B().refreshContainerSummaries) B().refreshContainerSummaries(S.currentScores);
                } else {
                    B().renderView(S.taxonomyData, S.currentScores);
                }
            }, function (result) {
                clusterObserved = true; // Result delivery by the durable run monitor.
                var accepted = acceptResult(result);
                if (accepted) resolveRecovered(accepted);
            });


        setAnalyzing(true);
        B().clearStatus();
        clearAnalysisLog(Boolean(options.continuation));
        document.getElementById('businessText').classList.remove('stale-results');

        var providerSelect = document.getElementById('providerSelect');
        var provider = providerSelect ? providerSelect.value : '';

        var requestBody = {
            businessText: text,
            analysisLanguage: window.TaxonomyContentLanguage?.analysis(),
            includeArchitectureView: requestedScope?.mode !== 'TAXONOMIES_ONLY'
                && document.getElementById('includeArchitectureView').checked,
            analysisScope: requestedScope
        };
        if (provider && provider !== 'MANUAL') {
            requestBody.provider = provider;
        }

        if (options.continuation) requestBody = Object.assign({}, options.continuation);
        S.activeAnalysisLanguage = requestBody.analysisLanguage || null;
        const workspacePin = sessionState.workspaceId == null ? '' : sessionState.workspaceId;
        var posted = fetch('/api/analyze', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'X-Analysis-Operation-Id': operationId,
                'X-Taxonomy-Workspace-Id': workspacePin },
            body: JSON.stringify(requestBody)
        })
            .then(async r => {
                if (!r.ok) {
                    const error = new Error('HTTP ' + r.status);
                    error.httpStatus = r.status;
                    try {
                        const body = await r.json();
                        const message = body && [body.detail, body.message, body.error]
                            .find(value => typeof value === 'string' && value.trim());
                        if (message) error.message = message.trim().slice(0, 1024);
                    } catch (invalidBody) {
                        // Empty/non-JSON proxy responses retain the numeric HTTP diagnostic.
                    }
                    throw error;
                }
                return r.json();
            })
            .then(acceptResult)
            .catch(err => {
                if (resultHandled) return acceptedResult;
                if (clusterObserved && !err.httpStatus && progress.acceptsResult()) {
                    B().showStatus('warning', isGermanLocale()
                        ? 'Verbindung zur Startanfrage unterbrochen; der gespeicherte Lauf wird weiter beobachtet.'
                        : 'The start request disconnected; observation of the saved run continues.');
                    return recoveredResult;
                }
                if (progress && !progress.acceptsResult()) return;
                if (progress) {
                    // A rejected HTTP request is not an active job. A lost connection may be.
                    if (!err.httpStatus && !options.recoveryManaged) progress.transportFailed();
                    else progress.finish('ERROR', false);
                }
                setAnalyzing(false);
                S.lastAnalysisStatus = 'ERROR';
                B().showStatus('danger', t('scoring.analysis.error', err.message));
                updateAnalysisLog({
                    timestamp: analysisStart,
                    totalNodes: 0,
                    matchedEntries: [],
                    warnings: [t('scoring.error', err.message)],
                    status: 'ERROR'
                });
                if (options.recoveryManaged) throw err;
            });
        return Promise.race([posted, recoveredResult]);
    }

    async function resumeAnalysis(operationId, expectedScope) {
        var session = window.TaxonomyAnalysisSession;
        var state = session && session.state();
        var lifecycle = window.__TaxonomyAnalysisSessionContext;
        var runtime = lifecycle && lifecycle.runtime || {};
        var field = document.getElementById('businessText');
        if (!state || !state.ready || !field || !window.TaxonomyAnalysisProgress) return false;
        var selection = ++recoverySelection;
        var workspace = state.workspaceId, generation = runtime.analysisGeneration, previousText = field.value;
        function inScope() {
            var current = session.state();
            return recoverySelection === selection && current.ready && current.workspaceId === workspace
                && runtime.analysisGeneration === generation && !runtime.invalidating;
        }
        var response = await window.TaxonomyAnalysisSessionApi.getRunInput(operationId, { workspaceId: workspace });
        var saved = await response.json();
        if (!inScope() || field.value !== previousText || saved.operationId !== operationId
                || typeof saved.businessText !== 'string' || !saved.scope
                || (saved.scope.workspaceId || '') !== (workspace || '')
                || expectedScope && !['workspaceId', 'repositoryId', 'branch', 'sourceCommit'].every(function (key) {
                    return saved.scope[key] === expectedScope[key];
                })) return false;

        // This is an explicit recovery choice. Restore the original requirement;
        // never label an old result as an assessment of the current edited draft.
        field.value = saved.businessText;
        field.classList.remove('stale-results');
        B().resetDiagramContext?.();
        if (lifecycle && typeof lifecycle.clearDerivedUi === 'function') lifecycle.clearDerivedUi();
        S.currentReasons = {}; S.currentDiscrepancies = []; S.currentProductCoverageGaps = [];
        S.currentArchView = null;
        acceptRelationSearchReport(null);
        S.evaluatedNodes = new Set(); S.pendingProposalNodeCode = null;
        S.analysisCoverage = null; S.analysisRecovery = null; S.recoveryContext = null;
        S.lastAnalysisScope = saved.analysisScope; S.lastAnalysisProvider = saved.provider;
        S.lastAnalysisReasonLength = saved.analysisReasonLength || null;
        S.lastAnalysisLanguage = saved.analysisLanguage || null;
        S.activeAnalysisLanguage = S.lastAnalysisLanguage;
        S.analysisProviderId = saved.provider || null;
        S.analysisOperationId = operationId;
        S.storedBusinessText = saved.businessText; S.lastAnalyzedText = null;
        S.lastAnalysisStatus = 'IN_PROGRESS'; window._currentProvisionalRelations = [];
        applyScoreEnvelope({}); clearAnalysisLog(true); setAnalyzing(true);
        B().renderView(S.taxonomyData, S.currentScores);
        var resultHandled = false;
        var monitor = window.TaxonomyAnalysisProgress.start(operationId, function (snapshot) {
            if (resultHandled || !inScope() || monitor && !monitor.acceptsResult()
                    || field.value !== saved.businessText) return;
            applyLocalRawScores(snapshot.rawScores || {}, true, true, true);
            mergeAnalysisReasons(snapshot.reasons);
            B().renderView(S.taxonomyData, S.currentScores);
        }, function (result) {
            if (!inScope() || !monitor.acceptsResult()) return;
            if (field.value !== saved.businessText) {
                monitor.finish(result.status, false); setAnalyzing(false); return;
            }
            resultHandled = true;
            monitor.finish(result.status, false, result.analysisDurationMillis);
            applyAnalysisResult(result, { text: saved.businessText, provider: saved.provider,
                requestedScope: saved.analysisScope, options: { recoveryManaged: true }, operationId: operationId, durable: true });
        }, saved.scope);
        return monitor;
    }

    // ── Interactive analysis (stores text, renders tree without LLM calls) ─────
    function runInteractiveAnalysis() {
        recoverySelection++;
        const text = document.getElementById('businessText').value.trim();
        if (!text) {
            B().showStatus('warning', t('scoring.enter.requirement'));
            return;
        }

        // Reset interactive state
        B().resetDiagramContext?.();
        S.lastAnalysisScope = null;
        S.lastAnalysisReasonLength = null;
        S.lastAnalysisLanguage = null;
        S.activeAnalysisLanguage = null;
        window.TaxonomyAnalysisScope?.acceptResult?.(null);
        S.storedBusinessText = text;
        S.lastAnalyzedText = text;
        S.evaluatedNodes = new Set();
        clearDerivedAnalysisView();
        S.currentScores = {};
        S.currentRawScores = {};
        S.currentEffectiveScores = {};
        S.currentScoreDetails = {};
        S.currentProductSuitabilityScores = {};
        S.scoreSemanticsVersion = 0;
        S.currentScoreSemanticsWarnings = [];
        S.currentReasons = {};
        var interactiveProvider = document.getElementById('providerSelect');
        S.lastAnalysisProvider = interactiveProvider ? interactiveProvider.value : null;
        S.analysisProviderId = S.lastAnalysisProvider;
        S.activeAnalysisLanguage = window.TaxonomyContentLanguage?.analysis() || (isGermanLocale() ? 'de' : 'en');
        S.analysisOperationId = null;
        S.lastAnalysisDurationMillis = null;
        S.lastAnalysisStatus = 'IN_PROGRESS';
        document.getElementById('businessText').classList.remove('stale-results');

        // Render the tree without scores; mark all expandable nodes as unevaluated
        B().renderView(S.taxonomyData, null);
        document.querySelectorAll('.tax-node, .tax-container').forEach(function (el) {
            if (el.querySelector(':scope > .tax-children')) {
                el.classList.add('tax-has-unevaluated');
            }
        });

        B().showStatus('info', t('analyze.interactive.info'));
    }

    // ── Streaming analysis (list / tabs views) ────────────────────────────────
    function runStreamingAnalysis() {
        var selection = ++recoverySelection;
        const text = document.getElementById('businessText').value.trim();
        if (!text) {
            B().showStatus('warning', t('scoring.enter.requirement'));
            return;
        }

        var requestedScope;
        try { requestedScope = window.TaxonomyAnalysisScope?.read(); }
        catch (invalidScope) { B().showStatus('warning', invalidScope.message); return; }

        setAnalyzing(true);
        B().resetDiagramContext?.();
        B().clearStatus();
        clearAnalysisLog();
        var llmCommLogEl = document.getElementById('llmCommLogContent');
        if (llmCommLogEl) {
            llmCommLogEl.innerHTML = '';
        }
        clearDerivedAnalysisView();
        S.currentScores = {};
        S.currentRawScores = {};
        S.currentEffectiveScores = {};
        S.currentScoreDetails = {};
        S.currentProductSuitabilityScores = {};
        S.scoreSemanticsVersion = 0;
        S.currentScoreSemanticsWarnings = [];
        S.currentReasons = {};
        S.analysisCoverage = null;
        S.lastAnalysisDurationMillis = null;
        S.lastAnalysisReasonLength = null;
        S.lastAnalysisLanguage = null;
        S.activeAnalysisLanguage = null;
        S.lastAnalysisStatus = 'IN_PROGRESS';
        S.storedBusinessText = text;
        document.getElementById('businessText').classList.remove('stale-results');

        // Render a clean tree without scores first
        B().renderView(S.taxonomyData, null);

        var providerSelect = document.getElementById('providerSelect');
        var provider = providerSelect ? providerSelect.value : '';

        S.activeAnalysisLanguage = window.TaxonomyContentLanguage?.analysis() || (isGermanLocale() ? 'de' : 'en');
        var url = '/api/analyze-stream?businessText=' + encodeURIComponent(text)
            + '&analysisLanguage=' + encodeURIComponent(S.activeAnalysisLanguage);
        if (requestedScope) {
            url += '&analysisMode=' + encodeURIComponent(requestedScope.mode);
            requestedScope.taxonomyRoots.forEach(function (root) { url += '&taxonomyRoots=' + encodeURIComponent(root); });
            S.lastAnalysisScope = requestedScope;
            window.TaxonomyAnalysisScope?.acceptResult?.(requestedScope);
        }
        if (provider && provider !== 'MANUAL') {
            url += '&provider=' + encodeURIComponent(provider);
        }
        S.analysisProviderId = provider || null;
        S.analysisOperationId = null;
        const eventSource = new EventSource(url);
        var finalized = false;

        eventSource.addEventListener('phase', function (e) {
            if (finalized || selection !== recoverySelection) { eventSource.close(); return; }
            const data = JSON.parse(e.data);
            if (data.operationId) S.analysisOperationId = data.operationId;
            if (data.analysisProviderId) S.analysisProviderId = data.analysisProviderId;
            B().showStatus('info', data.message);
        });

        eventSource.addEventListener('scores', function (e) {
            if (finalized || selection !== recoverySelection) { eventSource.close(); return; }
            const data = JSON.parse(e.data);
            if (data.operationId) S.analysisOperationId = data.operationId;
            if (data.analysisProviderId) S.analysisProviderId = data.analysisProviderId;
            var rawScores = data.rawScores || data.scores || {};
            try {
                Object.values(rawScores).forEach(requireScore);
            } catch (invalid) {
                // Validate before mutating the current evidence. Never inject error zeros.
                S.lastAnalysisStatus = 'PARTIAL';
                B().showStatus('warning', invalid.message);
                return;
            }
            var details = data.scoreDetails || {};
            Object.assign(S.currentRawScores, rawScores);
            Object.assign(S.currentScoreDetails, details);
            acceptAnalysisReasonLength(data, false);
            var reasonChanges = mergeAnalysisReasons(data.reasons);
            // A later family batch must reconcile products that already arrived.
            var changedScores = Object.assign({}, rawScores);
            reasonChanges.forEach(function (code) {
                if (Object.prototype.hasOwnProperty.call(S.currentRawScores, code)) changedScores[code] = S.currentRawScores[code];
            });
            Object.entries(S.currentScoreDetails).forEach(function ([code, detail]) {
                if (isProductScore(detail) && Object.prototype.hasOwnProperty.call(S.currentRawScores, code)
                        && Object.prototype.hasOwnProperty.call(rawScores, detail.parentCode)) {
                    changedScores[code] = S.currentRawScores[code];
                }
            });
            var effectiveScores = effectiveIncrementalScores(changedScores, S.currentScoreDetails);
            Object.assign(S.currentEffectiveScores, effectiveScores);
            Object.assign(S.currentScores, effectiveScores);
            if (data.provider) { S.lastAnalysisProvider = data.provider; }
            if (data.scoreSemanticsVersion) {
                S.scoreSemanticsVersion = data.scoreSemanticsVersion;
            }
            Object.entries(changedScores).forEach(function ([code, raw]) {
                if (isProductScore(S.currentScoreDetails[code])) S.currentProductSuitabilityScores[code] = clampScore(raw);
            });
            if (scoreUpdatesRequireRender()) B().renderView(S.taxonomyData, S.currentScores);
            else withScoreNodeUpdates(function () {
                Object.entries(changedScores).forEach(function ([code, raw]) {
                    var detail = S.currentScoreDetails[code];
                    applyScoreToNode(code, effectiveScores[code], S.currentReasons[code], detail, raw, true);
                });
            });
            if (B().refreshContainerSummaries) B().refreshContainerSummaries(S.currentScores);
            if (data.prompt !== undefined || data.rawResponse !== undefined) {
                appendLlmLogEntry(
                    data.message || data.description || 'streaming',
                    data.scores,
                    {
                        prompt: data.prompt || '',
                        rawResponse: data.rawResponse || '',
                        provider: data.provider || '',
                        durationMs: data.durationMs || 0,
                        reasons: data.reasons || {},
                        rootAssessments: data.rootAssessments || {},
                        scoreDetails: details,
                        error: data.error || null
                    }
                );
            }
        });

        eventSource.addEventListener('expanding', function (e) {
            if (finalized || selection !== recoverySelection) { eventSource.close(); return; }
            const data = JSON.parse(e.data);
            if (data.operationId) S.analysisOperationId = data.operationId;
            if (data.analysisProviderId) S.analysisProviderId = data.analysisProviderId;
            expandNodeByCode(data.parentCode);
            data.childCodes.forEach(function (code) {
                markNodeAsEvaluating(code);
            });
        });

        eventSource.addEventListener('complete', function (e) {
            if (finalized || selection !== recoverySelection) { eventSource.close(); return; }
            const data = JSON.parse(e.data);
            finalized = true;
            if (data.operationId) S.analysisOperationId = data.operationId;
            if (data.analysisProviderId) S.analysisProviderId = data.analysisProviderId;
            eventSource.close();
            setAnalyzing(false);
            var previousEvidence = scoreViewEvidence();
            applyScoreEnvelope({
                scores: data.totalScores,
                rawScores: data.rawScores,
                effectiveScores: data.effectiveScores || data.totalScores,
                scoreDetails: data.scoreDetails,
                productSuitabilityScores: data.productSuitabilityScores,
                scoreSemanticsVersion: data.scoreSemanticsVersion,
                scoreSemanticsWarnings: data.scoreSemanticsWarnings,
                analysisDurationMillis: data.analysisDurationMillis
            });
            acceptAnalysisReasonLength(data, false);
            mergeAnalysisReasons(data.reasons);
            if (Object.prototype.hasOwnProperty.call(data, 'analysisCoverage')) S.analysisCoverage = data.analysisCoverage;
            syncVisibleScoreNodes(previousEvidence);
            S.currentDiscrepancies = data.discrepancies || [];
            S.currentProductCoverageGaps = data.productCoverageGaps || [];
            S.lastAnalysisStatus = data.status || 'SUCCESS';
            updateAnalysisLog({ timestamp: new Date(), totalNodes: Object.keys(S.currentScores || {}).length,
                matchedEntries: Object.entries(S.currentScores || {}).filter(function (entry) { return entry[1] > 0; }),
                warnings: data.warnings || [], status: S.lastAnalysisStatus });
            S.storedBusinessText = text;
            S.lastAnalyzedText = text;
            const matchedCount = Object.values(data.totalScores).filter(v => v > 0).length;
            const partial = data.status === 'PARTIAL' || data.status === 'CANCELLED';
            let statusMsg = partial
                ? t('analyze.partial', (data.warnings || []).join(' ') || t('analyze.incomplete'), matchedCount)
                : t('analyze.complete', matchedCount);
            if (S.currentDiscrepancies.length > 0) {
                statusMsg += ' ' + t('analyze.discrepancies', S.currentDiscrepancies.length);
                console.log('[Taxonomy] Discrepancies:', S.currentDiscrepancies);
            }
            if (S.currentProductCoverageGaps.length > 0) {
                statusMsg += ' ' + t(
                    'analyze.product.coverage.gaps', S.currentProductCoverageGaps.length);
                console.log('[Taxonomy] Product coverage gaps:', S.currentProductCoverageGaps);
            }
            B().showStatus(partial ? 'warning' : data.status === 'ERROR' ? 'danger' : 'success', statusMsg);
            B().updateExportGroupVisibility();
        });

        eventSource.addEventListener('error', function (e) {
            if (finalized || selection !== recoverySelection) { eventSource.close(); return; }
            if (e.data) {
                try {
                    const data = JSON.parse(e.data);
                    finalized = true;
                    if (data.operationId) S.analysisOperationId = data.operationId;
                    if (data.analysisProviderId) S.analysisProviderId = data.analysisProviderId;
                    eventSource.close();
                    setAnalyzing(false);
                    var previousEvidence = scoreViewEvidence();
                    S.lastAnalysisStatus = data.status || 'PARTIAL';
                    acceptAnalysisReasonLength(data, false);
                    mergeAnalysisReasons(data.reasons);
                    if (data.scoreSemanticsUnavailable) {
                        // A failed server-side projection supplies raw evidence only. Rebuild
                        // comparable relevance from the already loaded catalogue, not raw suitability.
                        applyLocalRawScores(Object.assign(Object.create(null),
                            S.currentRawScores || {}, data.rawScores || {}), true, true);
                    } else applyScoreEnvelope({
                        scores: data.partialScores,
                        rawScores: data.rawScores,
                        effectiveScores: data.effectiveScores || data.partialScores,
                        scoreDetails: data.scoreDetails,
                        productSuitabilityScores: data.productSuitabilityScores,
                        scoreSemanticsVersion: data.scoreSemanticsVersion,
                        scoreSemanticsWarnings: data.scoreSemanticsWarnings,
                        analysisDurationMillis: data.analysisDurationMillis
                    });
                    S.lastAnalysisDurationMillis = Number.isSafeInteger(data.analysisDurationMillis)
                        && data.analysisDurationMillis >= 0 ? data.analysisDurationMillis : null;
                    if (Object.prototype.hasOwnProperty.call(data, 'analysisCoverage')) S.analysisCoverage = data.analysisCoverage;
                    if (typeof B().refreshAnalysisDuration === 'function') B().refreshAnalysisDuration();
                    syncVisibleScoreNodes(previousEvidence);
                    updateAnalysisLog({ timestamp: new Date(), totalNodes: Object.keys(S.currentScores || {}).length,
                        matchedEntries: Object.entries(S.currentScores || {}).filter(function (entry) { return entry[1] > 0; }),
                        warnings: data.warnings || [], status: S.lastAnalysisStatus });
                    S.currentDiscrepancies = data.discrepancies || [];
                    S.currentProductCoverageGaps = data.productCoverageGaps || [];
                    B().showStatus('warning', '⚠️ ' + data.errorMessage);
                } catch (parseErr) {
                    console.error('Failed to parse SSE error event:', parseErr);
                }
            }
        });

        eventSource.onerror = function (event) {
            if (finalized || selection !== recoverySelection) { eventSource.close(); return; }
            // A valid named "error" event already finalized the result in its handler.
            // Malformed payloads and bodyless transport failures still need the error path.
            if (event && event.data && S.lastAnalysisStatus !== 'IN_PROGRESS') return;
            finalized = true;
            eventSource.close();
            setAnalyzing(false);
            S.lastAnalysisStatus = 'ERROR';
            B().showStatus('danger', t('analyze.connection.lost'));
        };
    }

    // ── Incremental DOM update helpers ────────────────────────────────────────

    function withScoreNodeUpdates(update) {
        if (B().withTreeUpdateBatch) return B().withTreeUpdateBatch(update);
        return update();
    }

    function scoreUpdatesRequireRender() {
        return B().isRelevanceFilterActive?.() || S.currentView && S.currentView !== 'list' && S.currentView !== 'tabs';
    }

    function scoreViewEvidence() {
        var scores = S.currentScores || {}, raw = S.currentRawScores || {}, reasons = S.currentReasons || {};
        var codes = new Set(Object.keys(scores).concat(Object.keys(raw), Object.keys(reasons)));
        return JSON.stringify(Array.from(codes).sort().map(function (code) {
            return [code, scores[code], raw[code], describeScore(code, scores[code]).label, reasons[code]];
        }));
    }

    function syncVisibleScoreNodes(previousEvidence) {
        if (scoreUpdatesRequireRender()) {
            if (previousEvidence !== scoreViewEvidence()) B().renderView(S.taxonomyData, S.currentScores);
            // Root clarification coverage can change without new scores or reasons.
            else if (B().refreshContainerSummaries) B().refreshContainerSummaries(S.currentScores);
            refreshScoreStatistics();
            return;
        }
        // Reconcile terminal evidence without rebuilding the tree or losing focus/scroll.
        withScoreNodeUpdates(function () {
            document.querySelectorAll('.tax-node[data-code], [data-taxonomy-container]').forEach(function (node) {
                var code = node.getAttribute('data-code') || node.getAttribute('data-taxonomy-container');
                applyScoreToNode(code, (S.currentScores || {})[code],
                    (S.currentReasons || {})[code], (S.currentScoreDetails || {})[code],
                    (S.currentRawScores || {})[code], true);
            });
        });
        if (B().refreshContainerSummaries) B().refreshContainerSummaries(S.currentScores);
        refreshScoreStatistics();
    }

    function renderedScoreNode(code, scores) {
        return B().ensureNodeRendered(code, scores, true)
            || (B().findRenderedNode && B().findRenderedNode(code))
            || document.querySelector('[data-code="' + CSS.escape(code) + '"]')
            || document.querySelector('[data-taxonomy-container="' + CSS.escape(code) + '"]');
    }

    /** Apply a score (and optional reason) to a node already in the DOM without re-rendering. */
    function applyScoreToNode(code, pct, reason, detail, rawScore, deferContainerRefresh) {
        if (!deferContainerRefresh && B().refreshContainerSummaries) {
            B().refreshContainerSummaries(S.currentScores);
        }
        const el = renderedScoreNode(code, S.currentScores);
        if (!el) return;
        // Remove evaluating animation
        el.classList.remove('tax-evaluating');

        if (el.getAttribute('data-taxonomy-container')) { return; }

        const header = el.querySelector(':scope > .tax-node-header');
        if (!header) return;

        var raw = rawScore === undefined
            ? ((S.currentRawScores || {})[code] ?? pct) : rawScore;
        var scoreDetail = detail || (S.currentScoreDetails || {})[code];
        // Highlight comparable relevance only; pending suitability is not global relevance.
        if (pct > 0) {
            const alpha = Math.min(pct / 100, 1).toFixed(2);
            header.style.backgroundColor = 'rgba(0,128,0,' + alpha + ')';
            // Contrast-safe text color (WCAG 1.4.3)
            if (pct >= 75) { header.style.color = '#fff'; }
            else { header.style.color = '#1a1a1a'; }
        } else {
            header.style.backgroundColor = '';
            header.style.color = '';
        }

        if (Number.isFinite(pct) || isProductScore(scoreDetail)) {
            let badge = header.querySelector('.tax-pct');
            if (!badge) {
                badge = document.createElement('span');
                badge.className = 'tax-pct';
                badge.setAttribute('aria-hidden', 'true');
                header.appendChild(badge);
            }
            badge.textContent = scoreLabel(code, pct, raw, scoreDetail);
            badge.title = isProductScore(scoreDetail)
                ? t('scoring.score.tooltip.product')
                : t('scoring.score.tooltip.relevance');

            // Add/update reason icon
            if (reason) {
                let reasonIcon = header.querySelector('.tax-reason-icon');
                if (!reasonIcon) {
                    reasonIcon = document.createElement('span');
                    reasonIcon.className = 'tax-reason-icon';
                    header.appendChild(reasonIcon);
                }
                reasonIcon.textContent = '💬';
                reasonIcon.title = reason;
            }
        } else {
            // Missing evidence remains unassessed; a recorded zero keeps its badge.
            var previousBadge = header.querySelector('.tax-pct');
            if (previousBadge) previousBadge.remove();
            const previousReason = header.querySelector('.tax-reason-icon');
            if (previousReason) previousReason.remove();
        }

        if (Number.isFinite(pct) && pct > 0) {
            expandRenderedAncestors(el, S.currentScores);
        }

        // Update ARIA label with score info (WCAG 4.1.2)
        var nameEl = header.querySelector('.tax-name');
        var ariaLabel = code + ' ' + (nameEl ? nameEl.textContent : '');
        var rawForAria = rawScore === undefined
            ? ((S.currentRawScores || {})[code] ?? pct) : rawScore;
        var detailForAria = detail || (S.currentScoreDetails || {})[code];
        if (Number.isFinite(pct) || isProductScore(detailForAria)) {
            ariaLabel += ', ' + scoreAriaLabel(code, pct, rawForAria, detailForAria);
        }
        if (Number.isFinite(pct) && reason) { ariaLabel += ', ' + t('browse.node.reason', reason); }
        el.setAttribute('aria-label', ariaLabel);
    }

    function expandRenderedNode(el, scores, userInitiated) {
        if (B().setNodeExpanded) {
            B().setNodeExpanded(el, true, !userInitiated);
            return;
        }
        const children = el.querySelector(':scope > .tax-children');
        if (children) {
            B().materializeChildren(el, scores);
            children.style.display = '';
            const toggle = el.querySelector(':scope > .tax-node-header > .tax-toggle');
            if (toggle) toggle.textContent = '▼';
            el.setAttribute('aria-expanded', 'true');
        }
    }

    function expandRenderedAncestors(el, scores, userInitiated) {
        let parent = el.parentElement;
        while (parent) {
            if (parent.classList.contains('tax-children')) {
                const pNode = parent.parentElement;
                if (pNode) expandRenderedNode(pNode, scores, userInitiated);
            }
            parent = parent.parentElement;
        }
    }

    /** Expand a catalogue node or subtaxonomy group and all of its ancestors. */
    function expandNodeByCode(code, userInitiated) {
        const el = userInitiated ? B().ensureNodeRendered(code, S.currentScores)
            : renderedScoreNode(code, S.currentScores);
        if (!el) return;
        expandRenderedNode(el, S.currentScores, userInitiated);
        expandRenderedAncestors(el, S.currentScores, userInitiated);
    }

    /** Add a pulsing CSS class to indicate a node is currently being evaluated. */
    function markNodeAsEvaluating(code) {
        const el = renderedScoreNode(code, S.currentScores);
        if (el) el.classList.add('tax-evaluating');
    }

    /** Reveal assessed positive paths without opening every positive node's unassessed children. */
    function expandMatched(scores) {
        Object.entries(scores).forEach(([code, pct]) => {
            if (pct > 0) {
                const el = renderedScoreNode(code, scores);
                if (!el) return;
                expandRenderedAncestors(el, scores);
            }
        });
        highlightScoringPaths(scores);
    }

    /**
     * Highlight the scoring path from root through intermediate nodes to
     * scored leaves.  Any ancestor of a scored node that itself has a score
     * receives the CSS class {@code tax-scoring-path}, which renders a
     * coloured left-border so readers can trace the hierarchical narrowing.
     */
    function highlightScoringPaths(scores) {
        // Remove previous highlights
        document.querySelectorAll('.tax-scoring-path').forEach(function (el) {
            el.classList.remove('tax-scoring-path');
        });
        if (!scores) return;

        // Build a local index of taxonomy nodes by data-code to avoid
        // repeated global querySelector calls per scored entry.
        var codeToNode = {};
        document.querySelectorAll('.tax-node[data-code]').forEach(function (node) {
            var code = node.dataset.code;
            if (code) {
                codeToNode[code] = node;
            }
        });

        // For every scored leaf (code containing '-'), walk up to root and
        // mark each ancestor that also carries a score.
        Object.entries(scores).forEach(function (entry) {
            var code = entry[0];
            var pct  = entry[1];
            if (pct <= 0 || !code.includes('-')) return;

            var el = codeToNode[code];
            if (!el) return;

            var parent = el.parentElement;
            while (parent) {
                if (parent.classList.contains('tax-node')) {
                    var parentCode = parent.dataset.code;
                    if (parentCode && scores[parentCode] > 0) {
                        parent.classList.add('tax-scoring-path');
                    }
                }
                parent = parent.parentElement;
            }
        });
    }

    // ── Leaf Justification ────────────────────────────────────────────────────
    function canRequestFollowUp() {
        if (window.TaxonomyAnalysisSession && !window.TaxonomyAnalysisSession.state().ready) return false;
        if (S.lastAnalysisProvider && !S.analysisProviderId && !S.analysisOperationId) {
            B().showStatus('warning', t('scoring.followup.unavailable'));
            return false;
        }
        return true;
    }

    function requestLeafJustification(nodeCode, btnEl) {
        if (!S.storedBusinessText) {
            B().showStatus('warning', t('analyze.no.text'));
            return;
        }
        if (!canRequestFollowUp()) return;
        const originalText = btnEl.textContent;
        btnEl.disabled = true;
        btnEl.textContent = t('scoring.generating');

        fetch('/api/justify-leaf', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                nodeCode: nodeCode,
                analysisLanguage: S.activeAnalysisLanguage || S.lastAnalysisLanguage || window.TaxonomyContentLanguage?.analysis(),
                businessText: S.storedBusinessText,
                scores: S.currentScores || {},
                reasons: S.currentReasons || {},
                provider: S.analysisProviderId || null,
                operationId: S.analysisOperationId || null
            })
        })
            .then(r => {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            })
            .then(data => {
                btnEl.disabled = false;
                btnEl.textContent = originalText;
                showLeafJustificationModal(nodeCode, data.justification || t('scoring.no.justification'));
            })
            .catch(err => {
                btnEl.disabled = false;
                btnEl.textContent = originalText;
                console.error('[Taxonomy] Failed to get leaf justification for', nodeCode, err);
                B().showStatus('danger', t('scoring.justify.failed', nodeCode, err.message));
            });
    }

    /** Displays the leaf justification in the modal. */
    function showLeafJustificationModal(nodeCode, justification) {
        const modal = document.getElementById('leafJustificationModal');
        const titleEl = document.getElementById('leafJustificationModalTitle');
        const bodyEl = document.getElementById('leafJustificationModalBody');
        if (!modal || !titleEl || !bodyEl) return;
        titleEl.textContent = t('scoring.justification.title', nodeCode);
        bodyEl.textContent = justification;
        bodyEl.setAttribute('data-modal-loaded', 'true');
        const bsModal = new bootstrap.Modal(modal);
        bsModal.show();
    }

    // ── Architecture View (Impact Map) ──────────────────────────────────────

    /** Returns a Bootstrap badge color class for a NodeOrigin or RelationOrigin enum name. */
    function originBadgeColor(origin) {
        if (!origin) return 'light';
        switch (origin) {
            case 'DIRECT_SCORED': return 'success';
            case 'TRACE_INTERMEDIATE': return 'secondary';
            case 'PROPAGATED': return 'info';
            case 'SEED_CONTEXT': return 'warning';
            case 'ENRICHED_LEAF': return 'primary';
            case 'IMPACT_PROMOTED': return 'danger';
            case 'TAXONOMY_SEED': return 'warning';
            case 'PROPAGATED_TRACE': return 'secondary';
            case 'IMPACT_DERIVED': return 'danger';
            case 'SUGGESTED_CANDIDATE': return 'info';
            case 'LLM_SUPPORTED': return 'primary';
            default: return 'light';
        }
    }

    /**
     * Renders an elements table for either impact-selected or trace elements.
     * Shared between the Architecture Impact and Scoring Trace sections.
     */
    function renderElementsTable(elems, hotspotCodes) {
        var tbl = '';
        tbl += '<div class="table-responsive"><table class="table table-sm table-bordered small mb-2">';
        tbl += '<thead><tr><th>Code</th><th>Title</th><th>' + t('archview.col.scoring.path') + '</th><th>Sheet</th><th>Relevance</th><th>' + t('archview.col.llm.score') + '</th><th>' + t('archview.col.origin') + '</th><th>' + t('archview.col.impact') + '</th></tr></thead><tbody>';
        elems.forEach(function(e) {
            var rowClass = e.selectedForImpact ? 'table-warning' : (e.anchor ? 'table-success' : '');
            var sheetCfg = LAYER_CONFIG[e.taxonomySheet];
            var sheetLabel = sheetCfg ? sheetCfg.label : (e.taxonomySheet || '');
            var pathLabel = e.scoringPath || e.hierarchyPath || e.nodeCode;
            var originKey = e.origin ? 'node.origin.' + e.origin.replace(/_/g, '.').toLowerCase() : '';
            var originLabel = originKey ? t(originKey) : (e.includedBecause || '');
            var originBadge = e.origin ? '<span class="badge bg-' + originBadgeColor(e.origin) + ' text-dark">' + escapeHtml(originLabel) + '</span>' : escapeHtml(e.includedBecause || '');
            var scoped = e.origin === 'RELATION_EVIDENCE' || e.origin === 'REQUIREMENT_EVIDENCE';
            var detail = e.scoreDetail;
            var relevanceLabel = scoped
                ? (detail && Number.isFinite(detail.effectiveRelevance) ? scoreText(detail.effectiveRelevance) + '%' : '—')
                : scoreText(e.relevance * 100) + '%';
            var rawLabel = scoped
                ? (detail && Number.isFinite(detail.rawScore) ? String(detail.rawScore) : '—')
                : String(e.directLlmScore || 0);
            tbl += '<tr class="' + rowClass + '"' +
                (e.presenceReason ? ' title="' + escapeHtml(e.presenceReason) + '"' : '') + '>' +
                '<td>' + escapeHtml(e.nodeCode) + '</td>' +
                '<td>' + escapeHtml(e.title || '') + '</td>' +
                '<td class="text-muted small">' + escapeHtml(pathLabel) + '</td>' +
                '<td>' + escapeHtml(sheetLabel) + '</td>' +
                '<td>' + relevanceLabel + '</td>' +
                '<td>' + rawLabel + '</td>' +
                '<td>' + originBadge + '</td>' +
                '<td>' + (e.selectedForImpact ? '🎯' : '') +
                (e.anchor ? ' ★' : '') +
                (hotspotCodes.has(e.nodeCode) ? ' ⚠️' : '') + '</td>' +
                '</tr>';
        });
        tbl += '</tbody></table></div>';
        return tbl;
    }

    /**
     * Renders a single swimlane node (used by both flat and containment rendering).
     */
    function renderSwimNode(el, hotspotCodes, hotspotReasons) {
        var pct = scoreText(el.relevance * 100);
        var nodeClasses = 'impact-node';
        if (el.anchor) nodeClasses += ' impact-node-anchor';
        if (hotspotCodes.has(el.nodeCode)) nodeClasses += ' impact-node-hotspot';
        var opacity = 0.6 + (el.relevance * 0.4);
        var titleParts = el.nodeCode + ' ' + (el.title || '') + ' — ' + (el.includedBecause || '');
        if (hotspotCodes.has(el.nodeCode)) {
            titleParts += ' | ⚠️ hotspot: ' + (hotspotReasons[el.nodeCode] || t('analyze.hotspot.risk'));
        }
        var h = '<span class="' + nodeClasses + '" style="opacity:' + opacity.toFixed(2) + '"' +
            ' title="' + escapeHtml(titleParts) + '">';
        if (el.title) {
            h += '<strong>' + escapeHtml(el.title.substring(0, 40)) + '</strong>';
            h += ' <span class="impact-node-code">' + escapeHtml(el.nodeCode) + '</span>';
        } else {
            h += escapeHtml(el.nodeCode);
        }
        if (el.anchor) {
            h += ' <span class="impact-badge">★ ' + pct + '%</span>';
        } else {
            h += ' <span class="impact-badge">' + pct + '%</span>';
        }
        if (hotspotCodes.has(el.nodeCode)) {
            h += ' <span class="impact-badge impact-badge-hotspot">⚠️ ' +
                escapeHtml((hotspotReasons[el.nodeCode] || 'hotspot').substring(0, 40)) + '</span>';
        }
        h += '</span>';
        return h;
    }

    function relationSearchReportFrom(evidence) {
        if (!evidence || typeof evidence !== 'object') return null;
        // An explicit null is authoritative. Only older records may fall back to a nested report.
        var report = Object.prototype.hasOwnProperty.call(evidence, 'relationSearchReport')
            ? evidence.relationSearchReport : evidence.architectureView?.relationSearchReport;
        return report && typeof report === 'object' && !Array.isArray(report) ? report : null;
    }

    function renderIndependentRelationSearchReport(report) {
        var panel = document.getElementById('relationSearchReportPanel');
        if (!panel) return;
        panel.hidden = !report;
        panel.innerHTML = report ? renderRelationSearchReport(report) : '';
    }

    function acceptRelationSearchReport(evidence) {
        S.currentRelationSearchReport = relationSearchReportFrom(evidence);
        renderIndependentRelationSearchReport(S.currentRelationSearchReport);
    }

    function clearDerivedAnalysisView() {
        S.currentCopilot = null;
        acceptRelationSearchReport(null);
        S.currentArchView = null;
        // Only the derived summary requires a fallback when its evidence is cleared.
        // Preserve the reader's list/tab/diagram choice while a new run starts.
        if (S.currentView === 'summary') {
            if (typeof B().switchView === 'function') {
                B().switchView('list');
            } else {
                S.currentView = 'list';
            }
        }
        renderArchitectureView(null);
        var content = document.getElementById('architectureViewContent');
        if (content) content.innerHTML = '';
        var summaryButton = document.getElementById('viewSummary');
        if (summaryButton) summaryButton.style.display = 'none';
    }

    function renderRelationSearchReport(report) {
        if (!report) return '';
        var result = report.result || {};
        var budget = report.budget;
        var hasBudget = budget && [budget.callsPerSlice, budget.maxTotalCalls, budget.newCalls, budget.spentCalls]
            .every(function (value) { return Number.isSafeInteger(value) && value >= 0; })
            && typeof budget.automatic === 'boolean';
        var html = '<details class="relation-search-report border rounded p-2 mb-2"><summary>'
            + escapeHtml(t('relation.search.title')) + ' · '
            + escapeHtml(hasBudget ? t('relation.search.budget.total', budget.spentCalls, budget.maxTotalCalls)
                : t('relation.search.budget', report.totalCalls, report.maxCalls)) + '</summary>';
        if (hasBudget) {
            html += '<p class="small mt-2">' + escapeHtml(t('relation.search.budget.new', budget.newCalls)) + ' · '
                + escapeHtml(t('relation.search.budget.initial', budget.callsPerSlice)) + '<br>'
                + escapeHtml(t('relation.search.budget.automatic.' + (budget.automatic ? 'enabled' : 'disabled'))) + '</p>';
        }
        html += '<p class="small mt-2">' + escapeHtml(t(report.searchExhausted
            ? 'relation.search.exhausted' : 'relation.search.partial')) + '</p>';
        if (report.progress) {
            var p = report.progress;
            html += '<p class="fw-bold">' + escapeHtml(t('relation.search.progress', p.completedSearches,
                p.totalSearches, p.unresolvedSearches, p.pendingSearches)) + '</p>';
        }
        function section(label, items, format) {
            if (!Array.isArray(items) || !items.length) return;
            html += '<h6 class="mt-2">' + escapeHtml(t(label)) + '</h6>';
            items.slice(0, 30).forEach(function (item) {
                html += '<div class="small border-bottom py-1">' + format(item) + '</div>';
            });
            if (items.length > 30) html += '<p class="small">'
                + escapeHtml(t('relation.search.omitted', 30, items.length)) + '</p>';
        }
        var warnings = Array.isArray(report.warnings) ? report.warnings.slice() : [];
        var tasks = Array.isArray(report.tasks) ? report.tasks : [];
        var states = { PENDING: 'pending', RUNNING: 'running', COMPLETED: 'completed', UNRESOLVED: 'unresolved' };
        section('relation.search.tasks', tasks.filter(function (task) { return task.state !== 'COMPLETED'; }), function (task) {
            return escapeHtml(task.sourceId + ' → ' + task.targetRoot) + ' · '
                + escapeHtml(t('relation.search.task.' + (states[task.state] || 'unresolved')));
        });
        if (report.stopReason) warnings.push(report.stopReason);
        section('relation.search.warnings', warnings, function (warning) { return escapeHtml(warning); });
        section('relation.search.questions', result.unfinished, function (item) {
            return escapeHtml((item.sourceId || '') + ' · ' + (item.type || '') + ' · '
                + (item.reason || '') + ': ' + (item.question || ''));
        });
        section('relation.search.sources', report.sources, function (source) {
            var node = source.node || {};
            var parts = Array.isArray(source.contributions) ? source.contributions : [];
            var sourceHtml = '<strong>' + escapeHtml(node.id || '') + '</strong><br>'
                + escapeHtml(source.rationale || '') + '<br>' + escapeHtml(source.question || '');
            parts.slice(0, 4).forEach(function (part) {
                sourceHtml += '<div>' + escapeHtml(part.text || '') + '<br><q>'
                    + escapeHtml(part.quote || '') + '</q><br>'
                    + escapeHtml(t('relation.search.conditions')) + ': ' + escapeHtml(part.condition || '') + '</div>';
            });
            if (parts.length > 4) sourceHtml += '<p>'
                + escapeHtml(t('relation.search.omitted', 4, parts.length)) + '</p>';
            return sourceHtml;
        });
        section('relation.search.evidence', result.edges, function (edge) {
            var part = edge.contribution || {}, node = part.source || {}, target = edge.target || {}, evidence = edge.evidence || {};
            var from = edge.direction === 'INCOMING' ? target.id : node.id;
            var to = edge.direction === 'INCOMING' ? node.id : target.id;
            return '<strong>' + escapeHtml((from || '') + ' → ' + (to || '') + ' · '
                    + (edge.type || '') + ' · ' + (evidence.necessity || '')) + '</strong><br>'
                + escapeHtml((part.text || '') + ' / ' + (evidence.contribution || '')) + '<br>'
                + escapeHtml(evidence.rationale || '') + '<br>'
                + '<q>' + escapeHtml(part.quote || '') + '</q> / <q>' + escapeHtml(evidence.quote || '') + '</q><br>'
                + escapeHtml(t('relation.search.conditions')) + ': '
                + escapeHtml([part.condition, evidence.condition, evidence.alternativeGroup].filter(Boolean).join(' / '));
        });
        return html + '</details>';
    }

    function renderArchitectureView(view) {
        const panel = document.getElementById('architectureViewPanel');
        const content = document.getElementById('architectureViewContent');
        const placeholder = document.getElementById('architecturePlaceholder');
        if (!panel || !content) return;

        if (!view) {
            panel.style.display = 'none';
            if (placeholder) { placeholder.style.display = ''; }
            return;
        }

        let html = '';

        // ── View title + legend (generated from the active policy) ──
        if (view.viewTitle) {
            html += '<div class="archview-header mb-2">';
            html += '<h6 class="archview-title mb-1">' + escapeHtml(t(view.viewTitle)) + '</h6>';
            if (view.viewDescription) {
                html += '<p class="archview-description text-muted small mb-1">' + escapeHtml(t(view.viewDescription)) + '</p>';
            }
            if (view.activeRules && view.activeRules.length > 0) {
                html += '<div class="archview-legend small">';
                view.activeRules.forEach(function (rule) {
                    html += '<span class="badge bg-light text-dark border me-1 mb-1">' + escapeHtml(t(rule)) + '</span>';
                });
                html += '</div>';
            }
            html += '</div>';
        }

        if (!document.getElementById('relationSearchReportPanel')) {
            html += renderRelationSearchReport(view.relationSearchReport);
        }

        // Notes
        if (view.notes && view.notes.length > 0) {
            html += '<div class="alert alert-info py-1 px-2 small mb-2">' +
                view.notes.map(n => escapeHtml(n)).join('<br>') + '</div>';
        }

        const elements = view.includedElements || [];
        const relationships = view.includedRelationships || [];
        const anchors = view.anchors || [];

        // ── Build element-to-sheet lookup ──
        const elByCode = {};
        elements.forEach(el => { elByCode[el.nodeCode] = el; });

        // ── Detect change hotspots ──
        // A hotspot is an anchor with ≥2 outgoing relationships, or a node reached from ≥2 different anchors
        const outCount = {};
        const reachingAnchors = {};
        relationships.forEach(r => {
            outCount[r.sourceCode] = (outCount[r.sourceCode] || 0) + 1;
            // track which anchor reaches each target
            const srcEl = elByCode[r.sourceCode];
            if (srcEl && srcEl.anchor) {
                if (!reachingAnchors[r.targetCode]) reachingAnchors[r.targetCode] = new Set();
                reachingAnchors[r.targetCode].add(r.sourceCode);
            }
        });
        const hotspotCodes = new Set();
        const hotspotReasons = {};
        elements.forEach(el => {
            const reasons = [];
            if (el.anchor && (outCount[el.nodeCode] || 0) >= 2) {
                hotspotCodes.add(el.nodeCode);
                reasons.push('anchor with ' + outCount[el.nodeCode] + ' outgoing relations');
            }
            if (reachingAnchors[el.nodeCode] && reachingAnchors[el.nodeCode].size >= 2) {
                hotspotCodes.add(el.nodeCode);
                reasons.push('reached from ' + reachingAnchors[el.nodeCode].size + ' anchors');
            }
            if (reasons.length > 0) {
                hotspotReasons[el.nodeCode] = reasons.join(', ');
            }
        });

        // ── Group elements by taxonomy sheet ──
        const groups = {};
        elements.forEach(el => {
            const sheet = el.taxonomySheet || 'Unknown';
            if (!groups[sheet]) groups[sheet] = [];
            groups[sheet].push(el);
        });
        const sortedSheets = Object.keys(groups).sort((a, b) => {
            const oa = LAYER_CONFIG[a] ? LAYER_CONFIG[a].order : 99;
            const ob = LAYER_CONFIG[b] ? LAYER_CONFIG[b].order : 99;
            return oa - ob;
        });

        // ── Part 1: Impact Summary Bar ──
        html += '<div class="impact-summary-bar">';
        html += '<span class="impact-kpi">🎯 ' + anchors.length + ' ' + t('archview.kpi.direct.matches') + '</span>';
        html += '<span class="impact-kpi">📦 ' + elements.length + ' ' + t('archview.kpi.affected.elements') + '</span>';
        html += '<span class="impact-kpi">🔗 ' + relationships.length + ' ' + t('archview.kpi.relations') + '</span>';
        html += '<span class="impact-kpi">🏗️ ' + sortedSheets.length + ' ' + t('archview.kpi.layers') + '</span>';
        if (hotspotCodes.size > 0) {
            // Show concrete hotspot element names, not just a count
            var hotspotNames = [];
            hotspotCodes.forEach(function (code) {
                var el = elByCode[code];
                var name = el && el.title ? el.title : code;
                hotspotNames.push(name);
            });
            html += '<span class="impact-kpi impact-kpi-hotspot">⚠️ Hotspots: ' +
                escapeHtml(hotspotNames.join(', ')) + '</span>';
        }
        html += '</div>';

        // ── Part 2: Interactive Impact Network Graph + Swimlane Toggle ──
        if (elements.length > 0) {
            // Collect relationship types between layers for swimlane edge labels
            const layerRelations = {};
            relationships.forEach(r => {
                const srcSheet = elByCode[r.sourceCode] ? elByCode[r.sourceCode].taxonomySheet : null;
                const tgtSheet = elByCode[r.targetCode] ? elByCode[r.targetCode].taxonomySheet : null;
                if (srcSheet && tgtSheet && srcSheet !== tgtSheet) {
                    const key = srcSheet + '→' + tgtSheet;
                    if (!layerRelations[key]) layerRelations[key] = new Set();
                    layerRelations[key].add(r.relationType);
                }
            });

            // Toggle buttons
            html += '<div class="impact-graph-toggle">';
            html += '<button class="btn btn-sm btn-primary impact-view-btn" data-mode="graph">🔗 ' + escapeHtml(t('scoring.ui.network')) + '</button>';
            html += '<button class="btn btn-sm btn-outline-secondary impact-view-btn" data-mode="swimlane">🏗️ ' + escapeHtml(t('scoring.ui.layers')) + '</button>';
            html += '</div>';

            // Graph container (shown by default)
            html += '<div id="impactGraphView"></div>';

            // Swimlane fallback (hidden by default)
            html += '<div id="impactSwimView" style="display:none;">';
            html += '<div class="impact-map">';

            // ── Containment: build parent→children map ──
            var parentChildren = {};
            var childCodes = {};
            var suppressedParents = {};
            if (view.containmentEnabled) {
                elements.forEach(function (el) {
                    if (el.parentNodeCode && elByCode[el.parentNodeCode]) {
                        if (!parentChildren[el.parentNodeCode]) parentChildren[el.parentNodeCode] = [];
                        parentChildren[el.parentNodeCode].push(el);
                        childCodes[el.nodeCode] = true;
                    }
                });
                // Single-child parents → suppress parent, lift child
                Object.keys(parentChildren).forEach(function (parentCode) {
                    if (parentChildren[parentCode].length === 1) {
                        suppressedParents[parentCode] = true;
                        delete childCodes[parentChildren[parentCode][0].nodeCode];
                        delete parentChildren[parentCode];
                    }
                });
            }

            for (let i = 0; i < sortedSheets.length; i++) {
                const sheet = sortedSheets[i];
                const cfg = LAYER_CONFIG[sheet] || { order: 99, cls: '', icon: '⬜', label: sheet };
                const layerElements = groups[sheet];

                // Edge between swimlanes
                if (i > 0) {
                    const prevSheet = sortedSheets[i - 1];
                    const key = prevSheet + '→' + sheet;
                    const rKey = sheet + '→' + prevSheet;
                    const relTypes = layerRelations[key] || layerRelations[rKey] || new Set();
                    html += '<div class="impact-edge">│';
                    if (relTypes.size > 0) {
                        Array.from(relTypes).forEach(rt => {
                            html += ' <span class="impact-edge-label">' + escapeHtml(rt) + '</span>';
                        });
                    }
                    html += '<br>▼</div>';
                }

                html += '<div class="impact-swimlane ' + cfg.cls + '">';
                html += '<div class="impact-swimlane-title">' + cfg.icon + ' ' + escapeHtml(cfg.label) +
                    ' <span class="badge bg-secondary" style="font-size:0.7rem;">' + layerElements.length + '</span></div>';

                html += '<div class="impact-swimlane-nodes">';
                layerElements.sort(function(a, b) {
                    var aIsLeaf = a.nodeCode.includes('-') ? 0 : 1;
                    var bIsLeaf = b.nodeCode.includes('-') ? 0 : 1;
                    if (aIsLeaf !== bIsLeaf) return aIsLeaf - bIsLeaf;
                    return b.relevance - a.relevance;
                });
                layerElements.forEach(el => {
                    // Skip suppressed single-child parents
                    if (suppressedParents[el.nodeCode]) return;
                    // Skip children rendered inside their container
                    if (childCodes[el.nodeCode]) return;

                    // Multi-child parent → render as container with children inside
                    if (parentChildren[el.nodeCode] && parentChildren[el.nodeCode].length >= 2) {
                        html += '<div class="impact-container">';
                        html += '<div class="impact-container-label">' + escapeHtml(el.title || el.nodeCode) +
                            ' <span class="impact-node-code">' + escapeHtml(el.nodeCode) + '</span></div>';
                        html += '<div class="impact-container-children">';
                        parentChildren[el.nodeCode].forEach(function (child) {
                            html += renderSwimNode(child, hotspotCodes, hotspotReasons);
                        });
                        html += '</div></div>';
                        return;
                    }

                    html += renderSwimNode(el, hotspotCodes, hotspotReasons);
                });
                html += '</div></div>';
            }
            html += '</div>';
            html += '</div>'; // end impactSwimView
        }

        // ── Part 3: Detail Tables (collapsible) ──
        const hasElements = elements.length > 0;
        const hasRelationships = relationships.length > 0;
        if (hasElements || hasRelationships) {

            // Split elements into impact-selected and scoring trace
            var impactElements = elements.filter(function(e) { return e.selectedForImpact; });
            var traceElements = elements.filter(function(e) { return !e.selectedForImpact; });

            // ── Architecture Impact section (always open) ──
            if (impactElements.length > 0 || relationships.filter(function(r) { return r.relationCategory === 'impact'; }).length > 0) {
                html += '<details class="impact-details" open>';
                html += '<summary>🎯 ' + t('archview.section.impact') + ': ' + impactElements.length + ' ' + t('archview.section.elements') + ', ' +
                    relationships.filter(function(r) { return r.relationCategory === 'impact'; }).length + ' ' + t('archview.section.relations') + '</summary>';

                if (impactElements.length > 0) {
                    html += '<h6 class="mb-1 mt-2">' + t('archview.impact.elements.title') + '</h6>';
                    html += renderElementsTable(impactElements, hotspotCodes);
                }

                var impactRels = relationships.filter(function(r) { return r.relationCategory === 'impact'; });
                if (impactRels.length > 0) {
                    html += '<h6 class="mb-1">' + t('archview.impact.relations.title') + ' <span class="badge bg-primary">' + impactRels.length + '</span></h6>';
                    html += '<div class="table-responsive"><table class="table table-sm table-bordered small mb-2">';
                    html += '<thead><tr><th>' + t('archview.impact.col.source') + '</th><th>\u2192</th><th>' + t('archview.impact.col.target') + '</th><th>' + t('archview.impact.col.type') + '</th><th>' + t('archview.impact.col.relevance') + '</th><th>' + t('archview.col.confidence') + '</th><th>' + t('archview.col.origin') + '</th><th>' + t('archview.col.derivation') + '</th></tr></thead><tbody>';
                    impactRels.forEach(r => {
                        const relOriginKey = r.origin ? 'relation.origin.' + r.origin.replace(/_/g, '.').toLowerCase() : '';
                        const relOriginLabel = relOriginKey ? '<span class="badge bg-' + originBadgeColor(r.origin) + ' text-dark">' + escapeHtml(t(relOriginKey)) + '</span>' : '';
                        html += '<tr class="table-info">' +
                            '<td>' + escapeHtml(r.sourceCode) + '</td>' +
                            '<td>\u2192</td>' +
                            '<td>' + escapeHtml(r.targetCode) + '</td>' +
                            '<td>' + escapeHtml(r.relationType) + '</td>' +
                            '<td>' + scoreText(r.propagatedRelevance * 100) + '%</td>' +
                            '<td>' + (r.confidence ? (r.confidence * 100).toFixed(0) + '%' : '') + '</td>' +
                            '<td>' + relOriginLabel + '</td>' +
                            '<td class="small text-muted">' + escapeHtml(r.derivationReason || r.includedBecause || '') + '</td>' +
                            '</tr>';
                    });
                    html += '</tbody></table></div>';
                }
                html += '</details>';
            }

            // ── Scoring Trace section (collapsed by default) ──
            var traceRels = relationships.filter(function(r) { return r.relationCategory === 'trace'; });
            var seedRels = relationships.filter(function(r) { return r.relationCategory === 'seed'; });

            if (traceElements.length > 0 || traceRels.length > 0 || seedRels.length > 0) {
                html += '<details class="impact-details mt-2">';
                html += '<summary>🔍 ' + t('archview.section.trace') + ': ' + traceElements.length + ' ' + t('archview.section.elements') + ', ' +
                    (traceRels.length + seedRels.length) + ' ' + t('archview.section.relations') + '</summary>';

                if (traceElements.length > 0) {
                    html += '<h6 class="mb-1 mt-2">' + t('archview.trace.elements.title') + '</h6>';
                    html += renderElementsTable(traceElements, hotspotCodes);
                }

                if (traceRels.length > 0) {
                    html += '<h6 class="mb-1">' + t('archview.trace.relations.title') + ' <span class="badge bg-secondary">' + traceRels.length + '</span></h6>';
                    html += '<div class="table-responsive"><table class="table table-sm table-bordered small mb-0">';
                    html += '<thead><tr><th>' + t('archview.trace.col.source') + '</th><th>\u2192</th><th>' + t('archview.trace.col.target') + '</th><th>' + t('archview.trace.col.type') + '</th><th>' + t('archview.trace.col.relevance') + '</th><th>' + t('archview.trace.col.hops') + '</th><th>' + t('archview.col.origin') + '</th><th>' + t('archview.trace.col.reason') + '</th></tr></thead><tbody>';
                    traceRels.forEach(r => {
                        const traceOriginKey = r.origin ? 'relation.origin.' + r.origin.replace(/_/g, '.').toLowerCase() : '';
                        const traceOriginLabel = traceOriginKey ? '<span class="badge bg-' + originBadgeColor(r.origin) + ' text-dark">' + escapeHtml(t(traceOriginKey)) + '</span>' : '';
                        html += '<tr>' +
                            '<td>' + escapeHtml(r.sourceCode) + '</td>' +
                            '<td>\u2192</td>' +
                            '<td>' + escapeHtml(r.targetCode) + '</td>' +
                            '<td>' + escapeHtml(r.relationType) + '</td>' +
                            '<td>' + scoreText(r.propagatedRelevance * 100) + '%</td>' +
                            '<td>' + r.hopDistance + '</td>' +
                            '<td>' + traceOriginLabel + '</td>' +
                            '<td class="small text-muted">' + escapeHtml(r.derivationReason || r.includedBecause || '') + '</td>' +
                            '</tr>';
                    });
                    html += '</tbody></table></div>';
                }

                if (seedRels.length > 0) {
                    html += '<details class="mt-2"><summary class="small text-muted">' + t('archview.seed.relations.title') + ' <span class="badge bg-warning text-dark">' + seedRels.length + '</span></summary>';
                    html += '<div class="table-responsive"><table class="table table-sm table-bordered small mb-0">';
                    html += '<thead><tr><th>' + t('archview.trace.col.source') + '</th><th>\u2192</th><th>' + t('archview.trace.col.target') + '</th><th>' + t('archview.trace.col.type') + '</th><th>' + t('archview.trace.col.relevance') + '</th><th>' + t('archview.col.origin') + '</th><th>' + t('archview.trace.col.reason') + '</th></tr></thead><tbody>';
                    seedRels.forEach(r => {
                        const seedOriginKey = r.origin ? 'relation.origin.' + r.origin.replace(/_/g, '.').toLowerCase() : '';
                        const seedOriginLabel = seedOriginKey ? '<span class="badge bg-' + originBadgeColor(r.origin) + ' text-dark">' + escapeHtml(t(seedOriginKey)) + '</span>' : '';
                        html += '<tr class="table-warning">' +
                            '<td>' + escapeHtml(r.sourceCode) + '</td>' +
                            '<td>\u2192</td>' +
                            '<td>' + escapeHtml(r.targetCode) + '</td>' +
                            '<td>' + escapeHtml(r.relationType) + '</td>' +
                            '<td>' + scoreText(r.propagatedRelevance * 100) + '%</td>' +
                            '<td>' + seedOriginLabel + '</td>' +
                            '<td class="small text-muted">' + escapeHtml(r.derivationReason || r.includedBecause || '') + '</td>' +
                            '</tr>';
                    });
                    html += '</tbody></table></div></details>';
                }

                html += '</details>';
            }
        }

        if (!html) {
            html = '<p class="text-muted small mb-0">' + escapeHtml(t('scoring.ui.empty')) + '</p>';
        }

        content.innerHTML = html;
        panel.style.display = '';
        if (placeholder) { placeholder.style.display = 'none'; }

        // ── Render D3 impact graph if available ──
        var graphContainer = document.getElementById('impactGraphView');
        if (graphContainer && elements.length >= MIN_NODES_FOR_GRAPH &&
            typeof TaxonomyGraph !== 'undefined' && TaxonomyGraph.renderImpactForceGraph) {

            var graphNodes = elements.map(function (el) {
                return {
                    nodeCode: el.nodeCode,
                    title: el.title || el.nodeCode,
                    taxonomySheet: el.taxonomySheet,
                    hopDistance: el.hopDistance,
                    relevance: el.relevance,
                    anchor: el.anchor,
                    includedBecause: el.includedBecause
                };
            });

            var graphEdges = relationships.map(function (r) {
                return {
                    sourceCode: r.sourceCode,
                    targetCode: r.targetCode,
                    relationType: r.relationType,
                    propagatedRelevance: r.propagatedRelevance,
                    includedBecause: r.derivationReason || r.includedBecause || ''
                };
            });

            TaxonomyGraph.renderImpactForceGraph(graphContainer, graphNodes, graphEdges, {
                anchorCodes: new Set(anchors.map(function (a) { return a.nodeCode; })),
                hotspotCodes: hotspotCodes,
                hotspotReasons: hotspotReasons,
                layerConfig: LAYER_CONFIG,
                policyTitle: view.viewTitle ? t(view.viewTitle) : ''
            });
        } else if (graphContainer && elements.length < MIN_NODES_FOR_GRAPH) {
            // Too few nodes for a meaningful graph — show swimlane view instead
            graphContainer.style.display = 'none';
            var swimView = document.getElementById('impactSwimView');
            if (swimView) swimView.style.display = '';
            // Update toggle button states
            content.querySelectorAll('.impact-view-btn').forEach(function (btn) {
                btn.classList.toggle('btn-primary', btn.dataset.mode === 'swimlane');
                btn.classList.toggle('btn-outline-secondary', btn.dataset.mode !== 'swimlane');
            });
        }

        // ── Toggle button handlers ──
        content.querySelectorAll('.impact-view-btn').forEach(function (btn) {
            btn.addEventListener('click', function () {
                var mode = btn.dataset.mode;
                var gView = document.getElementById('impactGraphView');
                var sView = document.getElementById('impactSwimView');
                if (gView) gView.style.display = mode === 'graph' ? '' : 'none';
                if (sView) sView.style.display = mode === 'swimlane' ? '' : 'none';
                content.querySelectorAll('.impact-view-btn').forEach(function (b) {
                    b.classList.toggle('btn-primary', b.dataset.mode === mode);
                    b.classList.toggle('btn-outline-secondary', b.dataset.mode !== mode);
                });
            });
        });
    }

    function suggestedRelationsBulkActionsHtml(provisionalRelations) {
        var eligible = (provisionalRelations || []).some(function (hypothesis) {
            var status = hypothesis.status || 'PROVISIONAL';
            var id = Number(hypothesis.hypothesisId);
            return (status === 'PROVISIONAL' || status === 'PROPOSED')
                && hypothesis.appliedInCurrentAnalysis !== true
                && hypothesis.confidence >= 0.8 && Number.isSafeInteger(id) && id > 0;
        });
        return eligible ? '<button class="btn btn-sm btn-outline-success" onclick="window._acceptAllHighConfidence()" title="' + escapeHtml(t('scoring.ui.bulkTitle')) + '" aria-label="' + escapeHtml(t('scoring.ui.bulkAria')) + '">✅ ' + escapeHtml(t('scoring.ui.bulk')) + '</button>' : '';
    }

    function refreshSuggestedRelationsBulkActions() {
        var actions = document.getElementById('suggestedRelationsBulkActions');
        if (actions) actions.innerHTML = suggestedRelationsBulkActionsHtml(window._currentProvisionalRelations);
    }

    /**
     * Renders the Suggested Relationships panel from provisional relation hypotheses.
     */
    function renderSuggestedRelations(provisionalRelations) {
        var panel = document.getElementById('suggestedRelationsPanel');
        var content = document.getElementById('suggestedRelationsContent');
        var badge = document.getElementById('suggestedRelationsBadge');
        if (!panel || !content) return;

        if (!provisionalRelations || provisionalRelations.length === 0) {
            panel.style.display = 'none';
            return;
        }

        if (badge) badge.textContent = provisionalRelations.length;

        var html = '';
        html += '<div class="d-flex justify-content-between align-items-center mb-2">';
        html += '<small class="text-muted">' + escapeHtml(t('scoring.ui.hint')) + '</small>';
        html += '<span id="suggestedRelationsBulkActions">'
            + suggestedRelationsBulkActionsHtml(provisionalRelations) + '</span>';
        html += '</div>';
        html += '<div class="table-responsive"><table class="table table-sm table-bordered small mb-0">';
        html += '<thead><tr><th>' + escapeHtml(t('scoring.ui.source')) + '</th><th>→</th><th>' + escapeHtml(t('scoring.ui.target')) + '</th><th>' + escapeHtml(t('scoring.ui.type')) + '</th><th>' + escapeHtml(t('scoring.ui.confidence')) + '</th><th>' + escapeHtml(t('scoring.ui.reasoning')) + '</th><th>' + escapeHtml(t('scoring.ui.actions')) + '</th></tr></thead><tbody>';

        provisionalRelations.forEach(function (h, idx) {
            var confPct = (h.confidence * 100).toFixed(0);
            var confClass = h.confidence >= 0.8 ? 'text-success fw-bold' : (h.confidence >= 0.5 ? 'text-warning' : 'text-danger');
            var status = h.status || 'PROVISIONAL';
            var actions;
            if (status === 'ACCEPTED' || status === 'REJECTED') {
                actions = '<span class="badge bg-' + (status === 'ACCEPTED' ? 'success' : 'danger')
                    + '">' + escapeHtml(t(status === 'ACCEPTED'
                        ? 'scoring.badge.accepted' : 'scoring.badge.dismissed')) + '</span>';
            } else if (status !== 'PROVISIONAL' && status !== 'PROPOSED') {
                actions = '<span class="badge bg-secondary">' + escapeHtml(status) + '</span>';
            } else if (h.appliedInCurrentAnalysis === true) {
                actions = '<span class="badge bg-info">' + escapeHtml(t('scoring.badge.session.only')) + '</span>';
            } else {
                actions = '<button class="btn btn-sm btn-outline-success me-1" onclick="window._acceptHypothesis(' + idx + ')" title="' + escapeHtml(t('scoring.ui.accept')) + '" aria-label="' + escapeHtml(t('scoring.ui.acceptAria', h.sourceCode, h.targetCode)) + '">✅</button>' +
                    '<button class="btn btn-sm btn-outline-info me-1" onclick="window._applyForSession(' + idx + ')" title="' + escapeHtml(t('scoring.ui.session')) + '" aria-label="' + escapeHtml(t('scoring.ui.sessionAria', h.sourceCode, h.targetCode)) + '">📌</button>' +
                    '<button class="btn btn-sm btn-outline-danger" onclick="window._rejectHypothesis(' + idx + ')" title="' + escapeHtml(t('scoring.ui.dismiss')) + '" aria-label="' + escapeHtml(t('scoring.ui.dismissAria', h.sourceCode, h.targetCode)) + '">❌</button>';
            }
            html += '<tr id="suggested-row-' + idx + '">' +
                '<td>' + escapeHtml(h.sourceCode) + (h.sourceName ? '<br><small class="text-muted">' + escapeHtml(h.sourceName) + '</small>' : '') + '</td>' +
                '<td>→</td>' +
                '<td>' + escapeHtml(h.targetCode) + (h.targetName ? '<br><small class="text-muted">' + escapeHtml(h.targetName) + '</small>' : '') + '</td>' +
                '<td><span class="badge bg-secondary">' + escapeHtml(h.relationType) + '</span></td>' +
                '<td class="' + confClass + '">' + confPct + '%</td>' +
                '<td><small>' + escapeHtml(h.reasoning || '') + '</small></td>' +
                '<td class="text-nowrap">' + actions + '</td></tr>';
        });

        html += '</tbody></table></div>';
        content.innerHTML = html;
        panel.style.display = '';

        // Store for action handlers
        window._currentProvisionalRelations = provisionalRelations;
        // The Git adapter owns Undo commands and reinstalls them on restored rows.
        window.TaxonomyHypothesisReview?.renderRestoredState();
    }

    /**
     * Accept a single hypothesis: create proposal and auto-accept.
     */
    window._acceptHypothesis = function (idx) {
        var h = window._currentProvisionalRelations && window._currentProvisionalRelations[idx];
        if (!h) return;
        var row = document.getElementById('suggested-row-' + idx);
        if (row) row.style.opacity = '0.5';

        fetch('/api/proposals/from-hypothesis', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                sourceCode: h.sourceCode,
                targetCode: h.targetCode,
                relationType: h.relationType,
                confidence: h.confidence,
                rationale: h.reasoning
            })
        })
        .then(function (r) {
            if (r.status === 409) {
                // Proposal already exists, mark as already processed
                if (row) { row.classList.add('table-info'); row.style.opacity = '1'; }
                var actions = row && row.querySelector('td:last-child');
                if (actions) actions.innerHTML = '<span class="badge bg-info">' + escapeHtml(t('scoring.badge.already.exists')) + '</span>';
                B().showStatus('info', t('analyze.proposal.exists', h.sourceCode, h.targetCode));
                return null;
            }
            if (!r.ok) throw new Error('HTTP ' + r.status);
            return r.json();
        })
        .then(function (proposal) {
            if (proposal === null) return; // already handled (409)
            if (proposal && proposal.id) {
                // Auto-accept the created proposal
                return fetch('/api/proposals/' + proposal.id + '/accept', { method: 'POST' });
            }
        })
        .then(function () {
            if (row) { row.classList.add('table-success'); row.style.opacity = '1'; }
            var actions = row && row.querySelector('td:last-child');
            if (actions) actions.innerHTML = '<span class="badge bg-success">' + escapeHtml(t('scoring.badge.accepted')) + '</span>';
            B().showStatus('success', t('scoring.relation.accepted', h.sourceCode, h.targetCode));
        })
        .catch(function (err) {
            if (row) row.style.opacity = '1';
            B().showStatus('danger', t('scoring.relation.accept.failed', err.message));
        });
    };

    /**
     * Dismiss a hypothesis (just hides the row — no server action needed).
     */
    window._rejectHypothesis = function (idx) {
        var row = document.getElementById('suggested-row-' + idx);
        if (row) { row.classList.add('table-danger'); row.style.opacity = '0.4'; }
        var actions = row && row.querySelector('td:last-child');
        if (actions) actions.innerHTML = '<span class="badge bg-secondary">' + escapeHtml(t('scoring.badge.dismissed')) + '</span>';
    };

    /**
     * Apply a hypothesis for the current analysis session only.
     * Marks the hypothesis as "applied in current analysis" without permanently
     * accepting it. The relationship is used in the current Architecture View
     * and exports but is not persisted as a TaxonomyRelation.
     */
    window._applyForSession = function (idx) {
        var h = window._currentProvisionalRelations && window._currentProvisionalRelations[idx];
        if (!h) return;
        var row = document.getElementById('suggested-row-' + idx);

        // Mark as applied in current analysis via API
        if (h.hypothesisId) {
            fetch('/api/dsl/hypotheses/' + h.hypothesisId + '/apply-session', { method: 'POST' })
                .catch(function () { /* best effort */ });
        }

        // Mark in UI
        if (row) { row.classList.add('table-info'); }
        var actions = row && row.querySelector('td:last-child');
        if (actions) actions.innerHTML = '<span class="badge bg-info">' + escapeHtml(t('scoring.badge.session.only')) + '</span>';
        h.appliedInCurrentAnalysis = true;
        B().showStatus('info', '📌 Applied for this session: ' + h.sourceCode + ' → ' + h.targetCode);
    };

    /**
     * Accept all hypotheses with confidence >= 80%.
     */
    window._acceptAllHighConfidence = function () {
        var relations = window._currentProvisionalRelations;
        if (!relations) return;
        relations.forEach(function (h, idx) {
            if (h.confidence >= 0.8) {
                window._acceptHypothesis(idx);
            }
        });
    };

    // ── Summary View ──────────────────────────────────────────────────────────
    var summarySelection = null;

    function renderSummaryView(data, scores) {
        var container = document.getElementById('taxonomyTree');
        if (!container) return;

        var view = S.currentArchView;
        if (!view || !view.includedElements || view.includedElements.length === 0) {
            container.innerHTML = '<div class="summary-view"><p class="text-muted">' + escapeHtml(t('scoring.no.archview')) + '</p></div>';
            return;
        }

        // Project the display only; the saved architecture remains authoritative for exports.
        // "All" also preserves external/legacy elements absent from the current catalogue.
        var displayElements = view.includedElements;
        var displayRelationships = view.includedRelationships || [];
        var displayAnchors = view.anchors || [];
        if (B().isRelevanceFilterActive?.()) {
            var retainedCodes = new Set();
            function retain(node) {
                retainedCodes.add(node.code);
                (node.children || []).forEach(retain);
            }
            (data || []).forEach(retain);
            displayElements = displayElements.filter(function (el) { return retainedCodes.has(el.nodeCode); });
            var displayedCodes = new Set(displayElements.map(function (el) { return el.nodeCode; }));
            displayRelationships = displayRelationships.filter(function (relation) {
                return displayedCodes.has(relation.sourceCode) && displayedCodes.has(relation.targetCode);
            });
            displayAnchors = displayAnchors.filter(function (anchor) { return displayedCodes.has(anchor.nodeCode); });
        }
        if (displayElements.length === 0) {
            if (summarySelection && summarySelection.view === view) summarySelection = null;
            container.innerHTML = '<div class="summary-view"><p class="text-muted" role="status">' + escapeHtml(t('view.relevance.empty')) + '</p></div>';
            return;
        }

        var bt = document.getElementById('businessText');
        var requirement = bt ? bt.value.trim() : '';

        // Group elements by taxonomy sheet
        var groups = {};
        displayElements.forEach(function (el) {
            var sheet = el.taxonomySheet || 'Unknown';
            if (!groups[sheet]) groups[sheet] = [];
            groups[sheet].push(el);
        });

        // Sort groups by layer order
        var sortedSheets = Object.keys(groups).sort(function (a, b) {
            var oa = LAYER_CONFIG[a] ? LAYER_CONFIG[a].order : 99;
            var ob = LAYER_CONFIG[b] ? LAYER_CONFIG[b].order : 99;
            return oa - ob;
        });

        // Collect relationship types between layers for arrow labels
        var layerRelations = {};
        if (displayRelationships.length) {
            displayRelationships.forEach(function (r) {
                var srcSheet = null, tgtSheet = null;
                displayElements.forEach(function (el) {
                    if (el.nodeCode === r.sourceCode) srcSheet = el.taxonomySheet;
                    if (el.nodeCode === r.targetCode) tgtSheet = el.taxonomySheet;
                });
                if (srcSheet && tgtSheet && srcSheet !== tgtSheet) {
                    var key = srcSheet + '→' + tgtSheet;
                    if (!layerRelations[key]) layerRelations[key] = new Set();
                    layerRelations[key].add(r.relationType);
                }
            });
        }

        var html = '<div class="summary-view">';
        html += '<div class="summary-header">📋 ' + escapeHtml(t('scoring.ui.summary')) + '</div>';
        if (requirement) {
            html += '<div class="summary-requirement">"' + escapeHtml(requirement.substring(0, 200)) +
                (requirement.length > 200 ? '…' : '') + '"</div>';
        }

        for (var i = 0; i < sortedSheets.length; i++) {
            var sheet = sortedSheets[i];
            var cfg = LAYER_CONFIG[sheet] || { order: 99, cls: '', icon: '⬜', label: sheet };
            var elements = groups[sheet];

            // Arrow between groups
            if (i > 0) {
                var prevSheet = sortedSheets[i - 1];
                var key = prevSheet + '→' + sheet;
                var rKey = sheet + '→' + prevSheet;
                var relTypes = layerRelations[key] || layerRelations[rKey] || new Set();
                var arrowLabel = relTypes.size > 0 ? Array.from(relTypes).join(', ') : '';
                html += '<div class="summary-arrow">│<br>';
                if (arrowLabel) html += '<span class="arrow-label">' + escapeHtml(arrowLabel) + '</span><br>';
                html += '▼</div>';
            }

            html += '<div class="summary-layer ' + cfg.cls + '">';
            html += '<div class="summary-layer-title">' + cfg.icon + ' ' + escapeHtml(cfg.label) +
                ' <span class="badge bg-secondary" style="font-size:0.7rem;">' + elements.length + '</span></div>';

            elements.sort(function (a, b) { return b.relevance - a.relevance; });
            elements.forEach(function (el) {
                var pct = scoreText(el.relevance * 100);
                // Use full hierarchy path from backend (e.g. "CP > CP-1000 > CP-1023")
                var path = el.hierarchyPath || el.nodeCode;
                var titleParts = [path, el.title || '', el.includedBecause || '']
                    .filter(function (p) { return p.length > 0; });
                html += '<button type="button" class="summary-layer-element" aria-pressed="false" aria-controls="summarySelectionDetails" data-code="' + escapeHtml(el.nodeCode) +
                    '" title="' + escapeHtml(titleParts.join(' — ')) + '">';
                html += escapeHtml(el.nodeCode);
                if (el.title) html += ' \u2013 ' + escapeHtml(el.title.substring(0, 50));
                html += ' <span class="summary-pct">[' + pct + '%]</span>';
                if (el.anchor) html += ' ★';
                html += '</button>';
            });

            html += '</div>';
        }

        // Stats footer
        var anchorCount = displayAnchors.length;
        var elemCount = displayElements.length;
        var relCount = displayRelationships.length;
        html += '<div class="summary-stats">';
        html += escapeHtml(t('scoring.ui.stats', anchorCount, elemCount, relCount, sortedSheets.length));
        html += '</div>';
        html += '</div>';

        container.innerHTML = html;

        var details = document.createElement('section');
        details.id = 'summarySelectionDetails';
        details.setAttribute('aria-live', 'polite');
        details.hidden = true;
        container.querySelector('.summary-view').appendChild(details);

        function select(code) {
            var selected = displayElements.find(function (item) { return item.nodeCode === code; });
            if (!selected) {
                summarySelection = null;
                return;
            }
            summarySelection = { view: view, code: code };
            container.querySelectorAll('.summary-layer-element').forEach(function (button) {
                button.setAttribute('aria-pressed', String(button.dataset.code === code));
            });
            details.replaceChildren();
            details.hidden = false;
            var heading = document.createElement('h3');
            heading.className = 'h6';
            heading.textContent = code + (selected.title ? ' — ' + selected.title : '');
            details.appendChild(heading);
            [selected.hierarchyPath, selected.includedBecause, (S.currentReasons || {})[code]].forEach(function (value) {
                if (!value) return;
                var paragraph = document.createElement('p');
                paragraph.textContent = value;
                details.appendChild(paragraph);
            });
            var navigate = document.createElement('button');
            navigate.type = 'button';
            navigate.className = 'btn btn-sm btn-outline-primary';
            navigate.textContent = isGermanLocale() ? 'Im Taxonomiebaum anzeigen' : 'Show in taxonomy tree';
            navigate.addEventListener('click', function () {
                B().switchView('list');
                expandNodeByCode(code, true);
                var node = B().ensureNodeRendered(code, S.currentScores);
                if (node) {
                    node.scrollIntoView({ block: 'center' });
                    node.focus({ preventScroll: true });
                }
            });
            details.appendChild(navigate);
        }
        container.querySelectorAll('.summary-layer-element').forEach(function (button) {
            button.addEventListener('click', function () { select(this.dataset.code); });
        });
        if (summarySelection && summarySelection.view === view) select(summarySelection.code);
    }

    // ── Public API ────────────────────────────────────────────────────────────
    window.TaxonomyScoring = {
        runAnalysis: runAnalysis,
        resumeAnalysis: resumeAnalysis,
        runInteractiveAnalysis: runInteractiveAnalysis,
        runStreamingAnalysis: runStreamingAnalysis,
        applyScoreToNode: applyScoreToNode,
        describeScore: describeScore,
        formatPercentage: function (value) { return scoreText(value) + '%'; },
        applyLocalRawScores: applyLocalRawScores,
        syncVisibleScoreNodes: syncVisibleScoreNodes,
        markNodeAsEvaluating: markNodeAsEvaluating,
        expandMatched: expandMatched,
        highlightScoringPaths: highlightScoringPaths,
        expandNodeByCode: expandNodeByCode,
        setAnalyzing: setAnalyzing,
        clearAnalysisLog: clearAnalysisLog,
        updateAnalysisLog: updateAnalysisLog,
        appendLlmLogEntry: appendLlmLogEntry,
        requestLeafJustification: requestLeafJustification,
        canRequestFollowUp: canRequestFollowUp,
        showLeafJustificationModal: showLeafJustificationModal,
        renderRelationSearchReport: renderRelationSearchReport,
        renderArchitectureView: renderArchitectureView,
        relationSearchReportFrom: relationSearchReportFrom,
        acceptRelationSearchReport: acceptRelationSearchReport,
        acceptAnalysisReasonLength: acceptAnalysisReasonLength,
        clearDerivedAnalysisView: clearDerivedAnalysisView,
        renderIndependentRelationSearchReport: renderIndependentRelationSearchReport,
        renderSuggestedRelations: renderSuggestedRelations,
        refreshSuggestedRelationsBulkActions: refreshSuggestedRelationsBulkActions,
        renderSummaryView: renderSummaryView,
        escapeHtml: escapeHtml
    };

})();
