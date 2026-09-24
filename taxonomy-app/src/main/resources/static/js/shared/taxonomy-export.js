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
        // An explicit container is a view-level capture. The main Export action is
        // model-level and must not inherit transient zoom, pan, focus or filter state.
        if (containerId) {
            var svg = standaloneSvg(containerId);
            if (!svg) return unavailable('export.no.svg');
            downloadBlob(
                new Blob([serializeSvg(svg)], { type: 'image/svg+xml;charset=utf-8' }),
                'taxonomy-view.svg'
            );
            return true;
        }
        var requirement = document.getElementById('businessText');
        return diagramDownload('/api/diagram/svg', requirement ? requirement.value : '',
            'requirement-architecture.svg', 'text');
    }

    function rasterizeSvg(svgText, scaleFactor, filename) {
        var parsed = new DOMParser().parseFromString(svgText, 'image/svg+xml');
        var svg = parsed.documentElement;
        if (!svg || svg.localName !== 'svg' || parsed.querySelector('parsererror')) {
            return Promise.reject(new Error(exportMessage('The architecture SVG is invalid.',
                'Das Architektur-SVG ist ungültig.')));
        }
        var width = dimension(svg, 'width', 800);
        var height = dimension(svg, 'height', 400);
        var resolution = document.getElementById('pngResolution');
        var scale = scaleFactor || (resolution ? parseInt(resolution.value, 10) : 2) || 2;
        var blob = new Blob([svgText], { type: 'image/svg+xml;charset=utf-8' });
        var url = URL.createObjectURL(blob);
        return new Promise(function (resolve, reject) {
            var image = new Image();
            image.onload = function () {
                var canvas = document.createElement('canvas');
                canvas.width = Math.ceil(width * scale);
                canvas.height = Math.ceil(height * scale);
                var context = canvas.getContext('2d');
                context.fillStyle = '#ffffff';
                context.fillRect(0, 0, canvas.width, canvas.height);
                context.scale(scale, scale);
                context.drawImage(image, 0, 0, width, height);
                URL.revokeObjectURL(url);
                canvas.toBlob(function (png) {
                    if (!png) {
                        reject(new Error(exportMessage('PNG conversion failed.',
                            'PNG-Konvertierung fehlgeschlagen.')));
                        return;
                    }
                    downloadBlob(png, filename);
                    resolve(true);
                }, 'image/png');
            };
            image.onerror = function () {
                URL.revokeObjectURL(url);
                reject(new Error(exportMessage('PNG conversion failed.',
                    'PNG-Konvertierung fehlgeschlagen.')));
            };
            image.src = url;
        });
    }

    function exportPng(containerId, scaleFactor) {
        // Explicit callers keep the view-capture contract. The main export button
        // obtains a fresh deterministic full-model SVG from the server first.
        if (containerId) {
            var viewSvg = standaloneSvg(containerId);
            if (!viewSvg) return unavailable('export.no.svg');
            return rasterizeSvg(serializeSvg(viewSvg), scaleFactor, 'taxonomy-view.png')
                .catch(function (error) { return unavailable('export.png.failed', error.message); });
        }
        if (diagramExportBusy) return Promise.resolve(false);
        var state = window.TaxonomyState;
        var requirement = document.getElementById('businessText');
        var businessText = requirement ? requirement.value : '';
        if (!state || !state.currentArchView || !Array.isArray(state.currentArchView.includedElements)
                || !state.currentArchView.includedElements.length) {
            return unavailable('export.png.failed', exportMessage(
                'No existing architecture view is available. No new analysis was started.',
                'Keine vorhandene Architekturansicht verfügbar. Es wurde keine neue Analyse gestartet.'));
        }
        if (typeof state.lastAnalyzedText !== 'string' || !state.lastAnalyzedText.trim()
                || businessText !== state.lastAnalyzedText) {
            return unavailable('export.png.failed', exportMessage(
                'The requirement changed after analysis. Load the matching analysis before exporting.',
                'Die Anforderung wurde nach der Analyse geändert. Laden Sie vor dem Export die passende Analyse.'));
        }
        var headers = { 'Content-Type': 'application/json' };
        var csrf = document.querySelector('meta[name="_csrf"]');
        var csrfHeader = document.querySelector('meta[name="_csrf_header"]');
        if (csrf && csrfHeader) headers[csrfHeader.content] = csrf.content;
        diagramExportBusy = true;
        diagramStatus(exportMessage('Creating complete architecture PNG…',
            'Vollständiges Architektur-PNG wird erstellt…'), true, false);
        return fetch('/api/diagram/current/svg', {
            method: 'POST',
            headers: headers,
            credentials: 'same-origin',
            body: JSON.stringify(state.currentArchView)
        }).then(function (response) {
            if (!response.ok) {
                return response.json().catch(function () { return {}; }).then(function (problem) {
                    throw new Error(problem.error || problem.detail || 'HTTP ' + response.status);
                });
            }
            validateResponseType(response, 'requirement-architecture.svg');
            return response.text();
        }).then(function (svgText) {
            return rasterizeSvg(svgText, scaleFactor, 'requirement-architecture.png');
        }).then(function () {
            diagramStatus(exportMessage('Download ready. Zoom and pan were not exported.',
                'Download bereitgestellt. Zoom und Verschiebung wurden nicht exportiert.'), false, false);
            return true;
        }).catch(function (error) {
            diagramStatus(exportMessage('Export failed: ', 'Export fehlgeschlagen: ') + error.message, false, true);
            return false;
        }).finally(function () {
            diagramExportBusy = false;
        });
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
        var architecturePanel = document.getElementById('architectureViewPanel');
        var architectureActive = architecturePanel && architecturePanel.style.display !== 'none';
        if (state && state.currentArchView && architectureActive) {
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
            'blob');
    }

    function exportSparx(text) {
        if (diagramExportBusy) return Promise.resolve(false);
        if (!window.confirm(exportMessage(
                'Export an experimental fresh copy for Sparx EA? The ZIP contains XMI and a mapping/loss report. Some relations are labelled associations, not native semantic equivalents. Diagram layout is not included. Existing EA models and synchronization checkpoints are not updated.',
                'Experimentelle neue Kopie für Sparx EA exportieren? Das ZIP enthält XMI und einen Abbildungs-/Verlustbericht. Einige Beziehungen sind beschriftete Assoziationen, keine nativen semantischen Entsprechungen. Diagrammlayout ist nicht enthalten. Vorhandene EA-Modelle und Synchronisationsstände werden nicht aktualisiert.'))) return Promise.resolve(false);
        return diagramDownload('/api/diagram/sparx', text, 'requirement-architecture-sparx.zip', 'blob');
    }

    function exportArchiMate(text) {
        return diagramDownload('/api/diagram/archimate', text, 'requirement-architecture.xml',
            'blob');
    }

    function exportMermaid(text) {
        return diagramDownload('/api/diagram/mermaid', text, 'requirement-architecture.mmd',
            'text');
    }

    function exportStructurizrDsl(text) {
        return diagramDownload('/api/diagram/structurizr', text, 'workspace.dsl',
            'blob');
    }

    var diagramExportBusy = false;
    var diagramButtons = ['exportVisio', 'exportSparx', 'exportArchiMate', 'exportMermaid', 'exportStructurizr'];

    function exportMessage(english, german) {
        return (document.documentElement.lang || '').toLowerCase().startsWith('de') ? german : english;
    }

    function diagramStatus(message, busy, failed) {
        var status = document.getElementById('diagramExportStatus');
        if (!status) {
            status = document.createElement('div');
            status.id = 'diagramExportStatus';
            status.setAttribute('role', 'status');
            status.setAttribute('aria-live', 'polite');
            var spinner = document.createElement('span');
            spinner.id = 'diagramExportSpinner';
            spinner.className = 'spinner-border spinner-border-sm me-2';
            spinner.setAttribute('aria-hidden', 'true');
            status.appendChild(spinner);
            var label = document.createElement('span');
            label.id = 'diagramExportStatusText';
            status.appendChild(label);
            (document.getElementById('exportGroup') || document.body).appendChild(status);
        }
        status.className = 'alert w-100 mt-2 mb-0 ' + (failed ? 'alert-danger' : busy ? 'alert-info' : 'alert-success');
        status.setAttribute('aria-busy', String(busy));
        document.getElementById('diagramExportSpinner').hidden = !busy;
        document.getElementById('diagramExportStatusText').textContent = message;
        // Keep errors visible even when the export panel is collapsed or on another tab.
        // The established alert bridge supplies persistent, accessible global feedback.
        if (failed) alert(message);
    }

    function validateResponseType(response, filename) {
        var type = (response.headers && response.headers.get('Content-Type') || '').split(';')[0].trim().toLowerCase();
        var expected = filename.endsWith('.vsdx') ? ['application/vnd.ms-visio.drawing']
            : filename.endsWith('.zip') ? ['application/zip']
            : filename.endsWith('.svg') ? ['image/svg+xml']
            : filename.endsWith('.xml') ? ['application/xml', 'text/xml'] : ['text/plain'];
        if (response.redirected || expected.indexOf(type) < 0) {
            throw new Error(exportMessage('The server did not return the requested file format. Sign in again and retry; no file was downloaded.',
                'Der Server hat nicht das angeforderte Dateiformat geliefert. Melden Sie sich erneut an und versuchen Sie es noch einmal; keine Datei wurde heruntergeladen.'));
        }
    }

    // PKWARE APPNOTE 4.3.7 / 4.3.9 / 4.3.12: a directory entry must point
    // to a matching local header and a complete, non-overlapping data range.
    // Only small headers/names/descriptors are read. Payloads are never inflated.
    async function localZipRange(blob, entry, expectedName, directoryOffset) {
        var start = entry.getUint32(42, true);
        var compressed = entry.getUint32(20, true), uncompressed = entry.getUint32(24, true);
        var flags = entry.getUint16(8, true), method = entry.getUint16(10, true), crc = entry.getUint32(16, true);
        if (entry.getUint16(34, true) !== 0 || (flags & 0x2041) !== 0
                || (method !== 0 && method !== 8) || start + 30 > directoryOffset
                || compressed === 0xffffffff || uncompressed === 0xffffffff
                || (method === 0 && compressed !== uncompressed)) return null;
        var header = new DataView(await blob.slice(start, start + 30).arrayBuffer());
        var nameLength = header.getUint16(26, true), extraLength = header.getUint16(28, true);
        var dataStart = start + 30 + nameLength + extraLength, end = dataStart + compressed;
        if (header.getUint32(0, true) !== 0x04034b50 || header.getUint16(6, true) !== flags
                || header.getUint16(8, true) !== method || nameLength !== expectedName.length
                || dataStart > directoryOffset || end > directoryOffset) return null;
        var actualName = new Uint8Array(await blob.slice(start + 30, start + 30 + nameLength).arrayBuffer());
        if (actualName.some(function (byte, index) { return byte !== expectedName[index]; })) return null;
        if (flags & 8) {
            // Streaming writers such as ZipOutputStream put sizes/CRC in a descriptor.
            // Both signed and unsigned descriptors are part of the ZIP specification.
            if (end + 12 > directoryOffset) return null;
            var descriptor = new DataView(await blob.slice(end, Math.min(end + 16, directoryOffset)).arrayBuffer());
            var matches = function (at) {
                return descriptor.byteLength >= at + 12 && descriptor.getUint32(at, true) === crc
                    && descriptor.getUint32(at + 4, true) === compressed && descriptor.getUint32(at + 8, true) === uncompressed;
            };
            if (descriptor.getUint32(0, true) === 0x08074b50 && matches(4)) end += 16;
            else if (matches(0)) end += 12;
            else return null;
            if ((header.getUint32(14, true) !== 0 && header.getUint32(14, true) !== crc)
                    || (header.getUint32(18, true) !== 0 && header.getUint32(18, true) !== compressed)
                    || (header.getUint32(22, true) !== 0 && header.getUint32(22, true) !== uncompressed)) return null;
        } else if (header.getUint32(14, true) !== crc || header.getUint32(18, true) !== compressed
                || header.getUint32(22, true) !== uncompressed) return null;
        return { start: start, end: end, nonempty: compressed > 0 && uncompressed > 0 };
    }

    async function validateDownloadBytes(blob, filename) {
        // A transport guard, not a replacement for the server's OPC/schema validation.
        // Read bounded ZIP metadata, never inflate arbitrary archive content.
        var zip = filename.endsWith('.vsdx') || filename.endsWith('.zip');
        var invalid = !blob.size;
        if (zip && !invalid) {
            var head = new Uint8Array(await blob.slice(0, 4).arrayBuffer());
            invalid = blob.size < 52 || head[0] !== 0x50 || head[1] !== 0x4b || head[2] !== 3 || head[3] !== 4;
            var tail = new Uint8Array(await blob.slice(-65557).arrayBuffer());
            var end = -1;
            for (var i = tail.length - 22; i >= 0; i--) {
                if (tail[i] === 0x50 && tail[i + 1] === 0x4b && tail[i + 2] === 5 && tail[i + 3] === 6
                        && i + 22 + tail[i + 20] + 256 * tail[i + 21] === tail.length) {
                    end = i; break;
                }
            }
            invalid = invalid || end < 0;
            if (!invalid) {
                var record = new DataView(tail.buffer, tail.byteOffset + end, 22);
                var count = record.getUint16(10, true), length = record.getUint32(12, true), offset = record.getUint32(16, true);
                var endOffset = blob.size - tail.length + end;
                invalid = record.getUint16(4, true) !== 0 || record.getUint16(6, true) !== 0
                    || record.getUint16(8, true) !== count || !count || count > 128
                    || length > 262144 || offset + length !== endOffset;
                if (!invalid) {
                    var directory = new Uint8Array(await blob.slice(offset, offset + length).arrayBuffer());
                    var names = new Map(), ranges = [], cursor = 0;
                    for (var n = 0; n < count && !invalid; n++) {
                        if (cursor + 46 > directory.length) { invalid = true; break; }
                        var entry = new DataView(directory.buffer, directory.byteOffset + cursor);
                        var nameLength = entry.getUint16(28, true);
                        var next = cursor + 46 + nameLength + entry.getUint16(30, true) + entry.getUint16(32, true);
                        invalid = entry.getUint32(0, true) !== 0x02014b50 || !!(entry.getUint16(8, true) & 1)
                            || !nameLength || nameLength > 1024 || next > directory.length;
                        if (invalid) break;
                        var nameBytes = directory.subarray(cursor + 46, cursor + 46 + nameLength);
                        var name = String.fromCharCode.apply(null, nameBytes);
                        var range = await localZipRange(blob, entry, nameBytes, offset);
                        if (names.has(name) || !range) { invalid = true; break; }
                        names.set(name, range.nonempty); ranges.push(range); cursor = next;
                    }
                    var required = filename.endsWith('.vsdx')
                        ? ['[Content_Types].xml', 'visio/document.xml', 'visio/pages/pages.xml']
                        : ['architecture.xmi', 'manifest.json', 'README.txt'];
                    ranges.sort(function (left, right) { return left.start - right.start; });
                    invalid = invalid || cursor !== directory.length || required.some(function (name) { return !names.get(name); })
                        || ranges.some(function (range, index) { return index > 0 && ranges[index - 1].end > range.start; });
                }
            }
        } else if (!invalid) {
            var prefix = (await blob.slice(0, 512).text()).trimStart();
            invalid = /^(?:<!doctype\s+html|<html\b|<head\b|<body\b)/i.test(prefix);
        }
        if (invalid) throw new Error(exportMessage('The downloaded file is empty, incomplete or not the requested format. No file was saved.',
            'Die gelieferte Datei ist leer, unvollständig oder nicht im angeforderten Format. Keine Datei wurde gespeichert.'));
    }

    function diagramDownload(url, businessText, filename, responseType) {
        if (diagramExportBusy) return Promise.resolve(false);
        var state = window.TaxonomyState;
        if (!state || !state.currentArchView || !Array.isArray(state.currentArchView.includedElements)
                || !state.currentArchView.includedElements.length) {
            diagramStatus(exportMessage('No existing architecture view is available. No new analysis was started.',
                'Keine vorhandene Architekturansicht verfügbar. Es wurde keine neue Analyse gestartet.'), false, true);
            return Promise.resolve(false);
        }
        if (typeof state.lastAnalyzedText !== 'string' || !state.lastAnalyzedText.trim()
                || businessText !== state.lastAnalyzedText) {
            diagramStatus(exportMessage('The analysed text baseline is missing or the requirement has changed. Load the matching analysis before exporting.',
                'Der analysierte Anforderungstext fehlt oder wurde geändert. Laden Sie vor dem Export die passende Analyse.'), false, true);
            return Promise.resolve(false);
        }
        var body;
        try {
            // Freeze exactly the current view at the click, not a later mutable state.
            body = JSON.stringify(state.currentArchView);
        } catch (error) {
            diagramStatus(exportMessage('The architecture could not be serialized.',
                'Die Architektur konnte nicht serialisiert werden.'), false, true);
            return Promise.resolve(false);
        }
        var controls = diagramButtons.map(function (id) { return document.getElementById(id); })
            .filter(Boolean).map(function (button) { return { button: button, disabled: button.disabled }; });
        controls.forEach(function (control) { control.button.disabled = true; });
        diagramExportBusy = true;
        diagramStatus(exportMessage('Creating file from the existing architecture — no AI analysis…',
            'Datei wird aus der vorhandenen Architektur erstellt — ohne KI-Analyse…'), true, false);
        var headers = { 'Content-Type': 'application/json' };
        var csrf = document.querySelector('meta[name="_csrf"]');
        var csrfHeader = document.querySelector('meta[name="_csrf_header"]');
        if (csrf && csrfHeader) headers[csrfHeader.content] = csrf.content;
        // Keep base-path/workspace routing through the established fetch wrapper.
        return Promise.resolve().then(function () {
            return fetch(url.replace('/api/diagram/', '/api/diagram/current/'), {
                method: 'POST', headers: headers, credentials: 'same-origin', body: body
            });
        }).then(function (response) {
            if (!response.ok) {
                return response.json().catch(function () { return {}; }).then(function (problem) {
                    throw new Error(problem.error || problem.detail || 'HTTP ' + response.status);
                });
            }
            validateResponseType(response, filename);
            diagramStatus(exportMessage('Receiving architecture file…', 'Architekturdatei wird übertragen…'), true, false);
            return responseType === 'text' ? response.text() : response.blob();
        }).then(async function (content) {
            var blob = content instanceof Blob ? content : new Blob([content], { type: 'text/plain;charset=utf-8' });
            await validateDownloadBytes(blob, filename);
            downloadBlob(blob, filename);
            diagramStatus(exportMessage('Download ready. The architecture was kept unchanged.',
                'Download bereitgestellt. Die Architektur blieb unverändert.'), false, false);
            return true;
        }).catch(function (error) {
            diagramStatus(exportMessage('Export failed: ', 'Export fehlgeschlagen: ') + error.message, false, true);
            return false;
        }).finally(function () {
            controls.forEach(function (control) { control.button.disabled = control.disabled; });
            diagramExportBusy = false;
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
        exportSparx: exportSparx,
        exportArchiMate: exportArchiMate,
        exportMermaid: exportMermaid,
        exportStructurizrDsl: exportStructurizrDsl,
        exportJson: exportJson,
        exportDot: exportDot,
        exportMermaidTree: exportMermaidTree
    });

    function installSparxExportButton() {
        var reference = document.getElementById('exportVisio');
        if (!reference) return;
        var button = document.getElementById('exportSparx');
        var created = !button;
        if (created) {
            button = document.createElement('button');
            button.id = 'exportSparx'; button.type = 'button'; button.className = 'btn btn-outline-info';
            reference.insertAdjacentElement('afterend', button);
            // Existing templates have no legacy listener for this new button.
            button.addEventListener('click', function () {
                if (reference.disabled || button.disabled || diagramExportBusy) return;
                var requirement = document.getElementById('businessText');
                exportSparx(requirement ? requirement.value : '');
            });
            var enabled = function () { button.disabled = reference.disabled; };
            enabled();
            new MutationObserver(enabled).observe(reference, { attributes: true, attributeFilter: ['disabled'] });
        }
        button.textContent = exportMessage('Sparx EA / XMI (copy, experimental)', 'Sparx EA / XMI (Kopie, experimentell)');
        button.title = exportMessage('XMI + mapping report. Not a synchronization checkpoint or EA diagram export.',
            'XMI + Abbildungsbericht. Kein Synchronisationsstand und kein EA-Diagrammexport.');
        // Accent-border colors are not necessarily readable foreground colors.
        diagramButtons.forEach(function (id) {
            var control = document.getElementById(id);
            if (!control) return;
            var colors = {
                '--bs-btn-color': 'var(--bs-body-color)',
                '--bs-btn-border-color': 'var(--bs-secondary-color)',
                '--bs-btn-hover-color': 'var(--bs-body-bg)',
                '--bs-btn-hover-bg': 'var(--bs-body-color)',
                '--bs-btn-hover-border-color': 'var(--bs-body-color)',
                '--bs-btn-active-color': 'var(--bs-body-bg)',
                '--bs-btn-active-bg': 'var(--bs-body-color)'
            };
            Object.keys(colors).forEach(function (key) { control.style.setProperty(key, colors[key]); });
        });
    }

    installPdfRouteGuard();
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', installSparxExportButton, { once: true });
    else if (document.readyState) installSparxExportButton();
}());
