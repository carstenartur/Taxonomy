/**
 * taxonomy-impact-map.js — stable, layered result graph for requirement analysis.
 *
 * The previous force-directed graph was technically interactive but difficult to
 * read: labels collided, positions changed between renders and the narrow result
 * panel did not provide enough orientation. This module keeps the existing
 * TaxonomyGraph API and replaces only the architecture-impact renderer with a
 * deterministic workbench layout.
 */
(function () {
    'use strict';

    var SVG_NS = 'http://www.w3.org/2000/svg';
    var MAX_VISIBLE_NODES = 80;
    var CARD_WIDTH = 230;
    var CARD_HEIGHT = 86;
    var NODE_GAP = 18;
    var LAYER_GAP = 68;
    var OUTER_MARGIN = 38;
    var LAYER_HEADER_HEIGHT = 36;
    var CONTENT_TOP = 62;
    var instanceSequence = 0;

    var NODE_COLOR_TOKENS = {
        'Capabilities': '--taxonomy-layer-cap-surface',
        'CP': '--taxonomy-layer-cap-surface',
        'Business Processes': '--taxonomy-layer-proc-surface',
        'Business Roles': '--taxonomy-layer-proc-surface',
        'BP': '--taxonomy-layer-proc-surface',
        'BR': '--taxonomy-layer-proc-surface',
        'Services': '--taxonomy-layer-svc-surface',
        'COI Services': '--taxonomy-layer-svc-surface',
        'Core Services': '--taxonomy-layer-svc-surface',
        'CI': '--taxonomy-layer-svc-surface',
        'CR': '--taxonomy-layer-svc-surface',
        'Applications': '--taxonomy-layer-app-surface',
        'User Applications': '--taxonomy-layer-app-surface',
        'UA': '--taxonomy-layer-app-surface',
        'Information Products': '--taxonomy-layer-info-surface',
        'IP': '--taxonomy-layer-info-surface',
        'Communications Services': '--taxonomy-layer-comm-surface',
        'CO': '--taxonomy-layer-comm-surface',
        'Systems': '--taxonomy-layer-system-surface',
        'Components': '--taxonomy-layer-component-surface'
    };

    var FALLBACK_NODE_COLORS = {
        'CP': '#cfe2ff',
        'BP': '#d1e7dd',
        'BR': '#d1e7dd',
        'CI': '#e2d9f3',
        'CR': '#e2d9f3',
        'UA': '#fff3cd',
        'IP': '#cff4fc',
        'CO': '#f8d7da',
        'default': '#e9ecef'
    };

    var RELATION_COLORS = {
        'SUPPORTS': '#27834a',
        'REALIZES': '#326fbd',
        'USES': '#b66b00',
        'REQUIRES': '#c0392b',
        'DEPENDS_ON': '#7d3c98',
        'FULFILLS': '#218c53',
        'CONSUMES': '#b95f12',
        'ASSIGNED_TO': '#148f77',
        'PRODUCES': '#2874a6',
        'COMMUNICATES_WITH': '#76448a',
        'CONTAINS': '#65727e',
        'RELATED_TO': '#6c757d'
    };

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

    function createSvgElement(tagName, attributes) {
        var element = document.createElementNS(SVG_NS, tagName);
        Object.keys(attributes || {}).forEach(function (name) {
            if (attributes[name] !== undefined && attributes[name] !== null) {
                element.setAttribute(name, String(attributes[name]));
            }
        });
        return element;
    }

    function appendText(parent, className, x, y, text, fill) {
        var element = createSvgElement('text', {
            'class': className,
            'x': x,
            'y': y,
            'fill': fill
        });
        element.textContent = text;
        parent.appendChild(element);
        return element;
    }

    function cssValue(token, fallback) {
        var value = getComputedStyle(document.documentElement).getPropertyValue(token).trim();
        return value || fallback;
    }

    function prefixForSheet(sheet) {
        if (!sheet) return '';
        if (/^[A-Z]{2}$/.test(sheet)) return sheet;
        var match = String(sheet).match(/^([A-Z]{2})(?:\b|[-_])/);
        return match ? match[1] : '';
    }

    function nodeColor(sheet) {
        var token = NODE_COLOR_TOKENS[sheet];
        var prefix = prefixForSheet(sheet);
        if (!token && prefix) token = NODE_COLOR_TOKENS[prefix];
        var fallback = FALLBACK_NODE_COLORS[prefix] || FALLBACK_NODE_COLORS.default;
        return token ? cssValue(token, fallback) : fallback;
    }

    function parseColor(color) {
        var value = String(color || '').trim();
        var match;
        if ((match = value.match(/^#([0-9a-f]{3})$/i))) {
            return match[1].split('').map(function (part) {
                return parseInt(part + part, 16);
            });
        }
        if ((match = value.match(/^#([0-9a-f]{6})$/i))) {
            return [
                parseInt(match[1].substring(0, 2), 16),
                parseInt(match[1].substring(2, 4), 16),
                parseInt(match[1].substring(4, 6), 16)
            ];
        }
        if ((match = value.match(/^rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)/i))) {
            return [parseInt(match[1], 10), parseInt(match[2], 10), parseInt(match[3], 10)];
        }
        return [233, 236, 239];
    }

    function rgba(color, alpha) {
        var components = parseColor(color);
        return 'rgba(' + components[0] + ',' + components[1] + ',' + components[2] + ',' + alpha + ')';
    }

    function readableTextColor(color) {
        var components = parseColor(color).map(function (component) {
            var normalized = component / 255;
            return normalized <= 0.03928
                ? normalized / 12.92
                : Math.pow((normalized + 0.055) / 1.055, 2.4);
        });
        var luminance = 0.2126 * components[0] + 0.7152 * components[1] + 0.0722 * components[2];
        return luminance > 0.47 ? '#17212b' : '#ffffff';
    }

    function wrapLabel(value, maxCharacters, maxLines) {
        var words = String(value || '').trim().split(/\s+/).filter(Boolean);
        if (words.length === 0) return [''];
        var lines = [];
        var current = '';
        words.forEach(function (word) {
            var candidate = current ? current + ' ' + word : word;
            if (candidate.length <= maxCharacters || current.length === 0) {
                current = candidate;
            } else {
                lines.push(current);
                current = word;
            }
        });
        if (current) lines.push(current);
        if (lines.length > maxLines) {
            lines = lines.slice(0, maxLines);
            var lastIndex = maxLines - 1;
            var last = lines[lastIndex];
            lines[lastIndex] = last.substring(0, Math.max(1, maxCharacters - 1)).replace(/[\s.,;:]+$/, '') + '…';
        }
        return lines;
    }

    function relationColor(type) {
        return RELATION_COLORS[type] || cssValue('--bs-secondary-color', '#6c757d');
    }

    function normalizeNodes(nodes, options) {
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
            byId.set(id, {
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
            });
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

    function limitNodes(nodes) {
        if (nodes.length <= MAX_VISIBLE_NODES) {
            return { nodes: nodes.slice(), omitted: 0 };
        }
        var sorted = nodes.slice().sort(function (left, right) {
            if (left.anchor !== right.anchor) return left.anchor ? -1 : 1;
            if (left.hotspot !== right.hotspot) return left.hotspot ? -1 : 1;
            if (right.relevance !== left.relevance) return right.relevance - left.relevance;
            return left.id.localeCompare(right.id);
        });
        return {
            nodes: sorted.slice(0, MAX_VISIBLE_NODES),
            omitted: nodes.length - MAX_VISIBLE_NODES
        };
    }

    function visibleModel(state) {
        var candidates = state.allNodes.filter(function (node) {
            return state.showContext || !node.context;
        });

        if (state.mode === 'focus' && state.selectedNodeId) {
            var included = new Set([state.selectedNodeId]);
            state.allEdges.forEach(function (edge) {
                if (edge.source === state.selectedNodeId || edge.target === state.selectedNodeId) {
                    included.add(edge.source);
                    included.add(edge.target);
                }
            });
            candidates = candidates.filter(function (node) { return included.has(node.id); });
        }

        var limited = limitNodes(candidates);
        var ids = new Set(limited.nodes.map(function (node) { return node.id; }));
        var visibleEdges = state.allEdges.filter(function (edge) {
            return ids.has(edge.source) && ids.has(edge.target);
        });
        return {
            nodes: limited.nodes,
            edges: visibleEdges,
            nodeIds: ids,
            omitted: limited.omitted
        };
    }

    function buildLayout(model) {
        var groups = new Map();
        model.nodes.forEach(function (node) {
            var key = node.sheet;
            if (!groups.has(key)) {
                groups.set(key, {
                    sheet: key,
                    label: node.sheetLabel,
                    order: node.layerOrder,
                    color: nodeColor(key),
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
            layer.nodes.sort(function (left, right) {
                if (left.anchor !== right.anchor) return left.anchor ? -1 : 1;
                if (left.hotspot !== right.hotspot) return left.hotspot ? -1 : 1;
                if (right.relevance !== left.relevance) return right.relevance - left.relevance;
                return left.id.localeCompare(right.id);
            });
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

    function edgePath(source, target, lane) {
        var sourceY = source.y + source.height / 2;
        var targetY = target.y + target.height / 2;
        var laneOffset = lane * 12;
        var sourceX;
        var targetX;
        var controlDistance;

        if (source.layerIndex < target.layerIndex) {
            sourceX = source.x + source.width;
            targetX = target.x;
            controlDistance = Math.max(42, (targetX - sourceX) * 0.43);
            return 'M' + sourceX + ',' + sourceY +
                ' C' + (sourceX + controlDistance) + ',' + (sourceY + laneOffset) +
                ' ' + (targetX - controlDistance) + ',' + (targetY + laneOffset) +
                ' ' + targetX + ',' + targetY;
        }
        if (source.layerIndex > target.layerIndex) {
            sourceX = source.x;
            targetX = target.x + target.width;
            controlDistance = Math.max(42, (sourceX - targetX) * 0.43);
            return 'M' + sourceX + ',' + sourceY +
                ' C' + (sourceX - controlDistance) + ',' + (sourceY + laneOffset) +
                ' ' + (targetX + controlDistance) + ',' + (targetY + laneOffset) +
                ' ' + targetX + ',' + targetY;
        }

        sourceX = source.x + source.width;
        targetX = target.x + target.width;
        controlDistance = 42 + Math.min(80, Math.abs(targetY - sourceY) * 0.22) + Math.abs(laneOffset);
        return 'M' + sourceX + ',' + sourceY +
            ' C' + (sourceX + controlDistance) + ',' + (sourceY + laneOffset) +
            ' ' + (targetX + controlDistance) + ',' + (targetY + laneOffset) +
            ' ' + targetX + ',' + targetY;
    }

    function relationshipLanes(edges) {
        var groups = new Map();
        edges.forEach(function (edge) {
            var key = edge.source + '\u0000' + edge.target;
            if (!groups.has(key)) groups.set(key, []);
            groups.get(key).push(edge);
        });
        var lanes = new Map();
        groups.forEach(function (group) {
            group.forEach(function (edge, index) {
                lanes.set(edge.id, index - (group.length - 1) / 2);
            });
        });
        return lanes;
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
        var reducedMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
        var defaultSelection = normalizedNodes.slice().sort(function (left, right) {
            if (left.anchor !== right.anchor) return left.anchor ? -1 : 1;
            return right.relevance - left.relevance;
        })[0];

        var state = {
            allNodes: normalizedNodes,
            allEdges: normalizedEdges,
            nodeById: nodeById,
            showContext: true,
            mode: 'overview',
            selectedNodeId: defaultSelection ? defaultSelection.id : null,
            selectedEdgeId: null,
            searchQuery: '',
            visible: null,
            layout: null,
            nodeElements: new Map(),
            edgeElements: new Map(),
            autoFit: true,
            destroyed: false
        };

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
        var svg = createSvgElement('svg', {
            'role': 'img',
            'aria-label': t('impactmap.aria.diagram', normalizedNodes.length, 0),
            'tabindex': '0'
        });
        var defs = createSvgElement('defs');
        var viewport = createSvgElement('g', { 'class': 'impact-map-viewport' });
        svg.appendChild(defs);
        svg.appendChild(viewport);
        canvas.appendChild(svg);
        root.appendChild(canvas);

        var details = createElement('div', 'impact-map-details is-empty');
        details.setAttribute('aria-live', 'polite');
        root.appendChild(details);
        container.appendChild(root);

        var svgSelection = window.d3.select(svg);
        var viewportSelection = window.d3.select(viewport);
        var zoomBehavior = window.d3.zoom()
            .scaleExtent([0.28, 2.8])
            .on('zoom', function (event) {
                viewportSelection.attr('transform', event.transform);
            });
        svgSelection.call(zoomBehavior).on('dblclick.zoom', null);

        function viewportSize() {
            return {
                width: Math.max(320, canvas.clientWidth || container.clientWidth || 900),
                height: Math.max(300, canvas.clientHeight || 540)
            };
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
            if (!state.layout) return;
            var size = viewportSize();
            var padding = 34;
            var scale = Math.min(
                (size.width - padding * 2) / state.layout.width,
                (size.height - padding * 2) / state.layout.height,
                1.25
            );
            scale = clamp(scale, 0.28, 1.25);
            var translateX = (size.width - state.layout.width * scale) / 2;
            var translateY = (size.height - state.layout.height * scale) / 2;
            state.autoFit = true;
            var transform = window.d3.zoomIdentity.translate(translateX, translateY).scale(scale);
            if (reducedMotion) {
                svgSelection.call(zoomBehavior.transform, transform);
            } else {
                svgSelection.transition().duration(220).call(zoomBehavior.transform, transform);
            }
        }

        function centerOnNode(nodeId, requestedScale) {
            var position = state.layout && state.layout.positions.get(nodeId);
            if (!position) return;
            var size = viewportSize();
            var current = window.d3.zoomTransform(svg);
            var scale = requestedScale === undefined
                ? clamp(Math.max(current.k, 1.05), 0.8, 1.55)
                : clamp(requestedScale, 0.55, 1.55);
            var centerX = position.x + position.width / 2;
            var centerY = position.y + position.height / 2;
            applyTransform(window.d3.zoomIdentity
                .translate(size.width / 2 - centerX * scale, size.height / 2 - centerY * scale)
                .scale(scale));
        }

        function showReadableInitialView() {
            if (!state.layout) return;
            var size = viewportSize();
            var padding = 34;
            var fullFitScale = Math.min(
                (size.width - padding * 2) / state.layout.width,
                (size.height - padding * 2) / state.layout.height,
                1.25
            );
            if (fullFitScale >= 0.62 || !state.selectedNodeId) {
                fitView();
            } else {
                centerOnNode(state.selectedNodeId, 0.82);
            }
        }

        function updateViewBox() {
            var size = viewportSize();
            svg.setAttribute('viewBox', '0 0 ' + size.width + ' ' + size.height);
        }

        function setSelection(nodeId, edgeId, center) {
            state.selectedNodeId = nodeId || null;
            state.selectedEdgeId = edgeId || null;
            updateInteractionStyles();
            renderDetails();
            if (center && nodeId) centerOnNode(nodeId);
        }

        function matchingNodeIds() {
            var query = state.searchQuery.trim().toLocaleLowerCase();
            if (!query) return [];
            return state.allNodes.filter(function (node) {
                return node.id.toLocaleLowerCase().indexOf(query) >= 0 ||
                    node.title.toLocaleLowerCase().indexOf(query) >= 0 ||
                    node.sheetLabel.toLocaleLowerCase().indexOf(query) >= 0;
            }).map(function (node) { return node.id; });
        }

        function updateStatus() {
            if (!state.visible) return;
            var matches = matchingNodeIds();
            if (state.searchQuery.trim()) {
                var visibleMatchCount = matches.filter(function (id) {
                    return state.visible.nodeIds.has(id);
                }).length;
                status.textContent = t('impactmap.status.search', matches.length, visibleMatchCount);
            } else {
                status.textContent = t(
                    'impactmap.status.visible',
                    state.visible.nodes.length,
                    state.allNodes.length,
                    state.visible.edges.length
                );
            }
            if (state.visible.omitted > 0) {
                status.textContent += ' ' + t('impactmap.status.omitted', state.visible.omitted);
            }
        }

        function updateKpis() {
            var visibleNodes = state.visible.nodes;
            var direct = visibleNodes.filter(function (node) { return node.anchor; }).length;
            var hotspots = visibleNodes.filter(function (node) { return node.hotspot; }).length;
            directKpi._value.textContent = t('impactmap.kpi.direct', direct);
            elementKpi._value.textContent = t('impactmap.kpi.elements', visibleNodes.length);
            relationKpi._value.textContent = t('impactmap.kpi.relationships', state.visible.edges.length);
            layerKpi._value.textContent = t('impactmap.kpi.layers', state.layout.layers.length);
            hotspotKpi._value.textContent = t('impactmap.kpi.hotspots', hotspots);
            hotspotKpi.hidden = hotspots === 0;
        }

        function renderMarkers() {
            defs.replaceChildren();
            var types = new Set(state.visible.edges.map(function (edge) { return edge.type; }));
            types.forEach(function (type) {
                var markerId = instanceId + '-arrow-' + type.replace(/[^a-z0-9_-]/gi, '-');
                var marker = createSvgElement('marker', {
                    'id': markerId,
                    'viewBox': '0 -5 10 10',
                    'refX': '9',
                    'refY': '0',
                    'markerWidth': '6',
                    'markerHeight': '6',
                    'orient': 'auto',
                    'markerUnits': 'strokeWidth'
                });
                marker.appendChild(createSvgElement('path', {
                    'd': 'M0,-5L10,0L0,5Z',
                    'fill': relationColor(type)
                }));
                defs.appendChild(marker);
            });
        }

        function renderLayers() {
            state.layout.layers.forEach(function (layer) {
                var group = createSvgElement('g', { 'class': 'impact-map-layer' });
                var color = layer.color;
                group.appendChild(createSvgElement('rect', {
                    'class': 'impact-map-layer-column',
                    'x': layer.x - 10,
                    'y': layer.y,
                    'width': layer.width + 20,
                    'height': layer.height,
                    'rx': 12,
                    'fill': rgba(color, 0.075)
                }));
                group.appendChild(createSvgElement('rect', {
                    'class': 'impact-map-layer-header',
                    'x': layer.x,
                    'y': layer.y + 7,
                    'width': CARD_WIDTH,
                    'height': LAYER_HEADER_HEIGHT,
                    'rx': 8,
                    'fill': color,
                    'stroke': rgba(color, 0.72)
                }));
                var textColor = readableTextColor(color);
                appendText(group, 'impact-map-layer-title', layer.x + 12, layer.y + 30,
                    layer.label, textColor);
                var countText = appendText(group, 'impact-map-layer-count', layer.x + CARD_WIDTH - 12,
                    layer.y + 30, String(layer.nodes.length), textColor);
                countText.setAttribute('text-anchor', 'end');
                viewport.appendChild(group);
            });
        }

        function renderEdges() {
            var lanes = relationshipLanes(state.visible.edges);
            var showAllLabels = state.visible.edges.length <= 14;
            state.visible.edges.forEach(function (edge) {
                var source = state.layout.positions.get(edge.source);
                var target = state.layout.positions.get(edge.target);
                if (!source || !target) return;
                var pathData = edgePath(source, target, lanes.get(edge.id) || 0);
                var markerId = instanceId + '-arrow-' + edge.type.replace(/[^a-z0-9_-]/gi, '-');
                var group = createSvgElement('g', {
                    'class': 'impact-map-edge-group',
                    'data-edge-id': edge.id,
                    'data-source': edge.source,
                    'data-target': edge.target
                });
                var path = createSvgElement('path', {
                    'class': 'impact-map-edge',
                    'd': pathData,
                    'stroke': relationColor(edge.type),
                    'marker-end': 'url(#' + markerId + ')'
                });
                var hit = createSvgElement('path', {
                    'class': 'impact-map-edge-hit',
                    'd': pathData,
                    'tabindex': '0',
                    'role': 'button',
                    'aria-label': t('impactmap.aria.relationship', edge.type, edge.source, edge.target)
                });
                var tooltip = createSvgElement('title');
                tooltip.textContent = edge.source + ' → ' + edge.target + '\n' + edge.type +
                    (edge.includedBecause ? '\n' + edge.includedBecause : '');
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
                group.appendChild(path);
                group.appendChild(hit);

                var sourceCenterX = source.x + source.width / 2;
                var targetCenterX = target.x + target.width / 2;
                var sourceCenterY = source.y + source.height / 2;
                var targetCenterY = target.y + target.height / 2;
                var label = appendText(
                    group,
                    'impact-map-edge-label',
                    (sourceCenterX + targetCenterX) / 2,
                    (sourceCenterY + targetCenterY) / 2 - 5,
                    edge.type,
                    relationColor(edge.type)
                );
                label.setAttribute('text-anchor', 'middle');
                label.style.display = showAllLabels ? '' : 'none';
                group._label = label;
                viewport.appendChild(group);
                state.edgeElements.set(edge.id, group);
            });
        }

        function renderNodes() {
            state.visible.nodes.forEach(function (node) {
                var position = state.layout.positions.get(node.id);
                if (!position) return;
                var color = nodeColor(node.sheet);
                var textColor = readableTextColor(color);
                var groupClass = 'impact-map-node';
                if (node.anchor) groupClass += ' is-anchor';
                if (node.hotspot) groupClass += ' is-hotspot';
                var group = createSvgElement('g', {
                    'class': groupClass,
                    'transform': 'translate(' + position.x + ',' + position.y + ')',
                    'data-node-id': node.id,
                    'tabindex': '0',
                    'role': 'button',
                    'aria-label': t(
                        'impactmap.aria.node',
                        node.id,
                        node.title,
                        Math.round(node.relevance * 100),
                        node.anchor ? t('impactmap.direct') : t('impactmap.hop', node.hop)
                    )
                });
                group.appendChild(createSvgElement('rect', {
                    'class': 'impact-map-node-card',
                    'x': 0,
                    'y': 0,
                    'width': CARD_WIDTH,
                    'height': CARD_HEIGHT,
                    'rx': 9,
                    'fill': color
                }));

                appendText(group, 'impact-map-node-code', 12, 19, node.id, textColor);

                var scoreWidth = 56;
                group.appendChild(createSvgElement('rect', {
                    'class': 'impact-map-node-score-pill',
                    'x': CARD_WIDTH - scoreWidth - 8,
                    'y': 7,
                    'width': scoreWidth,
                    'height': 22,
                    'rx': 11,
                    'fill': rgba(textColor, textColor === '#ffffff' ? 0.18 : 0.12)
                }));
                var score = (node.anchor ? '★ ' : '') + Math.round(node.relevance * 100) + '%';
                var scoreText = appendText(group, 'impact-map-node-score', CARD_WIDTH - 36, 22, score, textColor);
                scoreText.setAttribute('text-anchor', 'middle');

                var titleLines = wrapLabel(node.title, 32, 2);
                titleLines.forEach(function (line, index) {
                    appendText(group, 'impact-map-node-title', 12, 43 + index * 15, line, textColor);
                });

                var meta = node.anchor ? t('impactmap.direct') : t('impactmap.hop', node.hop);
                appendText(group, 'impact-map-node-meta', 12, 77, meta + ' · ' + node.sheetLabel, textColor);

                if (node.hotspot) {
                    var hotspotText = appendText(group, 'impact-map-hotspot-mark', CARD_WIDTH - 13, 80, '⚠', '#b42318');
                    hotspotText.setAttribute('text-anchor', 'end');
                }

                var tooltip = createSvgElement('title');
                tooltip.textContent = node.id + ' — ' + node.title + '\n' +
                    node.sheetLabel + '\n' + Math.round(node.relevance * 100) + '%\n' +
                    (node.includedBecause || '') +
                    (node.hotspotReason ? '\n' + t('impactmap.hotspot') + ': ' + node.hotspotReason : '');
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
                    } else if (event.key.toLocaleLowerCase() === 'f') {
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

        function updateInteractionStyles() {
            var selectedNode = state.selectedNodeId;
            var selectedEdge = state.selectedEdgeId;
            var connectedNodeIds = new Set();
            var connectedEdgeIds = new Set();
            if (selectedNode) {
                connectedNodeIds.add(selectedNode);
                state.visible.edges.forEach(function (edge) {
                    if (edge.source === selectedNode || edge.target === selectedNode) {
                        connectedNodeIds.add(edge.source);
                        connectedNodeIds.add(edge.target);
                        connectedEdgeIds.add(edge.id);
                    }
                });
            }

            var matches = new Set(matchingNodeIds());
            var hasSearch = state.searchQuery.trim().length > 0;

            state.nodeElements.forEach(function (element, id) {
                element.classList.toggle('is-selected', id === selectedNode);
                element.classList.toggle('is-search-match', hasSearch && matches.has(id));
                var mutedBySelection = Boolean(selectedNode) && !connectedNodeIds.has(id);
                var mutedBySearch = hasSearch && !matches.has(id);
                element.classList.toggle('is-muted', mutedBySelection || mutedBySearch);
            });

            state.edgeElements.forEach(function (element, id) {
                var edge = state.visible.edges.find(function (candidate) { return candidate.id === id; });
                var connected = connectedEdgeIds.has(id);
                element.classList.toggle('is-selected', id === selectedEdge);
                element.classList.toggle('is-connected', connected);
                element.classList.toggle('is-muted', Boolean(selectedNode) && !connected);
                if (element._label) {
                    element._label.style.display = state.visible.edges.length <= 14 || connected || id === selectedEdge
                        ? '' : 'none';
                }
                if (hasSearch && edge) {
                    var searchRelevant = matches.has(edge.source) || matches.has(edge.target);
                    element.classList.toggle('is-muted', !searchRelevant);
                }
            });
            updateStatus();
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
            if (!state.visible.nodeIds.has(nodeId)) {
                state.showContext = true;
                contextCheckbox.checked = true;
                state.mode = 'overview';
                state.selectedNodeId = nodeId;
                state.selectedEdgeId = null;
                renderDiagram(false);
            } else {
                setSelection(nodeId, null, true);
            }
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
            actions.appendChild(detailsButton(t('impactmap.focus.element'), function () {
                state.selectedNodeId = node.id;
                state.selectedEdgeId = null;
                state.mode = 'focus';
                renderDiagram(true);
            }));
            primary.appendChild(actions);
            details.appendChild(primary);

            var related = createElement('div', 'impact-map-related');
            related.appendChild(createElement('div', 'impact-map-related-title', t('impactmap.related')));
            var relatedList = createElement('div', 'impact-map-related-list');
            var relationships = state.visible.edges.filter(function (edge) {
                return edge.source === node.id || edge.target === node.id;
            }).slice(0, 12);
            if (relationships.length === 0) {
                relatedList.appendChild(createElement('div', 'text-muted small', t('impactmap.no.relationships')));
            } else {
                relationships.forEach(function (edge) {
                    var outgoing = edge.source === node.id;
                    var otherId = outgoing ? edge.target : edge.source;
                    var other = state.nodeById.get(otherId);
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
            var sourceNode = state.nodeById.get(edge.source);
            var targetNode = state.nodeById.get(edge.target);
            related.appendChild(createElement(
                'div',
                'small',
                (sourceNode ? sourceNode.title : edge.source) + ' → ' + (targetNode ? targetNode.title : edge.target)
            ));
            details.appendChild(related);
        }

        function renderDetails() {
            if (state.selectedNodeId) {
                var node = state.nodeById.get(state.selectedNodeId);
                if (node) {
                    renderNodeDetails(node);
                    return;
                }
            }
            if (state.selectedEdgeId) {
                var edge = state.allEdges.find(function (candidate) {
                    return candidate.id === state.selectedEdgeId;
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
            overviewButton.setAttribute('aria-pressed', state.mode === 'overview' ? 'true' : 'false');
            focusButton.setAttribute('aria-pressed', state.mode === 'focus' ? 'true' : 'false');
            focusButton.disabled = !state.selectedNodeId;
        }

        function renderDiagram(autoFit) {
            if (state.destroyed) return;
            state.visible = visibleModel(state);
            state.layout = buildLayout(state.visible);
            state.nodeElements.clear();
            state.edgeElements.clear();
            viewport.replaceChildren();
            renderMarkers();
            renderLayers();
            renderEdges();
            renderNodes();
            updateViewBox();
            updateKpis();
            updateModeButtons();
            updateInteractionStyles();
            renderDetails();
            svg.setAttribute('aria-label', t(
                'impactmap.aria.diagram',
                state.visible.nodes.length,
                state.layout.layers.length
            ));
            if (autoFit !== false) {
                window.requestAnimationFrame(fitView);
            }
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
            var matches = matchingNodeIds();
            if (matches.length === 0) return;
            var first = matches[0];
            if (!state.visible.nodeIds.has(first)) {
                state.showContext = true;
                contextCheckbox.checked = true;
                state.mode = 'overview';
                state.selectedNodeId = first;
                state.selectedEdgeId = null;
                renderDiagram(false);
                status.textContent = t('impactmap.search.hidden');
            } else {
                setSelection(first, null, true);
            }
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
            if (!state.showContext && state.selectedNodeId) {
                var selected = state.nodeById.get(state.selectedNodeId);
                if (selected && selected.context) {
                    state.selectedNodeId = null;
                    state.selectedEdgeId = null;
                    state.mode = 'overview';
                }
            }
            renderDiagram(true);
        });
        overviewButton.addEventListener('click', function () {
            state.mode = 'overview';
            renderDiagram(true);
        });
        focusButton.addEventListener('click', function () {
            if (!state.selectedNodeId) {
                status.textContent = t('impactmap.focus.empty');
                return;
            }
            state.mode = 'focus';
            renderDiagram(true);
        });
        zoomInButton.addEventListener('click', function () {
            state.autoFit = false;
            svgSelection.call(zoomBehavior.scaleBy, 1.25);
        });
        zoomOutButton.addEventListener('click', function () {
            state.autoFit = false;
            svgSelection.call(zoomBehavior.scaleBy, 0.8);
        });
        fitButton.addEventListener('click', fitView);
        fullscreenButton.addEventListener('click', function () {
            if (document.fullscreenElement === root) {
                document.exitFullscreen();
            } else if (root.requestFullscreen) {
                root.requestFullscreen();
            }
        });

        svg.addEventListener('click', function (event) {
            if (event.target === svg) {
                setSelection(null, null, false);
            }
        });

        function onFullscreenChange() {
            var full = document.fullscreenElement === root;
            fullscreenButton.title = full ? t('impactmap.exit.fullscreen') : t('impactmap.fullscreen');
            fullscreenButton.setAttribute('aria-label', fullscreenButton.title);
            window.setTimeout(function () {
                updateViewBox();
                fitView();
            }, 0);
        }
        document.addEventListener('fullscreenchange', onFullscreenChange);

        var resizeTimer = null;
        var resizeObserver = typeof ResizeObserver !== 'undefined'
            ? new ResizeObserver(function () {
                window.clearTimeout(resizeTimer);
                resizeTimer = window.setTimeout(function () {
                    updateViewBox();
                    if (state.autoFit) fitView();
                }, 80);
            })
            : null;
        if (resizeObserver) resizeObserver.observe(canvas);

        container.__taxonomyImpactMapCleanup = function () {
            state.destroyed = true;
            window.clearTimeout(resizeTimer);
            document.removeEventListener('fullscreenchange', onFullscreenChange);
            if (resizeObserver) resizeObserver.disconnect();
            svgSelection.on('.zoom', null);
            delete container.__taxonomyImpactMapCleanup;
        };

        renderDiagram(false);
        window.requestAnimationFrame(showReadableInitialView);
        return state;
    }

    function install() {
        if (!window.TaxonomyGraph || typeof window.TaxonomyGraph.renderImpactForceGraph !== 'function') {
            console.warn('[Taxonomy] Impact-map workbench could not replace the graph renderer.');
            return;
        }
        if (!window.TaxonomyGraph.renderImpactForceGraphLegacy) {
            window.TaxonomyGraph.renderImpactForceGraphLegacy = window.TaxonomyGraph.renderImpactForceGraph;
        }
        window.TaxonomyGraph.renderImpactForceGraph = renderImpactMap;
        window.TaxonomyImpactMap = {
            render: renderImpactMap,
            normalizeNodes: normalizeNodes,
            normalizeEdges: normalizeEdges,
            buildLayout: buildLayout,
            visibleModel: visibleModel
        };
    }

    install();
}());
