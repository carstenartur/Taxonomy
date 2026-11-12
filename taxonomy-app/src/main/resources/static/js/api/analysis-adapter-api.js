/* Observe a durable analysis through the canonical authenticated transport. */
window.AnalysisAdapterApi = (function () {
    'use strict';
    var uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
    var gitCommit = /^(?:[a-f0-9]{40}|[a-f0-9]{64})$/;

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
        if (!uuid.test(id || '') || !gitCommit.test(commit || '')) throw new Error('Invalid analysis operation');
        rememberIdentity(options,id,commit);
        var path = '/api/analysis-adapters/' + id;
        var policy = Object.assign({}, options, {retries:0, observeResponse:undefined});
        var cleanup, lastSequence = -1;
        try {
            for (;;) {
                guard();
                if (response.headers.get('X-Analysis-Operation-Id') !== id
                        || response.headers.get('X-Analysis-Source-Commit') !== commit) throw staleSource();
                if (response.status !== 202) return response;
                var state = await response.json();
                guard();
                if (state.operationId !== id || state.sourceCommit !== commit || state.state !== 'RUNNING'
                        || !Number.isSafeInteger(state.sequence) || state.sequence < lastSequence)
                    throw new Error('Invalid analysis observation');
                if (lastSequence < 0 && options.onPending) {
                    cleanup = options.onPending(function () {
                        guard();
                        return client.request(scopedPath(original,path + '/cancel'), {method:'POST'}, policy);
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
                response = await client.request(scopedPath(original,path), {method:'GET', cache:'no-store'},
                        Object.assign({}, policy, {timeoutMillis:Math.max(1, deadline - Date.now())}));
            }
        } finally { if (typeof cleanup === 'function') cleanup(); }
    }

    function describe(error) {
        var code = error?.responseBody?.code || error?.responseBody?.error || error?.code;
        var key = {ANALYSIS_EXPIRED:'expired', CANCELLED:'cancelled', STALE_SOURCE:'stale',
            COMMAND_CONFLICT:'stale', OUTCOME_UNKNOWN:'unknown', ANALYSIS_FAILED:'failed', RESULT_TOO_LARGE:'failed',
            AI_EXTRACTION_FAILED:'failed', REGULATION_MAPPING_FAILED:'failed'}[code];
        if (key && window.TaxonomyI18n?.t) error.message = window.TaxonomyI18n.t('analysis.adapter.' + key);
        return error;
    }

    function staleSource() {
        return describe(Object.assign(new Error('Analysis source changed'), {code:'STALE_SOURCE'}));
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

    function scopedPath(original, path) {
        var url = new URL(original, window.location.href), pins = new URLSearchParams();
        ['repositoryId','workspaceId','branch'].forEach(function (key) {
            if (url.searchParams.has(key)) pins.set(key, url.searchParams.get(key));
        });
        return path + (pins.size ? '?' + pins : '');
    }

    // Tab storage holds opaque identifiers and command digests. The server verifies the
    // current account, repository, workspace, branch and original source for every read.
    function storageKey(surface) {
        var runtime = window.__TaxonomyAnalysisSessionContext?.runtime;
        var chrome = window.TaxonomyAppChrome?.getContext?.()?.architecture || {};
        var location = new URL(window.location.href);
        return 'taxonomy.' + (surface || 'document') + '.observation.' + JSON.stringify([location.pathname, runtime?.workspaceId,
            ...['repositoryId','workspaceId','branch'].map(key => location.searchParams.get(key)),
            chrome.repositoryId, chrome.workspaceScopeKey, chrome.branch]);
    }

    function readSaved(key) {
        try {
            var item = JSON.parse(window.sessionStorage.getItem(key));
            if (uuid.test(item?.token || '') && Date.now() - item.created < 86400000) return item;
        } catch (ignored) { /* Private browsing may disable storage. */ }
        return null;
    }

    function writeSaved(key, item) {
        try { window.sessionStorage.setItem(key,JSON.stringify(item)); }
        catch (ignored) { /* Server-side observation still works. */ }
    }

    function forget(key, token) {
        try {
            if (readSaved(key)?.token === token) window.sessionStorage.removeItem(key);
        } catch (ignored) { /* Browser storage is optional. */ }
    }

    function savedDocument() {
        var saved = readSaved(storageKey());
        return ['extract-ai','map-regulation'].includes(saved?.action) ? saved : null;
    }

    function forgetDocument(token) { forget(storageKey(),token); }

    function rememberIdentity(options, id, source) {
        var saved = options.observerStorage && readSaved(options.observerStorage);
        if (!saved || saved.token !== options.observerToken || !uuid.test(id || '')
                || !gitCommit.test(source || '')) return;
        saved.id = id; saved.source = source;
        writeSaved(options.observerStorage,saved);
    }

    async function commandDigest(url, init) {
        var data = JSON.stringify([init.method || 'GET',url,init.body || '',new Headers(init.headers).get('If-Match')]);
        var hash = await window.crypto.subtle.digest('SHA-256',new TextEncoder().encode(data));
        return Array.from(new Uint8Array(hash),b => b.toString(16).padStart(2,'0')).join('');
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
                return await client.request(scopedPath(original,'/api/analysis-adapters/observations/' + token), {method:'GET',cache:'no-store'},
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
        var fingerprint, saved;
        if (options.observationSurface && window.crypto.subtle) {
            fingerprint = await commandDigest(url,init);
            if (!current() || options.isCurrent && !options.isCurrent())
                throw new window.TaxonomyApiClient.ApiError('Request context was superseded',0,url,null,{code:'ABORTED'});
            options.observerStorage = storageKey(options.observationSurface);
            saved = readSaved(options.observerStorage);
            if (saved && saved.fingerprint === fingerprint) {
                options.observerToken = saved.token;
                try { return await readOriginal(saved,url,options,current); }
                catch (error) {
                    if (error.status >= 400 && error.status < 500) forget(options.observerStorage,saved.token);
                    throw error;
                }
            }
            writeSaved(options.observerStorage,{token,fingerprint,created:Date.now()});
        }
        options.observerToken = token;
        headers.set('X-Analysis-Observer', token);
        if (options.documentAction) {
            options.observerStorage = storageKey();
            writeSaved(options.observerStorage,{token,action:options.documentAction,created:Date.now()});
            if (options.onObserver) options.onObserver(token);
        }
        var original = send(url,Object.assign({},init,{headers}),Object.assign({},options,{retries:0}));
        // An upload can take longer than the admission lookup window. The initial request
        // remains the authority if parsing has not admitted any provider work yet.
        var observer = lookup(url,token,options,current,() => stopped,true).catch(error => {
            if (error.status === 404) return original;
            throw error;
        });
        try {
            var first = await Promise.race([original,observer]);
            var id = first.headers?.get('X-Analysis-Operation-Id');
            if (id) rememberIdentity(options,id,first.headers.get('X-Analysis-Source-Commit'));
            else if (options.observerStorage) forget(options.observerStorage,token);
            return first;
        } catch (error) {
            if (options.observerStorage && error.status >= 400 && error.status < 500) forget(options.observerStorage,token);
            throw error;
        } finally { stopped = true; }
    }

    async function readOriginal(saved, path, options, current) {
        var response;
        if (uuid.test(saved.id || '')) {
            var original = scopedPath(path,'/api/analysis-adapters/' + saved.id);
            var policy = Object.assign({},options,{retries:0,observeAdmission:undefined,observeResponse:undefined});
            response = await window.TaxonomyApiClient.request(original,{method:'GET',cache:'no-store'},policy);
        } else response = await lookup(path,saved.token,options,current,() => false,false);
        var id = response.headers.get('X-Analysis-Operation-Id'), source = response.headers.get('X-Analysis-Source-Commit');
        if (!uuid.test(id || '') || !gitCommit.test(source || '')
                || saved.id && (id !== saved.id || source !== saved.source)) throw staleSource();
        rememberIdentity(options,id,source);
        return response;
    }

    async function resumeDocument(saved, options) {
        var path = '/api/documents/' + saved.action;
        var current = window.TaxonomyApiClient.captureContext(options);
        try {
            options.observerStorage = storageKey();
            options.observerToken = saved.token;
            var first = await readOriginal(saved,path,options,current);
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
