/* Observe a durable analysis through the canonical authenticated transport. */
window.AnalysisAdapterApi = (function () {
    'use strict';
    var uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;

    async function follow(response, original, init, options, current) {
        if (response.status !== 202) return response;
        var client = window.TaxonomyApiClient;
        var signal = options.signal || init.signal;
        var deadline = Date.now() + (options.timeoutMillis > 0 ? options.timeoutMillis : 30 * 60 * 1000);
        function guard() {
            if (!current() || signal?.aborted || options.isCurrent && !options.isCurrent())
                throw new client.ApiError('Request context was superseded', 0, original, null, {code:'ABORTED'});
            if (Date.now() >= deadline)
                throw new client.ApiError('Analysis observation timed out', 0, original, null, {code:'TIMEOUT', retryable:true});
        }
        var id = response.headers.get('X-Analysis-Operation-Id');
        var commit = response.headers.get('X-Analysis-Source-Commit');
        if (!uuid.test(id || '') || !/^[a-f0-9]{40}$/.test(commit || '')) throw new Error('Invalid analysis operation');
        var originalUrl = new URL(original, window.location.href), pins = new URLSearchParams();
        ['repositoryId','workspaceId','branch'].forEach(function (key) {
            if (originalUrl.searchParams.has(key)) pins.set(key,originalUrl.searchParams.get(key));
        });
        var path = '/api/analysis-adapters/' + id;
        var suffix = pins.size ? '?' + pins : '';
        var policy = Object.assign({}, options, {retries:0, observeResponse:undefined});
        var cleanup, lastSequence = -1;
        try {
            for (;;) {
                guard();
                if (response.headers.get('X-Analysis-Operation-Id') !== id
                        || response.headers.get('X-Analysis-Source-Commit') !== commit) throw new Error('Analysis source changed');
                if (response.status !== 202) return response;
                var state = await response.json();
                guard();
                if (state.operationId !== id || state.sourceCommit !== commit || state.state !== 'RUNNING'
                        || !Number.isSafeInteger(state.sequence) || state.sequence < lastSequence)
                    throw new Error('Invalid analysis observation');
                if (lastSequence < 0 && options.onPending) {
                    cleanup = options.onPending(function () {
                        guard();
                        return client.request(path + '/cancel' + suffix, {method:'POST'}, policy);
                    });
                }
                lastSequence = state.sequence;
                var seconds = Number(response.headers.get('Retry-After')) || 2;
                var delay = Math.min(5000, Math.max(200, seconds * 1000), deadline - Date.now());
                await new Promise(function (resolve, reject) {
                    var timer = setTimeout(done, delay);
                    function done() { signal?.removeEventListener('abort', abort); resolve(); }
                    function abort() {
                        clearTimeout(timer);
                        signal?.removeEventListener('abort', abort);
                        reject(new client.ApiError('Request was cancelled', 0, original, null, {code:'ABORTED'}));
                    }
                    if (signal?.aborted) abort();
                    else signal?.addEventListener('abort', abort, {once:true});
                });
                guard();
                response = await client.request(path + suffix, {method:'GET', cache:'no-store'},
                        Object.assign({}, policy, {timeoutMillis:Math.max(1, deadline - Date.now())}));
            }
        } finally { if (typeof cleanup === 'function') cleanup(); }
    }

    function describe(error) {
        var key = {ANALYSIS_EXPIRED:'expired', CANCELLED:'cancelled', STALE_SOURCE:'stale',
            COMMAND_CONFLICT:'stale', OUTCOME_UNKNOWN:'unknown', ANALYSIS_FAILED:'failed', RESULT_TOO_LARGE:'failed'}[error?.responseBody?.code];
        if (key && window.TaxonomyI18n?.t) error.message = window.TaxonomyI18n.t('analysis.adapter.' + key);
        return error;
    }

    function pendingButton(anchor) {
        return function (cancel) {
            var button = document.createElement('button');
            button.type = 'button'; button.className = 'btn btn-sm btn-outline-secondary';
            button.textContent = window.TaxonomyI18n.t('analysis.adapter.cancel');
            button.addEventListener('click', function (event) {
                event.stopPropagation();
                button.disabled = true;
                cancel().catch(function (error) {
                    if (error?.responseBody?.code !== 'CANCELLED') button.disabled = false;
                });
            });
            anchor.after(button);
            return function () { button.remove(); };
        };
    }

    function request(url, init, options) {
        return window.TaxonomyApiClient.request(url, init, Object.assign({}, options, {observeResponse:follow,operationError:describe}));
    }

    return Object.freeze({request:request, follow:follow, describe:describe, pendingButton:pendingButton});
}());
