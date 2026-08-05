(function () {
    'use strict';

    const match = window.location.pathname.match(/^\/projects\/(\d+)\/matrices$/);
    if (!match) return;
    const projectId = Number(match[1]);
    const locale = (new URLSearchParams(window.location.search).get('lang')
        || document.documentElement.lang || 'en').toLowerCase().startsWith('de') ? 'de' : 'en';
    const state = { project: null, portfolio: null, activeType: 'taxonomy' };
    const matrixDefinitions = {
        taxonomy: { target: 'taxonomyMatrix', property: 'requirementTaxonomyMatrix', title: 'Requirements × taxonomy' },
        solution: { target: 'solutionMatrix', property: 'requirementSolutionMatrix', title: 'Requirements × solutions' },
        product: { target: 'productMatrix', property: 'solutionProductMatrix', title: 'Solutions × products' }
    };

    const labels = {
        en: { title: 'Portfolio matrices', portfolio: 'Portfolio', filters: 'Filters', search: 'Search rows and columns', minimum: 'Minimum coverage', relationship: 'Relationship', all: 'All', related: 'Related', empty: 'Empty', reset: 'Reset', exportCsv: 'Export filtered CSV', exportJson: 'Export filtered JSON', taxonomy: 'Requirements × taxonomy', solution: 'Requirements × solutions', product: 'Solutions × products', detail: 'Relationship detail', noData: 'No relationships match the active filters.', row: 'Row', column: 'Column', value: 'Coverage', relationAbsent: 'No stored relationship exists. This is not an explicit zero score.', openRequirement: 'Open requirement detail', review: 'Review status', evidence: 'Evidence', source: 'Source', selected: 'Selection', filtersActive: 'Active filters', none: 'none', failed: 'The matrix could not be loaded.' },
        de: { title: 'Portfolio-Matrizen', portfolio: 'Portfolio', filters: 'Filter', search: 'Zeilen und Spalten durchsuchen', minimum: 'Mindestabdeckung', relationship: 'Beziehung', all: 'Alle', related: 'Verknüpft', empty: 'Leer', reset: 'Zurücksetzen', exportCsv: 'Gefiltertes CSV exportieren', exportJson: 'Gefiltertes JSON exportieren', taxonomy: 'Anforderungen × Taxonomie', solution: 'Anforderungen × Lösungen', product: 'Lösungen × Produkte', detail: 'Beziehungsdetails', noData: 'Keine Beziehungen entsprechen den aktiven Filtern.', row: 'Zeile', column: 'Spalte', value: 'Abdeckung', relationAbsent: 'Es ist keine Beziehung gespeichert. Dies ist kein expliziter Null-Score.', openRequirement: 'Anforderungsdetails öffnen', review: 'Prüfstatus', evidence: 'Evidenz', source: 'Quelle', selected: 'Auswahl', filtersActive: 'Aktive Filter', none: 'keine', failed: 'Die Matrix konnte nicht geladen werden.' }
    };

    document.addEventListener('DOMContentLoaded', initialize);
    function l(key) { return labels[locale][key] || labels.en[key] || key; }

    async function initialize() {
        translateSurface();
        wireEvents();
        setBusy(true);
        try {
            [state.project, state.portfolio] = await Promise.all([
                api().getProject(projectId), api().getProjectPortfolio(projectId)
            ]);
            document.getElementById('matrixProject').textContent = `${state.project.projectKey} — ${state.project.title}`;
            renderAll();
        } catch (error) {
            showError(error);
        } finally {
            setBusy(false);
        }
    }

    function translateSurface() {
        document.documentElement.lang = locale;
        document.title = `${l('title')} — Taxonomy`;
        document.querySelector('.skip-link').textContent = locale === 'de' ? 'Zu den Matrizen springen' : 'Skip to matrices';
        document.getElementById('matrixPageTitle').textContent = l('title');
        document.getElementById('matrixHeading').textContent = l('title');
        document.getElementById('portfolioBack').textContent = l('portfolio');
        document.getElementById('portfolioBack').href = `/projects?lang=${locale}`;
        document.getElementById('filterHeading').textContent = l('filters');
        document.querySelector('label[for="matrixSearch"]').textContent = l('search');
        document.querySelector('label[for="minimumValue"]').textContent = l('minimum');
        document.querySelector('label[for="relationState"]').textContent = l('relationship');
        const relation = document.getElementById('relationState');
        relation.options[0].textContent = l('all'); relation.options[1].textContent = l('related'); relation.options[2].textContent = l('empty');
        document.getElementById('resetFilters').textContent = l('reset');
        document.getElementById('exportCsv').textContent = l('exportCsv');
        document.getElementById('exportJson').textContent = l('exportJson');
        document.getElementById('taxonomyMatrixTab').textContent = l('taxonomy');
        document.getElementById('solutionMatrixTab').textContent = l('solution');
        document.getElementById('productMatrixTab').textContent = l('product');
        document.getElementById('cellDetailTitle').textContent = l('detail');
    }

    function wireEvents() {
        ['matrixSearch', 'minimumValue', 'relationState'].forEach(id => document.getElementById(id).addEventListener('input', renderAll));
        document.getElementById('resetFilters').addEventListener('click', () => {
            document.getElementById('matrixSearch').value = '';
            document.getElementById('minimumValue').value = '0';
            document.getElementById('relationState').value = 'all';
            renderAll();
        });
        document.getElementById('exportCsv').addEventListener('click', () => exportCurrent('csv'));
        document.getElementById('exportJson').addEventListener('click', () => exportCurrent('json'));
        document.querySelectorAll('[data-bs-toggle="tab"]').forEach(button => button.addEventListener('shown.bs.tab', event => {
            if (event.target.id === 'taxonomyMatrixTab') state.activeType = 'taxonomy';
            if (event.target.id === 'solutionMatrixTab') state.activeType = 'solution';
            if (event.target.id === 'productMatrixTab') state.activeType = 'product';
        }));
        document.getElementById('matrixMain').addEventListener('click', event => {
            const cell = event.target.closest('.matrix-drilldown');
            if (cell) openCellDetail(cell.dataset.matrixType, cell.dataset.row, cell.dataset.column, Number(cell.dataset.value));
        });
    }

    function renderAll() {
        Object.entries(matrixDefinitions).forEach(([type, definition]) => renderMatrix(type, definition));
        const search = document.getElementById('matrixSearch').value.trim();
        const minimum = Number(document.getElementById('minimumValue').value || 0);
        const relation = document.getElementById('relationState').value;
        document.getElementById('activeFilters').textContent = `${l('filtersActive')}: `
            + ([search && `“${search}”`, minimum > 0 && `≥ ${minimum}%`, relation !== 'all' && l(relation)]
                .filter(Boolean).join(' · ') || l('none'));
    }

    function filteredMatrix(type) {
        const definition = matrixDefinitions[type];
        const matrix = state.portfolio && state.portfolio[definition.property];
        if (!matrix || !Array.isArray(matrix.rows) || !Array.isArray(matrix.columns)) return { rows: [], columns: [], values: {} };
        const search = document.getElementById('matrixSearch').value.trim().toLowerCase();
        const minimum = Number(document.getElementById('minimumValue').value || 0);
        const relation = document.getElementById('relationState').value;
        const rowMatches = row => !search || row.toLowerCase().includes(search)
            || matrix.columns.some(column => column.toLowerCase().includes(search) && cellVisible(valueAt(matrix, row, column), minimum, relation));
        const columnMatches = column => !search || column.toLowerCase().includes(search)
            || matrix.rows.some(row => row.toLowerCase().includes(search) && cellVisible(valueAt(matrix, row, column), minimum, relation));
        const rows = matrix.rows.filter(rowMatches).filter(row => matrix.columns.some(column => cellVisible(valueAt(matrix, row, column), minimum, relation)));
        const columns = matrix.columns.filter(columnMatches).filter(column => rows.some(row => cellVisible(valueAt(matrix, row, column), minimum, relation)));
        const values = {};
        rows.forEach(row => {
            values[row] = {};
            columns.forEach(column => {
                const value = valueAt(matrix, row, column);
                if (cellVisible(value, minimum, relation)) values[row][column] = value;
            });
        });
        return { rows, columns, values };
    }

    function cellVisible(value, minimum, relation) {
        if (relation === 'related' && value <= 0) return false;
        if (relation === 'empty' && value > 0) return false;
        return value >= minimum;
    }

    function valueAt(matrix, row, column) { return Number(matrix.values && matrix.values[row] && matrix.values[row][column] || 0); }

    function renderMatrix(type, definition) {
        const target = document.getElementById(definition.target);
        const matrix = filteredMatrix(type);
        target.textContent = '';
        if (!matrix.rows.length || !matrix.columns.length) {
            const empty = document.createElement('p'); empty.className = 'p-4 text-body-secondary mb-0'; empty.textContent = l('noData'); target.appendChild(empty); return;
        }
        const table = document.createElement('table'); table.className = 'table table-sm table-bordered align-middle mb-0';
        const caption = document.createElement('caption'); caption.className = 'visually-hidden'; caption.textContent = definition.title; table.appendChild(caption);
        const thead = document.createElement('thead'); const header = document.createElement('tr');
        const corner = document.createElement('th'); corner.scope = 'col'; corner.textContent = ''; header.appendChild(corner);
        matrix.columns.forEach(column => { const th = document.createElement('th'); th.scope = 'col'; th.textContent = column; th.title = column; header.appendChild(th); });
        thead.appendChild(header); table.appendChild(thead);
        const tbody = document.createElement('tbody');
        matrix.rows.forEach(rowName => {
            const row = document.createElement('tr'); const th = document.createElement('th'); th.scope = 'row'; th.textContent = rowName; row.appendChild(th);
            matrix.columns.forEach(column => {
                const value = Number(matrix.values[rowName] && matrix.values[rowName][column] || 0);
                const td = document.createElement('td'); td.className = 'matrix-cell'; td.dataset.value = String(value);
                const button = document.createElement('button'); button.type = 'button'; button.className = 'btn btn-sm w-100 matrix-drilldown ' + (value > 0 ? 'btn-outline-primary' : 'btn-link text-body-secondary');
                button.dataset.matrixType = type; button.dataset.row = rowName; button.dataset.column = column; button.dataset.value = String(value);
                button.textContent = value > 0 ? `${value}%` : '·';
                button.setAttribute('aria-label', `${rowName}, ${column}: ${value > 0 ? value + '%' : l('empty')}`);
                td.appendChild(button); row.appendChild(td);
            });
            tbody.appendChild(row);
        });
        table.appendChild(tbody); target.appendChild(table);
        addAlternativeList(target, type, matrix);
    }

    function addAlternativeList(target, type, matrix) {
        const details = document.createElement('details'); details.className = 'matrix-alternative mt-3 p-2 border rounded';
        const summary = document.createElement('summary'); summary.className = 'fw-semibold'; summary.textContent = locale === 'de' ? 'Alternative Listenansicht' : 'Alternative list view'; details.appendChild(summary);
        const list = document.createElement('div'); list.className = 'list-group list-group-flush mt-2';
        matrix.rows.forEach(row => matrix.columns.forEach(column => {
            const value = Number(matrix.values[row] && matrix.values[row][column] || 0);
            const button = document.createElement('button'); button.type = 'button'; button.className = 'list-group-item list-group-item-action matrix-drilldown';
            button.dataset.matrixType = type; button.dataset.row = row; button.dataset.column = column; button.dataset.value = String(value);
            button.innerHTML = `<div class="d-flex justify-content-between gap-2"><span>${escapeHtml(row)} → ${escapeHtml(column)}</span><strong>${value > 0 ? value + '%' : '—'}</strong></div>`;
            list.appendChild(button);
        }));
        details.appendChild(list); target.appendChild(details);
    }

    function openCellDetail(type, row, column, value) {
        const detail = describeRelationship(type, row, column, value);
        const body = document.getElementById('cellDetailBody'); body.textContent = '';
        const dl = document.createElement('dl'); dl.className = 'portfolio-card-meta';
        addDefinition(dl, l('row'), row); addDefinition(dl, l('column'), column); addDefinition(dl, l('value'), value > 0 ? `${value}%` : '—');
        Object.entries(detail.metadata).forEach(([key, metadataValue]) => addDefinition(dl, key, metadataValue));
        body.appendChild(dl);
        if (value <= 0) { const warning = document.createElement('div'); warning.className = 'alert alert-secondary mt-3'; warning.textContent = l('relationAbsent'); body.appendChild(warning); }
        if (detail.requirementId) {
            const link = document.createElement('a'); link.className = 'btn btn-primary mt-3'; link.href = `/projects/${projectId}/requirements/${detail.requirementId}?lang=${locale}`; link.textContent = l('openRequirement'); body.appendChild(link);
        }
        bootstrap.Offcanvas.getOrCreateInstance(document.getElementById('cellDetail')).show();
    }

    function describeRelationship(type, row, column, value) {
        const metadata = {};
        const requirements = state.portfolio.requirements || [];
        const solutions = state.portfolio.solutions || [];
        if (type === 'taxonomy') {
            const requirement = requirements.find(item => item.requirementKey === row || item.requirementKey === column);
            const nodeCode = requirement && requirement.requirementKey === row ? column : row;
            const taxonomyNode = (state.portfolio.taxonomyNodes || []).find(node => node.nodeCode === nodeCode);
            metadata[l('source')] = taxonomyNode ? `${taxonomyNode.title || nodeCode}` : nodeCode;
            metadata[l('review')] = value > 0 ? (locale === 'de' ? 'Aus aktuellem Snapshot' : 'From current snapshot') : '—';
            return { metadata, requirementId: requirement && requirement.id };
        }
        if (type === 'solution') {
            const requirement = requirements.find(item => item.requirementKey === row || item.requirementKey === column);
            const solutionKey = requirement && requirement.requirementKey === row ? column : row;
            const solution = solutions.find(item => item.solution && item.solution.solutionKey === solutionKey);
            const link = solution && (solution.requirements || []).find(item => requirement && item.requirementId === requirement.id);
            metadata[l('review')] = link ? humanize(link.reviewStatus) : '—'; metadata[l('evidence')] = link && link.evidence || '—';
            return { metadata, requirementId: requirement && requirement.id };
        }
        const solution = solutions.find(item => item.solution && (item.solution.solutionKey === row || item.solution.solutionKey === column));
        const productKey = solution && solution.solution.solutionKey === row ? column : row;
        const candidate = solution && (solution.productCandidates || []).find(item => item.product && item.product.productKey === productKey);
        metadata[l('review')] = candidate ? humanize(candidate.reviewStatus) : '—';
        metadata[l('selected')] = candidate ? humanize(candidate.selectionStatus) : '—';
        metadata[l('source')] = candidate && candidate.product.sourceReference || '—';
        return { metadata, requirementId: null };
    }

    function exportCurrent(format) {
        const type = state.activeType; const matrix = filteredMatrix(type);
        const definition = matrixDefinitions[type];
        if (format === 'json') return download(`${state.project.projectKey}-${type}-matrix.json`, 'application/json', JSON.stringify({ projectId, projectKey: state.project.projectKey, matrixType: type, ...matrix }, null, 2));
        const rows = [['row', ...matrix.columns]];
        matrix.rows.forEach(row => rows.push([row, ...matrix.columns.map(column => matrix.values[row] && matrix.values[row][column] || 0)]));
        download(`${state.project.projectKey}-${type}-matrix.csv`, 'text/csv;charset=utf-8', rows.map(values => values.map(csvCell).join(',')).join('\n'));
        document.getElementById('matrixLive').textContent = `${definition.title} exported`;
    }

    function csvCell(value) { const text = String(value == null ? '' : value); return /[",\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text; }
    function download(filename, contentType, content) { const blob = new Blob([content], { type: contentType }); const url = URL.createObjectURL(blob); const link = document.createElement('a'); link.href = url; link.download = filename; document.body.appendChild(link); link.click(); link.remove(); URL.revokeObjectURL(url); }
    function api() { if (!window.TaxonomyPortfolioApi) throw new Error('Portfolio API boundary is not available'); return window.TaxonomyPortfolioApi; }
    function addDefinition(target, term, value) { const dt = document.createElement('dt'); dt.textContent = term; const dd = document.createElement('dd'); dd.textContent = value == null ? '—' : value; target.append(dt, dd); }
    function setBusy(active) { document.getElementById('matrixBusy').classList.toggle('d-none', !active); }
    function showError(error) { const target = document.getElementById('matrixError'); target.textContent = error?.message || l('failed'); target.classList.remove('d-none'); target.focus(); }
    function humanize(value) { return String(value || '—').toLowerCase().replaceAll('_', ' ').replace(/\b\w/g, character => character.toUpperCase()); }
    function escapeHtml(value) { return window.TaxonomyUtils.escapeHtml(value); }
})();
