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

import { EditorView, basicSetup } from 'https://esm.sh/codemirror@6';
import { StreamLanguage } from 'https://esm.sh/@codemirror/language@6';
import { oneDark } from 'https://esm.sh/@codemirror/theme-one-dark@6';
import { linter, lintGutter } from 'https://esm.sh/@codemirror/lint@6';
import { autocompletion } from 'https://esm.sh/@codemirror/autocomplete@6';
import { Compartment } from 'https://esm.sh/@codemirror/state@6';
import { keymap } from 'https://esm.sh/@codemirror/view@6';

// ── TaxDSL token sets ──────────────────────────────────────────────────
const BLOCK_KEYWORDS = new Set([
    'element', 'relation', 'mapping', 'view', 'evidence',
    'requirement', 'meta', 'constraint', 'decision', 'pattern'
]);

const DOMAIN_TYPES = new Set([
    'Capability', 'Process', 'Service', 'CoreService', 'Application',
    'InformationProduct', 'UserApplication', 'System', 'Component'
]);

const RELATION_TYPES = new Set([
    'REALIZES', 'SUPPORTS', 'CONSUMES', 'USES', 'FULFILLS',
    'ASSIGNED_TO', 'DEPENDS_ON', 'PRODUCES', 'COMMUNICATES_WITH',
    'RELATED_TO', 'CONTAINS'
]);

const PROPERTY_KEYS = new Set([
    'title', 'description', 'taxonomy', 'status', 'confidence',
    'provenance', 'score', 'source', 'text', 'layout', 'include',
    'for-relation', 'type', 'model', 'summary'
]);

const STATUS_VALUES = new Set(['accepted', 'proposed', 'provisional', 'rejected']);

// ── TaxDSL StreamLanguage tokenizer ───────────────────────────────────
const taxDslMode = {
    startState: () => ({}),

    token(stream, _state) {
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

        // Braces
        if (stream.eat('{') || stream.eat('}')) return 'bracket';

        // Punctuation
        if (stream.eat(';') || stream.eat(':')) return 'punctuation';

        // Numbers (bare float/int)
        if (stream.match(/^\d+(\.\d+)?/)) return 'number';

        // Words / identifiers
        if (stream.match(/^[A-Za-z_][\w-]*/)) {
            const word = stream.current();
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
function taxDslCompletions(context) {
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

    // At the start of a line — suggest block keywords
    if (/^[\w-]*$/.test(trimmed)) {
        const kwOptions = [...BLOCK_KEYWORDS].map(k => ({
            label: k,
            type: 'keyword',
            info: k === 'element'  ? 'Define an architecture element' :
                  k === 'relation' ? 'Define a typed relation between elements' :
                  k === 'meta'     ? 'Document metadata block' :
                  k === 'view'     ? 'Define an architecture view' : undefined
        }));
        return { from: word.from, options: kwOptions };
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

function getCurrentTheme() {
    return document.documentElement.getAttribute('data-bs-theme') === 'dark'
        ? oneDark
        : EditorView.baseTheme({});
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
