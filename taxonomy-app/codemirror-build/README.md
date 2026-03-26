# CodeMirror Build

This directory contains the build configuration for the self-contained CodeMirror 6 bundle
used by the TaxDSL editor.

## Building

```bash
cd taxonomy-app/codemirror-build
npm install
node build.mjs
```

This produces `../src/main/resources/static/js/vendor/codemirror-bundle.mjs`.

## Updating CodeMirror

1. Update the version numbers in `package.json`
2. Run `npm install && node build.mjs`
3. Commit the updated `codemirror-bundle.mjs`

## Why a local bundle?

The TaxDSL editor previously loaded CodeMirror at runtime from `https://esm.sh`.
This broke in CI (Docker Selenium containers), offline deployments, and slow networks.
A local bundle eliminates the CDN dependency entirely.
