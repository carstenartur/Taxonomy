#!/usr/bin/env node
/**
 * Builds a self-contained CodeMirror 6 ESM bundle for the TaxDSL editor.
 *
 * Usage:
 *   cd taxonomy-app/codemirror-build
 *   npm install
 *   node build.mjs
 *
 * Output: ../src/main/resources/static/js/vendor/codemirror-bundle.mjs
 */
import * as esbuild from 'esbuild';
import { fileURLToPath } from 'url';
import { dirname, join } from 'path';

const __dirname = dirname(fileURLToPath(import.meta.url));
const outfile = join(__dirname, '..', 'src', 'main', 'resources', 'static', 'js', 'vendor', 'codemirror-bundle.mjs');

await esbuild.build({
  stdin: {
    contents: `
      // Re-export everything the TaxDSL editor needs from CodeMirror 6
      export { EditorView, basicSetup } from 'codemirror';
      export { StreamLanguage, HighlightStyle, syntaxHighlighting } from '@codemirror/language';
      export { tags } from '@lezer/highlight';
      export { oneDark } from '@codemirror/theme-one-dark';
      export { linter, lintGutter } from '@codemirror/lint';
      export { autocompletion } from '@codemirror/autocomplete';
      export { Compartment } from '@codemirror/state';
      export { keymap } from '@codemirror/view';
      export { MergeView } from '@codemirror/merge';
    `,
    resolveDir: __dirname,
    loader: 'js',
  },
  bundle: true,
  format: 'esm',
  outfile: outfile,
  minify: true,
  target: ['es2020'],
  banner: {
    js: '/* CodeMirror 6 bundle for TaxDSL editor — built with esbuild. Do not edit manually. */',
  },
});

console.log(`✅ CodeMirror bundle written to ${outfile}`);
