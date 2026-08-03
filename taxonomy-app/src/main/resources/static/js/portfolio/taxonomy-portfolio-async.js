/*
 * Keeps the portfolio UI compatible with the asynchronous analysis API.
 * POST requests return 202 immediately; this page-local fetch adapter polls the
 * persisted job resource and resolves the existing UI call with its terminal view.
 */
(function () {
    'use strict';

    const originalFetch = window.fetch.bind(window);
    const terminalStatuses = new Set(['SUCCESS', 'PARTIAL', 'FAILED', 'CANCELLED']);
    const pollIntervalMs = 400;
    const pollTimeoutMs = 10 * 60 * 1000;

    window.fetch = async function portfolioAwareFetch(input, init) {
        const response = await originalFetch(input, init);
        const method = String((init && init.method)
            || (input instanceof Request ? input.method : 'GET')).toUpperCase();
        const requestUrl = resolveUrl(input);
        if (method !== 'POST' || response.status !== 202 || !isAnalysisSubmission(requestUrl)) {
            return response;
        }

        const initialJob = await response.clone().json().catch(function () { return null; });
        const location = response.headers.get('Location');
        if (!initialJob || !initialJob.id || !location || terminalStatuses.has(initialJob.status)) {
            return response;
        }

        const completedJob = await pollJob(location, init && init.signal);
        const headers = new Headers(response.headers);
        headers.set('Content-Type', 'application/json');
        return new Response(JSON.stringify(completedJob), {
            status: 200,
            statusText: 'OK',
            headers: headers
        });
    };

    function resolveUrl(input) {
        const value = input instanceof Request ? input.url : String(input);
        return new URL(value, window.location.href);
    }

    function isAnalysisSubmission(url) {
        return url.origin === window.location.origin
            && url.pathname.startsWith('/api/projects/')
            && (url.pathname.endsWith('/analyses') || url.pathname.endsWith('/retry-failed'));
    }

    async function pollJob(location, signal) {
        const startedAt = Date.now();
        const jobUrl = new URL(location, window.location.href).toString();
        while (Date.now() - startedAt < pollTimeoutMs) {
            await delay(pollIntervalMs, signal);
            const response = await originalFetch(jobUrl, {
                method: 'GET',
                headers: { Accept: 'application/json' },
                credentials: 'same-origin',
                cache: 'no-store',
                signal: signal
            });
            if (!response.ok) {
                throw new Error('Unable to read the queued portfolio analysis job (HTTP '
                    + response.status + ').');
            }
            const job = await response.json();
            if (job && terminalStatuses.has(job.status)) return job;
        }
        throw new Error('The queued portfolio analysis did not finish within ten minutes.');
    }

    function delay(milliseconds, signal) {
        return new Promise(function (resolve, reject) {
            if (signal && signal.aborted) {
                reject(new DOMException('The operation was aborted.', 'AbortError'));
                return;
            }

            let settled = false;
            const onAbort = function () {
                if (settled) return;
                settled = true;
                window.clearTimeout(timeout);
                reject(new DOMException('The operation was aborted.', 'AbortError'));
            };
            const timeout = window.setTimeout(function () {
                if (settled) return;
                settled = true;
                if (signal) signal.removeEventListener('abort', onAbort);
                resolve();
            }, milliseconds);
            if (signal) signal.addEventListener('abort', onAbort, { once: true });
        });
    }
})();
