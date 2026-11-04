/* Snapshot data and controls adapter. The shared diagram owns all rendering and interaction. */
(function () {
    'use strict';

    const layerColors = Object.freeze({
        Capabilities: '#cfe2ff',
        'Business Processes': '#d1e7dd',
        'Business Roles': '#d1e7dd',
        'Core Services': '#fff3cd',
        'COI Services': '#fff3cd',
        Services: '#fff3cd',
        'User Applications': '#e9d8fd',
        Applications: '#e9d8fd',
        'Information Products': '#f8d7da',
        'Communications Services': '#cff4fc'
    });

    const relationColors = Object.freeze({
        SUPPORTS: '#27834a',
        REALIZES: '#326fbd',
        REALIZED_BY: '#326fbd',
        USES: '#b66b00',
        REQUIRES: '#c0392b',
        DEPENDS_ON: '#7d3c98',
        FULFILLS: '#218c53',
        CONSUMES: '#b95f12',
        ASSIGNED_TO: '#148f77',
        PRODUCES: '#2874a6',
        COMMUNICATES_WITH: '#76448a',
        CONTAINS: '#65727e',
        RELATED_TO: '#6c757d'
    });

    // Display only. The frozen graph keeps its unrounded normalized relevance.
    function relevanceText(value) {
        if (typeof value !== 'number' || !Number.isFinite(value) || value < 0 || value > 1) return '—';
        const percentage = value * 100;
        if (percentage > 0 && percentage < 0.000001) return '<0.000001';
        return String(Number(percentage.toFixed(6)));
    }

    function colorForLayer(type) {
        return layerColors[type] || '#e9ecef';
    }

    function colorForRelation(type) {
        return relationColors[type] || '#6c757d';
    }

    function computeHopDistances(scene) {
        const adjacency = new Map();
        scene.nodes.forEach(function (node) { adjacency.set(node.id, []); });
        scene.edges.forEach(function (edge) {
            if (adjacency.has(edge.sourceId) && adjacency.has(edge.targetId)) {
                adjacency.get(edge.sourceId).push(edge.targetId);
                adjacency.get(edge.targetId).push(edge.sourceId);
            }
        });

        const distances = new Map();
        const queue = [];
        scene.nodes.forEach(function (node) {
            if (node.anchor) {
                distances.set(node.id, 0);
                queue.push(node.id);
            }
        });
        let queueIndex = 0;
        while (queueIndex < queue.length) {
            const current = queue[queueIndex++];
            const distance = distances.get(current) + 1;
            adjacency.get(current).forEach(function (neighbor) {
                if (!distances.has(neighbor)) {
                    distances.set(neighbor, distance);
                    queue.push(neighbor);
                }
            });
        }
        scene.nodes.forEach(function (node) {
            if (!distances.has(node.id)) {
                distances.set(node.id, node.anchor ? 0 : Math.max(1, node.depth || 1));
            }
        });
        return distances;
    }

    function buildLayerGroups(nodes) {
        const groups = new Map();
        nodes.forEach(function (node) {
            if (!groups.has(node.layer)) groups.set(node.layer, []);
            groups.get(node.layer).push(node);
        });
        return Array.from(groups.entries())
            .sort(function (left, right) { return left[0] - right[0]; })
            .map(function (entry, index) {
                const layerNodes = entry[1].slice().sort(function (left, right) {
                    return left.y - right.y || left.id.localeCompare(right.id);
                });
                const labels = Array.from(new Set(layerNodes.map(function (node) {
                    return node.type;
                })));
                const minimumX = Math.min.apply(null, layerNodes.map(function (node) { return node.x; }));
                const maximumX = Math.max.apply(null, layerNodes.map(function (node) {
                    return node.x + node.width;
                }));
                return {
                    number: entry[0],
                    index: index,
                    label: labels.join(' / '),
                    nodes: layerNodes,
                    x: minimumX,
                    width: maximumX - minimumX,
                    color: colorForLayer(layerNodes[0].type)
                };
            });
    }

    function calculateBounds(nodes, groups) {
        if (nodes.length === 0) {
            return {x: 0, y: 0, width: 760, height: 420};
        }
        const minimumX = Math.min.apply(null, nodes.map(function (node) { return node.x; }));
        const maximumX = Math.max.apply(null, nodes.map(function (node) {
            return node.x + node.width;
        }));
        const minimumY = Math.min.apply(null, nodes.map(function (node) { return node.y; }));
        const maximumY = Math.max.apply(null, nodes.map(function (node) {
            return node.y + node.height;
        }));
        const headerTop = Math.max(4, minimumY - 44);
        const left = Math.min(minimumX - 14, groups.length ? groups[0].x - 14 : minimumX - 14);
        return {
            x: left,
            y: headerTop,
            width: maximumX - left + 18,
            height: maximumY - headerTop + 20,
            contentTop: minimumY,
            contentBottom: maximumY
        };
    }


    // Adapt the persisted scene without changing its authoritative geometry or metadata.
    function prepareScene(data, translate) {
        const t = translate || window.TaxonomyI18n.t;
        if (!data || !data.scene || !Array.isArray(data.scene.nodes)) {
            throw new Error(t('workbench.ui.noScene'));
        }
        const common = window.TaxonomyArchitectureDiagram;
        const source = data.scene;
        const edges = Array.isArray(source.edges) ? source.edges : [];
        const hops = computeHopDistances({ nodes: source.nodes, edges: edges });
        const nodes = source.nodes.map(function (node) {
            const color = colorForLayer(node.type);
            const textColor = common.readableTextColor(color);
            const metadata = (data.elements || {})[node.id] || {};
            return Object.assign({}, node, {
                context: node.context === undefined ? !node.anchor : Boolean(node.context),
                searchText: [node.id, node.label, node.type].join(' '),
                prefix: 'architecture',
                className: 'architecture-node' + (node.anchor ? ' is-anchor' : ''),
                color: color,
                textColor: textColor,
                ariaLabel: t('workbench.ui.nodeLabel', node.id, node.label, node.type, relevanceText(node.relevance)),
                score: (node.anchor ? '★ ' : '') + relevanceText(node.relevance) + '%',
                scoreFill: common.rgba(textColor, textColor === '#ffffff' ? 0.18 : 0.12),
                titleLines: common.wrapLabel(node.label, 32, 2),
                meta: (node.anchor ? t('workbench.ui.directMatch') : t('workbench.ui.hop', hops.get(node.id))) + ' · ' + node.type,
                tooltip: node.id + ' — ' + node.label + '\n' + node.type + '\n' + relevanceText(node.relevance) + '%'
                    + (metadata.presenceReason ? '\n' + metadata.presenceReason : '')
            });
        });
        return Object.assign({}, source, {
            nodes: nodes,
            hasAnchors: nodes.some(function (node) { return node.anchor; }),
            edges: edges.map(function (edge, index) {
                return Object.assign({}, edge, {
                    id: edge.id || 'edge-' + index + '-' + edge.sourceId + '-' + edge.targetId,
                    source: edge.sourceId,
                    target: edge.targetId,
                    prefix: 'architecture',
                    color: colorForRelation(edge.relationType),
                    ariaLabel: t('workbench.ui.edgeLabel', edge.relationType, edge.sourceId, edge.targetId),
                    tooltip: edge.sourceId + ' → ' + edge.targetId + '\n' + edge.relationType,
                    label: edge.relationType
                });
            })
        });
    }

    function layoutScene(model) {
        const common = window.TaxonomyArchitectureDiagram;
        const groups = buildLayerGroups(model.nodes);
        const bounds = calculateBounds(model.nodes, groups);
        const nodeById = new Map(model.nodes.map(function (node) { return [node.id, node]; }));
        const lanes = common.relationshipLanes(model.edges);
        return {
            nodes: model.nodes,
            edges: model.edges.map(function (edge) {
                const lane = lanes.get(edge.id) || 0;
                return Object.assign({}, edge, {
                    path: common.edgePath(nodeById.get(edge.sourceId), nodeById.get(edge.targetId), lane, edge),
                    labelX: (edge.sourceX + edge.targetX) / 2,
                    labelY: (edge.sourceY + edge.targetY) / 2 + lane * 12 - 6
                });
            }),
            layers: groups.map(function (layer) {
                return {
                    x: layer.x,
                    y: bounds.y,
                    width: layer.width,
                    height: bounds.contentBottom + 12 - bounds.y,
                    color: layer.color,
                    backgroundFill: common.rgba(layer.color, 0.08),
                    headerHeight: 34,
                    textColor: common.readableTextColor(layer.color),
                    label: common.wrapLabel(layer.label, 24, 1)[0],
                    count: layer.nodes.length,
                    labelOffsetY: 29
                };
            }),
            bounds: bounds
        };
    }

    function mount(root) {
    if (root.__architectureSnapshot) return root.__architectureSnapshot;
    let active = false, generation = 0, request = null, activeSource = {}, sourceKey = null;
    const retained = new Map();
    function el(id) { return root.querySelector('#' + id); }
    function t() { return window.TaxonomyI18n.t.apply(null, arguments); }

    const body = root;
    let projectId = body.dataset.projectId;
    let snapshotId = body.dataset.snapshotId;
    const workbench = el('architectureWorkbench');
    const canvas = el('architectureCanvasShell');
    const svg = el('architectureCanvas');
    const status = el('architectureStatus');
    const visibleStatus = el('architectureVisibleStatus');
    const alertBox = el('architectureAlert');
    const details = el('architectureDetails');
    const detailHint = el('detailHint');
    const searchInput = el('architectureSearch');
    const clearSearch = el('clearArchitectureSearch');
    const contextCheckbox = el('showArchitectureContext');
    const overviewButton = el('architectureOverview');
    const focusButton = el('architectureFocus');
    const fitButton = el('fitArchitecture');
    const zoomInButton = el('zoomArchitectureIn');
    const zoomOutButton = el('zoomArchitectureOut');
    const fullscreenButton = el('fullscreenArchitecture');

    const policyTitles = Object.freeze({
        'archview.policy.title.defaultImpact': body.dataset.policyTitleDefaultImpact,
        'archview.policy.title.leafOnly': body.dataset.policyTitleLeafOnly,
        'archview.policy.title.clustering': body.dataset.policyTitleClustering,
        'archview.policy.title.trace': body.dataset.policyTitleTrace
    });

    let projection = null;
    let scene = null;
    let nodeById = new Map();
    let edgeById = new Map();
    let diagramController = null;
    let pendingState = null;
    let publishedSelection = null;

    function setStatus(message) {
        status.textContent = message;
    }

    function showError(error) {
        if (!active) return;
        alertBox.textContent = error instanceof Error ? error.message : String(error);
        alertBox.classList.remove('d-none');
        setStatus(t('workbench.ui.loadFailed'));
        searchInput.value = '';
        clearSearch.hidden = true;
        [
            searchInput,
            clearSearch,
            contextCheckbox,
            overviewButton,
            focusButton,
            fitButton,
            zoomInButton,
            zoomOutButton,
            fullscreenButton
        ].forEach(function (control) { control.disabled = true; });
    }

    function text(value, fallback) {
        return value === null || value === undefined || String(value).trim() === ''
            ? (fallback || '—')
            : String(value);
    }

    function resolvePolicyTitle(value) {
        const key = text(value, '');
        const localized = t(key);
        const translated = localized && localized !== key ? localized : policyTitles[key];
        if (translated && translated !== key) return translated;
        if (key.startsWith('archview.policy.title.')) {
            return key.substring('archview.policy.title.'.length)
                .replace(/([a-z])([A-Z])/g, '$1 $2')
                .replace(/^./, function (character) { return character.toUpperCase(); });
        }
        return key || 'Persisted Copilot architecture';
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

    function clearDetails() {
        details.replaceChildren();
        detailHint.classList.remove('d-none');
    }

    function relationSignature(edge) {
        return edge.sourceId + '|' + edge.targetId + '|' + edge.relationType;
    }

    function renderNodeDetails(node) {
        const metadata = projection.elements[node.id] || {};
        detailRows([
            [t('workbench.ui.detail.element'), node.id],
            [t('workbench.ui.detail.title'), node.label],
            [t('workbench.ui.detail.layer'), node.type],
            [t('workbench.ui.detail.relevance'), relevanceText(node.relevance) + '%'],
            [t('workbench.ui.detail.direct_score'), metadata.directScore],
            [t('workbench.ui.detail.anchor'), node.anchor ? t('ui.yes') : t('ui.no')],
            [t('workbench.ui.detail.origin'), window.TaxonomyI18n.formatEnum(metadata.mappingOrigin)],
            [t('workbench.ui.detail.review'), window.TaxonomyI18n.formatEnum(metadata.reviewStatus)],
            [t('workbench.ui.detail.action'), window.TaxonomyI18n.formatEnum(metadata.actionStatus)],
            [t('workbench.ui.detail.reason'), metadata.presenceReason],
            [t('workbench.ui.detail.evidence'), metadata.actionEvidence],
            [t('workbench.ui.detail.hierarchy'), metadata.hierarchyPath],
            [t('workbench.ui.detail.decision'), metadata.decisionComment]
        ]);
    }

    function renderEdgeDetails(edge) {
        const metadata = projection.relations[relationSignature(edge)] || {};
        detailRows([
            [t('workbench.ui.detail.relationship'), edge.relationType],
            [t('workbench.ui.detail.source'), edge.sourceId],
            [t('workbench.ui.detail.target'), edge.targetId],
            [t('workbench.ui.detail.category'), edge.relationCategory],
            [t('workbench.ui.detail.relevance'), relevanceText(edge.relevance) + '%'],
            [t('workbench.ui.detail.origin'), window.TaxonomyI18n.formatEnum(metadata.relationOrigin)],
            [t('workbench.ui.detail.review'), window.TaxonomyI18n.formatEnum(metadata.reviewStatus)],
            [t('workbench.ui.detail.reason'), metadata.presenceReason],
            [t('workbench.ui.detail.decision'), metadata.decisionComment]
        ]);
    }

    function updateVisibleStatus(view) {
        if (view.state.searchQuery.trim()) {
            const visibleMatches = view.matches.filter(function (id) {
                return view.model.nodeIds.has(id);
            }).length;
            visibleStatus.textContent = t('workbench.ui.matching', view.matches.length, visibleMatches);
        } else {
            visibleStatus.textContent = t('workbench.ui.showing', view.model.nodes.length, scene.nodes.length, view.model.edges.length);
        }
    }

    function updateKpis(model) {
        function countLabel(count, singularKey, pluralKey) {
            return count + ' ' + t(count === 1 ? singularKey : pluralKey);
        }
        el('directKpi').textContent = model.nodes.filter(function (node) {
            return node.anchor;
        }).length + ' ' + t('workbench.ui.direct');
        el('elementKpi').textContent = countLabel(model.nodes.length,
            'workbench.ui.detail.element', 'workbench.ui.elements');
        el('relationKpi').textContent = countLabel(model.edges.length,
            'workbench.ui.detail.relationship', 'workbench.ui.relationships');
        el('layerKpi').textContent = countLabel(model.layers.length,
            'workbench.ui.detail.layer', 'workbench.ui.layers');
    }

    function onDiagramChange(view) {
        if (!active || !projection) return;
        const state = view.state;
        searchInput.value = state.searchQuery;
        clearSearch.hidden = !state.searchQuery;
        contextCheckbox.checked = state.showContext;
        overviewButton.setAttribute('aria-pressed', state.mode === 'overview' ? 'true' : 'false');
        focusButton.setAttribute('aria-pressed', state.mode === 'focus' ? 'true' : 'false');
        focusButton.disabled = !state.selectedNodeId;
        updateVisibleStatus(view);
        updateKpis(view.model);

        const selectedNode = nodeById.get(state.selectedNodeId);
        const selectedEdge = edgeById.get(state.selectedEdgeId);
        if (selectedNode) renderNodeDetails(selectedNode);
        else if (selectedEdge) renderEdgeDetails(selectedEdge);
        else clearDetails();

        window.TaxonomyArchitectureWorkflow?.showSnapshot(projection, state.selectedNodeId);
        if (publishedSelection !== state.selectedNodeId) {
            publishedSelection = state.selectedNodeId;
            document.dispatchEvent(new CustomEvent('taxonomy:architecture-selection', {
                detail: { kind: 'snapshot', elementId: state.selectedNodeId }
            }));
        }
    }

    function renderProvenance(data) {
        const visioButton = el('downloadArchitectureVisio');
        if (visioButton) visioButton.disabled = false;
        const model = data.modelName ? '/' + data.modelName : '';
        const created = data.snapshotCreatedAt
            ? ' · ' + new Date(data.snapshotCreatedAt).toLocaleString(window.TaxonomyI18n.getLocale())
            : '';
        el('architectureProvenance').textContent =
            'Snapshot ' + data.snapshotId
            + ' · ' + (data.snapshotStatus ? window.TaxonomyI18n.formatEnum(data.snapshotStatus) : t('workbench.ui.statusMissing'))
            + ' · ' + text(data.provider, t('workbench.ui.providerMissing')) + model
            + ' · ' + t('workbench.ui.branch') + ' ' + text(data.branchName, 'n/a')
            + ' · ' + t('workbench.ui.commit') + ' ' + text(data.commitSha, 'n/a').slice(0, 12)
            + created;
    }

    function renderWarnings(data) {
        const warnings = el('architectureWarnings');
        const section = el('architectureWarningsSection');
        warnings.replaceChildren();
        const values = Array.isArray(data.warnings) ? data.warnings : [];
        values.forEach(function (warning) {
            const item = document.createElement('li');
            item.textContent = warning;
            warnings.append(item);
        });
        section.classList.toggle('d-none', values.length === 0);
    }

    function publishContext() {
        if (!active || !projection) return;
        const data = projection;
        window.TaxonomyArchitectureWorkflow?.showSnapshot(data, diagramController?.getState().selectedNodeId || null);
        window.TaxonomyAppChrome?.updateContext({source:{projectId:data.projectId,projectKey:data.projectKey,
            requirementId:data.requirementId,requirementKey:data.requirementKey,requirementTitle:data.requirementTitle,
            snapshotId:data.snapshotId,requirementVersionId:data.exportProvenance?.requirementVersionId,
            requirementVersion:data.requirementVersionNumber,repositoryId:data.exportProvenance?.repositoryId,
            workspaceScopeKey:data.workspaceId || '__shared__',branch:data.branchName},editable:false}, 'snapshot');
    }
    function render(data, initialState, session) {
        scene = prepareScene(data, t);
        projection = data;
        nodeById = new Map(scene.nodes.map(function (node) { return [node.id, node]; }));
        edgeById = new Map(scene.edges.map(function (edge) { return [edge.id, edge]; }));
        alertBox.classList.add('d-none');

        if (diagramController) diagramController.destroy();
        diagramController = window.TaxonomyArchitectureDiagram.create(svg, {
            container: canvas,
            prefix: 'architecture',
            presentation: {
                viewportClass: 'architecture-viewport',
                selectedClass: 'is-selected',
                edgeLabelLimit: 12,
                highlightNeighbors: true
            },
            viewport: {
                minimumWidth: 320, minimumHeight: 300, fallbackWidth: 900, fallbackHeight: 540,
                minimumScale: 0.2, maximumScale: 4, fitPadding: 34, fitMaximumScale: 1.35,
                extendMinimumForFit: true
            },
            behavior: { focus: 'neighbors', clearOnBackground: true },
            layout: layoutScene,
            onChange: onDiagramChange,
            onFitState: function (fitState, scale) {
                workbench.dataset.fitState = fitState;
                if (Number.isFinite(scale)) workbench.dataset.fitScale = scale.toFixed(6);
            }
        });
        window.TaxonomySnapshotDiagram = diagramController;

        el('architectureTitle').textContent = resolvePolicyTitle(data.policyTitleKey || scene.title);
        renderProvenance(data);
        el('requirementText').textContent = data.requirementText || '';
        renderWarnings(data);
        contextCheckbox.disabled = !scene.hasAnchors;
        [searchInput, clearSearch, zoomInButton, zoomOutButton, overviewButton].forEach(function (button) {
            button.disabled = false;
        });
        fullscreenButton.disabled = false;
        fullscreenButton.hidden = typeof workbench.requestFullscreen !== 'function';
        diagramController.render(scene, initialState);
        pendingState = null;
        if (session) diagramController.restoreSession(Object.assign({}, session, { state: initialState }));
        else diagramController.fit();
        setExportsEnabled(true);
        publishContext();
        setStatus(t('workbench.ui.loaded', scene.nodes.length, scene.edges.length));
        document.dispatchEvent(new CustomEvent('taxonomy:architecture-diagram-updated'));
    }

    searchInput.addEventListener('input', function () {
        if (active) diagramController?.setSearch(searchInput.value);
    });
    searchInput.addEventListener('keydown', function (event) {
        if (!active || !diagramController) return;
        if (event.key === 'Escape') {
            diagramController.setSearch('');
            return;
        }
        if (event.key !== 'Enter') return;
        const first = diagramController.matchingNodeIds()[0];
        if (first) diagramController.select(first, null, { center: true, reveal: true });
    });
    clearSearch.addEventListener('click', function () {
        if (!active || !diagramController) return;
        diagramController.setSearch('');
        searchInput.focus();
    });
    contextCheckbox.addEventListener('change', function () {
        if (active) diagramController?.setContext(contextCheckbox.checked);
    });
    overviewButton.addEventListener('click', function () {
        if (active) diagramController?.unfocus();
    });
    focusButton.addEventListener('click', function () {
        if (!active || !diagramController) return;
        const state = diagramController.getState();
        if (!state.selectedNodeId) return;
        if (state.mode === 'focus') diagramController.unfocus();
        else diagramController.focus();
    });
    fitButton.addEventListener('click', function () {
        if (active) diagramController?.fit();
    });
    zoomInButton.addEventListener('click', function () {
        if (active) diagramController?.zoom(1.25);
    });
    zoomOutButton.addEventListener('click', function () {
        if (active) diagramController?.zoom(0.8);
    });
    fullscreenButton.addEventListener('click', function () {
        if (document.fullscreenElement === workbench) {
            document.exitFullscreen();
        } else if (workbench.requestFullscreen) {
            workbench.requestFullscreen();
        }
    });
    document.addEventListener('fullscreenchange', function () {
        if (!active) return;
        const full = document.fullscreenElement === workbench;
        fullscreenButton.title = full ? t('workbench.ui.exitFullscreen') : t('workbench.ui.fullscreen');
        fullscreenButton.setAttribute('aria-label', fullscreenButton.title);
        window.setTimeout(function () {
            if (active) diagramController?.fit();
        }, 0);
    });

    const exportOptions = el('architectureExportOptions');
    if (exportOptions) {
        const exportMenu = exportOptions.querySelector('.workbench-export-content');
        const exportTrigger = exportOptions.querySelector('summary');
        function positionExportMenu() {
            if (!exportOptions.open || !exportMenu) return;
            const margin = 16, gap = 8;
            const trigger = exportTrigger.getBoundingClientRect();
            const below = Math.max(0, window.innerHeight - trigger.bottom - gap - margin);
            const above = Math.max(0, trigger.top - gap - margin);
            const openAbove = below < Math.min(exportMenu.scrollHeight, window.innerHeight * 0.7) && above > below;
            exportMenu.style.setProperty('--workbench-export-preferred-height', Math.max(44, Math.min(window.innerHeight * 0.7,
                window.innerHeight - margin * 2, openAbove ? above : below)) + 'px');
            const menu = exportMenu.getBoundingClientRect();
            exportMenu.style.setProperty('--workbench-export-preferred-left', Math.max(margin,
                Math.min(trigger.left, window.innerWidth - menu.width - margin)) + 'px');
            const top = openAbove ? trigger.top - gap - menu.height : trigger.bottom + gap;
            exportMenu.style.setProperty('--workbench-export-preferred-top', Math.max(margin,
                Math.min(top, window.innerHeight - menu.height - margin)) + 'px');
        }
        exportOptions.addEventListener('toggle', positionExportMenu);
        window.addEventListener('resize', positionExportMenu);
        document.addEventListener('scroll', function (event) {
            if (!exportMenu || !exportMenu.contains(event.target)) positionExportMenu();
        }, true);
        exportOptions.addEventListener('keydown', function (event) {
            if (event.key === 'Escape' && exportOptions.open) {
                event.preventDefault();
                exportOptions.open = false;
                exportOptions.querySelector('summary').focus();
            }
        });
        document.addEventListener('click', function (event) {
            if (exportOptions.open && !exportOptions.contains(event.target)) {
                exportOptions.open = false;
            }
        });
        exportOptions.addEventListener('click', function (event) {
            if (event.target.closest('button:not(:disabled)')) {
                exportOptions.open = false;
                // Allow an export action that opens a dialog to move focus there.
                if (exportOptions.contains(document.activeElement)) {
                    exportOptions.querySelector('summary').focus();
                }
            }
        });
    }

    el('downloadArchitectureSvg').addEventListener('click', function () {
        if (active && projection) window.location.assign(ArchitectureWorkbenchApi.svgUrl(projectId, snapshotId, getSource()));
    });

    el('downloadArchitecturePdf').addEventListener('click', function () {
        if (active && projection) window.location.assign(ArchitectureWorkbenchApi.pdfUrl(projectId, snapshotId, getSource()));
    });

    function setExportsEnabled(enabled) {
        // The print adapter observes Fit readiness before cloning the source SVG.
        fitButton.disabled = !enabled;
        ['downloadArchitectureSvg','downloadArchitecturePdf','downloadArchitectureWord','downloadDecisionWord','downloadArchitectureArchiMate','downloadArchitectureVisio'].forEach(function(id) { if(el(id))el(id).disabled=!enabled; });
    }
    function normalizeSource(source) {
        const normalized = Object.assign({}, source, {kind:'snapshot'});
        ['repositoryId','branch','projectId','snapshotId'].forEach(function(key) {
            if (normalized[key] !== undefined && normalized[key] !== null) normalized[key] = String(normalized[key]);
        });
        if (source.workspaceScopeKey !== undefined || source.workspaceId !== undefined) {
            const workspace = source.workspaceScopeKey || source.workspaceId || '__shared__';
            normalized.workspaceScopeKey = String(workspace);
            normalized.workspaceId = workspace === '__shared__' ? '' : String(workspace);
        }
        return normalized;
    }
    function keyForSource(source) {
        const normalized = normalizeSource(source);
        return JSON.stringify(['repositoryId','workspaceScopeKey','branch','projectId','snapshotId'].map(function(key) { return normalized[key] ?? ''; }));
    }
    function getSource() {
        const data = projection;
        const source = Object.assign({}, activeSource, {kind:'snapshot',element:diagramController?.getState().selectedNodeId || pendingState?.selectedNodeId || null});
        if (data) {
            if (data.exportProvenance?.repositoryId != null) source.repositoryId = data.exportProvenance.repositoryId;
            if (Object.prototype.hasOwnProperty.call(data,'workspaceId')) {
                source.workspaceId = data.workspaceId || '';
                source.workspaceScopeKey = data.workspaceId || '__shared__';
            }
            if (data.branchName != null) source.branch = data.branchName;
            if (data.projectId != null) source.projectId = data.projectId;
            if (data.snapshotId != null) source.snapshotId = data.snapshotId;
            source.requirementId = data.requirementId;
            source.requirementVersionId = data.exportProvenance?.requirementVersionId;
        }
        return normalizeSource(source);
    }
    function remember() {
        if (!projection || !sourceKey || !diagramController) return;
        retained.set(sourceKey, {
            data: projection,
            session: diagramController.captureSession()
        });
        while (retained.size > 8) retained.delete(retained.keys().next().value);
    }

    async function activate(source) {
        const selectedSource = normalizeSource(source), key = keyForSource(selectedSource);
        if (key === sourceKey && projection && diagramController) {
            active = true;
            activeSource = selectedSource;
            diagramController.setActive(true);
            setExportsEnabled(true);
            window.TaxonomyArchitectureWorkflow?.setScope(getSource());
            if (selectedSource.element && selectedSource.element !== diagramController.getState().selectedNodeId) {
                diagramController.select(selectedSource.element, null, { center: true, reveal: true });
            }
            publishContext();
            return true;
        }
        remember();
        active = true;
        activeSource = selectedSource;
        projectId = selectedSource.projectId;
        snapshotId = selectedSource.snapshotId;
        sourceKey = key;
        body.dataset.projectId = projectId || '';
        body.dataset.snapshotId = snapshotId || '';
        window.TaxonomyArchitectureWorkflow?.setScope(selectedSource);
        const ticket = ++generation;
        request?.abort();
        const controller = new AbortController();
        request = controller;
        projection = null;
        scene = null;
        diagramController?.destroy();
        diagramController = null;
        window.TaxonomySnapshotDiagram = null;
        clearDetails();
        setExportsEnabled(false);
        alertBox.classList.add('d-none');
        setStatus(t('workbench.ui.loading'));
        pendingState = {
            selectedNodeId: selectedSource.element || null,
            selectedEdgeId: null,
            mode: 'overview',
            searchQuery: '',
            showContext: true
        };
        searchInput.value = '';
        clearSearch.hidden = true;
        contextCheckbox.checked = true;
        if (!projectId || !snapshotId) {
            showError(new Error(t('workbench.ui.missingScope')));
            return false;
        }
        try {
            await window.TaxonomyI18n.ready();
            if (!active || ticket !== generation) return false;
            const saved = retained.get(key);
            const data = saved?.data || await ArchitectureWorkbenchApi.load(
                selectedSource.projectId, selectedSource.snapshotId, selectedSource, controller.signal);
            if (!active || ticket !== generation) return false;
            const initialState = saved ? saved.session.state : pendingState;
            render(data, initialState, saved?.session);
            if (saved && selectedSource.element && selectedSource.element !== diagramController.getState().selectedNodeId) {
                diagramController.select(selectedSource.element, null, { center: true, reveal: true });
            }
            activeSource = getSource();
            sourceKey = keyForSource(activeSource);
            projectId = activeSource.projectId;
            snapshotId = activeSource.snapshotId;
            return true;
        } catch (error) {
            if (active && ticket === generation) showError(error);
            return false;
        }
    }

    const instance = {
        activate: activate,
        suspend: function () {
            remember();
            active = false;
            generation++;
            request?.abort();
            diagramController?.setActive(false);
            setExportsEnabled(false);
            if (exportOptions) exportOptions.open = false;
        },
        getSource: getSource,
        getData: function () { return projection; },
        getDiagram: function () { return diagramController; },
        select: function (id) {
            if (active && projection) diagramController?.select(id);
        }
    };
    root.__architectureSnapshot = instance;
    setExportsEnabled(false);
    return instance;
    }
    window.TaxonomyArchitectureSnapshot=Object.freeze({mount:mount, prepareScene:prepareScene, layoutScene:layoutScene});
    if(!document.getElementById('architectureWorkspace') && document.getElementById('architectureCanvas')) {
        const instance=mount(document.body),source={kind:'snapshot'},params=new URLSearchParams(location.search);
        // Standalone links accept the same source pins as the application workspace.
        ['repositoryId', 'workspaceId', 'workspaceScopeKey', 'branch', 'projectId', 'requirementId',
            'requirementVersionId', 'snapshotId', 'commit', 'revision', 'element', 'viewId', 'layout', 'layoutRevision',
            'sourceRepositoryId', 'sourceWorkspaceId', 'sourceWorkspaceScopeKey', 'sourceBranch', 'sourceProjectId',
            'sourceRequirementId', 'sourceRequirementVersionId', 'sourceSnapshotId', 'adoption'].forEach(function (key) {
                if (params.has(key)) source[key] = params.get(key);
            });
        source.projectId=source.projectId || document.body.dataset.projectId;
        source.snapshotId=source.snapshotId || document.body.dataset.snapshotId;
        instance.activate(source);
    }
}());
