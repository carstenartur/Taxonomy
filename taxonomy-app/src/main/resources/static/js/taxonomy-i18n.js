/**
 * taxonomy-i18n.js — Client-side internationalisation module.
 *
 * Loads translated strings from /api/i18n/{locale} and exposes a global
 * TaxonomyI18n.t(key, ...args) function for lookups.
 *
 * Usage:
 *   TaxonomyI18n.t('nav.analyze')            → "Analyze" (en) / "Analyse" (de)
 *   TaxonomyI18n.t('error.bad.request', msg)  → "Bad request: <msg>"
 */
(function () {
    'use strict';

    var strings = {};
    var currentLocale = 'en';
    var loaded = false;
    var loadPromise = null;

    /** Detect initial locale from <html lang="…">, cookie, or localStorage. */
    function detectLocale() {
        // 1. localStorage preference
        var stored = localStorage.getItem('taxonomy_language');
        if (stored) return stored;

        // 2. Cookie
        var match = document.cookie.match(/(?:^|;\s*)lang=([a-z]{2}(?:-[A-Z]{2})?)/);
        if (match) return match[1];

        // 3. <html lang="…">
        var htmlLang = document.documentElement.lang;
        if (htmlLang && htmlLang.length >= 2) return htmlLang.substring(0, 2);

        // 4. Fallback
        return 'en';
    }

    /**
     * Simple {0}, {1}, … parameter substitution.
     * @param {string} template - Message template with {n} placeholders
     * @param {Array} args - Replacement values
     */
    function format(template, args) {
        if (!args || args.length === 0) return template;
        return template.replace(/\{(\d+)\}/g, function (m, idx) {
            var i = parseInt(idx, 10);
            return i < args.length ? args[i] : m;
        });
    }

    /**
     * Translate a message key. Returns the translated string, or the key itself
     * as a fallback if no translation is found.
     *
     * @param {string} key - The message key (e.g. 'nav.analyze')
     * @param {...*} args - Optional substitution parameters
     * @returns {string}
     */
    function t(key) {
        var args = Array.prototype.slice.call(arguments, 1);
        var value = strings[key];
        if (value === undefined || value === null) {
            return format(key, args);
        }
        return format(value, args);
    }

    /**
     * Load translations from the server for the given locale.
     * @param {string} locale - BCP-47 language code (e.g. 'en', 'de')
     * @returns {Promise}
     */
    function load(locale) {
        currentLocale = locale || detectLocale();
        loadPromise = fetch('/api/i18n/' + encodeURIComponent(currentLocale))
            .then(function (resp) {
                if (!resp.ok) throw new Error('i18n load failed: ' + resp.status);
                return resp.json();
            })
            .then(function (data) {
                strings = data || {};
                loaded = true;
                document.documentElement.lang = currentLocale;
                document.dispatchEvent(new CustomEvent('taxonomy-i18n-loaded', { detail: { locale: currentLocale } }));
            })
            .catch(function (err) {
                console.warn('[i18n] Failed to load translations for ' + currentLocale + ':', err);
                loaded = true; // Mark as loaded so t() returns keys as fallback
            });
        return loadPromise;
    }

    /**
     * Switch the UI language. Persists to localStorage and cookie, then reloads.
     * @param {string} locale - Language code
     */
    function setLocale(locale) {
        localStorage.setItem('taxonomy_language', locale);
        document.cookie = 'lang=' + locale + ';path=/;max-age=31536000;SameSite=Lax';
        // Reload the page so that Thymeleaf re-renders with the new locale
        var url = new URL(window.location.href);
        url.searchParams.set('lang', locale);
        window.location.href = url.toString();
    }

    /**
     * Get the current locale.
     * @returns {string}
     */
    function getLocale() {
        return currentLocale;
    }

    /**
     * Check whether translations have been loaded.
     * @returns {boolean}
     */
    function isLoaded() {
        return loaded;
    }

    /**
     * Return a promise that resolves when translations are loaded.
     * @returns {Promise}
     */
    function ready() {
        return loadPromise || Promise.resolve();
    }

    /**
     * Convenience: translate a branch name (e.g. 'draft' → user-facing label).
     */
    function formatBranch(name) {
        if (!name) return '';
        var key = 'git.branch.' + name;
        var translated = t(key);
        return translated === key ? name : translated;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    window.TaxonomyI18n = {
        t: t,
        load: load,
        setLocale: setLocale,
        getLocale: getLocale,
        isLoaded: isLoaded,
        ready: ready,
        formatBranch: formatBranch
    };

    // Auto-load on script parse
    currentLocale = detectLocale();
    load(currentLocale);
}());
