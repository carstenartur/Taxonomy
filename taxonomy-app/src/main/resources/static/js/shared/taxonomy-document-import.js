/**
 * taxonomy-document-import.js
 *
 * Handles document upload (PDF/DOCX), candidate extraction, review,
 * and handoff to the existing analysis workflow.
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

    // Provenance panel elements
    const provenanceContent = document.getElementById('provenanceContent');

    let currentParseResult = null;

    if (!uploadBtn) return; // Guard: elements not present

    // ── Upload handler ─────────────────────────────────────────────────────────

    uploadBtn.addEventListener('click', async function () {
        const file = fileInput.files[0];
        if (!file) {
            statusArea.innerHTML = '<span class="text-warning">⚠️ ' + i18n('docimport.select.file', 'Please select a file.') + '</span>';
            return;
        }

        const formData = new FormData();
        formData.append('file', file);
        formData.append('title', titleInput.value || '');
        formData.append('sourceType', typeSelect.value || 'REGULATION');

        spinner.classList.remove('d-none');
        uploadBtn.disabled = true;
        statusArea.innerHTML = '<span class="text-muted">⏳ ' + i18n('docimport.uploading', 'Uploading and parsing…') + '</span>';

        try {
            const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
            const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content || 'X-CSRF-TOKEN';
            const headers = {};
            if (csrfToken) headers[csrfHeader] = csrfToken;

            const resp = await fetch('/api/documents/upload', {
                method: 'POST',
                headers: headers,
                body: formData
            });

            if (!resp.ok) {
                const err = await resp.json().catch(() => ({ error: 'Upload failed' }));
                statusArea.innerHTML = '<span class="text-danger">❌ ' + (err.error || 'Upload failed') + '</span>';
                return;
            }

            const result = await resp.json();
            currentParseResult = result;

            statusArea.innerHTML = '<span class="text-success">✅ ' +
                i18n('docimport.success', 'Extracted {0} candidates from {1} pages.')
                    .replace('{0}', result.totalCandidates)
                    .replace('{1}', result.totalPages) + '</span>';

            showCandidateReview(result);
        } catch (e) {
            statusArea.innerHTML = '<span class="text-danger">❌ ' + e.message + '</span>';
        } finally {
            spinner.classList.add('d-none');
            uploadBtn.disabled = false;
        }
    });

    // ── Candidate review ───────────────────────────────────────────────────────

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
            const card = document.createElement('div');
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

    // ── Select / deselect ──────────────────────────────────────────────────────

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

    // ── Analyze selected candidates ────────────────────────────────────────────

    if (analyzeBtn) {
        analyzeBtn.addEventListener('click', function () {
            if (!currentParseResult || !currentParseResult.candidates) return;

            const selected = [];
            document.querySelectorAll('.doc-candidate-cb:checked').forEach(function (cb) {
                const idx = parseInt(cb.dataset.idx, 10);
                if (currentParseResult.candidates[idx]) {
                    selected.push(currentParseResult.candidates[idx]);
                }
            });

            if (selected.length === 0) {
                statusArea.innerHTML = '<span class="text-warning">⚠️ ' +
                    i18n('docimport.none.selected', 'Please select at least one candidate.') + '</span>';
                return;
            }

            // Combine selected candidates into a single requirement text
            const combinedText = selected.map(function (c) {
                return (c.sectionHeading ? c.sectionHeading + ':\n' : '') + c.text;
            }).join('\n\n---\n\n');

            // Put the combined text into the analysis textarea
            var textarea = document.getElementById('businessText');
            if (textarea) {
                textarea.value = combinedText;
            }

            // Show provenance info
            updateProvenanceDisplay(currentParseResult, selected.length);

            // Switch to analysis card and optionally trigger analysis
            statusArea.innerHTML = '<span class="text-success">✅ ' +
                i18n('docimport.transferred', '{0} candidate(s) transferred to analysis.')
                    .replace('{0}', selected.length) + '</span>';

            // Scroll up to the analysis textarea
            if (textarea) textarea.scrollIntoView({ behavior: 'smooth', block: 'center' });
        });
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
