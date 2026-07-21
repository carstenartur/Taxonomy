/* Recognition-based assistance for technical versioning identifiers. */
window.TaxonomyRecognitionAssistance = (function () {
    'use strict';

    var COMMIT_INPUT_IDS = [
        'compareLeftCommit',
        'compareRightCommit',
        'transferSourceCommit',
        'transferTargetCommit'
    ];
    var RELATION_INPUT_IDS = ['transferRelationIds'];
    var COMMIT_PATTERN = /^[0-9a-f]{7,40}$/i;
    var RELATION_PATTERN = /\b([A-Z]{2}(?:-[A-Z0-9]+)?)\s+(REALIZES|SUPPORTS|CONSUMES|USES|FULFILLS|ASSIGNED_TO|DEPENDS_ON|PRODUCES|COMMUNICATES_WITH|CONTAINS|REQUIRES|RELATED_TO)\s+([A-Z]{2}(?:-[A-Z0-9]+)?)\b/g;

    function ensureDatalist(id) {
        var list = document.getElementById(id);
        if (list) return list;
        list = document.createElement('datalist');
        list.id = id;
        document.body.appendChild(list);
        return list;
    }

    function replaceOptions(list, values) {
        var fragment = document.createDocumentFragment();
        Array.from(values)
            .sort(function (left, right) { return left.value.localeCompare(right.value); })
            .forEach(function (entry) {
                var option = document.createElement('option');
                option.value = entry.value;
                if (entry.label) option.label = entry.label;
                fragment.appendChild(option);
            });
        list.replaceChildren(fragment);
    }

    function collectCommits() {
        var entries = new Map();
        document.querySelectorAll('[data-commit], [data-commit-id]').forEach(function (element) {
            var value = element.getAttribute('data-commit') || element.getAttribute('data-commit-id');
            if (!value || !COMMIT_PATTERN.test(value)) return;
            var container = element.closest('.timeline-entry, tr, .list-group-item, .card');
            var label = container ? container.textContent.replace(/\s+/g, ' ').trim().slice(0, 120) : '';
            entries.set(value, { value: value, label: label });
        });
        document.querySelectorAll('code, .git-sha').forEach(function (element) {
            var value = element.textContent.trim();
            if (COMMIT_PATTERN.test(value) && !entries.has(value)) {
                entries.set(value, { value: value, label: '' });
            }
        });
        return entries;
    }

    function collectRelations() {
        var entries = new Map();
        document.querySelectorAll('[data-source-code][data-target-code][data-relation-type]').forEach(function (element) {
            var value = [
                element.getAttribute('data-source-code'),
                element.getAttribute('data-relation-type'),
                element.getAttribute('data-target-code')
            ].join(' ').trim();
            if (value) entries.set(value, { value: value, label: element.textContent.trim().slice(0, 120) });
        });
        document.querySelectorAll('table tbody tr, .relation-item, .proposal-item').forEach(function (element) {
            var text = element.textContent.replace(/\s+/g, ' ').trim();
            var match;
            RELATION_PATTERN.lastIndex = 0;
            while ((match = RELATION_PATTERN.exec(text)) !== null) {
                var value = match[1] + ' ' + match[2] + ' ' + match[3];
                entries.set(value, { value: value, label: text.slice(0, 120) });
            }
        });
        return entries;
    }

    function attachInputs(inputIds, listId, entries, hintText) {
        if (!entries.size) return;
        var list = ensureDatalist(listId);
        replaceOptions(list, entries.values());
        inputIds.forEach(function (id) {
            var input = document.getElementById(id);
            if (!input) return;
            input.setAttribute('list', listId);
            input.setAttribute('autocomplete', 'off');
            if (!input.getAttribute('title')) input.setAttribute('title', hintText);
        });
    }

    function refresh() {
        var german = (document.documentElement.lang || '').toLowerCase().startsWith('de');
        attachInputs(
            COMMIT_INPUT_IDS,
            'taxonomyCommitOptions',
            collectCommits(),
            german ? 'Commit aus der geladenen Historie auswählen oder HEAD eingeben.'
                : 'Choose a commit from the loaded history or enter HEAD.'
        );
        attachInputs(
            RELATION_INPUT_IDS,
            'taxonomyRelationKeyOptions',
            collectRelations(),
            german ? 'Relation aus den geladenen Ergebnissen auswählen.'
                : 'Choose a relation from the loaded results.'
        );
    }

    function init() {
        refresh();
        var scheduled = false;
        new MutationObserver(function () {
            if (scheduled) return;
            scheduled = true;
            requestAnimationFrame(function () {
                scheduled = false;
                refresh();
            });
        }).observe(document.body, { subtree: true, childList: true, attributes: true });
        document.addEventListener('taxonomy:view-rendered', refresh);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init, { once: true });
    } else {
        init();
    }

    return { refresh: refresh };
}());
