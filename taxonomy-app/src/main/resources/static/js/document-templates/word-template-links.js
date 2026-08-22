(function (global) {
    'use strict';

    const DOTX_MEDIA_TYPE =
        'application/vnd.openxmlformats-officedocument.wordprocessingml.template';
    const WORD_EDIT_PREFIX = 'ms-word:ofe|u|';
    const WORD_NEW_FROM_TEMPLATE_PREFIX = 'ms-word:nft|u|';

    function requireHttpResourceUrl(value) {
        const url = value instanceof URL ? value : new URL(String(value));
        if (url.protocol !== 'https:' && url.protocol !== 'http:') {
            throw new TypeError('The WebDAV resource must use HTTP or HTTPS');
        }
        return url;
    }

    function isLoopbackHostname(hostname) {
        const normalized = String(hostname || '')
            .toLowerCase()
            .replace(/^\[/, '')
            .replace(/\]$/, '');
        return normalized === 'localhost'
            || normalized.endsWith('.localhost')
            || normalized === '::1'
            || normalized.startsWith('127.');
    }

    function requireDirectWordUrl(value) {
        const url = requireHttpResourceUrl(value);
        if (url.protocol === 'https:'
                || (url.protocol === 'http:' && isLoopbackHostname(url.hostname))) {
            return url;
        }
        throw new TypeError(
            'Direct Word editing requires HTTPS outside local development');
    }

    function absoluteWebDavUrl(resourceUrl, baseUrl) {
        if (!resourceUrl) {
            throw new TypeError('A WebDAV resource URL is required');
        }
        const effectiveBase = baseUrl || (global.location && global.location.href);
        if (!effectiveBase) {
            throw new TypeError('A base URL is required');
        }
        return requireHttpResourceUrl(new URL(resourceUrl, effectiveBase)).href;
    }

    function directWordLinkAllowed(webDavUrl) {
        try {
            requireDirectWordUrl(webDavUrl);
            return true;
        } catch (ignored) {
            return false;
        }
    }

    function editUri(webDavUrl) {
        return WORD_EDIT_PREFIX + requireDirectWordUrl(webDavUrl).href;
    }

    function newFromTemplateUri(webDavUrl) {
        return WORD_NEW_FROM_TEMPLATE_PREFIX + requireDirectWordUrl(webDavUrl).href;
    }

    function configureWordLink(anchor, webDavUrl, mode) {
        if (!anchor) {
            throw new TypeError('A link element is required');
        }
        if (mode !== 'edit' && mode !== 'new') {
            throw new TypeError('Word link mode must be edit or new');
        }
        const absoluteUrl = absoluteWebDavUrl(webDavUrl);
        const officeUri = mode === 'new'
            ? newFromTemplateUri(absoluteUrl)
            : editUri(absoluteUrl);
        anchor.setAttribute('href', officeUri);
        anchor.setAttribute('target', '_blank');
        anchor.setAttribute('rel', 'noopener noreferrer');
        anchor.setAttribute('type', DOTX_MEDIA_TYPE);
        anchor.dataset.webdavUrl = absoluteUrl;
        return absoluteUrl;
    }

    global.TaxonomyDocumentTemplateLinks = Object.freeze({
        DOTX_MEDIA_TYPE,
        absoluteWebDavUrl,
        directWordLinkAllowed,
        editUri,
        newFromTemplateUri,
        configureWordLink
    });
}(window));
