/* Read-only architecture graph adapter. The persisted server projection remains authoritative. */
(function () {
    'use strict';

    const SVG_NS = 'http://www.w3.org/2000/svg';
    const body = document.body;
    const projectId = body.dataset.projectId;
    const snapshotId = body.dataset.snapshotId;
    const workbench = document.getElementById('architectureWorkbench');
    const canvas = document.getElementById('architectureCanvasShell');
    const svg = document.getElementById('architectureCanvas');
    const status = document.getElementById('architectureStatus');
    const visibleStatus = document.getElementById('architectureVisibleStatus');
    const alertBox = document.getElementById('architectureAlert');
    const details = document.getElementById('architectureDetails');
    const detailHint = document.getElementById('detailHint');
    const searchInput = document.getElementById('architectureSearch');
    const clearSearch = document.getElementById('clearArchitectureSearch');
    const contextCheckbox = document.getElementById('showArchitectureContext');
    const overviewButton = document.getElementById('architectureOverview');
    const focusButton = document.getElementById('architectureFocus');
    const fitButton = document.getElementById('fitArchitecture');
    const zoomInButton = document.getElementById('zoomArchitectureIn');
    const zoomOutButton = document.getElementById('zoomArchitectureOut');
    const fullscreenButton = document.getElementById('fullscreenArchitecture');
    const reducedMotion = window.matchMedia
        && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    const policyTitles = Object.freeze({
        'archview.policy.title.defaultImpact': body.dataset.policyTitleDefaultImpact,
        'archview.policy.title.leafOnly': body.dataset.policyTitleLeafOnly,
        'archview.policy.title.clustering': body.dataset.policyTitleClustering,
        'archview.policy.title.trace': body.dataset.policyTitleTrace
    });

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

    let projection = null;
    let scene = null;
    let nodeById = new Map();
    let edgeById = new Map();
    let hopDistances = new Map();
    let svgSelection = null;
    let viewportSelection = null;
    let zoomBehavior = null;
    let resizeObserver = null;
    let resizeTimer = null;

    const state = {
        showContext: true,
        mode: 'overview',
        searchQuery: '',
        selectedNodeId: null,
        selectedEdgeId: null,
        visibleNodes: [],
        visibleEdges: [],
        visibleNodeIds: new Set(),
        nodeElements: new Map(),
        edgeElements: new Map(),
        layerGroups: [],
        bounds: null,
        autoFit: true
    };

    function setStatus(message) {
        status.textContent = message;
    }

    function showError(error) {
        alertBox.textContent = error instanceof Error ? error.message : String(error);
        alertBox.classList.remove('d-none');
        setStatus('Architecture could not be loaded.');
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
        const translated = policyTitles[key];
        if (translated && translated !== key) return translated;
        if (key.startsWith('archview.policy.title.')) {
            return key.substring('archview.policy.title.'.length)
                .replace(/([a-z])([A-Z])/g, '$1 $2')
                .replace(/^./, function (character) { return character.toUpperCase(); });
        }
        return key || 'Persisted Copilot architecture';
    }

    function createSvgElement(tagName, attributes) {
        const element = document.createElementNS(SVG_NS, tagName);
        Object.entries(attributes || {}).forEach(function (entry) {
            if (entry[1] !== undefined && entry[1] !== null) {
                element.setAttribute(entry[0], String(entry[1]));
            }
        });
        return element;
    }

    function appendSvgText(parent, className, x, y, value, fill) {
        const element = createSvgElement('text', {
            class: className,
            x: x,
            y: y,
            fill: fill
        });
        element.textContent = value;
        parent.appendChild(element);
        return element;
    }

    function wrapLabel(value, maximumCharacters, maximumLines) {
        const words = text(value, '').trim().split(/\s+/).filter(Boolean);
        if (words.length === 0) return [''];
        const lines = [];
        let current = '';
        words.forEach(function (word) {
            const candidate = current ? current + ' ' + word : word;
            if (candidate.length <= maximumCharacters || current.length === 0) {
                current = candidate;
            } else {
                lines.push(current);
                current = word;
            }
        });
        if (current) lines.push(current);
        if (lines.length > maximumLines) {
            lines.splice(maximumLines);
            const lastIndex = maximumLines - 1;
            lines[lastIndex] = lines[lastIndex]
                .substring(0, Math.max(1, maximumCharacters - 1))
                .replace(/[\s.,;:]+$/, '') + '…';
        }
        return lines;
    }

    function parseColor(color) {
        const value = String(color || '').trim();
        let match = value.match(/^#([0-9a-f]{3})$/i);
        if (match) {
            return match[1].split('').map(function (part) {
                return parseInt(part + part, 16);
            });
        }
        match = value.match(/^#([0-9a-f]{6})$/i);
        if (match) {
            return [
                parseInt(match[1].substring(0, 2), 16),
                parseInt(match[1].substring(2, 4), 16),
                parseInt(match[1].substring(4, 6), 16)
            ];
        }
        return [233, 236, 239];
    }

    function readableTextColor(color) {
        const components = parseColor(color).map(function (component) {
            const normalized = component / 255;
            return normalized <= 0.03928
                ? normalized / 12.92
                : Math.pow((normalized + 0.055) / 1.055, 2.4);
        });
        const luminance = 0.2126 * components[0]
            + 0.7152 * components[1]
            + 0.0722 * components[2];
        return luminance > 0.47 ? '#17212b' : '#ffffff';
    }

    function rgba(color, alpha) {
        const components = parseColor(color);
        return 'rgba(' + components[0] + ',' + components[1] + ','
            + components[2] + ',' + alpha + ')';
    }

    function colorForLayer(type) {
        return layerColors[type] || '#e9ecef';
    }

    function colorForRelation(type) {
        return relationColors[type] || '#6c757d';
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

    function renderEdgeDetails(edge) {
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

    function computeHopDistances() {
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

    function visibleModel() {
        let nodes = scene.nodes.filter(function (node) {
            return state.showContext || node.anchor;
        });
        if (state.mode === 'focus' && state.selectedNodeId) {
            const included = new Set([state.selectedNodeId]);
            scene.edges.forEach(function (edge) {
                if (edge.sourceId === state.selectedNodeId || edge.targetId === state.selectedNodeId) {
                    included.add(edge.sourceId);
                    included.add(edge.targetId);
                }
            });
            nodes = nodes.filter(function (node) { return included.has(node.id); });
        }
        const nodeIds = new Set(nodes.map(function (node) { return node.id; }));
        const edges = scene.edges.filter(function (edge) {
            return nodeIds.has(edge.sourceId) && nodeIds.has(edge.targetId);
        });
        return {nodes: nodes, edges: edges, nodeIds: nodeIds};
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

    function viewportSize() {
        return {
            width: Math.max(320, canvas.clientWidth || 900),
            height: Math.max(300, canvas.clientHeight || 540)
        };
    }

    function updateViewBox() {
        const size = viewportSize();
        svg.setAttribute('viewBox', '0 0 ' + size.width + ' ' + size.height);
    }

    function applyTransform(transform) {
        state.autoFit = false;
        if (reducedMotion) {
            svgSelection.call(zoomBehavior.transform, transform);
        } else {
            svgSelection.transition().duration(180).call(zoomBehavior.transform, transform);
        }
    }

    function fitView() {
        if (!state.bounds || !zoomBehavior) return;
        const size = viewportSize();
        const padding = 34;
        let scale = Math.min(
            (size.width - padding * 2) / Math.max(1, state.bounds.width),
            (size.height - padding * 2) / Math.max(1, state.bounds.height),
            1.35
        );
        scale = Math.max(0.2, Math.min(1.35, scale));
        const translateX = (size.width - state.bounds.width * scale) / 2
            - state.bounds.x * scale;
        const translateY = (size.height - state.bounds.height * scale) / 2
            - state.bounds.y * scale;
        state.autoFit = true;
        const transform = d3.zoomIdentity.translate(translateX, translateY).scale(scale);
        if (reducedMotion) {
            svgSelection.call(zoomBehavior.transform, transform);
        } else {
            svgSelection.transition().duration(220).call(zoomBehavior.transform, transform);
        }
    }

    function centerOnNode(nodeId) {
        const node = nodeById.get(nodeId);
        if (!node) return;
        const size = viewportSize();
        const current = d3.zoomTransform(svg);
        const scale = Math.max(0.9, Math.min(1.55, Math.max(current.k, 1.05)));
        const centerX = node.x + node.width / 2;
        const centerY = node.y + node.height / 2;
        applyTransform(d3.zoomIdentity
            .translate(size.width / 2 - centerX * scale, size.height / 2 - centerY * scale)
            .scale(scale));
    }

    function renderMarkers(defs) {
        const types = new Set(state.visibleEdges.map(function (edge) { return edge.relationType; }));
        types.forEach(function (type) {
            const markerId = 'architecture-arrow-' + type.replace(/[^a-z0-9_-]/gi, '-');
            const marker = createSvgElement('marker', {
                id: markerId,
                viewBox: '0 -5 10 10',
                refX: 9,
                refY: 0,
                markerWidth: 6,
                markerHeight: 6,
                orient: 'auto',
                markerUnits: 'strokeWidth'
            });
            marker.appendChild(createSvgElement('path', {
                d: 'M0,-5L10,0L0,5Z',
                fill: colorForRelation(type)
            }));
            defs.appendChild(marker);
        });
    }

    function renderLayers(viewport) {
        const top = state.bounds.y;
        const bottom = state.bounds.contentBottom + 12;
        state.layerGroups.forEach(function (layer) {
            const group = createSvgElement('g', {class: 'architecture-layer'});
            group.appendChild(createSvgElement('rect', {
                class: 'architecture-layer-column',
                x: layer.x - 10,
                y: top,
                width: layer.width + 20,
                height: bottom - top,
                rx: 12,
                fill: rgba(layer.color, 0.08)
            }));
            group.appendChild(createSvgElement('rect', {
                class: 'architecture-layer-header',
                x: layer.x,
                y: top + 7,
                width: layer.width,
                height: 34,
                rx: 8,
                fill: layer.color
            }));
            const textColor = readableTextColor(layer.color);
            appendSvgText(group, 'architecture-layer-title', layer.x + 12, top + 29,
                wrapLabel(layer.label, 24, 1)[0], textColor);
            const count = appendSvgText(
                group,
                'architecture-layer-count',
                layer.x + layer.width - 12,
                top + 29,
                String(layer.nodes.length),
                textColor
            );
            count.setAttribute('text-anchor', 'end');
            viewport.appendChild(group);
        });
    }

    function relationshipLanes(edges) {
        const groups = new Map();
        edges.forEach(function (edge) {
            const key = edge.sourceId + '\u0000' + edge.targetId;
            if (!groups.has(key)) groups.set(key, []);
            groups.get(key).push(edge);
        });
        const lanes = new Map();
        groups.forEach(function (items) {
            items.forEach(function (edge, index) {
                lanes.set(edge.id, index - (items.length - 1) / 2);
            });
        });
        return lanes;
    }

    function edgePath(edge, lane) {
        const source = nodeById.get(edge.sourceId);
        const target = nodeById.get(edge.targetId);
        const sourceX = edge.sourceX;
        const sourceY = edge.sourceY;
        const targetX = edge.targetX;
        const targetY = edge.targetY;
        const laneOffset = lane * 12;
        if (source && target && source.layer === target.layer) {
            const right = Math.max(source.x + source.width, target.x + target.width)
                + 42 + Math.abs(laneOffset);
            return 'M' + sourceX + ',' + sourceY
                + ' C' + right + ',' + (sourceY + laneOffset)
                + ' ' + right + ',' + (targetY + laneOffset)
                + ' ' + targetX + ',' + targetY;
        }
        const distance = Math.max(42, Math.abs(targetX - sourceX) * 0.43);
        const direction = targetX >= sourceX ? 1 : -1;
        return 'M' + sourceX + ',' + sourceY
            + ' C' + (sourceX + direction * distance) + ',' + (sourceY + laneOffset)
            + ' ' + (targetX - direction * distance) + ',' + (targetY + laneOffset)
            + ' ' + targetX + ',' + targetY;
    }

    function renderEdges(viewport) {
        const lanes = relationshipLanes(state.visibleEdges);
        const showAllLabels = state.visibleEdges.length <= 12;
        state.visibleEdges.forEach(function (edge) {
            const pathData = edgePath(edge, lanes.get(edge.id) || 0);
            const markerId = 'architecture-arrow-'
                + edge.relationType.replace(/[^a-z0-9_-]/gi, '-');
            const group = createSvgElement('g', {
                class: 'architecture-edge',
                'data-edge-id': edge.id,
                'data-source': edge.sourceId,
                'data-target': edge.targetId
            });
            const path = createSvgElement('path', {
                class: 'architecture-edge-path',
                d: pathData,
                stroke: colorForRelation(edge.relationType),
                'marker-end': 'url(#' + markerId + ')'
            });
            const hit = createSvgElement('path', {
                class: 'architecture-edge-hit',
                d: pathData,
                tabindex: 0,
                role: 'button',
                'aria-label': edge.relationType + ' from ' + edge.sourceId + ' to ' + edge.targetId
            });
            const tooltip = createSvgElement('title');
            tooltip.textContent = edge.sourceId + ' → ' + edge.targetId
                + '\n' + edge.relationType;
            hit.appendChild(tooltip);
            hit.addEventListener('click', function (event) {
                event.stopPropagation();
                setSelection(null, edge.id, false);
            });
            hit.addEventListener('keydown', function (event) {
                if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault();
                    setSelection(null, edge.id, false);
                }
            });
            group.append(path, hit);

            const label = appendSvgText(
                group,
                'architecture-edge-label',
                (edge.sourceX + edge.targetX) / 2,
                (edge.sourceY + edge.targetY) / 2 + (lanes.get(edge.id) || 0) * 12 - 6,
                edge.relationType,
                colorForRelation(edge.relationType)
            );
            label.setAttribute('text-anchor', 'middle');
            label.style.display = showAllLabels ? '' : 'none';
            group._label = label;
            viewport.appendChild(group);
            state.edgeElements.set(edge.id, group);
        });
    }

    function renderNodes(viewport) {
        state.visibleNodes.forEach(function (node) {
            const color = colorForLayer(node.type);
            const textColor = readableTextColor(color);
            let className = 'architecture-node';
            if (node.anchor) className += ' is-anchor';
            const group = createSvgElement('g', {
                class: className,
                transform: 'translate(' + node.x + ',' + node.y + ')',
                'data-node-id': node.id,
                tabindex: 0,
                role: 'button',
                'aria-label': node.id + ', ' + node.label + ', ' + node.type
                    + ', relevance ' + Math.round(node.relevance * 100) + ' percent'
            });
            group.appendChild(createSvgElement('rect', {
                class: 'architecture-node-card',
                x: 0,
                y: 0,
                width: node.width,
                height: node.height,
                rx: 9,
                fill: color
            }));
            appendSvgText(group, 'architecture-node-code', 12, 19, node.id, textColor);

            const scoreWidth = 58;
            group.appendChild(createSvgElement('rect', {
                class: 'architecture-node-score-pill',
                x: node.width - scoreWidth - 8,
                y: 7,
                width: scoreWidth,
                height: 22,
                rx: 11,
                fill: rgba(textColor, textColor === '#ffffff' ? 0.18 : 0.12)
            }));
            const score = (node.anchor ? '★ ' : '') + Math.round(node.relevance * 100) + '%';
            const scoreText = appendSvgText(
                group,
                'architecture-node-score',
                node.width - 37,
                22,
                score,
                textColor
            );
            scoreText.setAttribute('text-anchor', 'middle');

            wrapLabel(node.label, 32, 2).forEach(function (line, index) {
                appendSvgText(group, 'architecture-node-title', 12, 43 + index * 15,
                    line, textColor);
            });
            const distance = node.anchor
                ? 'Direct match'
                : 'Hop ' + hopDistances.get(node.id);
            appendSvgText(
                group,
                'architecture-node-meta',
                12,
                node.height - 8,
                distance + ' · ' + node.type,
                textColor
            );

            const tooltip = createSvgElement('title');
            const metadata = projection.elements[node.id] || {};
            tooltip.textContent = node.id + ' — ' + node.label
                + '\n' + node.type
                + '\n' + Math.round(node.relevance * 100) + '%'
                + (metadata.presenceReason ? '\n' + metadata.presenceReason : '');
            group.appendChild(tooltip);

            group.addEventListener('click', function (event) {
                event.stopPropagation();
                setSelection(node.id, null, false);
            });
            group.addEventListener('dblclick', function (event) {
                event.preventDefault();
                event.stopPropagation();
                state.selectedNodeId = node.id;
                state.selectedEdgeId = null;
                state.mode = 'focus';
                renderDiagram(true);
            });
            group.addEventListener('keydown', function (event) {
                if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault();
                    setSelection(node.id, null, false);
                } else if (event.key.toLowerCase() === 'f') {
                    event.preventDefault();
                    state.selectedNodeId = node.id;
                    state.selectedEdgeId = null;
                    state.mode = 'focus';
                    renderDiagram(true);
                }
            });
            viewport.appendChild(group);
            state.nodeElements.set(node.id, group);
        });
    }

    function matchingNodeIds() {
        const query = state.searchQuery.trim().toLowerCase();
        if (!query) return [];
        return scene.nodes.filter(function (node) {
            return node.id.toLowerCase().includes(query)
                || node.label.toLowerCase().includes(query)
                || node.type.toLowerCase().includes(query);
        }).map(function (node) { return node.id; });
    }

    function updateInteractionStyles() {
        const selectedNode = state.selectedNodeId;
        const selectedEdge = state.selectedEdgeId;
        const connectedNodeIds = new Set();
        const connectedEdgeIds = new Set();
        if (selectedNode) {
            connectedNodeIds.add(selectedNode);
            state.visibleEdges.forEach(function (edge) {
                if (edge.sourceId === selectedNode || edge.targetId === selectedNode) {
                    connectedNodeIds.add(edge.sourceId);
                    connectedNodeIds.add(edge.targetId);
                    connectedEdgeIds.add(edge.id);
                }
            });
        }
        const matches = new Set(matchingNodeIds());
        const hasSearch = state.searchQuery.trim().length > 0;

        state.nodeElements.forEach(function (element, id) {
            const connected = connectedNodeIds.has(id);
            const searchMatch = matches.has(id);
            const muted = id !== selectedNode
                && ((selectedNode && !connected) || (hasSearch && !searchMatch));
            element.classList.toggle('is-selected', id === selectedNode);
            element.classList.toggle('is-search-match', hasSearch && searchMatch);
            element.classList.toggle('is-muted', Boolean(muted));
        });

        state.edgeElements.forEach(function (element, id) {
            const source = element.dataset.source;
            const target = element.dataset.target;
            const connected = connectedEdgeIds.has(id);
            const searchRelevant = !hasSearch || matches.has(source) || matches.has(target);
            const muted = id !== selectedEdge
                && ((selectedNode && !connected) || (hasSearch && !searchRelevant));
            element.classList.toggle('is-selected', id === selectedEdge);
            element.classList.toggle('is-connected', connected);
            element.classList.toggle('is-muted', Boolean(muted));
            if (element._label) {
                element._label.style.display = state.visibleEdges.length <= 12
                    || connected || id === selectedEdge || (hasSearch && searchRelevant)
                    ? '' : 'none';
            }
        });
        updateVisibleStatus();
    }

    function updateVisibleStatus() {
        const matches = matchingNodeIds();
        if (state.searchQuery.trim()) {
            const visibleMatches = matches.filter(function (id) {
                return state.visibleNodeIds.has(id);
            }).length;
            visibleStatus.textContent = matches.length + ' matching elements; '
                + visibleMatches + ' currently visible.';
        } else {
            visibleStatus.textContent = 'Showing ' + state.visibleNodes.length + ' of '
                + scene.nodes.length + ' elements and ' + state.visibleEdges.length
                + ' relationships.';
        }
    }

    function updateKpis() {
        document.getElementById('directKpi').textContent = state.visibleNodes.filter(function (node) {
            return node.anchor;
        }).length + ' direct';
        document.getElementById('elementKpi').textContent = state.visibleNodes.length + ' elements';
        document.getElementById('relationKpi').textContent = state.visibleEdges.length + ' relationships';
        document.getElementById('layerKpi').textContent = state.layerGroups.length + ' layers';
    }

    function updateModeButtons() {
        overviewButton.setAttribute('aria-pressed', state.mode === 'overview' ? 'true' : 'false');
        focusButton.setAttribute('aria-pressed', state.mode === 'focus' ? 'true' : 'false');
        focusButton.disabled = !state.selectedNodeId;
    }

    function setSelection(nodeId, edgeId, center) {
        const leavingFocusMode = !nodeId && state.mode === 'focus';
        state.selectedNodeId = nodeId || null;
        state.selectedEdgeId = edgeId || null;
        if (leavingFocusMode) {
            state.mode = 'overview';
            renderDiagram(true);
            return;
        }
        updateModeButtons();
        updateInteractionStyles();
        if (state.selectedNodeId) {
            renderNodeDetails(nodeById.get(state.selectedNodeId));
        } else if (state.selectedEdgeId) {
            renderEdgeDetails(edgeById.get(state.selectedEdgeId));
        } else {
            clearDetails();
        }
        if (center && state.selectedNodeId) centerOnNode(state.selectedNodeId);
    }

    function renderDiagram(autoFit) {
        const model = visibleModel();
        state.visibleNodes = model.nodes;
        state.visibleEdges = model.edges;
        state.visibleNodeIds = model.nodeIds;
        state.layerGroups = buildLayerGroups(model.nodes);
        state.bounds = calculateBounds(model.nodes, state.layerGroups);
        state.nodeElements.clear();
        state.edgeElements.clear();
        svg.replaceChildren();

        const defs = createSvgElement('defs');
        const viewport = createSvgElement('g', {class: 'architecture-viewport'});
        svg.append(defs, viewport);
        viewportSelection = d3.select(viewport);
        renderMarkers(defs);
        renderLayers(viewport);
        renderEdges(viewport);
        renderNodes(viewport);
        if (state.selectedNodeId && !state.visibleNodeIds.has(state.selectedNodeId)) {
            state.selectedNodeId = null;
            state.selectedEdgeId = null;
            state.mode = 'overview';
            clearDetails();
        }
        if (state.selectedEdgeId
                && !state.visibleEdges.some(function (edge) { return edge.id === state.selectedEdgeId; })) {
            state.selectedEdgeId = null;
            clearDetails();
        }
        updateViewBox();
        updateKpis();
        updateModeButtons();
        updateInteractionStyles();
        if (autoFit !== false) window.requestAnimationFrame(fitView);
    }

    function renderProvenance(data) {
        const model = data.modelName ? '/' + data.modelName : '';
        const created = data.snapshotCreatedAt
            ? ' · ' + new Date(data.snapshotCreatedAt).toLocaleString()
            : '';
        document.getElementById('architectureProvenance').textContent =
            'Snapshot ' + data.snapshotId
            + ' · ' + text(data.snapshotStatus, 'status n/a')
            + ' · ' + text(data.provider, 'provider n/a') + model
            + ' · branch ' + text(data.branchName, 'n/a')
            + ' · commit ' + text(data.commitSha, 'n/a').slice(0, 12)
            + created;
    }

    function renderWarnings(data) {
        const warnings = document.getElementById('architectureWarnings');
        const section = document.getElementById('architectureWarningsSection');
        warnings.replaceChildren();
        const values = Array.isArray(data.warnings) ? data.warnings : [];
        values.forEach(function (warning) {
            const item = document.createElement('li');
            item.textContent = warning;
            warnings.append(item);
        });
        section.classList.toggle('d-none', values.length === 0);
    }

    function render(data) {
        if (!data || !data.scene || !Array.isArray(data.scene.nodes)) {
            throw new Error('The persisted analysis does not contain an architecture scene.');
        }
        projection = data;
        scene = data.scene;
        scene.edges = Array.isArray(scene.edges) ? scene.edges : [];
        nodeById = new Map(scene.nodes.map(function (node) { return [node.id, node]; }));
        edgeById = new Map(scene.edges.map(function (edge, index) {
            if (!edge.id) edge.id = 'edge-' + index + '-' + edge.sourceId + '-' + edge.targetId;
            return [edge.id, edge];
        }));
        hopDistances = computeHopDistances();
        alertBox.classList.add('d-none');

        svgSelection = d3.select(svg);
        zoomBehavior = d3.zoom()
            .scaleExtent([0.2, 4])
            .on('zoom', function (event) {
                if (viewportSelection) viewportSelection.attr('transform', event.transform);
            });
        svgSelection.call(zoomBehavior).on('dblclick.zoom', null);

        document.getElementById('architectureTitle').textContent = resolvePolicyTitle(scene.title);
        renderProvenance(data);
        document.getElementById('requirementText').textContent = data.requirementText || '';
        renderWarnings(data);
        contextCheckbox.disabled = !scene.nodes.some(function (node) { return node.anchor; });
        [fitButton, zoomInButton, zoomOutButton, overviewButton].forEach(function (button) {
            button.disabled = false;
        });
        fullscreenButton.hidden = typeof workbench.requestFullscreen !== 'function';
        renderDiagram(true);
        setStatus('Loaded ' + scene.nodes.length + ' elements and '
            + scene.edges.length + ' relationships from persisted Copilot snapshot.');

        if (resizeObserver) resizeObserver.disconnect();
        resizeObserver = typeof ResizeObserver !== 'undefined'
            ? new ResizeObserver(function () {
                window.clearTimeout(resizeTimer);
                resizeTimer = window.setTimeout(function () {
                    updateViewBox();
                    if (state.autoFit) fitView();
                }, 80);
            })
            : null;
        if (resizeObserver) resizeObserver.observe(canvas);
    }

    searchInput.addEventListener('input', function () {
        state.searchQuery = searchInput.value;
        clearSearch.hidden = state.searchQuery.length === 0;
        updateInteractionStyles();
    });

    searchInput.addEventListener('keydown', function (event) {
        if (event.key === 'Escape') {
            searchInput.value = '';
            state.searchQuery = '';
            clearSearch.hidden = true;
            updateInteractionStyles();
            return;
        }
        if (event.key !== 'Enter') return;
        const matches = matchingNodeIds();
        if (matches.length === 0) return;
        const first = matches[0];
        if (!state.visibleNodeIds.has(first)) {
            state.showContext = true;
            contextCheckbox.checked = true;
            state.mode = 'overview';
            state.selectedNodeId = first;
            state.selectedEdgeId = null;
            renderDiagram(false);
        }
        setSelection(first, null, true);
    });

    clearSearch.addEventListener('click', function () {
        searchInput.value = '';
        state.searchQuery = '';
        clearSearch.hidden = true;
        searchInput.focus();
        updateInteractionStyles();
    });

    contextCheckbox.addEventListener('change', function () {
        state.showContext = contextCheckbox.checked;
        renderDiagram(true);
    });

    overviewButton.addEventListener('click', function () {
        state.mode = 'overview';
        renderDiagram(true);
    });

    focusButton.addEventListener('click', function () {
        if (!state.selectedNodeId) return;
        state.mode = 'focus';
        renderDiagram(true);
    });

    fitButton.addEventListener('click', fitView);
    zoomInButton.addEventListener('click', function () {
        state.autoFit = false;
        svgSelection.call(zoomBehavior.scaleBy, 1.25);
    });
    zoomOutButton.addEventListener('click', function () {
        state.autoFit = false;
        svgSelection.call(zoomBehavior.scaleBy, 0.8);
    });

    fullscreenButton.addEventListener('click', function () {
        if (document.fullscreenElement === workbench) {
            document.exitFullscreen();
        } else if (workbench.requestFullscreen) {
            workbench.requestFullscreen();
        }
    });

    document.addEventListener('fullscreenchange', function () {
        const full = document.fullscreenElement === workbench;
        fullscreenButton.title = full ? 'Exit fullscreen' : 'Fullscreen';
        fullscreenButton.setAttribute('aria-label', fullscreenButton.title);
        window.setTimeout(function () {
            updateViewBox();
            fitView();
        }, 0);
    });

    svg.addEventListener('click', function (event) {
        const target = event.target;
        if (!target || typeof target.closest !== 'function'
                || !target.closest('.architecture-node, .architecture-edge-hit')) {
            setSelection(null, null, false);
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

    Promise.resolve()
        .then(function () {
            return ArchitectureWorkbenchApi.load(projectId, snapshotId);
        })
        .then(render)
        .catch(showError);
}());
