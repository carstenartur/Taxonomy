/*
 * Portfolio product request normalization.
 *
 * HTML datetime-local controls intentionally return a local date and time
 * without an offset. The product REST contract uses java.time.Instant, so the
 * browser must turn the entered local value into an ISO-8601 instant before
 * sending JSON. Keep this adapter ahead of the other portfolio fetch wrappers.
 */
(function () {
    'use strict';

    const previousFetch = window.fetch.bind(window);

    window.fetch = function normalizedPortfolioProductFetch(input, init) {
        const requestUrl = resolveUrl(input);
        const method = String((init && init.method)
            || (input instanceof Request ? input.method : 'GET')).toUpperCase();

        if (method !== 'POST' || !isProductCollection(requestUrl)
                || !init || typeof init.body !== 'string' || !isJson(init.headers)) {
            return previousFetch(input, init);
        }

        let payload;
        try {
            payload = JSON.parse(init.body);
        } catch (error) {
            return previousFetch(input, init);
        }

        if (!payload.verifiedAt || hasExplicitOffset(payload.verifiedAt)) {
            return previousFetch(input, init);
        }

        const parsed = new Date(payload.verifiedAt);
        if (Number.isNaN(parsed.getTime())) {
            return previousFetch(input, init);
        }

        payload.verifiedAt = parsed.toISOString();
        return previousFetch(input, Object.assign({}, init, {
            body: JSON.stringify(payload)
        }));
    };

    function resolveUrl(input) {
        if (input instanceof Request) return new URL(input.url, window.location.href);
        return new URL(String(input), window.location.href);
    }

    function isProductCollection(url) {
        return /^\/api\/products\/?$/.test(url.pathname);
    }

    function isJson(headers) {
        return (new Headers(headers || {}).get('Content-Type') || '')
                .toLowerCase().includes('application/json');
    }

    function hasExplicitOffset(value) {
        return /(?:z|[+-]\d{2}:\d{2})$/i.test(String(value));
    }
}());
