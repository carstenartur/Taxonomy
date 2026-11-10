/* Read-only evidence of one frozen preview, using the working editor's scene renderer. */
window.TaxonomyArchitectureReconciliation = (function () {
    'use strict';
    var parts = ['base', 'local', 'incoming', 'proposed'];
    function t(key) { return window.TaxonomyI18n.t.apply(null, arguments); }
    function node(tag, text, className) {
        var result = document.createElement(tag);
        if (text != null) result.textContent = text;
        if (className) result.className = className;
        return result;
    }
    function render(container, preview) {
        if (!preview.fingerprint || !preview.comparison || !window.ArchitectureEditorRenderer) {
            throw new Error(t('lifecycle.reconcile.unavailable'));
        }
        var comparison = preview.comparison, renderers = [], rows = [], disposed = false;
        var root = node('section', null, 'taxonomy-reconciliation');
        root.dataset.reconciliationPreview = ''; root.dataset.previewFingerprint = preview.fingerprint;
        root.append(node('h3', t('lifecycle.reconcile.comparison')), node('p', t('lifecycle.reconcile.explanation')));
        var scenes = node('div', null, 'taxonomy-reconciliation__scenes'); root.append(scenes);
        function select(blockId) {
            rows.forEach(function (entry) { entry.row.setAttribute('aria-selected', String(entry.blockId === blockId)); });
            var id = blockId && blockId.startsWith('element:') ? blockId.slice('element:'.length) : null;
            renderers.forEach(function (entry) { entry.renderer.select(id && entry.ids.has(id) ? id : null); });
        }
        parts.forEach(function (part) {
            var panel = node('section', null, 'taxonomy-reconciliation__scene');
            panel.append(node('h4', t('lifecycle.reconcile.version.' + part)));
            scenes.append(panel);
            var scene = comparison.scenes[part];
            if (!scene) { panel.append(node('p', t('lifecycle.reconcile.proposedUnavailable'))); return; }
            var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
            svg.dataset.reconciliationScene = part;
            // The existing editor adapter uses this viewport; the shared scene bounds drive its fit.
            svg.setAttribute('viewBox', '0 0 1000 420');
            svg.setAttribute('role', 'group'); svg.setAttribute('aria-label', t('lifecycle.reconcile.version.' + part));
            svg.setAttribute('tabindex', '0');
            panel.append(svg);
            if (!(scene.nodes || []).length) panel.append(node('p', t('lifecycle.reconcile.noElements')));
            var count = node('p'); count.setAttribute('role', 'status'); panel.append(count);
            var renderer = window.ArchitectureEditorRenderer(svg, function (id) { select(id ? 'element:' + id : null); },
                function (edge) { select('relation:' + edge.sourceId + ' ' + edge.relationType + ' ' + edge.targetId); },
                function (visible, total) { count.textContent = t('lifecycle.reconcile.visibleElements', visible, total); });
            renderers.push({renderer:renderer, ids:new Set((scene.nodes || []).map(function (item) { return item.id; }))});
            renderer.render(scene, null, comparison.typeRoots);
            var source = node('details');
            source.append(node('summary', t('lifecycle.reconcile.source')), node('pre', preview[part === 'proposed' ? 'mergedDsl' : part + 'Dsl'] || ''));
            panel.append(source);
        });
        var details = node('div', null, 'taxonomy-reconciliation__properties');
        var table = node('table'), head = node('thead'), header = node('tr'), body = node('tbody');
        body.dataset.reconciliationProperties = '';
        ['object', 'property', 'category'].concat(parts).forEach(function (column) {
            var cell = node('th', t(parts.includes(column) ? 'lifecycle.reconcile.version.' + column : 'lifecycle.reconcile.' + column));
            cell.scope = 'col'; header.append(cell);
        });
        head.append(header); table.append(head, body); details.append(table); root.append(details);
        (comparison.properties || []).forEach(function (property) {
            var row = node('tr'); row.dataset.blockId = property.blockId; row.dataset.reconciliationCategory = property.category;
            row.setAttribute('aria-selected', 'false');
            var identity = node('td'), focus = node('button', property.blockId); focus.type = 'button';
            focus.addEventListener('click', function () { select(property.blockId); }); identity.append(focus);
            row.append(identity, node('td', property.property), node('td', t('lifecycle.reconcile.category.' + property.category)));
            parts.forEach(function (part) {
                var values = property[part];
                row.append(node('td', values == null ? t('lifecycle.reconcile.notProposed')
                    : values.length ? values.join('\n') : t('lifecycle.reconcile.absent')));
            });
            body.append(row); rows.push({row:row, blockId:property.blockId});
        });
        if (!rows.length) root.append(node('p', t('lifecycle.reconcile.noProperties')));
        container.replaceChildren(root);
        // Fit after insertion so the shared renderer observes the real dialog dimensions.
        renderers.forEach(function (entry) { entry.renderer.fit(); });
        return {destroy:function () {
            if (disposed) return; disposed = true;
            renderers.forEach(function (entry) { entry.renderer.destroy(); }); root.remove();
        }};
    }
    return {render:render};
}());
