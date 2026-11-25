/* Safe, readable prompts with access to the original diagnostic text. */
(function () {
    'use strict';

    // Recovered evidence can be substantially larger than the live log preview.
    var maxStructuredValues = 2000;
    var maxStructuredDepth = 20;

    function element(tag, className, text) {
        var result = document.createElement(tag);
        if (className) result.className = className;
        if (text !== undefined) result.textContent = text;
        return result;
    }

    function structure(value, depth, budget) {
        if (depth > maxStructuredDepth || --budget.remaining < 0) return null;
        if (Array.isArray(value) && value.length) {
            var items = element('ol');
            for (var entry of value) {
                var item = structure(entry, depth + 1, budget);
                if (!item) return null;
                var listItem = element('li');
                listItem.append(item);
                items.append(listItem);
            }
            return items;
        }
        if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
            var keys = Object.keys(value);
            if (keys.length) {
                var fields = element('dl');
                for (var key of keys) {
                    var child = structure(value[key], depth + 1, budget);
                    if (!child) return null;
                    var definition = element('dd');
                    definition.append(child);
                    fields.append(element('dt', '', key), definition);
                }
                return fields;
            }
        }
        // Display string values as plain text, decoding only the outer JSON serialization.
        return element('pre', '', typeof value === 'string' && value.length ? value : JSON.stringify(value));
    }

    function structuredPreview(raw) {
        var marker = /^INPUT\r?\n/m.exec(raw);
        var inputAt = marker ? marker.index + marker[0].length : 0;
        var input = raw.slice(inputAt);
        if (!/^\s*[\[{]/.test(input)) return null;

        var parsed;
        try {
            parsed = JSON.parse(input);
        } catch (_) {
            // Preserve partial or malformed evidence verbatim.
            return null;
        }
        var content = structure(parsed, 0, { remaining: maxStructuredValues });
        if (!content) return null;

        var preview = element('div', 'llm-log-prompt llm-prompt-readable');
        if (inputAt) preview.append(element('pre', 'llm-prompt-instructions', raw.slice(0, inputAt)));
        preview.append(content);
        return preview;
    }

    function alternate(de, en) {
        return (document.documentElement.lang || 'en').toLowerCase().startsWith('de') ? de : en;
    }

    function render(value, options) {
        var raw = typeof value === 'string' ? value : '';
        var length = options.originalLength;
        var truncated = Number.isSafeInteger(length) && length > raw.length;
        var preview = truncated ? null : structuredPreview(raw);
        var field = element('div', 'llm-prompt-field mb-2');
        var heading = element('div', 'd-flex flex-wrap align-items-center justify-content-between gap-2');
        var copy = element('button', 'btn btn-sm btn-outline-secondary llm-log-copy', alternate('Rohprompt kopieren', 'Copy raw prompt'));
        copy.type = 'button';
        copy.disabled = !raw;
        copy.setAttribute('aria-live', 'polite');
        copy.addEventListener('click', async function () {
            if (!raw) return;
            try {
                await window.TaxonomyUtils.copyText(raw);
                copy.textContent = alternate('Kopiert', 'Copied');
            } catch (_) {
                copy.textContent = alternate('Kopieren fehlgeschlagen', 'Copy failed');
            }
        });
        heading.append(element(options.headingTag || 'strong', '', options.title || 'Prompt'), copy);
        field.append(heading);
        if (truncated) {
            field.append(element('div', 'text-muted', alternate('Gekürzte Vorschau; ursprüngliche Länge: ',
                'Truncated preview; original length: ') + length + alternate(' Zeichen.', ' characters.')));
        }
        var original = element('pre', 'llm-log-prompt', raw || options.emptyMessage || '');
        if (preview) {
            var details = element('details');
            details.append(element('summary', '', alternate('Unveränderter Rohprompt', 'Unchanged raw prompt')), original);
            field.append(preview, details);
        } else {
            field.append(original);
        }
        return field;
    }

    window.TaxonomyPromptDisplay = { render: render };
}());
