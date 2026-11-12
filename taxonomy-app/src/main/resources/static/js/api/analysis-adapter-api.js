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
        if (!uuid.test(id || '') || !/^(?:[a-f0-9]{40}|[a-f0-9]{64})$/.test(commit || '')) throw new Error('Invalid analysis operation');
        var saved = options.observerToken && savedDocument();
        if (saved && saved.token === options.observerToken) {
            saved.id = id; saved.source = commit;
            try { window.sessionStorage.setItem(storageKey(),JSON.stringify(saved)); } catch (ignored) { /* Keep reading. */ }
        }
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

    function observationPath(original, token) {
        var url = new URL(original, window.location.href), pins = new URLSearchParams();
        ['repositoryId','workspaceId','branch'].forEach(function (key) {
            if (url.searchParams.has(key)) pins.set(key, url.searchParams.get(key));
        });
        return '/api/analysis-adapters/observations/' + token + (pins.size ? '?' + pins : '');
    }

    // Persist only an opaque observation and the document action in this tab. The server binds
    // each lookup to the current account, repository, workspace, branch and original source.
    function storageKey() {
        var runtime = window.__TaxonomyAnalysisSessionContext?.runtime;
        var chrome = window.TaxonomyAppChrome?.getContext?.()?.architecture || {};
        var location = new URL(window.location.href);
        return 'taxonomy.document.observation.' + JSON.stringify([location.pathname, runtime?.workspaceId,
            ...['repositoryId','workspaceId','branch'].map(key => location.searchParams.get(key)),
            chrome.repositoryId, chrome.workspaceScopeKey, chrome.branch]);
    }

    function savedDocument() {
        try {
            var item = JSON.parse(window.sessionStorage.getItem(storageKey()));
            if (uuid.test(item?.token || '') && ['extract-ai','map-regulation'].includes(item.action)
                    && Date.now() - item.created < 86400000) return item;
        } catch (ignored) { /* Storage may be disabled for this browser. */ }
        return null;
    }

    function forgetDocument(token) {
        try {
            if (savedDocument()?.token === token) window.sessionStorage.removeItem(storageKey());
        } catch (ignored) { /* The request itself does not require browser storage. */ }
    }

    function newToken() {
        if (window.crypto.randomUUID) return window.crypto.randomUUID();
        var bytes = window.crypto.getRandomValues(new Uint8Array(16));
        bytes[6] = (bytes[6] & 15) | 64;
        bytes[8] = (bytes[8] & 63) | 128;
        var hex = Array.from(bytes, b => b.toString(16).padStart(2,'0')).join('');
        return [hex.slice(0,8),hex.slice(8,12),hex.slice(12,16),hex.slice(16,20),hex.slice(20)].join('-');
    }

    async function lookup(original, token, options, current, stopped, waitForAdmission) {
        var client = window.TaxonomyApiClient, until = Date.now() + 30000, delay = waitForAdmission ? 350 : 0;
        for (;;) {
            if (delay) await new Promise(resolve => setTimeout(resolve, delay));
            if (stopped() || !current() || options.isCurrent && !options.isCurrent())
                throw new client.ApiError('Request context was superseded',0,original,null,{code:'ABORTED'});
            try {
                return await client.request(observationPath(original, token), {method:'GET',cache:'no-store'},
                    Object.assign({},options,{retries:0,observeAdmission:undefined,observeResponse:undefined}));
            } catch (error) {
                if (error.status !== 404 || Date.now() >= until) throw error;
                delay = Math.min(2000,Math.max(350,delay * 2));
            }
        }
    }

    async function admission(send, url, init, options, current) {
        if (!options.onPending || !window.crypto?.getRandomValues) return send(url, init, options);
        var token = newToken(), headers = new Headers(init.headers), stopped = false;
        headers.set('X-Analysis-Observer', token);
        if (options.documentAction) {
            try {
                window.sessionStorage.setItem(storageKey(),JSON.stringify({token,action:options.documentAction,created:Date.now()}));
            } catch (ignored) { /* In-flight observation still works without persistent storage. */ }
            options.observerToken = token;
            if (options.onObserver) options.onObserver(token);
        }
        var original = send(url,Object.assign({},init,{headers}),Object.assign({},options,{retries:0}));
        // An upload can take longer than the admission lookup window. The initial request
        // remains the authority if parsing has not admitted any provider work yet.
        var observer = lookup(url,token,options,current,() => stopped,true).catch(error => {
            if (error.status === 404) return original;
            throw error;
        });
        try { return await Promise.race([original,observer]); }
        finally { stopped = true; }
    }

    async function resumeDocument(saved, options) {
        var path = '/api/documents/' + saved.action;
        var current = window.TaxonomyApiClient.captureContext(options);
        try {
            var first;
            options.observerToken = saved.token;
            if (uuid.test(saved.id || '')) {
                first = await window.TaxonomyApiClient.request('/api/analysis-adapters/' + saved.id,{method:'GET',cache:'no-store'},options);
                if (first.headers.get('X-Analysis-Operation-Id') !== saved.id
                        || first.headers.get('X-Analysis-Source-Commit') !== saved.source) throw new Error('Analysis source changed');
            } else first = await lookup(path,saved.token,options,current,() => false,false);
            var response = await follow(first,path,{},options,current);
            var result = await response.json();
            if (current() && options.isCurrent()) forgetDocument(saved.token);
            return result;
        } catch (error) {
            if (current() && options.isCurrent() && error.status >= 400 && error.status < 500) forgetDocument(saved.token);
            throw describe(error);
        }
    }

    function request(url, init, options) {
        var policy = Object.assign({},options,{observeAdmission:admission,observeResponse:follow,operationError:describe});
        return window.TaxonomyApiClient.request(url,init,policy).catch(function (error) {
            if (policy.observerToken && error.status >= 400 && error.status < 500) forgetDocument(policy.observerToken);
            throw error;
        });
    }

    return Object.freeze({request:request,follow:follow,describe:describe,pendingButton:pendingButton,
        admission:admission,savedDocument:savedDocument,forgetDocument:forgetDocument,resumeDocument:resumeDocument});
}());
