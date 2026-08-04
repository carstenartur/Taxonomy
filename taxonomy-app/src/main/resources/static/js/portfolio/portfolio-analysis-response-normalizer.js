/*
 * Portfolio analysis response normalization.
 *
 * The persistent job centre consumes the asynchronous HTTP contract: a JSON
 * job representation, status 202 and a canonical Location header. Preserve
 * failures unchanged, but normalize successful compatibility responses so the
 * job is registered and can be polled after navigation or reload.
 */
(function () {
    'use strict';

    const previousFetch = window.fetch.bind(window);

    window.fetch = async function normalizedPortfolioAnalysisFetch(input, init) {
        const requestUrl = resolveUrl(input);
        const method = String((init && init.method)
            || (input instanceof Request ? input.method : 'GET')).toUpperCase();
        const response = await previousFetch(input, init);

        if (method !== 'POST' || !isAnalysisSubmission(requestUrl) || !response.ok) {
            return response;
        }

        const job = await response.clone().json().catch(function () { return null; });
        if (!job || !job.id) return response;

        const existingLocation = response.headers.get('Location');
        if (response.status === 202 && existingLocation) return response;

        const location = existingLocation || canonicalLocation(requestUrl, job);
        if (!location) return response;

        const headers = new Headers(response.headers);
        headers.set('Content-Type', 'application/json');
        headers.set('Location', location);
        return new Response(JSON.stringify(job), {
            status: 202,
            statusText: 'Accepted',
            headers: headers
        });
    };

    function resolveUrl(input) {
        const value = input instanceof Request ? input.url : String(input);
        return new URL(value, window.location.href);
    }

    function isAnalysisSubmission(url) {
        return url.origin === window.location.origin
            && /^\/api\/projects\/\d+\/(?:analyses|requirements\/\d+\/analyses|analysis-jobs\/[^/]+\/retry-failed)$/.test(
                url.pathname);
    }

    function canonicalLocation(requestUrl, job) {
        const projectMatch = requestUrl.pathname.match(/^\/api\/projects\/(\d+)\//);
        const projectId = job.projectId || (projectMatch ? Number(projectMatch[1]) : null);
        if (!projectId) return null;
        return '/api/projects/' + encodeURIComponent(projectId)
            + '/analysis-jobs/' + encodeURIComponent(job.id);
    }
}());
