/* Personal next-run preferences; recorded analysis language is separate evidence. */
(function () {
    'use strict';
    const choices = {analysis: ['ui', 'de', 'en'], document: ['analysis', 'ui', 'de', 'en']};
    const defaults = {analysis: 'ui', document: 'analysis'};
    const values = {};
    function recorded(value) { return value === 'de' || value === 'en' ? value : null; }
    function uiLanguage() {
        const locale = window.TaxonomyI18n?.getLocale?.() || document.documentElement.lang;
        return String(locale || 'en').toLowerCase().startsWith('de') ? 'de' : 'en';
    }
    function key(kind) {
        const user = document.querySelector('meta[name="taxonomy-user"]')?.content;
        if (!user || user === 'anonymousUser') return null;
        const base = window.TaxonomyI18n?.resolveUrl?.('/') || '/';
        return 'taxonomy.content-language.v1:' + encodeURIComponent(base) + ':' + encodeURIComponent(user) + ':' + kind;
    }
    function preference(kind) {
        if (!choices[kind]) throw new TypeError('Unknown content-language preference');
        if (values[kind] === undefined) {
            let value = null;
            try { const storageKey = key(kind); if (storageKey) value = localStorage.getItem(storageKey); }
            catch (unavailable) { /* This page still supports an explicit choice. */ }
            values[kind] = choices[kind].includes(value) ? value : defaults[kind];
        }
        return values[kind];
    }
    function setPreference(kind, value) {
        if (!choices[kind]?.includes(value)) throw new TypeError('Unsupported content-language preference');
        values[kind] = value;
        try { const storageKey = key(kind); if (storageKey) localStorage.setItem(storageKey, value); }
        catch (unavailable) { /* Retain the current page's selection. */ }
        document.querySelectorAll('[data-content-language="' + kind + '"]').forEach(control => { control.value = value; });
        document.dispatchEvent(new CustomEvent('taxonomy:content-language-changed', {detail: {kind, value}}));
    }
    function analysis() {
        const value = preference('analysis');
        return value === 'ui' ? uiLanguage() : value;
    }
    function documentLanguage(analysisLanguage, choice) {
        const value = choice || preference('document');
        if (!choices.document.includes(value)) throw new TypeError('Unsupported document language');
        if (value === 'analysis') return recorded(analysisLanguage);
        return value === 'ui' ? uiLanguage() : value;
    }
    function init() {
        document.querySelectorAll('[data-content-language]').forEach(control => {
            const kind = control.getAttribute('data-content-language');
            control.value = preference(kind);
            control.addEventListener('change', () => setPreference(kind, control.value));
        });
    }
    window.TaxonomyContentLanguage = {analysis, documentLanguage, preference, setPreference, recorded, uiLanguage};
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
    else init();
}());
