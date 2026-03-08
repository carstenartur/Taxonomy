/* taxonomy-export.js – Export functionality for NATO NC3T Taxonomy Browser */

(function () {
    'use strict';

    /**
     * Export the current SVG from the given container as a standalone SVG file.
     * Inlines essential CSS so the SVG renders correctly without external stylesheets.
     * @param {string} containerId - ID of the element that contains the SVG.
     */
    function exportSvg(containerId) {
        var container = document.getElementById(containerId || 'taxonomyTree');
        if (!container) { return; }
        var svgEl = container.querySelector('svg');
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
     * Export the current SVG as a PNG file at 2× resolution.
     * @param {string} containerId - ID of the element that contains the SVG.
     */
    function exportPng(containerId) {
        var container = document.getElementById(containerId || 'taxonomyTree');
        if (!container) { return; }
        var svgEl = container.querySelector('svg');
        if (!svgEl) {
            alert('No SVG found in current view. Switch to Sunburst, Tree, or Decision view.');
            return;
        }

        var w = parseFloat(svgEl.getAttribute('width')) || svgEl.clientWidth || 800;
        var h = parseFloat(svgEl.getAttribute('height')) || svgEl.clientHeight || 400;
        var scale = 2;

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
        exportCsv: exportCsv,
        exportVisio: exportVisio,
        exportArchiMate: exportArchiMate,
        exportJson: exportJson
    };

})();
