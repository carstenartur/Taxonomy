// Browser API substitutes only; assertions and test discovery belong to JUnit.
const fs = require('node:fs');
const vm = require('node:vm');

class Element {
    constructor(tag, attributes = {}) {
        this.localName = tag;
        this.attributes = {...attributes};
        this.children = [];
        this.style = {};
        this.namespaceURI = 'http://www.w3.org/2000/svg';
    }
    setAttribute(key, value) { this.attributes[key] = String(value); }
    getAttribute(key) { return this.attributes[key] ?? null; }
    cloneNode() {
        const copy = new Element(this.localName, this.attributes);
        copy.children = this.children.map(child => child.cloneNode(true));
        return copy;
    }
    querySelectorAll() { return []; }
    querySelector() { return null; }
    insertBefore(child) { this.children.unshift(child); }
    appendChild(child) { this.children.push(child); }
    click() {}
    remove() {}
}

async function probe(source, scenario) {
    const downloads = [], requests = [], pdfs = [], alerts = [], filenames = [];
    const viewport = new Element('svg', {width: '800', height: '400', viewBox: '0 0 800 400'});
    viewport.children.push(new Element('g', {transform: 'translate(-1000,0) scale(2)'}));
    const complete = new Element('svg', {width: '1600', height: '1100', viewBox: '0 0 1600 1100'});
    complete.children.push(new Element('text', {'data-node-code': 'CP-outside-viewport'}));
    const container = {querySelector: () => viewport, querySelectorAll: () => []};
    const elements = {
        impactGraphView: container, taxonomyTree: container,
        architectureViewPanel: {style: {display: scenario === 'pdf-hidden-panel' ? 'none' : ''}},
        businessText: {value: scenario === 'pdf-stale' ? 'Changed requirement' : 'Accepted requirement'},
        diagramExportStatus: new Element('div'),
        diagramExportSpinner: new Element('span'),
        diagramExportStatusText: new Element('span')
    };
    const document = {
        documentElement: {lang: 'en'},
        getElementById: id => elements[id] || null,
        querySelector: selector => selector === '#impactGraphView svg' ? viewport : null,
        createElementNS: (_, tag) => new Element(tag),
        createElement: tag => {
            const element = new Element(tag);
            if (tag === 'a') element.click = () => filenames.push(element.download);
            return element;
        },
        body: new Element('body'), addEventListener() {}
    };
    const state = {
        currentArchView: {includedElements: [{nodeCode: 'CP-outside-viewport'}], includedRelationships: []},
        lastAnalyzedText: 'Accepted requirement', lastAnalysisStatus: 'COMPLETE',
        analysisOperationId: 'accepted-operation', analysisProviderId: 'LOCAL_ONNX'
    };
    if (scenario === 'pdf-large') {
        state.currentArchView.includedElements = Array.from({length: 500}, (_, index) => ({
            nodeCode: index === 0 ? 'CP-outside-viewport' : 'CP-' + index,
            title: 'Full model label COMPLETE-LABEL-SUFFIX-' + index
        }));
        state.currentArchView.includedRelationships = [{relationId: 7, sourceCode: 'CP-outside-viewport',
            targetCode: 'CP-499', relationType: 'DEPENDS_ON', confidence: 0,
            derivationReason: 'RECORDED-RELATION-EVIDENCE'}];
    }
    const window = {
        TaxonomyState: state,
        TaxonomyUtils: {isTaxonomyContainer: node => node.code === 'CP'},
        setTimeout() {},
        jspdf: {jsPDF: class {
            constructor(options) { this.record = {options}; pdfs.push(this.record); }
            svg(svg, options) {
                this.record.svg = {attributes: svg.attributes, children: svg.children.map(child => ({tag: child.localName, attributes: child.attributes}))};
                this.record.renderOptions = options;
                return Promise.resolve();
            }
            save(filename) { this.record.filename = filename; }
        }}
    };
    if (scenario === 'pdf-no-client-renderer') delete window.jspdf;
    vm.runInNewContext(source, {
        window, document, Element, Blob, TaxonomyI18n: {t: key => key},
        URL: {createObjectURL: blob => { downloads.push(blob); return 'blob:test'; }, revokeObjectURL() {}},
        DOMParser: class {
            parseFromString() { return {documentElement: complete.cloneNode(true), querySelector: () => null}; }
        },
        fetch: async (url, options) => {
            requests.push({url, ...options});
            if (url === '/api/scores/export') return {ok: true, json: async () => JSON.parse(options.body)};
            if (url === '/api/diagram/current/pdf') {
                // Transport fixture only; real PDF generation and contents are verified by Java/PDFBox.
                const bytes = scenario === 'pdf-truncated' ? '%PDF-1.7\nTRUNCATED'
                    : scenario === 'pdf-wrong-signature' ? 'wrong-file\n%%EOF\n'
                    : '%PDF-1.7\nSERVER-DOCUMENT-BYTES\n%%EOF\n';
                if (scenario === 'pdf-large') state.currentArchView.includedElements.length = 0;
                return {ok: true, redirected: scenario === 'pdf-login',
                    headers: {get: () => scenario === 'pdf-wrong-type' ? 'text/html' : 'application/pdf'},
                    blob: async () => new Blob([bytes], {type: 'application/pdf'})};
            }
            return {ok: true, redirected: false, headers: {get: () => 'image/svg+xml'},
                text: async () => '<svg xmlns="http://www.w3.org/2000/svg" width="1600" height="1100"><text data-node-code="CP-outside-viewport">Outside viewport</text></svg>'};
        },
        alert: message => alerts.push(message)
    }, {filename: 'taxonomy-export.js'});
    if (scenario === 'csv') {
        window.TaxonomyExport.exportCsv({zero: 0, fraction: 0.125, invalid: NaN},
            [{code: 'CP', name: 'Capabilities', children: [{code: 'zero', name: 'Explicit zero'}, {code: 'fraction', name: 'Fraction'}]}]);
    } else if (scenario === 'json') {
        window.TaxonomyExport.exportJson({zero: 0}, {}, 'Accepted requirement', 'LOCAL_ONNX');
    } else {
        await window.TaxonomyExport.exportPdf(scenario === 'pdf-explicit' ? 'impactGraphView' : undefined);
    }
    await new Promise(resolve => setImmediate(resolve));
    return {requests, pdfs, alerts, filenames, downloads: await Promise.all(downloads.map(blob => blob.text())),
        status: elements.diagramExportStatusText.textContent};
}

probe(fs.readFileSync(process.argv[2], 'utf8'), process.argv[3])
    .then(result => process.stdout.write(JSON.stringify(result)))
    .catch(error => { process.stderr.write(error.stack); process.exitCode = 1; });
