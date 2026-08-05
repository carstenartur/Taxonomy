/* taxonomy-export.js – Deterministic export adapters for Taxonomy browser views. */
(function () {
    'use strict';

    var t = TaxonomyI18n.t;

    function currentSvg(containerId) {
        var container = document.getElementById(containerId || 'taxonomyTree');
        if (!container) return null;
        var svg = container.querySelector('svg');
        if (!svg && container._canvasRenderer
                && window.TaxonomyViews && window.TaxonomyViews.buildExportSVG) {
            svg = window.TaxonomyViews.buildExportSVG(
                container._canvasData || [],
                container._canvasScores || null,
                { expandAll: true }
            );
        }
        return svg;
    }

    function standaloneSvg(containerId) {
        var source = currentSvg(containerId);
        if (!source) return null;
        var clone = source.cloneNode(true);
        clone.setAttribute('xmlns', 'http://www.w3.org/2000/svg');
        var style = document.createElementNS('http://www.w3.org/2000/svg', 'style');
        style.textContent = [
            'text { font-family: Arial, Helvetica, sans-serif; }',
            '.tv-link, .dm-link { fill: none; }',
            '.tv-node text, .dm-node text { font-size: 12px; }',
            '.dm-rank { font-size: 13px; }'
        ].join('\n');
        clone.insertBefore(style, clone.firstChild);
        return clone;
    }

    function serializeSvg(svg) {
        return new XMLSerializer().serializeToString(svg);
    }

    function exportSvg(containerId) {
        var svg = standaloneSvg(containerId);
        if (!svg) return unavailable('export.no.svg');
        downloadBlob(
            new Blob([serializeSvg(svg)], { type: 'image/svg+xml;charset=utf-8' }),
            'taxonomy-view.svg'
        );
    }

    function exportPng(containerId, scaleFactor) {
        var svg = standaloneSvg(containerId);
        if (!svg) return unavailable('export.no.svg');
        var width = dimension(svg, 'width', 800);
        var height = dimension(svg, 'height', 400);
        var resolution = document.getElementById('pngResolution');
        var scale = scaleFactor || (resolution ? parseInt(resolution.value, 10) : 2) || 2;
        var blob = new Blob([serializeSvg(svg)], { type: 'image/svg+xml;charset=utf-8' });
        var url = URL.createObjectURL(blob);
        var image = new Image();
        image.onload = function () {
            var canvas = document.createElement('canvas');
            canvas.width = width * scale;
            canvas.height = height * scale;
            var context = canvas.getContext('2d');
            context.fillStyle = '#ffffff';
            context.fillRect(0, 0, canvas.width, canvas.height);
            context.scale(scale, scale);
            context.drawImage(image, 0, 0);
            URL.revokeObjectURL(url);
            canvas.toBlob(function (png) {
                if (png) downloadBlob(png, 'taxonomy-view.png');
            }, 'image/png');
        };
        image.onerror = function () {
            URL.revokeObjectURL(url);
            unavailable('export.png.failed');
        };
        image.src = url;
    }

    /**
     * Resolve the semantic export target. Once an architecture result exists,
     * PDF means the architecture diagram and must never silently degrade to the
     * taxonomy tree or to a screenshot of the surrounding page.
     */
    function resolvePdfTarget(containerId, filename) {
        if (containerId) {
            return {
                containerId: containerId,
                filename: filename || 'taxonomy-view.pdf'
            };
        }

        var state = window.TaxonomyState;
        if (state && state.currentArchView) {
            if (document.querySelector('#impactGraphView svg')) {
                return {
                    containerId: 'impactGraphView',
                    filename: 'requirement-architecture.pdf'
                };
            }
            return {
                error: 'The architecture result has no vector graph to export. '
                    + 'Open the Architecture view and select Network Graph first.'
            };
        }

        return {
            containerId: 'taxonomyTree',
            filename: filename || 'taxonomy-view.pdf'
        };
    }

    function exportPdf(containerId, filename) {
        var target = resolvePdfTarget(containerId, filename);
        if (target.error) return unavailable('export.pdf.failed', target.error);

        var svg = standaloneSvg(target.containerId);
        if (!svg) return unavailable('export.no.svg');
        if (typeof window.jspdf === 'undefined') {
            return unavailable('export.pdf.failed', 'PDF renderer is unavailable');
        }
        var PDF = window.jspdf.jsPDF;
        var width = dimension(svg, 'width', 800);
        var height = dimension(svg, 'height', 400);
        var documentPdf = new PDF({
            orientation: width > height ? 'landscape' : 'portrait',
            unit: 'pt',
            format: [width, height]
        });
        if (typeof documentPdf.svg !== 'function') {
            return unavailable('export.pdf.failed', 'Vector SVG renderer is unavailable');
        }
        documentPdf.svg(svg, { x: 0, y: 0, width: width, height: height })
            .then(function () { documentPdf.save(target.filename); })
            .catch(function (error) {
                unavailable('export.pdf.failed', error.message);
            });
    }

    function exportCsv(scores, taxonomyData) {
        if (!scores || !taxonomyData) return;
        var paths = {}, names = {}, levels = {};
        function visit(node, ancestors, level) {
            names[node.code] = node.name || '';
            levels[node.code] = level;
            paths[node.code] = ancestors.concat(node.name ? node.code + ' ' + node.name : node.code);
            (node.children || []).forEach(function (child) {
                visit(child, paths[node.code], level + 1);
            });
        }
        taxonomyData.forEach(function (root) { visit(root, [], 0); });
        var lines = ['Rank,Code,Name,Score,Path,Level'];
        Object.entries(scores)
            .filter(function (entry) { return entry[1] > 0; })
            .sort(function (left, right) { return right[1] - left[1]; })
            .forEach(function (entry, index) {
                var code = entry[0];
                lines.push([
                    index + 1,
                    csvField(code),
                    csvField(names[code] || ''),
                    entry[1],
                    csvField((paths[code] || []).join(' > ')),
                    levels[code] || 0
                ].join(','));
            });
        downloadBlob(
            new Blob(['\uFEFF' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8' }),
            'taxonomy-scores.csv'
        );
    }

    function exportJson(scores, reasons, businessText, provider) {
        if (!scores || Object.keys(scores).length === 0) {
            return unavailable('export.json.no.scores');
        }
        postJson('/api/scores/export', {
            requirement: businessText || '',
            scores: scores,
            reasons: reasons || {},
            provider: provider || ''
        }).then(function (data) {
            downloadBlob(
                new Blob([JSON.stringify(data, null, 2)], { type: 'application/json;charset=utf-8' }),
                'taxonomy-scores-' + new Date().toISOString().slice(0, 10) + '.json'
            );
        }).catch(function (error) {
            unavailable('export.json.failed', error.message);
        });
    }

    function exportVisio(text) {
        return diagramDownload('/api/diagram/visio', text, 'requirement-architecture.vsdx',
            'export.visio.no.text', 'export.visio.failed', 'blob');
    }

    function exportArchiMate(text) {
        return diagramDownload('/api/diagram/archimate', text, 'requirement-architecture.xml',
            'export.archimate.no.text', 'export.archimate.failed', 'blob');
    }

    function exportMermaid(text) {
        return diagramDownload('/api/diagram/mermaid', text, 'requirement-architecture.mmd',
            'export.mermaid.no.text', 'export.mermaid.failed', 'text');
    }

    function exportStructurizrDsl(text) {
        return diagramDownload('/api/diagram/structurizr', text, 'workspace.dsl',
            'export.structurizr.no.text', 'export.structurizr.failed', 'blob');
    }

    function diagramDownload(url, businessText, filename, missingKey, failedKey, responseType) {
        if (!businessText || !businessText.trim()) return unavailable(missingKey);
        return fetch(url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            credentials: 'same-origin',
            body: JSON.stringify({ businessText: businessText })
        }).then(requireOk).then(function (response) {
            return responseType === 'text' ? response.text() : response.blob();
        }).then(function (content) {
            var blob = content instanceof Blob
                ? content
                : new Blob([content], { type: 'text/plain;charset=utf-8' });
            downloadBlob(blob, filename);
        }).catch(function (error) {
            unavailable(failedKey, error.message);
        });
    }

    function exportDot(scores, taxonomyData) {
        if (!taxonomyData || !window.TaxonomyViews || !window.TaxonomyViews.buildDotExport) {
            return unavailable('export.dot.no.data');
        }
        downloadBlob(
            new Blob([window.TaxonomyViews.buildDotExport(taxonomyData, scores || {})],
                { type: 'text/vnd.graphviz;charset=utf-8' }),
            'taxonomy-tree.dot'
        );
    }

    function exportMermaidTree(scores, taxonomyData) {
        if (!taxonomyData || !window.TaxonomyViews || !window.TaxonomyViews.buildMermaidTreeExport) {
            return unavailable('export.mermaid.tree.no.data');
        }
        downloadBlob(
            new Blob([window.TaxonomyViews.buildMermaidTreeExport(taxonomyData, scores || {})],
                { type: 'text/plain;charset=utf-8' }),
            'taxonomy-tree.mmd'
        );
    }

    function postJson(url, body) {
        return fetch(url, {
            method: 'POST',
            headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
            credentials: 'same-origin',
            body: JSON.stringify(body)
        }).then(requireOk).then(function (response) { return response.json(); });
    }

    function requireOk(response) {
        if (response.ok) return response;
        throw new Error('HTTP ' + response.status);
    }

    function unavailable(key, detail) {
        var message;
        try {
            var translated = detail ? t(key, detail) : t(key);
            message = translated === key && detail ? detail : translated;
        } catch (ignored) {
            message = detail || 'The requested export is unavailable.';
        }
        alert(message);
        return false;
    }

    function dimension(svg, attribute, fallback) {
        var explicit = parseFloat(svg.getAttribute(attribute));
        if (explicit) return explicit;
        if (svg.viewBox && svg.viewBox.baseVal) {
            var viewBoxValue = attribute === 'width'
                ? svg.viewBox.baseVal.width
                : svg.viewBox.baseVal.height;
            if (viewBoxValue) return viewBoxValue;
        }
        return svg[attribute === 'width' ? 'clientWidth' : 'clientHeight'] || fallback;
    }

    function csvField(value) {
        if (value === null || value === undefined) return '';
        var string = String(value);
        return /[,"\n]/.test(string) ? '"' + string.replace(/"/g, '""') + '"' : string;
    }

    function downloadBlob(blob, filename) {
        var url = URL.createObjectURL(blob);
        var anchor = document.createElement('a');
        anchor.href = url;
        anchor.download = filename;
        document.body.appendChild(anchor);
        anchor.click();
        anchor.remove();
        window.setTimeout(function () { URL.revokeObjectURL(url); }, 1000);
    }

    /**
     * Capture PDF clicks before the legacy target listener can execute its old
     * browser-print fallback. The deterministic router above is the sole owner
     * of PDF export semantics.
     */
    function installPdfRouteGuard() {
        document.addEventListener('click', function (event) {
            var target = event.target instanceof Element
                ? event.target.closest('#exportPdf')
                : null;
            if (!target) return;
            event.preventDefault();
            event.stopImmediatePropagation();
            exportPdf();
        }, true);
    }

    window.TaxonomyExport = Object.freeze({
        exportSvg: exportSvg,
        exportPng: exportPng,
        exportPdf: exportPdf,
        exportCsv: exportCsv,
        exportVisio: exportVisio,
        exportArchiMate: exportArchiMate,
        exportMermaid: exportMermaid,
        exportStructurizrDsl: exportStructurizrDsl,
        exportJson: exportJson,
        exportDot: exportDot,
        exportMermaidTree: exportMermaidTree
    });

    installPdfRouteGuard();
}());