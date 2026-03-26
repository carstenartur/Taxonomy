/**
 * CodeMirror 6 integration for the TaxDSL editor.
 *
 * Provides:
 *  - Custom TaxDSL v2 syntax highlighting via StreamLanguage
 *  - Context-aware autocompletion
 *  - Live validation (debounced lint via /api/dsl/validate)
 *  - Dark-mode theme switching
 *  - Shift+Alt+F format shortcut (delegates to window.dslFormatContent)
 *
 * Exposes window.dslCmView (EditorView) once ready, and dispatches a
 * 'cm-ready' CustomEvent on #dslEditorContainer.
 */

import {
    EditorView, basicSetup,
    StreamLanguage, HighlightStyle, syntaxHighlighting,
    tags,
    oneDark,
    linter, lintGutter,
    autocompletion,
    Compartment,
    keymap,
    MergeView
} from '/js/vendor/codemirror-bundle.mjs';

// ── TaxDSL token sets ──────────────────────────────────────────────────
const BLOCK_KEYWORDS = new Set([
    'element', 'relation', 'mapping', 'view', 'evidence',
    'requirement', 'meta', 'constraint', 'decision', 'pattern',
    // Provenance block types (v2.1)
    'source', 'sourceVersion', 'sourceFragment', 'requirementSourceLink', 'candidate'
]);

const DOMAIN_TYPES = new Set([
    'Capability', 'Process', 'Service', 'CoreService', 'Application',
    'InformationProduct', 'UserApplication', 'System', 'Component',
    'COIService', 'CommunicationsService', 'BusinessRole'
]);

const RELATION_TYPES = new Set([
    'REALIZES', 'SUPPORTS', 'CONSUMES', 'USES', 'FULFILLS',
    'ASSIGNED_TO', 'DEPENDS_ON', 'PRODUCES', 'COMMUNICATES_WITH',
    'RELATED_TO', 'CONTAINS', 'REQUIRES'
]);

const PROPERTY_KEYS = new Set([
    'title', 'description', 'taxonomy', 'status', 'confidence',
    'provenance', 'score', 'source', 'text', 'layout', 'include',
    'for-relation', 'type', 'model', 'summary'
]);

const STATUS_VALUES = new Set(['accepted', 'proposed', 'provisional', 'rejected']);

const PROVENANCE_VALUES = new Set(['manual', 'llm-inferred', 'imported', 'propagated']);

const BLOCK_KEYWORD_INFO = {
    element:               'Define an architecture element',
    relation:              'Define a typed relation between elements',
    meta:                  'Document metadata block',
    view:                  'Define an architecture view',
    evidence:              'Define evidence supporting an element or relation',
    requirement:           'Define a requirement that elements must fulfill',
    mapping:               'Map elements to requirements or external frameworks',
    constraint:            'Define an architectural constraint',
    decision:              'Record an architecture decision',
    pattern:               'Define an architecture pattern',
    source:                'Define a provenance source artifact',
    sourceVersion:         'Define a specific version of a source artifact',
    sourceFragment:        'Define a fragment within a source version',
    requirementSourceLink: 'Link a requirement to a source fragment',
    candidate:             'Define a candidate element for the architecture'
};

const PROPERTY_DESCRIPTIONS = {
    title:          'Element or block title',
    description:    'Detailed description text',
    taxonomy:       'Taxonomy classification code',
    status:         'Element status (accepted, proposed, provisional, rejected)',
    confidence:     'Confidence score (0.0 to 1.0)',
    provenance:     'Origin of the element (manual, llm-inferred, imported, propagated)',
    score:          'Numeric score value',
    source:         'Source reference',
    text:           'Free-text content',
    layout:         'View layout configuration',
    include:        'Include elements in a view',
    'for-relation': 'Target relation for evidence',
    type:           'Element domain type',
    model:          'Model identifier',
    summary:        'Brief summary'
};

// ── Dynamic taxonomy code cache ────────────────────────────────────────
let cachedTaxCodes = null;

