/* Read-only architecture graph adapter. The server projection remains authoritative. */
(function () {
    'use strict';

    const body = document.body;
    const projectId = body.dataset.projectId;
    const snapshotId = body.dataset.snapshotId;
    const svg = d3.select('#architectureCanvas');
    const status = document.getElementById('architectureStatus');
    const alertBox = document.getElementById('architectureAlert');
    const details = document.getElementById('architectureDetails');
    const detailHint = document.getElementById('detailHint');
    let zoomBehavior = null;
    let projection = null;

    const layerColors = Object.freeze({
        Capabilities: '#dbeafe',
        'Business Processes': '#dcfce7',
        'Business Roles': '#dcfce7',
        'Core Services': '#fef3c7',
        'COI Services': '#fef3c7',
        Services: '#fef3c7',
        'User Applications': '#f3e8ff',
        Applications: '#f3e8ff',
        'Information Products': '#ffe4e6',
        'Communications Services': '#cffafe'
    });

    function setStatus(message) {
        status.textContent = message;
    }

    function showError(error) {
        alertBox.textContent = error instanceof Error ? error.message : String(error);
        alertBox.classList.remove('d-none');
        setStatus('Architecture could not be loaded.');
    }

    function text(value, fallback) {
        return value === null || value === undefined || String(value).trim() === ''
            ? (fallback || '—')
            : String(value);
    }

    function detailRows(rows) {
        details.replaceChildren();
        rows.forEach(function (row) {
            const term = document.createElement('dt');
            term.textContent = row[0];
            const description = document.createElement('dd');
            description.textContent = text(row[1]);
            details.append(term, description);
        });
        detailHint.classList.add('d-none');
    }

    function selectNode(node, element) {
        svg.selectAll('.is-selected').classed('is-selected', false);
        d3.select(element).classed('is-selected', true);
        const metadata = projection.elements[node.id] || {};
        detailRows([
            ['Element', node.id],
            ['Title', node.label],
            ['Layer', node.type],
            ['Relevance', Math.round(node.relevance * 100) + '%'],
            ['Direct score', metadata.directScore],
            ['Anchor', node.anchor ? 'Yes' : 'No'],
            ['Origin', metadata.mappingOrigin],
            ['Review', metadata.reviewStatus],
            ['Action', metadata.actionStatus],
            ['Reason', metadata.presenceReason],
            ['Evidence', metadata.actionEvidence],
            ['Hierarchy', metadata.hierarchyPath],
            ['Decision', metadata.decisionComment]
        ]);
    }

    function relationSignature(edge) {
        return edge.sourceId + '|' + edge.targetId + '|' + edge.relationType;
    }

    function selectEdge(edge, element) {
        svg.selectAll('.is-selected').classed('is-selected', false);
        d3.select(element).classed('is-selected', true);
        const metadata = projection.relations[relationSignature(edge)] || {};
        detailRows([
            ['Relationship', edge.relationType],
            ['Source', edge.sourceId],
            ['Target', edge.targetId],
            ['Category', edge.relationCategory],
            ['Relevance', Math.round(edge.relevance * 100) + '%'],
            ['Origin', metadata.relationOrigin],
            ['Review', metadata.reviewStatus],
            ['Reason', metadata.presenceReason],
            ['Decision', metadata.decisionComment]
        ]);
    }

    function keyboardActivate(handler) {
        return function (event, datum) {
            if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault();
                handler(datum, this);
            }
        };
    }

    function renderLegend(nodes) {
        const legend = document.getElementById('architectureLegend');
        legend.replaceChildren();
        Array.from(new Set(nodes.map(function (node) { return node.type; }))).sort()
            .forEach(function (type) {
                const item = document.createElement('span');
                item.className = 'legend-item';
                const swatch = document.createElement('span');
                swatch.className = 'legend-swatch';
                swatch.style.background = layerColors[type] || '#f1f5f9';
                const label = document.createElement('span');
                label.textContent = type;
                item.append(swatch, label);
                legend.append(item);
            });
    }

    function wrapLabel(selection, label) {
        const words = text(label, 'Unnamed element').split(/\s+/);
        const lines = [];
        let current = '';
        words.forEach(function (word) {
            if (current && (current + ' ' + word).length > 32 && lines.length < 1) {
                lines.push(current);
                current = word;
            } else {
                current = current ? current + ' ' + word : word;
            }
        });
        if (current) lines.push(current);
        lines.slice(0, 2).forEach(function (line, index) {
            selection.append('tspan')
                .attr('x', 14)
                .attr('dy', index === 0 ? 0 : 17)
                .text(line);
        });
    }

    function render(data) {
        projection = data;
        const scene = data.scene;
        svg.attr('viewBox', '0 0 ' + scene.width + ' ' + scene.height);
        svg.selectAll('*').remove();

        const defs = svg.append('defs');
        defs.append('marker')
            .attr('id', 'architectureArrow')
            .attr('markerWidth', 10)
            .attr('markerHeight', 8)
            .attr('refX', 9)
            .attr('refY', 4)
            .attr('orient', 'auto')
            .append('path')
            .attr('d', 'M0,0 L10,4 L0,8 z')
            .attr('fill', '#586174');

        const viewport = svg.append('g').attr('class', 'architecture-viewport');

        const edges = viewport.append('g')
            .attr('aria-label', 'Architecture relationships')
            .selectAll('g')
            .data(scene.edges)
            .join('g')
            .attr('class', 'architecture-edge')
            .attr('role', 'button')
            .attr('tabindex', 0)
            .attr('aria-label', function (edge) {
                return edge.sourceId + ' ' + edge.relationType + ' ' + edge.targetId;
            })
            .on('click', function (event, edge) { selectEdge(edge, this); })
            .on('keydown', keyboardActivate(selectEdge));

        edges.append('line')
            .attr('x1', function (edge) { return edge.sourceX; })
            .attr('y1', function (edge) { return edge.sourceY; })
            .attr('x2', function (edge) { return edge.targetX; })
            .attr('y2', function (edge) { return edge.targetY; })
            .attr('stroke', '#586174')
            .attr('stroke-width', function (edge) { return 1.2 + edge.relevance * 1.8; })
            .attr('marker-end', 'url(#architectureArrow)');

        edges.append('text')
            .attr('x', function (edge) { return (edge.sourceX + edge.targetX) / 2; })
            .attr('y', function (edge) { return (edge.sourceY + edge.targetY) / 2 - 6; })
            .attr('text-anchor', 'middle')
            .attr('font-size', 11)
            .attr('fill', '#394150')
            .text(function (edge) { return edge.relationType; });

        const nodes = viewport.append('g')
            .attr('aria-label', 'Architecture elements')
            .selectAll('g')
            .data(scene.nodes)
            .join('g')
            .attr('class', 'architecture-node')
            .attr('transform', function (node) { return 'translate(' + node.x + ',' + node.y + ')'; })
            .attr('role', 'button')
            .attr('tabindex', 0)
            .attr('aria-label', function (node) {
                return node.id + ' ' + node.label + ', ' + node.type;
            })
            .on('click', function (event, node) { selectNode(node, this); })
            .on('keydown', keyboardActivate(selectNode));

        nodes.append('rect')
            .attr('width', function (node) { return node.width; })
            .attr('height', function (node) { return node.height; })
            .attr('rx', 10)
            .attr('fill', function (node) { return layerColors[node.type] || '#f1f5f9'; })
            .attr('stroke', function (node) { return node.anchor ? '#172554' : '#64748b'; })
            .attr('stroke-width', function (node) { return node.anchor ? 3 : 1.3; });

        nodes.append('text')
            .attr('x', 14)
            .attr('y', 22)
            .attr('font-size', 11)
            .attr('font-weight', 'bold')
            .attr('fill', '#334155')
            .text(function (node) { return node.id; });

        nodes.append('text')
            .attr('x', function (node) { return node.width - 12; })
            .attr('y', 18)
            .attr('text-anchor', 'end')
            .attr('font-size', 10)
            .attr('fill', '#475569')
            .text(function (node) {
                return node.type + ' · ' + Math.round(node.relevance * 100) + '%';
            });

        nodes.append('text')
            .attr('x', 14)
            .attr('y', 44)
            .attr('font-size', 13)
            .attr('fill', '#0f172a')
            .each(function (node) { wrapLabel(d3.select(this), node.label); });

        zoomBehavior = d3.zoom()
            .scaleExtent([0.2, 4])
            .on('zoom', function (event) {
                viewport.attr('transform', event.transform);
            });
        svg.call(zoomBehavior);

        renderLegend(scene.nodes);
        document.getElementById('architectureTitle').textContent = scene.title;
        document.getElementById('architectureProvenance').textContent =
            'Snapshot ' + data.snapshotId
            + ' · ' + text(data.provider, 'provider n/a')
            + ' · branch ' + text(data.branchName, 'n/a')
            + ' · commit ' + text(data.commitSha, 'n/a').slice(0, 12);
        document.getElementById('requirementText').textContent = data.requirementText;

        const warnings = document.getElementById('architectureWarnings');
        const warningSection = document.getElementById('architectureWarningsSection');
        warnings.replaceChildren();
        if (data.warnings.length > 0) {
            data.warnings.forEach(function (warning) {
                const item = document.createElement('li');
                item.textContent = warning;
                warnings.append(item);
            });
            warningSection.classList.remove('d-none');
        }

        setStatus('Loaded ' + scene.nodes.length + ' elements and '
            + scene.edges.length + ' relationships from persisted snapshot.');
    }

    document.getElementById('fitArchitecture').addEventListener('click', function () {
        if (zoomBehavior) {
            svg.transition().duration(180).call(zoomBehavior.transform, d3.zoomIdentity);
        }
    });

    document.getElementById('downloadArchitectureSvg').addEventListener('click', function () {
        window.location.assign(ArchitectureWorkbenchApi.svgUrl(projectId, snapshotId));
    });

    document.getElementById('downloadArchitecturePdf').addEventListener('click', function () {
        window.location.assign(ArchitectureWorkbenchApi.pdfUrl(projectId, snapshotId));
    });

    if (!projectId || !snapshotId) {
        showError(new Error(
            'Open this workbench with both projectId and snapshotId. '
            + 'No arbitrary page will be exported as a fallback.'));
        return;
    }

    ArchitectureWorkbenchApi.load(projectId, snapshotId)
        .then(render)
        .catch(showError);
}());
