# UI, role, state, and accessibility acceptance matrix

The checked matrix lives in `.github/ui-acceptance-matrix.json`. The workflow and browser script reject profile drift rather than silently running an undeclared combination.

## Roles

- **USER** — read, analyse, search, and export; architecture mutations must return 403 and mutation controls must not imply availability.
- **ARCHITECT** — architecture and proposal mutations are available; administrator navigation remains hidden.
- **ADMIN** — administrator navigation and all architecture workflows are available.

Each run provisions deterministic local USER and ARCHITECT accounts through the real admin API. Browser authentication remains form-based and keyboard-only; API setup uses explicit HTTP Basic credentials, preserving CSRF protection for browser sessions.

## Dynamic states

The browser test captures DOM, screenshot, ARIA snapshot, and axe evidence after:

1. successful scored analysis;
2. stale results after requirement editing;
3. deterministic provider failure;
4. role-appropriate proposal mutation feedback;
5. an open application dialog with focus inside it;
6. dialog close and focus restoration.

Critical, serious, and moderate axe findings fail the run. Browser console errors and any external browser request also fail.

## Browser and visual profiles

- desktop Chromium / USER;
- desktop Firefox / ARCHITECT;
- mobile WebKit / ADMIN;
- Chromium reflow equivalent to 200% zoom;
- Chromium reflow equivalent to 400% zoom;
- Chromium Forced Colors / ARCHITECT.

Browser zoom reduces the CSS layout viewport. The tests use the equivalent effective viewport and assert that document-level horizontal scrolling is not introduced. Reduced Motion is active in every profile.

## Evidence retention

Every state produces:

- full-page PNG;
- serialized DOM HTML;
- ARIA snapshot when supported by Playwright;
- one machine-readable report containing checks, axe findings, viewport data, console errors, external requests, and any failure stack.

Artifacts are retained for 30 days to make UI regressions reviewable rather than relying only on a green check name. The workflow itself uses immutable GitHub Action references and is covered by the repository supply-chain gate. Normal CI additionally verifies reactor-wide coverage, CodeQL, Trivy, database/container compatibility, and the strict bounded-context architecture gate.
