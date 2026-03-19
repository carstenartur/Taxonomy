/**
 * taxonomy-document-import.js
 *
 * Handles document upload (PDF/DOCX), candidate extraction, review,
 * and handoff to the existing analysis workflow.
 *
 * Supports three import modes:
 * - extract:     Rule-based paragraph extraction (default)
 * - ai-extract:  AI-assisted requirement extraction via LLM
 * - reg-map:     Direct regulation-to-architecture mapping via LLM
 */
(function () {
    'use strict';

    const uploadBtn    = document.getElementById('docImportUploadBtn');
    const fileInput    = document.getElementById('docImportFile');
    const titleInput   = document.getElementById('docImportTitle');
    const typeSelect   = document.getElementById('docImportType');
    const spinner      = document.getElementById('docImportSpinner');
    const statusArea   = document.getElementById('docImportStatus');
    const reviewPanel  = document.getElementById('docCandidateReviewPanel');
    const candidateList = document.getElementById('docCandidateList');
    const countBadge   = document.getElementById('docCandidateCount');
    const sourceInfo   = document.getElementById('docCandidateSourceInfo');
    const selectAllBtn = document.getElementById('docSelectAllBtn');
    const deselectBtn  = document.getElementById('docDeselectAllBtn');
    const analyzeBtn   = document.getElementById('docAnalyzeSelectedBtn');

    // AI extraction panel elements
    const aiResultPanel   = document.getElementById('docAiResultPanel');
    const aiCandidateList = document.getElementById('docAiCandidateList');
    const aiComparisonBadge = document.getElementById('docAiComparisonBadge');
    const aiSelectAllBtn  = document.getElementById('docAiSelectAllBtn');
    const aiDeselectAllBtn = document.getElementById('docAiDeselectAllBtn');
    const aiAnalyzeBtn    = document.getElementById('docAiAnalyzeBtn');

    // Regulation mapping panel elements
    const regMapPanel     = document.getElementById('docRegMapResultPanel');
    const regMapTableBody = document.getElementById('docRegMapTableBody');
    const regMapSourceInfo = document.getElementById('docRegMapSourceInfo');

    // Provenance panel elements
    const provenanceContent = document.getElementById('provenanceContent');

    let currentParseResult = null;
    let currentAiCandidates = null;

    if (!uploadBtn) return; // Guard: elements not present

    // ── Upload handler ─────────────────────────────────────────────────────────

    uploadBtn.addEventListener('click', async function () {
        const file = fileInput.files[0];
        if (!file) {
            statusArea.innerHTML = '<span class="text-warning">⚠️ ' + i18n('docimport.select.file', 'Please select a file.') + '</span>';
            return;
        }

        const mode = getSelectedMode();

        switch (mode) {
            case 'extract':
                await uploadAndExtract(file);
                break;
            case 'ai-extract':
                await uploadAndExtractAi(file);
                break;
            case 'reg-map':
                await uploadAndMapRegulation(file);
                break;
        }
    });

    function getSelectedMode() {
        var checked = document.querySelector('input[name="importMode"]:checked');
        return checked ? checked.value : 'extract';
    }

    // ── Rule-based extraction (existing) ───────────────────────────────────────

    async function uploadAndExtract(file) {
        const formData = new FormData();
        formData.append('file', file);
        formData.append('title', titleInput.value || '');
        formData.append('sourceType', typeSelect.value || 'REGULATION');

        spinner.classList.remove('d-none');
        uploadBtn.disabled = true;
        hideAllResultPanels();
        statusArea.innerHTML = '<span class="text-muted">⏳ ' + i18n('docimport.uploading', 'Uploading and parsing…') + '</span>';

        try {
            var resp = await fetchWithCsrf('/api/documents/upload', formData);
            if (!resp.ok) {
                var err = await resp.json().catch(function() { return { error: 'Upload failed' }; });
                statusArea.innerHTML = '<span class="text-danger">❌ ' + escapeHtml(err.error || 'Upload failed') + '</span>';
                return;
            }

            var result = await resp.json();
            currentParseResult = result;

            statusArea.innerHTML = '<span class="text-success">✅ ' +
                i18n('docimport.success', 'Extracted {0} candidates from {1} pages.')
                    .replace('{0}', result.totalCandidates)
                    .replace('{1}', result.totalPages) + '</span>';

            showCandidateReview(result);
        } catch (e) {
            statusArea.innerHTML = '<span class="text-danger">❌ ' + escapeHtml(e.message) + '</span>';
        } finally {
            spinner.classList.add('d-none');
            uploadBtn.disabled = false;
        }
    }

    // ── AI-assisted extraction ─────────────────────────────────────────────────

    async function uploadAndExtractAi(file) {
        var formData = new FormData();
        formData.append('file', file);
        formData.append('sourceType', typeSelect.value || 'REGULATION');

        spinner.classList.remove('d-none');
        uploadBtn.disabled = true;
        hideAllResultPanels();
        statusArea.innerHTML = '<span class="text-muted">⏳ ' + i18n('docimport.ai.extracting', 'AI is analyzing the document…') + '</span>';

        try {
            var resp = await fetchWithCsrf('/api/documents/extract-ai', formData);
            if (!resp.ok) {
                var err = await resp.json().catch(function() { return { error: 'AI extraction failed' }; });
                statusArea.innerHTML = '<span class="text-danger">❌ ' + escapeHtml(err.error || 'AI extraction failed') + '</span>';
                return;
            }

            var result = await resp.json();
            currentAiCandidates = result.aiCandidates || [];

            statusArea.innerHTML = '<span class="text-success">✅ ' +
                i18n('docimport.ai.success', 'AI extracted {0} requirements from {1} pages.')
                    .replace('{0}', currentAiCandidates.length)
                    .replace('{1}', result.totalPages) + '</span>';

            showAiExtractionResults(result);
        } catch (e) {
            statusArea.innerHTML = '<span class="text-danger">❌ ' + escapeHtml(e.message) + '</span>';
        } finally {
            spinner.classList.add('d-none');
            uploadBtn.disabled = false;
        }
    }

    // ── Regulation-to-architecture mapping ─────────────────────────────────────

    async function uploadAndMapRegulation(file) {
        var formData = new FormData();
        formData.append('file', file);

        spinner.classList.remove('d-none');
        uploadBtn.disabled = true;
        hideAllResultPanels();
        statusArea.innerHTML = '<span class="text-muted">⏳ ' + i18n('docimport.regmap.mapping', 'Mapping regulation to architecture…') + '</span>';

        try {
            var resp = await fetchWithCsrf('/api/documents/map-regulation', formData);
            if (!resp.ok) {
                var err = await resp.json().catch(function() { return { error: 'Regulation mapping failed' }; });
                statusArea.innerHTML = '<span class="text-danger">❌ ' + escapeHtml(err.error || 'Regulation mapping failed') + '</span>';
                return;
            }

            var result = await resp.json();
            var matches = result.matches || [];

            statusArea.innerHTML = '<span class="text-success">✅ ' +
                i18n('docimport.regmap.success', 'Found {0} architecture matches from {1} pages.')
                    .replace('{0}', matches.length)
                    .replace('{1}', result.totalPages) + '</span>';

            showRegulationMappingResults(result);
        } catch (e) {
            statusArea.innerHTML = '<span class="text-danger">❌ ' + escapeHtml(e.message) + '</span>';
        } finally {
            spinner.classList.add('d-none');
            uploadBtn.disabled = false;
        }
    }

    // ── Candidate review (rule-based) ──────────────────────────────────────────

    function showCandidateReview(result) {
        reviewPanel.style.display = '';
        countBadge.textContent = result.totalCandidates;
        sourceInfo.innerHTML = '<strong>' + escapeHtml(result.fileName || '') + '</strong> — ' +
            (result.mimeType || '') + ' — ' + result.totalPages + ' page(s)' +
            (result.sourceArtifactId ? ' — Source #' + result.sourceArtifactId : '');

        candidateList.innerHTML = '';
        if (!result.candidates || result.candidates.length === 0) {
            candidateList.innerHTML = '<p class="text-muted">' +
                i18n('docimport.no.candidates', 'No requirement candidates were found in this document.') + '</p>';
            return;
        }

        result.candidates.forEach(function (c, idx) {
            var card = document.createElement('div');
            card.className = 'border rounded p-2 mb-2 bg-white';
            card.innerHTML =
                '<div class="form-check">' +
                '  <input class="form-check-input doc-candidate-cb" type="checkbox" id="cand-' + idx + '" checked data-idx="' + idx + '">' +
                '  <label class="form-check-label" for="cand-' + idx + '">' +
                '    <small class="text-muted">#' + (idx + 1) +
                       (c.sectionHeading ? ' — ' + escapeHtml(c.sectionHeading) : '') +
                       (c.pageNumber ? ' (p.' + c.pageNumber + ')' : '') + '</small>' +
                '  </label>' +
                '</div>' +
                '<div class="small mt-1" style="white-space:pre-wrap;">' + escapeHtml(c.text) + '</div>';
            candidateList.appendChild(card);
        });
    }

    // ── AI extraction results ──────────────────────────────────────────────────

    function showAiExtractionResults(result) {
        if (!aiResultPanel) return;
        aiResultPanel.style.display = '';

        var aiCandidates = result.aiCandidates || [];
        var ruleBasedCount = result.ruleBased ? result.ruleBased.length : 0;

        if (aiComparisonBadge) {
            aiComparisonBadge.innerHTML =
                '🤖 ' + i18n('docimport.ai.comparison', 'AI extracted {0} candidates vs 📝 Rule-based: {1} paragraphs')
                    .replace('{0}', aiCandidates.length)
                    .replace('{1}', ruleBasedCount) +
                ' — <strong>' + escapeHtml(result.fileName || '') + '</strong> (' + result.totalPages + ' pages)';
        }

        if (!aiCandidateList) return;
        aiCandidateList.innerHTML = '';

        if (aiCandidates.length === 0) {
            aiCandidateList.innerHTML = '<p class="text-muted">' +
                i18n('docimport.ai.no.results', 'AI did not extract any requirement candidates.') + '</p>';
            return;
        }

        aiCandidates.forEach(function (c, idx) {
            var confidencePct = Math.round((c.confidence || 0) * 100);
            var badgeClass = confidencePct >= 80 ? 'bg-success' : (confidencePct >= 50 ? 'bg-warning text-dark' : 'bg-secondary');
            var card = document.createElement('div');
            card.className = 'border rounded p-2 mb-2 bg-white';
            card.innerHTML =
                '<div class="form-check">' +
                '  <input class="form-check-input doc-ai-candidate-cb" type="checkbox" id="ai-cand-' + idx + '" checked data-idx="' + idx + '">' +
                '  <label class="form-check-label" for="ai-cand-' + idx + '">' +
                '    <span class="badge bg-info me-1">' + escapeHtml(c.type || 'UNKNOWN') + '</span>' +
                '    <span class="badge ' + badgeClass + ' me-1">' + confidencePct + '%</span>' +
                       (c.sectionRef ? ' <span class="text-muted">' + escapeHtml(c.sectionRef) + '</span>' : '') +
                '  </label>' +
                '</div>' +
                '<div class="small mt-1" style="white-space:pre-wrap;">' + escapeHtml(c.text || '') + '</div>';
            aiCandidateList.appendChild(card);
        });
    }

    // ── Regulation mapping results ─────────────────────────────────────────────

    function showRegulationMappingResults(result) {
        if (!regMapPanel) return;
        regMapPanel.style.display = '';

        var matches = result.matches || [];

        if (regMapSourceInfo) {
            regMapSourceInfo.innerHTML = '<strong>' + escapeHtml(result.fileName || '') + '</strong> — ' +
                result.totalPages + ' page(s) — ' + matches.length + ' match(es)';
        }

        if (!regMapTableBody) return;
        regMapTableBody.innerHTML = '';

        if (matches.length === 0) {
            regMapTableBody.innerHTML = '<tr><td colspan="5" class="text-muted">' +
                i18n('docimport.regmap.no.matches', 'No architecture matches found.') + '</td></tr>';
            return;
        }

        matches.forEach(function (m) {
            var confidencePct = Math.round((m.confidence || 0) * 100);
            var badgeClass = confidencePct >= 80 ? 'bg-success' : (confidencePct >= 50 ? 'bg-warning text-dark' : 'bg-secondary');
            var linkBadge = getLinkTypeBadge(m.linkType);
            var row = document.createElement('tr');
            row.innerHTML =
                '<td><code>' + escapeHtml(m.nodeCode || '') + '</code></td>' +
                '<td>' + linkBadge + '</td>' +
                '<td><span class="badge ' + badgeClass + '">' + confidencePct + '%</span></td>' +
                '<td>' + escapeHtml(m.paragraphRef || '—') + '</td>' +
                '<td class="small">' + escapeHtml(m.reason || '') + '</td>';
            regMapTableBody.appendChild(row);
        });
    }

    function getLinkTypeBadge(linkType) {
        var colors = {
            'MANDATES': 'bg-danger', 'REQUIRES': 'bg-warning text-dark',
            'ENABLES': 'bg-success', 'CONSTRAINS': 'bg-info',
            'REFERENCES': 'bg-secondary'
        };
        var cls = colors[linkType] || 'bg-secondary';
        return '<span class="badge ' + cls + '">' + escapeHtml(linkType || 'UNKNOWN') + '</span>';
    }

    // ── Select / deselect (rule-based) ─────────────────────────────────────────

    if (selectAllBtn) {
        selectAllBtn.addEventListener('click', function () {
            document.querySelectorAll('.doc-candidate-cb').forEach(function (cb) { cb.checked = true; });
        });
    }
    if (deselectBtn) {
        deselectBtn.addEventListener('click', function () {
            document.querySelectorAll('.doc-candidate-cb').forEach(function (cb) { cb.checked = false; });
        });
    }

    // ── Select / deselect (AI) ─────────────────────────────────────────────────

    if (aiSelectAllBtn) {
        aiSelectAllBtn.addEventListener('click', function () {
            document.querySelectorAll('.doc-ai-candidate-cb').forEach(function (cb) { cb.checked = true; });
        });
    }
    if (aiDeselectAllBtn) {
        aiDeselectAllBtn.addEventListener('click', function () {
            document.querySelectorAll('.doc-ai-candidate-cb').forEach(function (cb) { cb.checked = false; });
        });
    }

    // ── Analyze selected candidates (rule-based) ──────────────────────────────

    if (analyzeBtn) {
        analyzeBtn.addEventListener('click', function () {
            if (!currentParseResult || !currentParseResult.candidates) return;
            transferSelectedCandidates('.doc-candidate-cb', currentParseResult.candidates, function (c) {
                return (c.sectionHeading ? c.sectionHeading + ':\n' : '') + c.text;
            });
        });
    }

    // ── Analyze selected AI candidates ────────────────────────────────────────

    if (aiAnalyzeBtn) {
        aiAnalyzeBtn.addEventListener('click', function () {
            if (!currentAiCandidates) return;
            transferSelectedCandidates('.doc-ai-candidate-cb', currentAiCandidates, function (c) {
                return (c.sectionRef ? c.sectionRef + ':\n' : '') + c.text;
            });
        });
    }

    function transferSelectedCandidates(checkboxSelector, candidates, textFn) {
        var selected = [];
        document.querySelectorAll(checkboxSelector + ':checked').forEach(function (cb) {
            var idx = parseInt(cb.dataset.idx, 10);
            if (candidates[idx]) {
                selected.push(candidates[idx]);
            }
        });

        if (selected.length === 0) {
            statusArea.innerHTML = '<span class="text-warning">⚠️ ' +
                i18n('docimport.none.selected', 'Please select at least one candidate.') + '</span>';
            return;
        }

        var combinedText = selected.map(textFn).join('\n\n---\n\n');

        var textarea = document.getElementById('businessText');
        if (textarea) {
            textarea.value = combinedText;
        }

        if (currentParseResult) {
            updateProvenanceDisplay(currentParseResult, selected.length);
        }

        statusArea.innerHTML = '<span class="text-success">✅ ' +
            i18n('docimport.transferred', '{0} candidate(s) transferred to analysis.')
                .replace('{0}', selected.length) + '</span>';

        if (textarea) textarea.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }

    // ── Helper: hide all result panels ─────────────────────────────────────────

    function hideAllResultPanels() {
        if (reviewPanel) reviewPanel.style.display = 'none';
        if (aiResultPanel) aiResultPanel.style.display = 'none';
        if (regMapPanel) regMapPanel.style.display = 'none';
    }

    // ── Provenance display ─────────────────────────────────────────────────────

    function updateProvenanceDisplay(result, candidateCount) {
        if (!provenanceContent) return;
        provenanceContent.innerHTML =
            '<div class="small">' +
            '<strong>' + i18n('provenance.source', 'Source') + ':</strong> ' + escapeHtml(result.fileName || '') + '<br>' +
            '<strong>' + i18n('provenance.type', 'Type') + ':</strong> ' +
                escapeHtml(document.getElementById('docImportType')?.selectedOptions?.[0]?.text || '') + '<br>' +
            '<strong>' + i18n('provenance.artifact', 'Artifact ID') + ':</strong> ' + (result.sourceArtifactId || '—') + '<br>' +
            '<strong>' + i18n('provenance.candidates', 'Candidates') + ':</strong> ' + candidateCount + ' selected<br>' +
            '<strong>' + i18n('provenance.pages', 'Pages') + ':</strong> ' + result.totalPages +
            '</div>';
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    async function fetchWithCsrf(url, formData) {
        var csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
        var csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content || 'X-CSRF-TOKEN';
        var headers = {};
        if (csrfToken) headers[csrfHeader] = csrfToken;

        return fetch(url, {
            method: 'POST',
            headers: headers,
            body: formData
        });
    }

    function escapeHtml(text) {
        var div = document.createElement('div');
        div.appendChild(document.createTextNode(text));
        return div.innerHTML;
    }

    function i18n(key, fallback) {
        if (typeof window.TaxonomyI18n !== 'undefined' && window.TaxonomyI18n[key]) {
            return window.TaxonomyI18n[key];
        }
        return fallback || key;
    }
})();
