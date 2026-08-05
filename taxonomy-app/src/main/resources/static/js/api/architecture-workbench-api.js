/* Server boundary for the architecture workbench. */
window.ArchitectureWorkbenchApi = (function () {
    'use strict';

    async function getJson(url) {
        const response = await fetch(url, {
            method: 'GET',
            headers: { Accept: 'application/json' },
            credentials: 'same-origin',
            cache: 'no-store'
        });
        if (response.ok) return response.json();
        const problem = await response.json().catch(function () { return null; });
        const message = problem?.detail || problem?.message || ('HTTP ' + response.status);
        const error = new Error(message);
        error.status = response.status;
        throw error;
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

    function base(projectId, snapshotId) {
        return '/api/projects/' + positiveInteger(projectId, 'projectId')
            + '/architecture-workbench/' + snapshot(snapshotId);
    }

    return {
        load: function (projectId, snapshotId) {
            return getJson(base(projectId, snapshotId));
        },
        svgUrl: function (projectId, snapshotId) {
            return base(projectId, snapshotId) + '.svg';
        },
        pdfUrl: function (projectId, snapshotId) {
            return base(projectId, snapshotId) + '.pdf';
        }
    };
}());
