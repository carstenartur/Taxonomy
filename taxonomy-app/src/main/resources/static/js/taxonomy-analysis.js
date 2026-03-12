/* taxonomy-analysis.js – Gap Analysis, Pattern Detection, Recommendation & Copilot UI */

(function () {
    'use strict';

    // ── Helpers ────────────────────────────────────────────────────────────────

    function escapeHtml(s) {
        if (!s) return '';
        return String(s)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function getBusinessText() {
        var el = document.getElementById('businessText');
        return el ? el.value.trim() : '';
    }

    function getCurrentScores() {
        // Access the scores from the main taxonomy.js module via the shared state
        return window._taxonomyCurrentScores || {};
    }

    function hasScores() {
        var scores = getCurrentScores();
        return scores && Object.keys(scores).length > 0;
    }

    function showPanelLoading(contentId) {
        var el = document.getElementById(contentId);
        if (el) {
            el.innerHTML =
                '<div class="text-center text-muted py-2">' +
                '<div class="spinner-border spinner-border-sm me-1" role="status"></div> ' +
                'Analyzing\u2026</div>';
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

    function runGapAnalysis() {
        if (!hasScores()) {
            showPanelError('gapAnalysisContent', 'Please run an analysis first to get scores.');
            return;
        }
        showPanelLoading('gapAnalysisContent');
        var panel = document.getElementById('gapAnalysisPanel');
        if (panel) panel.open = true;

        fetch('/api/gap/analyze', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                scores: getCurrentScores(),
                businessText: getBusinessText(),
                minScore: 50
            })
        })
        .then(function (r) {
            if (!r.ok) throw new Error('HTTP ' + r.status);
            return r.json();
        })
        .then(function (data) {
            renderGapAnalysis(data);
        })
        .catch(function (err) {
            showPanelError('gapAnalysisContent', 'Gap analysis failed: ' + err.message);
        });
    }

    function renderGapAnalysis(data) {
        var el = document.getElementById('gapAnalysisContent');
        if (!el) return;
        var html = '';

        // Summary
        html += '<div class="d-flex gap-3 mb-2 flex-wrap">';
        html += '<span class="badge bg-primary">Anchors: ' + data.totalAnchors + '</span>';
        html += '<span class="badge ' + (data.totalGaps > 0 ? 'bg-danger' : 'bg-success') + '">Gaps: ' + data.totalGaps + '</span>';
        html += '</div>';

        // Missing Relations
        if (data.missingRelations && data.missingRelations.length > 0) {
            html += '<h6 class="small fw-semibold mt-2">\u274C Missing Relations <span class="badge bg-danger">' + data.missingRelations.length + '</span></h6>';
            html += '<div class="table-responsive"><table class="table table-sm table-hover mb-2" style="font-size:0.82em;">';
            html += '<thead><tr><th>Source Node</th><th>Source Root</th><th>Expected Relation</th><th>Expected Target</th><th>Description</th></tr></thead><tbody>';
            data.missingRelations.forEach(function (mr) {
                html += '<tr>';
                html += '<td><code>' + escapeHtml(mr.sourceNodeCode) + '</code></td>';
                html += '<td><span class="badge bg-light text-dark border">' + escapeHtml(mr.sourceRoot) + '</span></td>';
                html += '<td><span class="badge bg-info text-dark">' + escapeHtml(mr.expectedRelationType) + '</span></td>';
                html += '<td><span class="badge bg-light text-dark border">' + escapeHtml(mr.expectedTargetRoot) + '</span></td>';
                html += '<td class="small text-muted">' + escapeHtml(mr.description || '') + '</td>';
                html += '</tr>';
            });
            html += '</tbody></table></div>';
        }

        // Incomplete Patterns
        if (data.incompletePatterns && data.incompletePatterns.length > 0) {
            html += '<h6 class="small fw-semibold mt-2">\u26A0\uFE0F Incomplete Patterns <span class="badge bg-warning text-dark">' + data.incompletePatterns.length + '</span></h6>';
            html += '<div class="table-responsive"><table class="table table-sm table-hover mb-2" style="font-size:0.82em;">';
            html += '<thead><tr><th>Node</th><th>Root</th><th>Pattern</th><th>Missing</th></tr></thead><tbody>';
            data.incompletePatterns.forEach(function (ip) {
                html += '<tr>';
                html += '<td><code>' + escapeHtml(ip.nodeCode) + '</code></td>';
                html += '<td><span class="badge bg-light text-dark border">' + escapeHtml(ip.taxonomyRoot) + '</span></td>';
                html += '<td class="small">' + escapeHtml(ip.patternDescription) + '</td>';
                html += '<td class="small text-danger">' + escapeHtml(ip.missingElement) + '</td>';
                html += '</tr>';
            });
            html += '</tbody></table></div>';
        }

        // Coverage Gaps
        if (data.coverageGaps && data.coverageGaps.length > 0) {
            html += '<h6 class="small fw-semibold mt-2">\uD83D\uDCCA Coverage Gaps <span class="badge bg-secondary">' + data.coverageGaps.length + '</span></h6>';
            html += '<div class="table-responsive"><table class="table table-sm table-hover mb-2" style="font-size:0.82em;">';
            html += '<thead><tr><th>Node</th><th>Root</th><th>Score</th><th>Gap</th></tr></thead><tbody>';
            data.coverageGaps.forEach(function (cg) {
                html += '<tr>';
                html += '<td><code>' + escapeHtml(cg.nodeCode) + '</code></td>';
                html += '<td><span class="badge bg-light text-dark border">' + escapeHtml(cg.taxonomyRoot) + '</span></td>';
                html += '<td>' + cg.coverageScore + '%</td>';
                html += '<td class="small text-muted">' + escapeHtml(cg.gapDescription) + '</td>';
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

        if (data.totalGaps === 0) {
            html += '<div class="alert alert-success py-1 px-2 small mb-0 mt-2">\u2705 No gaps detected — architecture coverage is complete for the analyzed nodes.</div>';
        }

        el.innerHTML = html;
    }

    // ── Pattern Detection ─────────────────────────────────────────────────────

    function runPatternDetection() {
        if (!hasScores()) {
            showPanelError('patternDetectionContent', 'Please run an analysis first to get scores.');
            return;
        }
        showPanelLoading('patternDetectionContent');
        var panel = document.getElementById('patternDetectionPanel');
        if (panel) panel.open = true;

        fetch('/api/patterns/detect', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                scores: getCurrentScores(),
                minScore: 50
            })
        })
        .then(function (r) {
            if (!r.ok) throw new Error('HTTP ' + r.status);
            return r.json();
        })
        .then(function (data) {
            renderPatternDetection(data);
        })
        .catch(function (err) {
            showPanelError('patternDetectionContent', 'Pattern detection failed: ' + err.message);
        });
    }

    function renderPatternDetection(data) {
        var el = document.getElementById('patternDetectionContent');
        if (!el) return;
        var html = '';

        // Summary
        html += '<div class="d-flex gap-3 mb-2 flex-wrap">';
        html += '<span class="badge bg-success">Complete Patterns: ' + (data.matchedPatterns ? data.matchedPatterns.length : 0) + '</span>';
        html += '<span class="badge bg-warning text-dark">Incomplete: ' + (data.incompletePatterns ? data.incompletePatterns.length : 0) + '</span>';
        html += '<span class="badge bg-primary">Coverage: ' + (data.patternCoverage * 100).toFixed(0) + '%</span>';
        html += '</div>';

        // Matched patterns
        if (data.matchedPatterns && data.matchedPatterns.length > 0) {
            html += '<h6 class="small fw-semibold mt-2">\u2705 Complete Patterns</h6>';
            data.matchedPatterns.forEach(function (p) {
                html += renderPatternCard(p, 'success');
            });
        }

        // Incomplete patterns
        if (data.incompletePatterns && data.incompletePatterns.length > 0) {
            html += '<h6 class="small fw-semibold mt-2">\u26A0\uFE0F Incomplete Patterns</h6>';
            data.incompletePatterns.forEach(function (p) {
                html += renderPatternCard(p, 'warning');
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
            html += '<div class="text-muted small">No patterns detected for the current scores.</div>';
        }

        el.innerHTML = html;
    }

    function renderPatternCard(pattern, color) {
        var pct = (pattern.completeness * 100).toFixed(0);
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
        if (pattern.expectedSteps && pattern.expectedSteps.length > 0) {
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

    function runRecommendation() {
        if (!hasScores()) {
            showPanelError('recommendationContent', 'Please run an analysis first to get scores.');
            return;
        }
        showPanelLoading('recommendationContent');
        var panel = document.getElementById('recommendationPanel');
        if (panel) panel.open = true;

        fetch('/api/recommend', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                scores: getCurrentScores(),
                businessText: getBusinessText(),
                minScore: 50
            })
        })
        .then(function (r) {
            if (!r.ok) throw new Error('HTTP ' + r.status);
            return r.json();
        })
        .then(function (data) {
            renderRecommendation(data);
        })
        .catch(function (err) {
            showPanelError('recommendationContent', 'Recommendation failed: ' + err.message);
        });
    }

    function renderRecommendation(data) {
        var el = document.getElementById('recommendationContent');
        if (!el) return;
        var html = '';

        // Confidence
        var conf = data.confidence ? data.confidence.toFixed(0) : 0;
        var confColor = conf >= 70 ? 'success' : (conf >= 40 ? 'warning' : 'danger');
        html += '<div class="d-flex gap-3 mb-2 flex-wrap align-items-center">';
        html += '<span class="badge bg-' + confColor + (confColor === 'warning' ? ' text-dark' : '') + '" style="font-size:0.9em;">\uD83C\uDFAF Confidence: ' + conf + '%</span>';
        html += '<span class="badge bg-success">Confirmed: ' + (data.confirmedElements ? data.confirmedElements.length : 0) + '</span>';
        html += '<span class="badge bg-info text-dark">Proposed: ' + (data.proposedElements ? data.proposedElements.length : 0) + '</span>';
        html += '<span class="badge bg-secondary">Relations: ' + (data.suggestedRelations ? data.suggestedRelations.length : 0) + '</span>';
        html += '</div>';

        // Reasoning
        if (data.reasoning && data.reasoning.length > 0) {
            html += '<div class="alert alert-light py-1 px-2 small mb-2 border">';
            html += '<strong>\uD83D\uDCA1 Reasoning:</strong><ul class="mb-0 ps-3">';
            data.reasoning.forEach(function (r) {
                html += '<li>' + escapeHtml(r) + '</li>';
            });
            html += '</ul></div>';
        }

        // Confirmed Elements
        if (data.confirmedElements && data.confirmedElements.length > 0) {
            html += '<h6 class="small fw-semibold mt-2">\u2705 Confirmed Elements</h6>';
            html += renderRecommendedElementsTable(data.confirmedElements, 'success');
        }

        // Proposed Elements
        if (data.proposedElements && data.proposedElements.length > 0) {
            html += '<h6 class="small fw-semibold mt-2">\uD83D\uDCA1 Proposed Elements</h6>';
            html += renderRecommendedElementsTable(data.proposedElements, 'info');
        }

        // Suggested Relations
        if (data.suggestedRelations && data.suggestedRelations.length > 0) {
            html += '<h6 class="small fw-semibold mt-2">\uD83D\uDD17 Suggested Relations</h6>';
            html += '<div class="table-responsive"><table class="table table-sm table-hover mb-2" style="font-size:0.82em;">';
            html += '<thead><tr><th>Source</th><th></th><th>Target</th><th>Type</th><th>Reasoning</th></tr></thead><tbody>';
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

    function renderRecommendedElementsTable(elements, color) {
        var html = '<div class="table-responsive"><table class="table table-sm table-hover mb-2" style="font-size:0.82em;">';
        html += '<thead><tr><th>Code</th><th>Title</th><th>Root</th><th>Score</th><th>Reasoning</th></tr></thead><tbody>';
        elements.forEach(function (e) {
            html += '<tr>';
            html += '<td><code>' + escapeHtml(e.nodeCode) + '</code></td>';
            html += '<td>' + escapeHtml(e.title || '') + '</td>';
            html += '<td><span class="badge bg-light text-dark border">' + escapeHtml(e.taxonomyRoot || '') + '</span></td>';
            html += '<td><span class="badge bg-' + color + (color === 'warning' ? ' text-dark' : '') + '">' + e.score + '</span></td>';
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
        if (!code) {
            var content = document.getElementById('graphResultsContent');
            if (content) {
                content.innerHTML = '<div class="alert alert-warning py-1 px-2 small mb-0">Please enter a node code.</div>';
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
                'Analyzing enriched failure impact\u2026</div>';
        }

        fetch('/api/graph/node/' + encodeURIComponent(code) + '/enriched-failure-impact?maxHops=' + maxHops)
            .then(function (r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            })
            .then(function (data) {
                if (content) content.innerHTML = renderEnrichedFailureImpact(data);
            })
            .catch(function (err) {
                if (content) {
                    content.innerHTML = '<div class="alert alert-warning py-1 px-2 small mb-0">Enriched failure impact failed: ' +
                        escapeHtml(err.message) + '</div>';
                }
            });
    }

    function renderEnrichedFailureImpact(data) {
        var html = '';

        // Summary stats
        html += '<div class="d-flex gap-2 mb-2 flex-wrap">';
        html += '<span class="badge bg-danger">\u26A0\uFE0F Failed: ' + escapeHtml(data.failedNodeCode) + '</span>';
        html += '<span class="badge bg-danger">\uD83D\uDD34 Direct: ' + (data.directlyAffected ? data.directlyAffected.length : 0) + '</span>';
        html += '<span class="badge bg-warning text-dark">\uD83D\uDFE0 Indirect: ' + (data.indirectlyAffected ? data.indirectlyAffected.length : 0) + '</span>';
        html += '<span class="badge bg-dark">Total: ' + data.totalAffected + '</span>';
        var riskColor = data.riskScore > 5 ? 'danger' : (data.riskScore > 2 ? 'warning' : 'success');
        html += '<span class="badge bg-' + riskColor + (riskColor === 'warning' ? ' text-dark' : '') + '">\uD83D\uDCC8 Risk: ' + data.riskScore.toFixed(2) + '</span>';
        html += '</div>';

        // Affected Requirements
        if (data.affectedRequirements && data.affectedRequirements.length > 0) {
            html += '<div class="alert alert-danger py-1 px-2 small mb-2">';
            html += '<strong>\uD83D\uDCCB Affected Requirements (' + data.affectedRequirements.length + '):</strong> ';
            html += data.affectedRequirements.map(function (r) { return escapeHtml(r); }).join(', ');
            html += '</div>';
        }

        // Directly Affected
        if (data.directlyAffected && data.directlyAffected.length > 0) {
            html += '<h6 class="small fw-semibold">\uD83D\uDD34 Directly Affected (Hop 1)</h6>';
            html += renderEnrichedElementsTable(data.directlyAffected);
        }

        // Indirectly Affected
        if (data.indirectlyAffected && data.indirectlyAffected.length > 0) {
            html += '<h6 class="small fw-semibold mt-2">\uD83D\uDFE0 Indirectly Affected (Hop 2+)</h6>';
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
        html += '<thead><tr><th>Code</th><th>Title</th><th>Sheet</th><th>Relevance</th><th>Hop</th><th>Requirements</th></tr></thead><tbody>';
        elements.forEach(function (e) {
            html += '<tr>';
            html += '<td><code>' + escapeHtml(e.nodeCode) + '</code></td>';
            html += '<td>' + escapeHtml(e.title || '') + '</td>';
            html += '<td><span class="badge bg-light text-dark border">' + escapeHtml(e.taxonomySheet || '') + '</span></td>';
            html += '<td>' + (e.relevance * 100).toFixed(0) + '%</td>';
            html += '<td>' + e.hopDistance + '</td>';
            html += '<td>';
            if (e.coveredByRequirements && e.coveredByRequirements.length > 0) {
                html += '<span class="badge bg-danger">' + e.requirementCount + ' req(s)</span> ';
                html += '<span class="small text-muted">' + e.coveredByRequirements.map(function (r) { return escapeHtml(r); }).join(', ') + '</span>';
            } else {
                html += '<span class="text-muted small">none</span>';
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
                'Importing ' + escapeHtml(file.name) + '\u2026</div>';
            statusEl.style.display = '';
        }

        fetch('/api/import/archimate', {
            method: 'POST',
            body: formData
        })
        .then(function (r) {
            if (!r.ok) throw new Error('HTTP ' + r.status);
            return r.json();
        })
        .then(function (data) {
            if (statusEl) {
                var html = '<div class="alert alert-success py-1 px-2 small mb-0">';
                html += '\u2705 Imported: ' + (data.elementsImported || 0) + ' elements, ';
                html += (data.relationsImported || 0) + ' relations';
                if (data.notes && data.notes.length > 0) {
                    html += '<br><small>' + data.notes.map(function (n) { return escapeHtml(n); }).join('; ') + '</small>';
                }
                html += '</div>';
                statusEl.innerHTML = html;
            }
        })
        .catch(function (err) {
            if (statusEl) {
                statusEl.innerHTML = '<div class="alert alert-danger py-1 px-2 small mb-0">\u274C Import failed: ' +
                    escapeHtml(err.message) + '</div>';
            }
        });
    }

    // ── Copilot One-Click Flow ────────────────────────────────────────────────

    function runCopilotFlow() {
        var bt = getBusinessText();
        if (!bt) {
            showCopilotStatus('warning', 'Please enter a business requirement first.');
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
                '<span id="copilotStepLabel">Step 1/4: Analyzing requirement\u2026</span></div>';
        }

        // Step 1: Trigger the main analysis (use existing analyze button logic)
        var analyzeBtn = document.getElementById('analyzeBtn');
        if (analyzeBtn && !hasScores()) {
            // Trigger analysis and wait for scores
            showCopilotStep('Step 1/4: Analyzing requirement\u2026');
            analyzeBtn.click();
            // Poll for scores to appear
            waitForScores(function () {
                continueCopilotFlow();
            });
        } else if (hasScores()) {
            // Already have scores, continue
            continueCopilotFlow();
        } else {
            showCopilotStatus('danger', 'Cannot start analysis. Please check AI status.');
            resetCopilotBtn();
        }
    }

    function waitForScores(callback) {
        var attempts = 0;
        var maxAttempts = 60; // 60 attempts at 1s intervals = 60s timeout
        var interval = setInterval(function () {
            attempts++;
            if (hasScores()) {
                clearInterval(interval);
                callback();
            } else if (attempts >= maxAttempts) {
                clearInterval(interval);
                showCopilotStatus('warning', 'Analysis timed out. Please try analyzing manually first, then use the Copilot.');
                resetCopilotBtn();
            }
        }, 1000);
    }

    function continueCopilotFlow() {
        var results = {};

        // Step 2: Gap Analysis
        showCopilotStep('Step 2/4: Running gap analysis\u2026');
        fetch('/api/gap/analyze', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                scores: getCurrentScores(),
                businessText: getBusinessText(),
                minScore: 50
            })
        })
        .then(function (r) { return r.json(); })
        .then(function (gapData) {
            results.gaps = gapData;
            renderGapAnalysis(gapData);
            var gapPanel = document.getElementById('gapAnalysisPanel');
            if (gapPanel) gapPanel.open = true;

            // Step 3: Pattern Detection
            showCopilotStep('Step 3/4: Detecting patterns\u2026');
            return fetch('/api/patterns/detect', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    scores: getCurrentScores(),
                    minScore: 50
                })
            });
        })
        .then(function (r) { return r.json(); })
        .then(function (patternData) {
            results.patterns = patternData;
            renderPatternDetection(patternData);
            var patternPanel = document.getElementById('patternDetectionPanel');
            if (patternPanel) patternPanel.open = true;

            // Step 4: Recommendation
            showCopilotStep('Step 4/4: Generating recommendation\u2026');
            return fetch('/api/recommend', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    scores: getCurrentScores(),
                    businessText: getBusinessText(),
                    minScore: 50
                })
            });
        })
        .then(function (r) { return r.json(); })
        .then(function (recData) {
            results.recommendation = recData;
            renderRecommendation(recData);
            var recPanel = document.getElementById('recommendationPanel');
            if (recPanel) recPanel.open = true;

            // Render copilot summary
            renderCopilotSummary(results);
            resetCopilotBtn();
        })
        .catch(function (err) {
            showCopilotStatus('danger', 'Copilot flow failed: ' + err.message);
            resetCopilotBtn();
        });
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

    function renderCopilotSummary(results) {
        var el = document.getElementById('copilotContent');
        if (!el) return;
        var html = '';

        html += '<div class="alert alert-success py-2 px-3 mb-2">';
        html += '<strong>\u2705 Architecture Copilot Analysis Complete</strong>';
        html += '</div>';

        html += '<div class="row g-2">';

        // Gaps summary card
        var gapCount = results.gaps ? results.gaps.totalGaps : 0;
        html += '<div class="col-4">';
        html += '<div class="card text-center ' + (gapCount > 0 ? 'border-danger' : 'border-success') + '">';
        html += '<div class="card-body py-2">';
        html += '<div class="fs-4">' + (gapCount > 0 ? '\u274C' : '\u2705') + '</div>';
        html += '<div class="fw-bold">' + gapCount + ' Gap(s)</div>';
        html += '<div class="small text-muted">' + (results.gaps ? results.gaps.totalAnchors : 0) + ' anchors analyzed</div>';
        html += '</div></div></div>';

        // Patterns summary card
        var patternPct = results.patterns ? (results.patterns.patternCoverage * 100).toFixed(0) : 0;
        var patternColor = patternPct >= 70 ? 'success' : (patternPct >= 40 ? 'warning' : 'danger');
        html += '<div class="col-4">';
        html += '<div class="card text-center border-' + patternColor + '">';
        html += '<div class="card-body py-2">';
        html += '<div class="fs-4">\uD83E\uDDE9</div>';
        html += '<div class="fw-bold">' + patternPct + '% Patterns</div>';
        html += '<div class="small text-muted">' + (results.patterns && results.patterns.matchedPatterns ? results.patterns.matchedPatterns.length : 0) + ' complete</div>';
        html += '</div></div></div>';

        // Recommendation summary card
        var conf = results.recommendation ? results.recommendation.confidence.toFixed(0) : 0;
        var confColor = conf >= 70 ? 'success' : (conf >= 40 ? 'warning' : 'danger');
        html += '<div class="col-4">';
        html += '<div class="card text-center border-' + confColor + '">';
        html += '<div class="card-body py-2">';
        html += '<div class="fs-4">\uD83D\uDCA1</div>';
        html += '<div class="fw-bold">' + conf + '% Confidence</div>';
        html += '<div class="small text-muted">' + (results.recommendation && results.recommendation.proposedElements ? results.recommendation.proposedElements.length : 0) + ' proposals</div>';
        html += '</div></div></div>';

        html += '</div>';

        html += '<div class="small text-muted mt-2">\uD83D\uDC47 Scroll down for detailed results in the Gap Analysis, Pattern Detection, and Recommendation panels.</div>';

        el.innerHTML = html;
    }

    // ── Event binding ─────────────────────────────────────────────────────────

    document.addEventListener('DOMContentLoaded', function () {
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
        runArchiMateImport: runArchiMateImport
    };

})();
