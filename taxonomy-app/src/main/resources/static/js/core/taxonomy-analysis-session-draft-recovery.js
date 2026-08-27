/* taxonomy-analysis-session-draft-recovery.js – reconcile ambiguous optimistic writes */
(function () {
    'use strict';
    var C = window.__TaxonomyAnalysisSessionContext;
    if (!C || C.draftWriteRecoveryInstalled) return;

    var nativeJsonRequest = C.jsonRequest;
    var comparable = C.comparable;
    var RECONCILIATION_DELAYS_MS = [0, 100, 250, 500, 1000, 2000, 4000];

    function methodOf(options) {
        return String((options && options.method) || 'GET').toUpperCase();
    }

    function isDraftPut(url, options) {
        var resolved = C.requestUrl(url);
        return methodOf(options) === 'PUT'
            && resolved
            && resolved.origin === window.location.origin
            && /\/api\/analysis-drafts\/[^/]+$/.test(resolved.pathname);
    }

    function requestBody(options) {
        try {
            return options && options.body ? JSON.parse(options.body) : null;
        } catch (error) {
            return null;
        }
    }

    function isAmbiguousWriteFailure(error) {
        if (!error || error.code === 'ABORTED') return false;
        if (error.code === 'TIMEOUT' || error.code === 'NETWORK_ERROR') return true;
        if (error.status === 502 || error.status === 503 || error.status === 504) return true;
        return error.status === 0 && !error.code;
    }

    function pause(delay) {
        if (!delay) return Promise.resolve();
        return new Promise(function (resolve) {
            window.setTimeout(resolve, delay);
        });
    }

    function recoveryConflict(originalError, expectedVersion, currentVersion) {
        var error = new Error(
            'Analysis draft changed while a previous write outcome was unknown');
        error.name = 'AnalysisDraftRecoveryConflict';
        error.status = 409;
        error.code = 'DRAFT_RECOVERY_CONFLICT';
        error.requestId = originalError.requestId || null;
        error.expectedVersion = expectedVersion;
        error.currentVersion = currentVersion;
        error.cause = originalError;
        return error;
    }

    function dispatchRecovered(view, expectedVersion, originalError, attempt) {
        document.dispatchEvent(new CustomEvent('taxonomy:analysis-draft-write-reconciled', {
            detail: {
                workspaceId: C.runtime.workspaceId,
                expectedVersion: expectedVersion,
                version: view.version,
                requestId: originalError.requestId || null,
                attempts: attempt + 1
            }
        }));
    }

    function reconcile(
            endpoint, serializedPayload, expectedVersion, originalError, attempt) {
        var delay = RECONCILIATION_DELAYS_MS[attempt];
        return pause(delay).then(function () {
            return nativeJsonRequest(endpoint, { method: 'GET' });
        }).then(function (view) {
            if (view && view.payload
                    && comparable(view.payload) === serializedPayload) {
                dispatchRecovered(view, expectedVersion, originalError, attempt);
                return view;
            }

            var currentVersion = view && view.version !== undefined
                ? Number(view.version) : null;
            if (view && (expectedVersion === null
                    || currentVersion !== Number(expectedVersion))) {
                throw recoveryConflict(
                    originalError, expectedVersion, currentVersion);
            }

            if (attempt + 1 < RECONCILIATION_DELAYS_MS.length) {
                return reconcile(
                    endpoint, serializedPayload, expectedVersion,
                    originalError, attempt + 1);
            }
            throw originalError;
        }, function (readError) {
            if (attempt + 1 < RECONCILIATION_DELAYS_MS.length) {
                return reconcile(
                    endpoint, serializedPayload, expectedVersion,
                    originalError, attempt + 1);
            }
            if (window.console && typeof window.console.warn === 'function') {
                window.console.warn(
                    '[Taxonomy] Could not reconcile ambiguous draft write', {
                        requestId: originalError.requestId || null,
                        expectedVersion: expectedVersion,
                        readError: readError
                    });
            }
            throw originalError;
        });
    }

    C.jsonRequest = function (url, options) {
        if (!isDraftPut(url, options)) {
            return nativeJsonRequest(url, options);
        }
        var body = requestBody(options);
        if (!body || !Object.prototype.hasOwnProperty.call(body, 'payload')) {
            return nativeJsonRequest(url, options);
        }
        var expectedVersion = body.expectedVersion === null
                || body.expectedVersion === undefined
            ? null : Number(body.expectedVersion);
        var serializedPayload = comparable(body.payload);

        return nativeJsonRequest(url, options).catch(function (error) {
            if (!isAmbiguousWriteFailure(error)) throw error;
            return reconcile(
                url, serializedPayload, expectedVersion, error, 0);
        });
    };
    C.draftWriteRecoveryInstalled = true;
}());
