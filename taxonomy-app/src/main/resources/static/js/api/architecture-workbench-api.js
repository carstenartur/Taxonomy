/* Server boundary for the architecture workbench. */
window.ArchitectureWorkbenchApi = (function () {
    'use strict';

    function getJson(url, signal, source) {
        const options = {signal: signal};
        if (source && (source.workspaceScopeKey !== undefined || source.workspaceId !== undefined)) {
            const workspace = source.workspaceScopeKey || source.workspaceId || '__shared__';
            options.pinnedWorkspaceId = workspace === '__shared__' ? '' : String(workspace);
        }
        return window.TaxonomyApiClient.getJson(url, options);
    }

    function positiveInteger(value, field) {
        const parsed = Number(value);
        if (!Number.isSafeInteger(parsed) || parsed <= 0) {
            throw new TypeError(field + ' must be a positive integer');
        }
        return parsed;
    }

    function snapshot(value) {
        const normalized = String(value || '').trim();
        if (!normalized || !/^[A-Za-z0-9._-]{1,128}$/.test(normalized)) {
            throw new TypeError('snapshotId is invalid');
        }
        return encodeURIComponent(normalized);
    }

    function scoped(path, source) {
        if (source) {
            const target = new URL(path, window.location.href);
            ['repositoryId','workspaceScopeKey','workspaceId','branch'].forEach(function(key){if(source[key]!==undefined && source[key]!==null)target.searchParams.set(key,source[key]);});
            if(source.workspaceScopeKey)target.searchParams.set('workspaceId',source.workspaceScopeKey==='__shared__'?'':source.workspaceScopeKey);
            return target.pathname+target.search;
        }
        return window.TaxonomyArchitectureWorkflow?.scopeUrl(path) || path;
    }

    function base(projectId, snapshotId, suffix, source, withLanguage) {
        const path = '/api/projects/' + positiveInteger(projectId, 'projectId')
            + '/architecture-workbench/' + snapshot(snapshotId) + (suffix || '');
        const url = window.TaxonomyI18n?.resolveUrl?.(path) || path;
        return scoped(withLanguage ? withDocumentLanguage(url) : url, source);
    }

    function withDocumentLanguage(url) {
        const language = window.TaxonomyContentLanguage?.documentLanguage(null);
        return language ? url + '?language=' + encodeURIComponent(language) : url;
    }

    function wordUrl(projectId, snapshotId, report, source) {
        const path = '/api/projects/' + positiveInteger(projectId, 'projectId')
            + '/snapshots/' + snapshot(snapshotId) + '/' + report + '/docx';
        const url = window.TaxonomyI18n?.resolveUrl?.(path) || path;
        return scoped(withDocumentLanguage(url), source);
    }

    return {
        architectureWordUrl: function (projectId, snapshotId, source) {
            return wordUrl(projectId, snapshotId, 'architecture-report', source);
        },
        decisionWordUrl: function (projectId, snapshotId, source) {
            return wordUrl(projectId, snapshotId, 'decision-report', source);
        },
        load: function (projectId, snapshotId, source, signal) {
            return getJson(base(projectId, snapshotId, null, source), signal, source);
        },
        svgUrl: function (projectId, snapshotId, source) {
            return base(projectId, snapshotId, '.svg', source, true);
        },
        pdfUrl: function (projectId, snapshotId, source) {
            return base(projectId, snapshotId, '.pdf', source, true);
        },
        archiMateUrl: function (projectId, snapshotId, source) {
            return base(projectId, snapshotId, '.archimate.xml', source);
        },
        visioBundleUrl: function (projectId, snapshotId, source) {
            return base(projectId, snapshotId, '.visio.zip', source);
        },
        archiMateBundleUrl: function (projectId, snapshotId, source) {
            return base(projectId, snapshotId, '.archimate.zip', source);
        },
        visioUrl: function (projectId, snapshotId, source) {
            return base(projectId, snapshotId, '.vsdx', source);
        }
    };
}());
