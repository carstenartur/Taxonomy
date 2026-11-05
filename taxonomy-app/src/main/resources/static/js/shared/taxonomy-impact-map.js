/* Analysis source adapter. The shared architecture diagram owns all SVG and interaction state. */
(function () {
    'use strict';

    var diagramApi = window.TaxonomyArchitectureDiagram;
    var MAX_VISIBLE_NODES = 80;
    var CARD_WIDTH = 230;
    var CARD_HEIGHT = 86;
    var NODE_GAP = 18;
    var LAYER_GAP = 68;
    var OUTER_MARGIN = 38;
    var LAYER_HEADER_HEIGHT = 36;
    var CONTENT_TOP = 62;
    var instanceSequence = 0;

    function t(key) {
        var args = Array.prototype.slice.call(arguments, 1);
        if (window.TaxonomyI18n && window.TaxonomyI18n.t) {
            var translated = window.TaxonomyI18n.t.apply(null, [key].concat(args));
            if (translated !== key) return translated;
        }
        var fallbacks = {
            'impactmap.title': 'Requirement impact map',
            'impactmap.description': 'Stable layer layout. Select an element to inspect its evidence and relationships.',
            'impactmap.search.placeholder': 'Find code or title…',
            'impactmap.search.label': 'Search impact-map elements',
            'impactmap.search.clear': 'Clear search',
            'impactmap.context': 'Context nodes',
            'impactmap.overview': 'Overview',
            'impactmap.focus': 'Focus',
            'impactmap.fit': 'Fit',
            'impactmap.zoom.in': 'Zoom in',
            'impactmap.zoom.out': 'Zoom out',
            'impactmap.fullscreen': 'Fullscreen',
            'impactmap.exit.fullscreen': 'Exit fullscreen',
            'impactmap.kpi.direct': '{0} direct',
            'impactmap.kpi.elements': '{0} elements',
            'impactmap.kpi.relationships': '{0} relationships',
            'impactmap.kpi.layers': '{0} layers',
            'impactmap.kpi.hotspots': '{0} hotspots',
            'impactmap.status.visible': 'Showing {0} of {1} elements and {2} relationships.',
            'impactmap.status.search': '{0} matching elements; {1} currently visible.',
            'impactmap.status.omitted': '{0} lower-priority elements are omitted to keep the diagram readable.',
            'impactmap.empty': 'No architecture elements are available for this result.',
            'impactmap.unavailable': 'The impact map requires the D3 visualization library.',
            'impactmap.details.hint': 'Select an element or relationship to see the explanation, relevance and connected architecture elements.',
            'impactmap.details.selected': 'Selected element',
            'impactmap.details.relationship': 'Selected relationship',
            'impactmap.layer': 'Layer',
            'impactmap.relevance': 'Relevance',
            'impactmap.distance': 'Distance',
            'impactmap.reason': 'Reason',
            'impactmap.type': 'Type',
            'impactmap.source': 'Source',
            'impactmap.target': 'Target',
            'impactmap.direct': 'Direct match',
            'impactmap.hop': 'Hop {0}',
            'impactmap.related': 'Connected relationships',
            'impactmap.open.graph': 'Open in Graph Explorer',
            'impactmap.focus.element': 'Focus this element',
            'impactmap.unfocus': 'Unfocus',
            'impactmap.select.source': 'Select source',
            'impactmap.select.target': 'Select target',
            'impactmap.no.relationships': 'No displayed relationships are connected to this element.',
            'impactmap.aria.diagram': 'Requirement impact map with {0} elements in {1} layers',
            'impactmap.aria.node': '{0}, {1}, relevance {2} percent, {3}',
            'impactmap.aria.relationship': '{0} from {1} to {2}',
            'impactmap.hotspot': 'Hotspot',
            'impactmap.search.hidden': 'The selected search result was hidden. Overview and context nodes were restored.',
            'impactmap.focus.empty': 'Select an element before switching to focus mode.'
        };
        var template = fallbacks[key] || key;
        return template.replace(/\{(\d+)\}/g, function (match, index) {
            var valueIndex = parseInt(index, 10);
            return valueIndex < args.length ? String(args[valueIndex]) : match;
        });
    }

    function clamp(value, min, max) {
        return Math.max(min, Math.min(max, value));
    }

    function safeNumber(value, fallback) {
        var number = Number(value);
        return Number.isFinite(number) ? number : fallback;
    }

    function createElement(tagName, className, text) {
        var element = document.createElement(tagName);
        if (className) element.className = className;
        if (text !== undefined && text !== null) element.textContent = text;
        return element;
    }

    function prefixForSheet(sheet) {
        if (!sheet) return '';
        if (/^[A-Z]{2}$/.test(sheet)) return sheet;
        var match = String(sheet).match(/^([A-Z]{2})(?:\b|[-_])/);
        return match ? match[1] : '';
    }

    function normalizeNodes(nodes, options) {
        options = options || {};
        var anchors = options.anchorCodes instanceof Set ? options.anchorCodes : new Set(options.anchorCodes || []);
        var hotspots = options.hotspotCodes instanceof Set ? options.hotspotCodes : new Set(options.hotspotCodes || []);
        var reasons = options.hotspotReasons || {};
        var layerConfig = options.layerConfig || {};
        var byId = new Map();

        (Array.isArray(nodes) ? nodes : []).forEach(function (node) {
            var id = String(node.nodeCode || node.id || '').trim();
            if (!id || byId.has(id)) return;
            var sheet = node.taxonomySheet || node.taxonomyRoot || 'Unknown';
            var config = layerConfig[sheet] || layerConfig[prefixForSheet(sheet)] || {};
            var hop = Math.max(0, Math.round(safeNumber(node.hopDistance, 0)));
            var anchor = anchors.has(id) || Boolean(node.anchor);
            var normalized = {
                id: id,
                title: String(node.title || node.name || id),
                sheet: String(sheet),
                sheetLabel: String(config.label || sheet),
                layerOrder: safeNumber(config.order, 99),
                relevance: clamp(safeNumber(node.relevance, 0), 0, 1),
                hop: hop,
                anchor: anchor,
                context: !anchor && hop > 0,
                hotspot: hotspots.has(id),
                hotspotReason: String(reasons[id] || ''),
                includedBecause: String(node.includedBecause || node.presenceReason || '')
            };
            normalized.searchText = [normalized.id, normalized.title, normalized.sheetLabel].join(' ');
            byId.set(id, normalized);
        });
        return Array.from(byId.values());
    }

    function normalizeEdges(edges, nodeById) {
        var seen = new Set();
        var normalized = [];
        (Array.isArray(edges) ? edges : []).forEach(function (edge, index) {
            var source = String(edge.sourceCode || (edge.source && edge.source.id) || edge.source || '').trim();
            var target = String(edge.targetCode || (edge.target && edge.target.id) || edge.target || '').trim();
            var type = String(edge.relationType || edge.type || 'RELATED_TO');
            if (!source || !target || !nodeById.has(source) || !nodeById.has(target)) return;
            var key = source + '\u0000' + target + '\u0000' + type;
            if (seen.has(key)) return;
            seen.add(key);
            normalized.push({
                id: 'edge-' + index + '-' + source + '-' + target,
                source: source,
                target: target,
                type: type,
                relevance: clamp(safeNumber(edge.propagatedRelevance, edge.relevance || 0), 0, 1),
                includedBecause: String(edge.includedBecause || edge.derivationReason || '')
            });
        });
        return normalized;
    }

    function rankNodes(left, right) {
        if (left.anchor !== right.anchor) return left.anchor ? -1 : 1;
        if (left.hotspot !== right.hotspot) return left.hotspot ? -1 : 1;
        if (right.relevance !== left.relevance) return right.relevance - left.relevance;
        return left.id.localeCompare(right.id);
    }

    function visibleModel(state) {
        return diagramApi.visibleModel({ nodes: state.allNodes, edges: state.allEdges }, state,
            { modelNodes: MAX_VISIBLE_NODES, rankNodes: rankNodes });
    }

    function buildLayout(model, palette) {
        palette = palette || diagramApi.taxonomyPalette();
        var groups = new Map();
        model.nodes.forEach(function (node) {
            var key = node.sheet;
            if (!groups.has(key)) {
                groups.set(key, {
                    sheet: key,
                    label: node.sheetLabel,
                    order: node.layerOrder,
                    color: palette.layer(key),
                    nodes: []
                });
            }
            groups.get(key).nodes.push(node);
        });

        var layers = Array.from(groups.values()).sort(function (left, right) {
            if (left.order !== right.order) return left.order - right.order;
            return left.label.localeCompare(right.label);
        });

        layers.forEach(function (layer) {
            layer.nodes.sort(rankNodes);
        });

        var maxRows = Math.max(1, layers.reduce(function (maximum, layer) {
            return Math.max(maximum, layer.nodes.length);
        }, 0));
        var width = OUTER_MARGIN * 2 + layers.length * CARD_WIDTH + Math.max(0, layers.length - 1) * LAYER_GAP;
        var height = CONTENT_TOP + maxRows * CARD_HEIGHT + Math.max(0, maxRows - 1) * NODE_GAP + OUTER_MARGIN;
        var positions = new Map();

        layers.forEach(function (layer, layerIndex) {
            layer.x = OUTER_MARGIN + layerIndex * (CARD_WIDTH + LAYER_GAP);
            layer.y = 12;
            layer.width = CARD_WIDTH;
            layer.height = height - 24;
            layer.nodes.forEach(function (node, rowIndex) {
                positions.set(node.id, {
                    x: layer.x,
                    y: CONTENT_TOP + rowIndex * (CARD_HEIGHT + NODE_GAP),
                    width: CARD_WIDTH,
                    height: CARD_HEIGHT,
                    layerIndex: layerIndex,
                    rowIndex: rowIndex,
                    node: node
                });
            });
        });

        return {
            layers: layers,
            positions: positions,
            width: Math.max(width, 360),
            height: Math.max(height, 300)
        };
    }

    function layoutScene(model) {
        var palette = diagramApi.taxonomyPalette(), layout = buildLayout(model, palette);
        var lanes = diagramApi.relationshipLanes(model.edges);
        return {
            bounds: { x: 0, y: 0, width: layout.width, height: layout.height },
            layers: layout.layers.map(function (layer) {
                return Object.assign({}, layer, {
                    backgroundFill: diagramApi.rgba(layer.color, 0.075),
                    headerHeight: LAYER_HEADER_HEIGHT,
                    headerStroke: diagramApi.rgba(layer.color, 0.72),
                    textColor: diagramApi.readableTextColor(layer.color),
                    count: layer.nodes.length,
                    labelOffsetY: 30
                });
            }),
            nodes: model.nodes.map(function (node) {
                var position = layout.positions.get(node.id);
                var color = palette.layer(node.sheet), textColor = diagramApi.readableTextColor(color);
                return Object.assign({}, node, {
                    x: position.x, y: position.y, width: CARD_WIDTH, height: CARD_HEIGHT, prefix: 'impact-map',
                    className: 'impact-map-node' + (node.anchor ? ' is-anchor' : '') + (node.hotspot ? ' is-hotspot' : ''),
                    color: color, textColor: textColor, scoreWidth: 56, metaY: 77,
                    ariaLabel: t('impactmap.aria.node', node.id, node.title, Math.round(node.relevance * 100), node.anchor ? t('impactmap.direct') : t('impactmap.hop', node.hop)),
                    score: (node.anchor ? '★ ' : '') + Math.round(node.relevance * 100) + '%',
                    scoreFill: diagramApi.rgba(textColor, textColor === '#ffffff' ? 0.18 : 0.12),
                    titleLines: diagramApi.wrapLabel(node.title, 32, 2),
                    meta: (node.anchor ? t('impactmap.direct') : t('impactmap.hop', node.hop)) + ' · ' + node.sheetLabel,
                    tooltip: node.id + ' — ' + node.title + '\n' + node.sheetLabel + '\n' + Math.round(node.relevance * 100) + '%\n'
                        + (node.includedBecause || '') + (node.hotspotReason ? '\n' + t('impactmap.hotspot') + ': ' + node.hotspotReason : '')
                });
            }),
            edges: model.edges.map(function (edge) {
                var source = layout.positions.get(edge.source), target = layout.positions.get(edge.target);
                return Object.assign({}, edge, {
                    sourceId: edge.source, targetId: edge.target, prefix: 'impact-map',
                    className: 'impact-map-edge-group', pathClass: 'impact-map-edge',
                    path: diagramApi.edgePath(source, target, lanes.get(edge.id) || 0), color: palette.relation(edge.type),
                    ariaLabel: t('impactmap.aria.relationship', edge.type, edge.source, edge.target),
                    tooltip: edge.source + ' → ' + edge.target + '\n' + edge.type + (edge.includedBecause ? '\n' + edge.includedBecause : ''),
                    label: edge.type, labelX: (source.x + source.width / 2 + target.x + target.width / 2) / 2,
                    labelY: (source.y + source.height / 2 + target.y + target.height / 2) / 2 - 5
                });
            })
        };
    }

    function createButton(icon, label, title, optionalLabel) {
        var button = createElement('button', 'impact-map-button');
        button.type = 'button';
        button.title = title || label;
        button.setAttribute('aria-label', title || label);
        var iconSpan = createElement('span', 'impact-map-button-icon', icon);
        iconSpan.setAttribute('aria-hidden', 'true');
        button.appendChild(iconSpan);
        if (label) {
            button.appendChild(createElement('span', 'impact-map-button-label' + (optionalLabel ? ' optional' : ''), label));
        }
        return button;
    }

    function createKpi(icon, text) {
        var element = createElement('span', 'impact-map-kpi');
        var iconElement = createElement('span', '', icon);
        iconElement.setAttribute('aria-hidden', 'true');
        element.appendChild(iconElement);
        var value = createElement('span', '', text);
        element.appendChild(value);
        element._value = value;
        return element;
    }

    function renderImpactMap(container, nodes, edges, options) {
        options = options || {};
        if (!container) return null;

        if (typeof container.__taxonomyImpactMapCleanup === 'function') {
            container.__taxonomyImpactMapCleanup();
        }
        container.replaceChildren();

        if (typeof window.d3 === 'undefined') {
            container.appendChild(createElement('div', 'impact-map-empty', t('impactmap.unavailable')));
            return null;
        }

        var normalizedNodes = normalizeNodes(nodes, options);
        if (normalizedNodes.length === 0) {
            container.appendChild(createElement('div', 'impact-map-empty', t('impactmap.empty')));
            return null;
        }

        var nodeById = new Map(normalizedNodes.map(function (node) { return [node.id, node]; }));
        var normalizedEdges = normalizeEdges(edges, nodeById);
        var instanceId = 'taxonomy-impact-map-' + (++instanceSequence);
        var diagram = null;
        var currentView = null;

        var publishedSelection = null;
        var root = createElement('section', 'impact-map-workbench');
        root.setAttribute('aria-labelledby', instanceId + '-title');
        root.dataset.impactMapInstance = instanceId;

        var header = createElement('div', 'impact-map-workbench-header');
        var heading = createElement('div', 'impact-map-heading');
        var title = createElement('h6', 'impact-map-title', options.policyTitle || t('impactmap.title'));
        title.id = instanceId + '-title';
        heading.appendChild(title);
        heading.appendChild(createElement('p', 'impact-map-subtitle', t('impactmap.description')));
        header.appendChild(heading);

        var kpis = createElement('div', 'impact-map-kpis');
        var directKpi = createKpi('🎯', '');
        var elementKpi = createKpi('▦', '');
        var relationKpi = createKpi('↗', '');
        var layerKpi = createKpi('▤', '');
        var hotspotKpi = createKpi('⚠', '');
        [directKpi, elementKpi, relationKpi, layerKpi, hotspotKpi].forEach(function (kpi) {
            kpis.appendChild(kpi);
        });
        header.appendChild(kpis);
        root.appendChild(header);

        var toolbar = createElement('div', 'impact-map-toolbar');
        toolbar.setAttribute('role', 'toolbar');
        toolbar.setAttribute('aria-label', t('impactmap.title'));

        var searchWrap = createElement('div', 'impact-map-search-wrap');
        var searchInput = createElement('input', 'impact-map-search');
        searchInput.type = 'search';
        searchInput.placeholder = t('impactmap.search.placeholder');
        searchInput.setAttribute('aria-label', t('impactmap.search.label'));
        var clearSearch = createElement('button', 'impact-map-search-clear', '×');
        clearSearch.type = 'button';
        clearSearch.title = t('impactmap.search.clear');
        clearSearch.setAttribute('aria-label', t('impactmap.search.clear'));
        clearSearch.hidden = true;
        searchWrap.appendChild(searchInput);
        searchWrap.appendChild(clearSearch);
        toolbar.appendChild(searchWrap);

        var contextLabel = createElement('label', 'impact-map-toggle');
        var contextCheckbox = createElement('input');
        contextCheckbox.type = 'checkbox';
        contextCheckbox.checked = true;
        contextLabel.appendChild(contextCheckbox);
        contextLabel.appendChild(createElement('span', '', t('impactmap.context')));
        toolbar.appendChild(contextLabel);

        var modeGroup = createElement('div', 'impact-map-button-group');
        var overviewButton = createButton('▦', t('impactmap.overview'), t('impactmap.overview'));
        overviewButton.setAttribute('aria-pressed', 'true');
        var focusButton = createButton('◎', t('impactmap.focus'), t('impactmap.focus'));
        focusButton.setAttribute('aria-pressed', 'false');
        modeGroup.appendChild(overviewButton);
        modeGroup.appendChild(focusButton);
        toolbar.appendChild(modeGroup);

        var zoomGroup = createElement('div', 'impact-map-button-group');
        var zoomOutButton = createButton('−', '', t('impactmap.zoom.out'));
        var fitButton = createButton('⊙', t('impactmap.fit'), t('impactmap.fit'), true);
        var zoomInButton = createButton('+', '', t('impactmap.zoom.in'));
        var fullscreenButton = createButton('⛶', '', t('impactmap.fullscreen'));
        zoomGroup.appendChild(zoomOutButton);
        zoomGroup.appendChild(fitButton);
        zoomGroup.appendChild(zoomInButton);
        zoomGroup.appendChild(fullscreenButton);
        fullscreenButton.hidden = typeof root.requestFullscreen !== 'function';
        toolbar.appendChild(zoomGroup);

        var status = createElement('div', 'impact-map-status');
        status.setAttribute('role', 'status');
        status.setAttribute('aria-live', 'polite');
        toolbar.appendChild(status);
        root.appendChild(toolbar);

        var canvas = createElement('div', 'impact-map-canvas');
        var svg = diagramApi.element('svg', {
            'role': 'img',
            'aria-label': t('impactmap.aria.diagram', normalizedNodes.length, 0),
            'tabindex': '0'
        });
        canvas.appendChild(svg);
        root.appendChild(canvas);

        var details = createElement('div', 'impact-map-details is-empty');
        details.setAttribute('aria-live', 'polite');
        root.appendChild(details);
        container.appendChild(root);

        function updateStatus() {
            if (!currentView.model) return;
            var matches = currentView.matches;
            if (currentView.state.searchQuery.trim()) {
                var visibleMatchCount = matches.filter(function (id) {
                    return currentView.model.nodeIds.has(id);
                }).length;
                status.textContent = t('impactmap.status.search', matches.length, visibleMatchCount);
            } else {
                status.textContent = t(
                    'impactmap.status.visible',
                    currentView.model.nodes.length,
                    normalizedNodes.length,
                    currentView.model.edges.length
                );
            }
            if (currentView.model.omitted > 0) {
                status.textContent += ' ' + t('impactmap.status.omitted', currentView.model.omitted);
            }
        }

        function updateKpis() {
            var visibleNodes = currentView.model.nodes;
            var direct = visibleNodes.filter(function (node) { return node.anchor; }).length;
            var hotspots = visibleNodes.filter(function (node) { return node.hotspot; }).length;
            directKpi._value.textContent = t('impactmap.kpi.direct', direct);
            elementKpi._value.textContent = t('impactmap.kpi.elements', visibleNodes.length);
            relationKpi._value.textContent = t('impactmap.kpi.relationships', currentView.model.edges.length);
            layerKpi._value.textContent = t('impactmap.kpi.layers', currentView.model.layers.length);
            hotspotKpi._value.textContent = t('impactmap.kpi.hotspots', hotspots);
            hotspotKpi.hidden = hotspots === 0;
        }

        function appendDetailPair(list, label, value) {
            list.appendChild(createElement('dt', '', label));
            list.appendChild(createElement('dd', '', value || '—'));
        }

        function detailsButton(label, handler) {
            var button = createElement('button', 'btn btn-sm btn-outline-primary', label);
            button.type = 'button';
            button.addEventListener('click', handler);
            return button;
        }

        function selectRelatedNode(nodeId) {
            diagram.select(nodeId, null, { center: true, reveal: true });
        }

        function renderNodeDetails(node) {
            details.classList.remove('is-empty');
            details.replaceChildren();
            var primary = createElement('div', 'impact-map-detail-primary');
            var detailHeading = createElement('div', 'impact-map-detail-heading');
            detailHeading.appendChild(createElement('span', 'impact-map-detail-title', node.title));
            detailHeading.appendChild(createElement('span', 'impact-map-detail-code', node.id));
            primary.appendChild(detailHeading);

            var list = createElement('dl', 'impact-map-detail-grid');
            appendDetailPair(list, t('impactmap.layer'), node.sheetLabel);
            appendDetailPair(list, t('impactmap.relevance'), Math.round(node.relevance * 100) + '%');
            appendDetailPair(list, t('impactmap.distance'), node.anchor ? t('impactmap.direct') : t('impactmap.hop', node.hop));
            appendDetailPair(list, t('impactmap.reason'), node.includedBecause || node.hotspotReason || '—');
            primary.appendChild(list);

            var actions = createElement('div', 'impact-map-detail-actions');
            actions.appendChild(detailsButton(t('impactmap.open.graph'), function () {
                if (window.TaxonomyGraph && window.TaxonomyGraph.openGraphExplorer) {
                    window.TaxonomyGraph.openGraphExplorer(node.id);
                }
            }));
            actions.appendChild(detailsButton(t(currentView.state.mode === 'focus' ? 'impactmap.unfocus' : 'impactmap.focus.element'), function () {
                if (currentView.state.mode === 'focus') diagram.unfocus();
                else diagram.focus(node.id);
            }));
            primary.appendChild(actions);
            details.appendChild(primary);

            var related = createElement('div', 'impact-map-related');
            related.appendChild(createElement('div', 'impact-map-related-title', t('impactmap.related')));
            var relatedList = createElement('div', 'impact-map-related-list');
            var relationships = currentView.model.edges.filter(function (edge) {
                return edge.source === node.id || edge.target === node.id;
            }).slice(0, 12);
            if (relationships.length === 0) {
                relatedList.appendChild(createElement('div', 'text-muted small', t('impactmap.no.relationships')));
            } else {
                relationships.forEach(function (edge) {
                    var outgoing = edge.source === node.id;
                    var otherId = outgoing ? edge.target : edge.source;
                    var other = nodeById.get(otherId);
                    var button = createElement('button', 'impact-map-related-item');
                    button.type = 'button';
                    var direction = outgoing ? '→ ' : '← ';
                    button.appendChild(createElement(
                        'span',
                        'impact-map-related-code',
                        direction + otherId + (other && other.title !== otherId ? ' — ' + other.title : '')
                    ));
                    button.appendChild(createElement('span', 'impact-map-related-type', edge.type));
                    button.addEventListener('click', function () { selectRelatedNode(otherId); });
                    relatedList.appendChild(button);
                });
            }
            related.appendChild(relatedList);
            details.appendChild(related);
        }

        function renderEdgeDetails(edge) {
            details.classList.remove('is-empty');
            details.replaceChildren();
            var primary = createElement('div', 'impact-map-detail-primary');
            var detailHeading = createElement('div', 'impact-map-detail-heading');
            detailHeading.appendChild(createElement('span', 'impact-map-detail-title', edge.type));
            detailHeading.appendChild(createElement('span', 'impact-map-detail-code', edge.source + ' → ' + edge.target));
            primary.appendChild(detailHeading);

            var list = createElement('dl', 'impact-map-detail-grid');
            appendDetailPair(list, t('impactmap.source'), edge.source);
            appendDetailPair(list, t('impactmap.target'), edge.target);
            appendDetailPair(list, t('impactmap.type'), edge.type);
            appendDetailPair(list, t('impactmap.relevance'), Math.round(edge.relevance * 100) + '%');
            appendDetailPair(list, t('impactmap.reason'), edge.includedBecause || '—');
            primary.appendChild(list);

            var actions = createElement('div', 'impact-map-detail-actions');
            actions.appendChild(detailsButton(t('impactmap.select.source'), function () {
                selectRelatedNode(edge.source);
            }));
            actions.appendChild(detailsButton(t('impactmap.select.target'), function () {
                selectRelatedNode(edge.target);
            }));
            primary.appendChild(actions);
            details.appendChild(primary);

            var related = createElement('div', 'impact-map-related');
            related.appendChild(createElement('div', 'impact-map-related-title', t('impactmap.details.relationship')));
            var sourceNode = nodeById.get(edge.source);
            var targetNode = nodeById.get(edge.target);
            related.appendChild(createElement(
                'div',
                'small',
                (sourceNode ? sourceNode.title : edge.source) + ' → ' + (targetNode ? targetNode.title : edge.target)
            ));
            details.appendChild(related);
        }

        function renderDetails() {
            if (currentView.state.selectedNodeId) {
                var node = nodeById.get(currentView.state.selectedNodeId);
                if (node) {
                    renderNodeDetails(node);
                    return;
                }
            }
            if (currentView.state.selectedEdgeId) {
                var edge = normalizedEdges.find(function (candidate) {
                    return candidate.id === currentView.state.selectedEdgeId;
                });
                if (edge) {
                    renderEdgeDetails(edge);
                    return;
                }
            }
            details.className = 'impact-map-details is-empty';
            details.textContent = t('impactmap.details.hint');
        }

        function updateModeButtons() {
            overviewButton.setAttribute('aria-pressed', currentView.state.mode === 'overview' ? 'true' : 'false');
            focusButton.setAttribute('aria-pressed', currentView.state.mode === 'focus' ? 'true' : 'false');
            focusButton.disabled = !currentView.state.selectedNodeId;
            var label = t(currentView.state.mode === 'focus' ? 'impactmap.unfocus' : 'impactmap.focus');
            focusButton.querySelector('.impact-map-button-label').textContent = label;
            focusButton.title = label;
            focusButton.setAttribute('aria-label', label);
        }

        function onDiagramChange(view) {
            currentView = view;
            contextCheckbox.checked = view.state.showContext;
            if (searchInput.value !== view.state.searchQuery) searchInput.value = view.state.searchQuery;
            clearSearch.hidden = !view.state.searchQuery;
            updateKpis();
            updateModeButtons();
            updateStatus();
            renderDetails();
            svg.setAttribute('aria-label', t('impactmap.aria.diagram', view.model.nodes.length, view.model.layers.length));
            if (publishedSelection !== view.state.selectedNodeId) {
                publishedSelection = view.state.selectedNodeId;
                if (publishedSelection) window.TaxonomyGraph?.selectNode?.(publishedSelection);
                document.dispatchEvent(new CustomEvent('taxonomy:architecture-selection', {
                    detail: { kind: 'analysis', elementId: publishedSelection }
                }));
            }
        }

        searchInput.addEventListener('input', function () {
            diagram.setSearch(searchInput.value);
        });
        searchInput.addEventListener('keydown', function (event) {
            if (event.key === 'Escape') {
                diagram.setSearch('');
                return;
            }
            if (event.key !== 'Enter') return;
            var matches = diagram.matchingNodeIds();
            if (matches.length === 0) return;
            var hidden = !diagram.getModel().nodeIds.has(matches[0]);
            diagram.select(matches[0], null, { center: true, reveal: true });
            if (hidden) status.textContent = t('impactmap.search.hidden');
        });
        clearSearch.addEventListener('click', function () {
            diagram.setSearch('');
            searchInput.focus();
        });
        contextCheckbox.addEventListener('change', function () {
            diagram.setContext(contextCheckbox.checked);
        });
        overviewButton.addEventListener('click', function () { diagram.unfocus(); });
        focusButton.addEventListener('click', function () {
            if (!currentView.state.selectedNodeId) {
                status.textContent = t('impactmap.focus.empty');
                return;
            }
            if (currentView.state.mode === 'focus') diagram.unfocus();
            else diagram.focus();
        });
        zoomInButton.addEventListener('click', function () { diagram.zoom(1.25); });
        zoomOutButton.addEventListener('click', function () { diagram.zoom(0.8); });
        fitButton.addEventListener('click', function () { diagram.fit(); });
        fullscreenButton.addEventListener('click', function () {
            if (document.fullscreenElement === root) {
                document.exitFullscreen();
            } else if (root.requestFullscreen) {
                root.requestFullscreen();
            }
        });

        function onFullscreenChange() {
            var full = document.fullscreenElement === root;
            fullscreenButton.title = full ? t('impactmap.exit.fullscreen') : t('impactmap.fullscreen');
            fullscreenButton.setAttribute('aria-label', fullscreenButton.title);
            diagram.fit();
        }
        document.addEventListener('fullscreenchange', onFullscreenChange);

        diagram = diagramApi.create(svg, {
            container: canvas,
            prefix: 'impact-map',
            presentation: { viewportClass: 'impact-map-viewport', selectedClass: 'is-selected', edgeLabelLimit: 14, highlightNeighbors: true },
            viewport: { minimumWidth: 320, minimumHeight: 300, fallbackWidth: 900, fallbackHeight: 540,
                minimumScale: 0.01, maximumScale: 2.8, fitPadding: 34, fitMaximumScale: 1.25 },
            behavior: { focus: 'neighbors', clearOnBackground: true },
            limits: { modelNodes: MAX_VISIBLE_NODES, suggestions: 32 },
            rankNodes: rankNodes,
            layout: layoutScene,
            onChange: onDiagramChange,
            onFitState: function (value, scale) {
                root.dataset.fitState = value;
                if (Number.isFinite(scale)) root.dataset.fitScale = scale.toFixed(6);
            },
            onRegion: options.onRegion,
            onSuggestion: options.onSuggestion
        });
        container.__taxonomyImpactMapDiagram = diagram;
        container.__taxonomyImpactMapCleanup = function () {
            diagram.destroy();
            document.removeEventListener('fullscreenchange', onFullscreenChange);
            delete container.__taxonomyImpactMapDiagram;
            delete container.__taxonomyImpactMapCleanup;
        };
        diagram.render({ nodes: normalizedNodes, edges: normalizedEdges });
        diagram.fit();
        return { diagram: diagram };
    }

    window.TaxonomyImpactMap = Object.freeze({
        render: renderImpactMap,
        normalizeNodes: normalizeNodes,
        normalizeEdges: normalizeEdges,
        buildLayout: buildLayout,
        layoutScene: layoutScene,
        visibleModel: visibleModel,
        calculateFitTransform: diagramApi.calculateFitTransform,
        shouldMuteEdge: diagramApi.shouldMuteEdge,
        shouldMuteNode: diagramApi.shouldMuteNode,
        shouldClearSelectionFromClick: diagramApi.shouldClearSelectionFromClick
    });
}());
