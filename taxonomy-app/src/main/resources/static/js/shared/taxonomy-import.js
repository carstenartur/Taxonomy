/* taxonomy-import.js – Framework Import functionality */

(function () {
    'use strict';
    var t = TaxonomyI18n.t;

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
                select.innerHTML = '<option value="">' + t('import.select.profile') + '</option>';
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
            '<strong>' + t('import.elements.strong') + '</strong> ' + Array.from(profile.supportedElementTypes).join(', ') +
            '<br><strong>' + t('import.relations.strong') + '</strong> ' + Array.from(profile.supportedRelationTypes).join(', ') +
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

        setImportStatus(t('import.previewing'), 'info');

        fetch('/api/import/preview/' + encodeURIComponent(profileId), {
            method: 'POST',
            body: formData
        })
        .then(function (res) { return res.json(); })
        .then(function (result) {
            renderResult(result, true);
        })
        .catch(function (err) {
            setImportStatus(t('import.preview.failed', err.message), 'danger');
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

        setImportStatus(t('import.importing'), 'info');

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
            setImportStatus(t('import.import.failed', err.message), 'danger');
        });
    }

    function getSelectedProfile() {
        var select = document.getElementById('importProfileSelect');
        if (!select || !select.value) {
            alert(t('import.select.profile.alert'));
            return null;
        }
        return select.value;
    }

    function getSelectedFile() {
        var fileInput = document.getElementById('importFrameworkFile');
        if (!fileInput || !fileInput.files || !fileInput.files[0]) {
            alert(t('import.select.file.alert'));
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
        var statusText = result.success ? (isPreview ? t('import.preview.ok') : t('import.import.success')) : t('import.import.fail');

        var html = '<div class="alert alert-' + statusClass + ' py-2 small mb-2">' +
            '<strong>' + statusText + '</strong> — ' + (result.profileDisplayName || result.profileId) +
            '</div>';

        html += '<table class="table table-sm table-bordered small mb-2">' +
            '<tr><td>' + t('import.elements.label') + '</td><td>' + result.elementsTotal + ' / ' + result.elementsMapped + '</td></tr>' +
            '<tr><td>' + t('import.relations.label') + '</td><td>' + result.relationsTotal + ' / ' + result.relationsMapped + '</td></tr>';

        if (!isPreview) {
            html += '<tr><td>' + t('import.relations.created') + '</td><td>' + result.relationsCreated + '</td></tr>' +
                '<tr><td>' + t('import.hypotheses.created') + '</td><td>' + result.hypothesesCreated + '</td></tr>';
        }
        html += '</table>';

        if (result.unmappedTypes && result.unmappedTypes.length > 0) {
            html += '<details class="small mb-2"><summary class="text-warning">' + t('import.unmapped.types', result.unmappedTypes.length) + '</summary>' +
                '<ul class="mb-0">' + result.unmappedTypes.map(function (typ) { return '<li>' + escapeHtml(typ) + '</li>'; }).join('') + '</ul></details>';
        }

        if (result.warnings && result.warnings.length > 0) {
            html += '<details class="small mb-2"><summary class="text-muted">' + t('import.warnings', result.warnings.length) + '</summary>' +
                '<ul class="mb-0">' + result.warnings.slice(0, 20).map(function (w) { return '<li>' + escapeHtml(w) + '</li>'; }).join('') + '</ul>';
            if (result.warnings.length > 20) {
                html += '<p class="text-muted">' + t('import.warnings.more', result.warnings.length - 20) + '</p>';
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