async function fetchTaxCodes() {
    if (cachedTaxCodes) return cachedTaxCodes;
    try {
        const resp = await fetch('/api/taxonomy');
        const data = await resp.json();
        cachedTaxCodes = extractAllCodes(data);
        return cachedTaxCodes;
    } catch {
        return [];
    }
}

function extractAllCodes(node) {
    const codes = [];
    if (Array.isArray(node)) {
        for (const child of node) codes.push(...extractAllCodes(child));
    } else if (node && typeof node === 'object') {
        if (node.code) codes.push({ code: node.code, title: node.title || '' });
        if (node.children) {
            for (const child of node.children) codes.push(...extractAllCodes(child));
        }
    }
    return codes;
}

// ── TaxDSL StreamLanguage tokenizer ───────────────────────────────────
const taxDslMode = {
    startState: () => ({ braceDepth: 0 }),

    token(stream, state) {
        // Whitespace
        if (stream.eatSpace()) return null;

        // Line comment
        if (stream.peek() === '#') {
            stream.skipToEnd();
            return 'comment';
        }

        // String literal
        if (stream.peek() === '"') {
            stream.next();
            while (!stream.eol()) {
                const ch = stream.next();
                if (ch === '\\') { stream.next(); continue; }
                if (ch === '"') break;
            }
            return 'string';
        }

        // Braces (with depth tracking for context-dependent keywords)
        if (stream.eat('{')) { state.braceDepth++; return 'bracket'; }
        if (stream.eat('}')) { if (state.braceDepth > 0) state.braceDepth--; return 'bracket'; }

        // Punctuation
        if (stream.eat(';') || stream.eat(':')) return 'punctuation';

        // Numbers (bare float/int)
        if (stream.match(/^\d+(\.\d+)?/)) return 'number';

        // Words / identifiers
        if (stream.match(/^[A-Za-z_][\w-]*/)) {
            const word = stream.current();
            // "source" is uniquely context-dependent: it is both a block keyword (at line start:
            // source SRC-001 { ... }) and a property key (inside a block: source: "...").
            // Other provenance keywords (sourceVersion, etc.) are only block keywords.
            if (word === 'source') {
                return state.braceDepth > 0 ? 'propertyName' : 'keyword';
            }
            if (BLOCK_KEYWORDS.has(word))  return 'keyword';
            if (DOMAIN_TYPES.has(word))    return 'typeName';
            if (RELATION_TYPES.has(word))  return 'operatorKeyword';
            if (STATUS_VALUES.has(word))   return 'bool';
            if (PROPERTY_KEYS.has(word))   return 'propertyName';
            // Taxonomy codes: 2-letter prefix + 4 digits (e.g. CP-1023, BP-1327)
            // Also matches extended IDs like REQ-001, EV-001 used in DSL for requirements/evidence
            if (/^[A-Z]{2,4}-\d+$/.test(word)) return 'variableName';
            return null;
        }

        stream.next();
        return null;
    }
};

const taxDslLanguage = StreamLanguage.define(taxDslMode);

