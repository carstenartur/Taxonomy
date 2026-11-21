/* Exact checkpoint coverage for the bounded C1-S1/R7 delivery. */
(function () {
    'use strict';
    // The report retains its audit text; localize known review guidance only for display.
    var guidanceKeys = new Map([
        [
                "This preview represents the changes after explicit review of service fulfilment and requirement derivation.",
                "editor.admbw.guidance.reviewedChanges"
        ],
        [
                "The operational constraint is a proposed restatement grounded in the quoted requirement; independent upstream evidence was not supplied.",
                "editor.admbw.guidance.derivedConstraint"
        ],
        [
                "The selected QEA reference identities apply to the inspected basis; independent architecture review remains required.",
                "editor.admbw.guidance.basisScope"
        ],
        [
                "Verify catalogue identities against the supplied catalogue. Record unresolved capabilities as Issues.",
                "editor.admbw.guidance.catalogue"
        ],
        [
                "Check whether an Environment and EnvironmentalCondition are needed.",
                "editor.admbw.guidance.environment"
        ],
        [
                "Check the actual ADMBw diagram subtype, diagram notes and native delivery in Enterprise Architect.",
                "editor.admbw.guidance.nativeDiagram"
        ],
        [
                "Check the SAMIT requirement sentence, Require.7 tagged fields and agreement of the Subjekt with the planned realizer.",
                "editor.admbw.guidance.requirement"
        ],
        [
                "Verify the catalogue SubjectOfOperationalConstraint and the native Satisfy roles (R7-MK08).",
                "editor.admbw.guidance.subject"
        ],
        [
                "Check additional allowed realizer types and view grouping against the applicable project guide.",
                "editor.admbw.guidance.realizer"
        ]
]);
    function message(value, t) {
        var key = guidanceKeys.get(value);
        if (!key) return value;
        var translated = t(key);
        return translated && translated !== key ? translated : value;
    }
    function render(coverage, t, selectElement) {
        function node(tag, value) {
            var result = document.createElement(tag);
            if (value !== undefined) result.textContent = value;
            return result;
        }
        function label(prefix, value) {
            var key = prefix + value, translated = t(key);
            return translated === key ? value : translated;
        }
        var section = node('section');
        section.className = 'editor-admbw-coverage mb-3 text-break';
        section.setAttribute('aria-label', t('editor.admbw.coverage.title'));
        section.dataset.coverageStatus = coverage ? coverage.status : 'UNRECORDED';
        section.append(node('h3', t('editor.admbw.coverage.title')));
        if (!coverage) {
            section.append(node('p', t('editor.admbw.coverage.unavailable')));
            return section;
        }
        section.append(node('p', label('editor.admbw.coverage.status.', coverage.status)),
            node('p', coverage.sourceViewId ? t('editor.admbw.coverage.scopeView', coverage.sourceViewId)
                : t('editor.admbw.coverage.scopeAll')),
            node('p', t('editor.admbw.coverage.limit')));
        var table = node('table'), head = node('thead'), row = node('tr'), body = node('tbody');
        table.className = 'table table-sm';
        ['kind', 'total', 'represented', 'omitted'].forEach(function (key) {
            var cell = node('th', t('editor.admbw.coverage.' + key));
            cell.setAttribute('scope', 'col'); row.append(cell);
        });
        head.append(row);
        ['elements', 'relations', 'requirements', 'mappings'].forEach(function (kind) {
            var values = coverage[kind], line = node('tr'), title = node('th', t('editor.admbw.coverage.' + kind));
            title.setAttribute('scope', 'row'); line.append(title);
            ['total', 'represented', 'omitted'].forEach(function (key) { line.append(node('td', String(values[key]))); });
            body.append(line);
        });
        table.append(head, body); section.append(table);
        (coverage.sourceAnalyses || []).forEach(function (source) {
            section.append(node('p', t('editor.admbw.coverage.sourceAnalysis', source.viewId, source.snapshotId,
                label('editor.admbw.coverage.sourceStatus.', source.status))));
        });
        if (coverage.omissions && coverage.omissions.length) {
            var details = node('details'), list = node('ul');
            details.append(node('summary', t('editor.admbw.coverage.omissionList', coverage.omissions.length)));
            coverage.omissions.forEach(function (omission) {
                var item = node('li');
                item.append(node('span', omission.ref + (omission.name ? ' · ' + omission.name : '') + ' · '
                    + label('editor.admbw.coverage.reason.', omission.reason)));
                if (omission.kind === 'ELEMENT' && omission.ref.startsWith('element:') && selectElement) {
                    var button = node('button', t('editor.admbw.coverage.inspectElement'));
                    button.type = 'button'; button.className = 'btn btn-sm btn-link';
                    button.addEventListener('click', function () { selectElement(omission.ref.substring(8)); });
                    item.append(button);
                }
                list.append(item);
            });
            details.append(list); section.append(details);
        }
        return section;
    }
    window.TaxonomyAdmbwCoverage = {render: render, message: message};
}());
