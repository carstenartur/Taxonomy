/* taxonomy-export.js – Export functionality for NATO NC3T Taxonomy Browser */

(function () {
    'use strict';

    /**
     * Export the current SVG from the given container as a standalone SVG file.
     * Inlines essential CSS so the SVG renders correctly without external stylesheets.
     * If the container uses a Canvas renderer, generates SVG on-the-fly via buildExportSVG.
     * @param {string} containerId - ID of the element that contains the SVG.
     */
    function exportSvg(containerId) {
        var container = document.getElementById(containerId || 'taxonomyTree');
        if (!container) { return; }
        var svgEl = container.querySelector('svg');

        // Phase 2: If Canvas view is active, generate SVG on-the-fly
        if (!svgEl && container._canvasRenderer && window.TaxonomyViews && window.TaxonomyViews.buildExportSVG) {
            svgEl = window.TaxonomyViews.buildExportSVG(
                container._canvasData || [],
                container._canvasScores || null,
                { expandAll: true }
            );
        }

        if (!svgEl) {
            alert('No SVG found in current view. Switch to Sunburst, Tree, or Decision view.');
            return;
        }

        var clone = svgEl.cloneNode(true);
        clone.setAttribute('xmlns', 'http://www.w3.org/2000/svg');

        // Inline basic styles so the SVG is standalone
        var style = document.createElementNS('http://www.w3.org/2000/svg', 'style');
        style.textContent = [
            'text { font-family: Arial, Helvetica, sans-serif; }',
            '.tv-link, .dm-link { fill: none; }',
            '.tv-node text, .dm-node text { font-size: 12px; }',
            '.dm-rank { font-size: 13px; }'
        ].join('\n');
        clone.insertBefore(style, clone.firstChild);

        var serializer = new XMLSerializer();
        var svgStr = serializer.serializeToString(clone);
        var blob = new Blob([svgStr], { type: 'image/svg+xml;charset=utf-8' });
        downloadBlob(blob, 'taxonomy-view.svg');
    }

    /**
     * Export the current SVG as a PNG file at configurable resolution.
     * Falls back to generating SVG via buildExportSVG when Canvas renderer is active.
     * @param {string} containerId - ID of the element that contains the SVG.
     * @param {number} [scaleFactor] - Resolution scale (1, 2, or 4). Defaults to value from UI or 2.
     */
    function exportPng(containerId, scaleFactor) {
        var container = document.getElementById(containerId || 'taxonomyTree');
        if (!container) { return; }
        var svgEl = container.querySelector('svg');

        // Phase 2: If Canvas view is active, generate SVG on-the-fly
        if (!svgEl && container._canvasRenderer && window.TaxonomyViews && window.TaxonomyViews.buildExportSVG) {
            svgEl = window.TaxonomyViews.buildExportSVG(
                container._canvasData || [],
                container._canvasScores || null,
                { expandAll: true }
            );
        }

        if (!svgEl) {
            alert('No SVG found in current view. Switch to Sunburst, Tree, or Decision view.');
            return;
        }

        var w = parseFloat(svgEl.getAttribute('width')) || svgEl.clientWidth || 800;
        var h = parseFloat(svgEl.getAttribute('height')) || svgEl.clientHeight || 400;

        // Phase 3: support configurable resolution
        var scaleEl = document.getElementById('pngResolution');
        var scale = scaleFactor || (scaleEl ? parseInt(scaleEl.value, 10) : 2) || 2;

        var clone = svgEl.cloneNode(true);
        clone.setAttribute('xmlns', 'http://www.w3.org/2000/svg');
        var serializer = new XMLSerializer();
        var svgStr = serializer.serializeToString(clone);
        var blob = new Blob([svgStr], { type: 'image/svg+xml;charset=utf-8' });
        var url = URL.createObjectURL(blob);

        var img = new Image();
        img.onload = function () {
            var canvas = document.createElement('canvas');
            canvas.width = w * scale;
            canvas.height = h * scale;
            var ctx = canvas.getContext('2d');
            ctx.fillStyle = '#ffffff';
            ctx.fillRect(0, 0, canvas.width, canvas.height);
            ctx.scale(scale, scale);
            ctx.drawImage(img, 0, 0);
            URL.revokeObjectURL(url);
            canvas.toBlob(function (pngBlob) {
                downloadBlob(pngBlob, 'taxonomy-view.png');
            }, 'image/png');
        };
        img.onerror = function () {
            URL.revokeObjectURL(url);
            alert('Failed to export PNG. Try SVG export instead.');
        };
        img.src = url;
    }

    /**
     * Export scores as a CSV file (UTF-8 BOM for Excel compatibility).
     * Columns: Rank, Code, Name, Score, Path, Level
     * @param {Object} scores       - Map of node code → match percentage.
     * @param {Array}  taxonomyData - Array of root taxonomy nodes.
     */
    function exportCsv(scores, taxonomyData) {
        if (!scores || !taxonomyData) { return; }

        var pathMap = {}, nameMap = {}, levelMap = {};

        function walk(node, ancestors, level) {
            nameMap[node.code] = node.name || '';
            levelMap[node.code] = level;
            var display = node.name ? (node.code + ' ' + node.name) : node.code;
            pathMap[node.code] = ancestors.concat(display);
            if (node.children) {
                node.children.forEach(function (child) {
                    walk(child, pathMap[node.code], level + 1);
                });
            }
        }
        taxonomyData.forEach(function (root) { walk(root, [], 0); });

        var sorted = Object.entries(scores)
            .filter(function (e) { return e[1] > 0; })
            .sort(function (a, b) { return b[1] - a[1]; });

        var lines = ['Rank,Code,Name,Score,Path,Level'];
        sorted.forEach(function (e, i) {
            var code = e[0];
            var pct = e[1];
            var name = nameMap[code] || '';
            var path = (pathMap[code] || []).join(' > ');
            var level = levelMap[code] || 0;
            lines.push([
                i + 1,
                csvField(code),
                csvField(name),
                pct,
                csvField(path),
                level
            ].join(','));
        });

        // UTF-8 BOM so Excel opens the file correctly
        var bom = '\uFEFF';
        var csv = bom + lines.join('\r\n');
        var blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
        downloadBlob(blob, 'taxonomy-scores.csv');
    }

    /**
     * Export the architecture view as a Visio .vsdx file via the backend.
     * Calls POST /api/diagram/visio with the business text and triggers a download.
     * @param {string} businessText - The business requirement text used for analysis.
     */
    function exportVisio(businessText) {
        if (!businessText || !businessText.trim()) {
            alert('Please enter a business requirement text before exporting to Visio.');
            return;
        }
        fetch('/api/diagram/visio', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ businessText: businessText })
        })
        .then(function (resp) {
            if (!resp.ok) {
                throw new Error('Export failed (HTTP ' + resp.status + ')');
            }
            return resp.blob();
        })
        .then(function (blob) {
            downloadBlob(blob, 'requirement-architecture.vsdx');
        })
        .catch(function (err) {
            alert('Visio export failed: ' + err.message);
        });
    }

    /**
     * Export the architecture view as an ArchiMate XML file via the backend.
     * Calls POST /api/diagram/archimate with the business text and triggers a download.
     * @param {string} businessText - The business requirement text used for analysis.
     */
    function exportArchiMate(businessText) {
        if (!businessText || !businessText.trim()) {
            alert('Please enter a business requirement text before exporting to ArchiMate.');
            return;
        }
        fetch('/api/diagram/archimate', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ businessText: businessText })
        })
        .then(function (resp) {
            if (!resp.ok) {
                throw new Error('Export failed (HTTP ' + resp.status + ')');
            }
            return resp.blob();
        })
        .then(function (blob) {
            downloadBlob(blob, 'requirement-architecture.xml');
        })
        .catch(function (err) {
            alert('ArchiMate export failed: ' + err.message);
        });
    }

    /**
     * Export the current analysis scores and reasons as a JSON file via the backend.
     * Calls POST /api/scores/export and triggers a download of the resulting SavedAnalysis JSON.
     * @param {Object} scores       - Map of node code → score.
     * @param {Object} reasons      - Map of node code → reason text (may be empty).
     * @param {string} businessText - The business requirement text.
     * @param {string} provider     - LLM provider name (optional).
     */
    function exportJson(scores, reasons, businessText, provider) {
        if (!scores || Object.keys(scores).length === 0) {
            alert('No scores to export. Please run an analysis first.');
            return;
        }
        fetch('/api/scores/export', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                requirement: businessText || '',
                scores: scores,
                reasons: reasons || {},
                provider: provider || ''
            })
        })
        .then(function (resp) {
            if (!resp.ok) {
                throw new Error('Export failed (HTTP ' + resp.status + ')');
            }
            return resp.json();
        })
        .then(function (data) {
            var json = JSON.stringify(data, null, 2);
            var blob = new Blob([json], { type: 'application/json;charset=utf-8' });
            var filename = 'taxonomy-scores-' + new Date().toISOString().slice(0, 10) + '.json';
            downloadBlob(blob, filename);
        })
        .catch(function (err) {
            alert('JSON export failed: ' + err.message);
        });
    }

    /**
     * Export the architecture view as a Mermaid flowchart text file via the backend.
     * Calls POST /api/diagram/mermaid with the business text and triggers a download.
     * @param {string} businessText - The business requirement text used for analysis.
     */
    function exportMermaid(businessText) {
        if (!businessText || !businessText.trim()) {
            alert('Please enter a business requirement text before exporting to Mermaid.');
            return;
        }
        fetch('/api/diagram/mermaid', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ businessText: businessText })
        })
        .then(function (resp) {
            if (!resp.ok) {
                throw new Error('Export failed (HTTP ' + resp.status + ')');
            }
            return resp.text();
        })
        .then(function (mermaidText) {
            var blob = new Blob([mermaidText], { type: 'text/plain;charset=utf-8' });
            downloadBlob(blob, 'requirement-architecture.mmd');
        })
        .catch(function (err) {
            alert('Mermaid export failed: ' + err.message);
        });
    }

    /**
     * Export the taxonomy tree as a DOT (Graphviz) file.
     * @param {Object} scores       - Map of node code → match percentage.
     * @param {Array}  taxonomyData - Array of root taxonomy nodes.
     */
    function exportDot(scores, taxonomyData) {
        if (!taxonomyData || !window.TaxonomyViews || !window.TaxonomyViews.buildDotExport) {
            alert('DOT export requires taxonomy data. Please load the taxonomy first.');
            return;
        }
        var dot = window.TaxonomyViews.buildDotExport(taxonomyData, scores || {});
        var blob = new Blob([dot], { type: 'text/vnd.graphviz;charset=utf-8' });
        downloadBlob(blob, 'taxonomy-tree.dot');
    }

    /**
     * Export the taxonomy tree as a Mermaid diagram file.
     * This exports the full taxonomy hierarchy (not the architecture view).
     * @param {Object} scores       - Map of node code → match percentage.
     * @param {Array}  taxonomyData - Array of root taxonomy nodes.
     */
    function exportMermaidTree(scores, taxonomyData) {
        if (!taxonomyData || !window.TaxonomyViews || !window.TaxonomyViews.buildMermaidTreeExport) {
            alert('Mermaid tree export requires taxonomy data. Please load the taxonomy first.');
            return;
        }
        var mmd = window.TaxonomyViews.buildMermaidTreeExport(taxonomyData, scores || {});
        var blob = new Blob([mmd], { type: 'text/plain;charset=utf-8' });
        downloadBlob(blob, 'taxonomy-tree.mmd');
    }

    /**
     * Export the current view as a vector PDF using jsPDF + svg2pdf.js.
     * Falls back to window.print() if the libraries are not loaded.
     */
    function exportPdf() {
        // Try vector PDF if jsPDF and svg2pdf.js are available
        if (typeof window.jspdf !== 'undefined' && typeof window.svg2pdf !== 'undefined') {
            var container = document.getElementById('taxonomyTree');
            var svgEl = container ? container.querySelector('svg') : null;

            // Generate SVG on-the-fly if Canvas renderer is active
            if (!svgEl && container && container._canvasRenderer && window.TaxonomyViews && window.TaxonomyViews.buildExportSVG) {
                svgEl = window.TaxonomyViews.buildExportSVG(
                    container._canvasData || [],
                    container._canvasScores || null,
                    { expandAll: true }
                );
            }

            if (svgEl) {
                var w = parseFloat(svgEl.getAttribute('width')) || 800;
                var h = parseFloat(svgEl.getAttribute('height')) || 400;
                // Determine page orientation: landscape if wider than tall
                var orientation = w > h ? 'landscape' : 'portrait';
                var jsPDF = window.jspdf.jsPDF;
                var doc = new jsPDF({ orientation: orientation, unit: 'pt', format: [w, h] });
                doc.svg(svgEl, { x: 0, y: 0, width: w, height: h }).then(function () {
                    doc.save('taxonomy-view.pdf');
                });
                return;
            }
        }
        // Fallback to browser print
        window.print();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    function csvField(val) {
        if (val === null || val === undefined) { return ''; }
        var s = String(val);
        if (s.indexOf(',') !== -1 || s.indexOf('"') !== -1 || s.indexOf('\n') !== -1) {
            return '"' + s.replace(/"/g, '""') + '"';
        }
        return s;
    }

    function downloadBlob(blob, filename) {
        var url = URL.createObjectURL(blob);
        var a = document.createElement('a');
        a.href = url;
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        setTimeout(function () { URL.revokeObjectURL(url); }, 1000);
    }

    // ── Public API ─────────────────────────────────────────────────────────────
    window.TaxonomyExport = {
        exportSvg: exportSvg,
        exportPng: exportPng,
        exportPdf: exportPdf,
        exportCsv: exportCsv,
        exportVisio: exportVisio,
        exportArchiMate: exportArchiMate,
        exportMermaid: exportMermaid,
        exportJson: exportJson,
        exportDot: exportDot,
        exportMermaidTree: exportMermaidTree
    };

})();