// ── Autocompletion ─────────────────────────────────────────────────────
async function taxDslCompletions(context) {
    const word = context.matchBefore(/[\w-]*/);
    if (!word || (word.from === word.to && !context.explicit)) return null;

    const line = context.state.doc.lineAt(context.pos);
    const before = line.text.slice(0, context.pos - line.from);
    const trimmed = before.trimStart();

    // After 'element <ID> type '
    if (/^element\s+[A-Z]{2,4}-\d+\s+type\s+[\w-]*$/.test(trimmed)) {
        return {
            from: word.from,
            options: [...DOMAIN_TYPES].map(t => ({ label: t, type: 'type' }))
        };
    }

    // After 'type: '  (property value context)
    if (/\btype\s*:\s*[\w-]*$/.test(trimmed)) {
        return {
            from: word.from,
            options: [...DOMAIN_TYPES].map(t => ({ label: t, type: 'type' }))
        };
    }

    // After 'relation <ID> ' — suggest relation types
    if (/^relation\s+[A-Z]{2,4}-\d+\s+[\w-]*$/.test(trimmed)) {
        return {
            from: word.from,
            options: [...RELATION_TYPES].map(t => ({ label: t, type: 'keyword' }))
        };
    }

    // After 'status: '
    if (/\bstatus\s*:\s*[\w-]*$/.test(trimmed)) {
        return {
            from: word.from,
            options: [...STATUS_VALUES].map(v => ({ label: v, type: 'constant' }))
        };
    }

    // After 'confidence: '
    if (/\bconfidence\s*:\s*[\d.]*$/.test(trimmed)) {
        return {
            from: word.from,
            options: Array.from({ length: 11 }, (_, i) => ({
                label: (i / 10).toFixed(1),
                type: 'constant',
                info: i === 0 ? 'No confidence' : i === 10 ? 'Full confidence' : undefined
            }))
        };
    }

    // After 'provenance: '
    if (/\bprovenance\s*:\s*[\w-]*$/.test(trimmed)) {
        return {
            from: word.from,
            options: [...PROVENANCE_VALUES].map(v => ({ label: v, type: 'constant' }))
        };
    }

    // At the start of a line (no indentation) — suggest block keywords
    if (/^[\w-]*$/.test(before)) {
        const kwOptions = [...BLOCK_KEYWORDS].map(k => ({
            label: k,
            type: 'keyword',
            info: BLOCK_KEYWORD_INFO[k]
        }));
        return { from: word.from, options: kwOptions };
    }

    // After 'element ' — suggest taxonomy codes for the element ID
    if (/^element\s+[\w-]*$/.test(trimmed)) {
        const codes = await fetchTaxCodes();
        if (codes.length > 0) {
            return {
                from: word.from,
                options: codes.map(c => ({
                    label: c.code,
                    type: 'variable',
                    info: c.title || 'Taxonomy code'
                }))
            };
        }
    }

    // Inside a block body (indented line) — suggest property keys
    if (/^\s+[\w-]*$/.test(before)) {
        return {
            from: word.from,
            options: [...PROPERTY_KEYS].map(k => ({
                label: k + ':',
                type: 'property',
                info: PROPERTY_DESCRIPTIONS[k] || '',
                apply: k + ': '
            }))
        };
    }

    return null;
}

// ── Live validation linter ─────────────────────────────────────────────
let lintTimer = null;

const taxDslLinter = linter(view => {
    return new Promise(resolve => {
        clearTimeout(lintTimer);
        lintTimer = setTimeout(() => {
            const text = view.state.doc.toString();
            fetch('/api/dsl/validate', {
                method: 'POST',
                headers: { 'Content-Type': 'text/plain' },
                body: text
            })
            .then(r => r.json())
            .then(data => {
                const diags = [];
                const addDiag = (msg, severity) => {
                    const message = typeof msg === 'string' ? msg : JSON.stringify(msg);
                    // Try to parse line number from message like "line 3: ..."
                    let from = 0;
                    let to = Math.min(text.length, 1);
                    const lineMatch = message.match(/line\s+(\d+)/i);
                    if (lineMatch) {
                        const lineNo = parseInt(lineMatch[1], 10);
                        if (lineNo >= 1 && lineNo <= view.state.doc.lines) {
                            const line = view.state.doc.line(lineNo);
                            from = line.from;
                            to = line.to;
                        }
                    }
                    diags.push({ from, to, severity, message });
                };
                if (data.errors)   for (const e of data.errors)   addDiag(e, 'error');
                if (data.warnings) for (const w of data.warnings) addDiag(w, 'warning');
                resolve(diags);
            })
            .catch(() => resolve([]));
        }, 500);
    });
}, { delay: 500 });

// ── Theme compartment ──────────────────────────────────────────────────
const themeCompartment = new Compartment();

