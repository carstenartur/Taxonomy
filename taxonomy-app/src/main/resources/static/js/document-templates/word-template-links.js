(function (global) {
    'use strict';

    const DOTX_MEDIA_TYPE =
        'application/vnd.openxmlformats-officedocument.wordprocessingml.template';
    const WORD_EDIT_PREFIX = 'ms-word:ofe|u|';
    const WORD_NEW_FROM_TEMPLATE_PREFIX = 'ms-word:nft|u|';

    function requireHttpUrl(value) {
        const url = value instanceof URL ? value : new URL(String(value));
        if (url.protocol !== 'https:' && url.protocol !== 'http:') {
            throw new TypeError('The WebDAV resource must use HTTP or HTTPS');
        }
        return url;
    }

    function absoluteWebDavUrl(resourceUrl, baseUrl) {
        if (!resourceUrl) {
            throw new TypeError('A WebDAV resource URL is required');
        }
        const effectiveBase = baseUrl || (global.location && global.location.href);
        if (!effectiveBase) {
            throw new TypeError('A base URL is required');
        }
        return requireHttpUrl(new URL(resourceUrl, effectiveBase)).href;
    }

    function editUri(webDavUrl) {
        return WORD_EDIT_PREFIX + requireHttpUrl(webDavUrl).href;
    }

    function newFromTemplateUri(webDavUrl) {
        return WORD_NEW_FROM_TEMPLATE_PREFIX + requireHttpUrl(webDavUrl).href;
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
        editUri,
        newFromTemplateUri,
        configureWordLink
    });
}(window));
