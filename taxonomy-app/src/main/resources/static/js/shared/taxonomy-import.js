/* taxonomy-import.js – Framework Import functionality */

(function () {
    'use strict';

    var profilesCache = null;

    /**
     * Load available import profiles from the server.
     */
    function loadProfiles() {
        fetch('/api/import/profiles')
            .then(function (res) { return res.json(); })
            .then(function (profiles) {
                profilesCache = profiles;
                var select = document.getElementById('importProfileSelect');
                if (!select) return;
                select.innerHTML = '<option value="">-- Select Profile --</option>';
                profiles.forEach(function (p) {
                    var opt = document.createElement('option');
                    opt.value = p.profileId;
                    opt.textContent = p.displayName + ' (' + p.acceptedFileFormat + ')';
                    select.appendChild(opt);
                });
            })
            .catch(function (err) {
                console.warn('Failed to load import profiles:', err);
            });
    }

    /**
     * Show profile details when a profile is selected.
     */
    function onProfileChange() {
        var select = document.getElementById('importProfileSelect');
        var infoDiv = document.getElementById('importProfileInfo');
        var fileInput = document.getElementById('importFrameworkFile');
        if (!select || !infoDiv) return;

        var profileId = select.value;
        if (!profileId || !profilesCache) {
            infoDiv.innerHTML = '';
            return;
        }

        var profile = profilesCache.find(function (p) { return p.profileId === profileId; });
        if (!profile) return;

        // Set accepted file types
        if (fileInput) {
            var formatMap = { xml: '.xml', csv: '.csv', dsl: '.dsl,.txt', xlsx: '.xlsx,.xls' };
            fileInput.accept = formatMap[profile.acceptedFileFormat] || '*';
        }

        infoDiv.innerHTML =
            '<small class="text-muted">' +
            '<strong>Elements:</strong> ' + Array.from(profile.supportedElementTypes).join(', ') +
            '<br><strong>Relations:</strong> ' + Array.from(profile.supportedRelationTypes).join(', ') +
            '</small>';
    }

    /**
     * Preview an import (dry run).
     */
    function previewImport() {
        var profileId = getSelectedProfile();
        var file = getSelectedFile();
        if (!profileId || !file) return;

        var formData = new FormData();
        formData.append('file', file);

        setImportStatus('Previewing...', 'info');

        fetch('/api/import/preview/' + encodeURIComponent(profileId), {
            method: 'POST',
            body: formData
        })
        .then(function (res) { return res.json(); })
        .then(function (result) {
            renderResult(result, true);
        })
        .catch(function (err) {
            setImportStatus('Preview failed: ' + err.message, 'danger');
        });
    }

    /**
     * Execute a full import.
     */
    function executeImport() {
        var profileId = getSelectedProfile();
        var file = getSelectedFile();
        if (!profileId || !file) return;

        var branch = document.getElementById('importBranch');
        var branchValue = branch ? branch.value || 'main' : 'main';

        var formData = new FormData();
        formData.append('file', file);

        setImportStatus('Importing...', 'info');

        fetch('/api/import/' + encodeURIComponent(profileId) + '?branch=' + encodeURIComponent(branchValue), {
            method: 'POST',
            body: formData
        })
        .then(function (res) { return res.json(); })
        .then(function (response) {
            var result = response.result || response;
            renderResult(result, false);
        })
        .catch(function (err) {
            setImportStatus('Import failed: ' + err.message, 'danger');
        });
    }

    function getSelectedProfile() {
        var select = document.getElementById('importProfileSelect');
        if (!select || !select.value) {
            alert('Please select an import profile.');
            return null;
        }
        return select.value;
    }

    function getSelectedFile() {
        var fileInput = document.getElementById('importFrameworkFile');
        if (!fileInput || !fileInput.files || !fileInput.files[0]) {
            alert('Please select a file to import.');
            return null;
        }
        return fileInput.files[0];
    }

    function setImportStatus(message, type) {
        var div = document.getElementById('importResultArea');
        if (!div) return;
        div.innerHTML = '<div class="alert alert-' + type + ' py-2 small">' + message + '</div>';
    }

    function renderResult(result, isPreview) {
        var div = document.getElementById('importResultArea');
        if (!div) return;

        var statusClass = result.success ? 'success' : 'danger';
        var statusText = result.success ? (isPreview ? 'Preview OK' : 'Import Successful') : 'Failed';

        var html = '<div class="alert alert-' + statusClass + ' py-2 small mb-2">' +
            '<strong>' + statusText + '</strong> — ' + (result.profileDisplayName || result.profileId) +
            '</div>';

        html += '<table class="table table-sm table-bordered small mb-2">' +
            '<tr><td>Elements (total/mapped)</td><td>' + result.elementsTotal + ' / ' + result.elementsMapped + '</td></tr>' +
            '<tr><td>Relations (total/mapped)</td><td>' + result.relationsTotal + ' / ' + result.relationsMapped + '</td></tr>';

        if (!isPreview) {
            html += '<tr><td>Relations created</td><td>' + result.relationsCreated + '</td></tr>' +
                '<tr><td>Hypotheses created</td><td>' + result.hypothesesCreated + '</td></tr>';
        }
        html += '</table>';

        if (result.unmappedTypes && result.unmappedTypes.length > 0) {
            html += '<details class="small mb-2"><summary class="text-warning">Unmapped types (' + result.unmappedTypes.length + ')</summary>' +
                '<ul class="mb-0">' + result.unmappedTypes.map(function (t) { return '<li>' + escapeHtml(t) + '</li>'; }).join('') + '</ul></details>';
        }

        if (result.warnings && result.warnings.length > 0) {
            html += '<details class="small mb-2"><summary class="text-muted">Warnings (' + result.warnings.length + ')</summary>' +
                '<ul class="mb-0">' + result.warnings.slice(0, 20).map(function (w) { return '<li>' + escapeHtml(w) + '</li>'; }).join('') + '</ul>';
            if (result.warnings.length > 20) {
                html += '<p class="text-muted">...and ' + (result.warnings.length - 20) + ' more</p>';
            }
            html += '</details>';
        }

        div.innerHTML = html;
    }

    var escapeHtml = TaxonomyUtils.escapeHtml;

    // ── Initialization ─────────────────────────────────────────────────────────

    document.addEventListener('DOMContentLoaded', function () {
        loadProfiles();

        var profileSelect = document.getElementById('importProfileSelect');
        if (profileSelect) {
            profileSelect.addEventListener('change', onProfileChange);
        }

        var previewBtn = document.getElementById('importPreviewBtn');
        if (previewBtn) {
            previewBtn.addEventListener('click', previewImport);
        }

        var importBtn = document.getElementById('importExecuteBtn');
        if (importBtn) {
            importBtn.addEventListener('click', executeImport);
        }
    });

})();