// ── Light-mode syntax highlighting ────────────────────────────────────
const taxDslLightHighlight = syntaxHighlighting(HighlightStyle.define([
    { tag: tags.keyword,         color: '#7c3aed', fontWeight: 'bold' },  // Purple — element, relation, meta, view…
    { tag: tags.typeName,        color: '#d97706' },                       // Orange — Capability, Service, Process…
    { tag: tags.operatorKeyword, color: '#dc2626', fontWeight: 'bold' },   // Red — REALIZES, SUPPORTS, USES…
    { tag: tags.propertyName,    color: '#059669' },                       // Teal — title, description, status…
    { tag: tags.string,          color: '#16a34a' },                       // Green — "Secure Voice Communications"
    { tag: tags.comment,         color: '#6b7280', fontStyle: 'italic' },  // Grey italic — # comment
    { tag: tags.variableName,    color: '#2563eb' },                       // Blue — CP-1023, BP-1327, REQ-001
    { tag: tags.number,          color: '#0891b2' },                       // Cyan — 0.85, 42
    { tag: tags.bool,            color: '#ea580c' },                       // Orange — accepted, proposed, rejected
    { tag: tags.bracket,         color: '#64748b' },                       // Slate — { }
    { tag: tags.punctuation,     color: '#94a3b8' },                       // Light grey — : ;
]));

function getCurrentTheme() {
    return document.documentElement.getAttribute('data-bs-theme') === 'dark'
        ? oneDark
        : taxDslLightHighlight;
}

// ── Merge / diff view ──────────────────────────────────────────────────

/**
 * Creates a side-by-side merge/diff view using @codemirror/merge.
 * @param {HTMLElement} container - The DOM element to mount the merge view into.
 * @param {string} originalDoc - The original (left) document text.
 * @param {string} modifiedDoc - The modified (right) document text.
 * @returns {MergeView} The merge view instance.
 */
export function createMergeView(container, originalDoc, modifiedDoc) {
    container.innerHTML = '';
    const mv = new MergeView({
        a: {
            doc: originalDoc,
            extensions: [
                EditorView.editable.of(false),
                getCurrentTheme()
            ]
        },
        b: {
            doc: modifiedDoc,
            extensions: [
                EditorView.editable.of(false),
                getCurrentTheme()
            ]
        },
        parent: container,
        collapseUnchanged: { margin: 3, minSize: 4 }
    });
    return mv;
}

// ── Editor initialization ──────────────────────────────────────────────
document.addEventListener('DOMContentLoaded', () => {
    const container = document.getElementById('dslEditorContainer');
    if (!container) return;

    const view = new EditorView({
        doc: '',
        extensions: [
            basicSetup,
            taxDslLanguage,
            autocompletion({ override: [taxDslCompletions] }),
            taxDslLinter,
            lintGutter(),
            themeCompartment.of(getCurrentTheme()),
            EditorView.theme({
                '&': { height: 'auto', minHeight: '300px', fontSize: '0.82rem' },
                '.cm-scroller': {
                    fontFamily: 'var(--bs-font-monospace, monospace)',
                    overflow: 'auto',
                    maxHeight: '500px'
                }
            }),
            keymap.of([{
                key: 'Shift-Alt-f',
                run: () => {
                    if (typeof window.dslFormatContent === 'function') {
                        window.dslFormatContent();
                    }
                    return true;
                }
            }])
        ],
        parent: container
    });

    window.dslCmView = view;
    container.dispatchEvent(new CustomEvent('cm-ready'));

    // Pre-fetch taxonomy codes for autocompletion
    fetchTaxCodes();

    // Sync theme when dark mode toggles
    const observer = new MutationObserver(() => {
        view.dispatch({
            effects: themeCompartment.reconfigure(getCurrentTheme())
        });
    });
    observer.observe(document.documentElement, {
        attributes: true,
        attributeFilter: ['data-bs-theme']
    });
});
