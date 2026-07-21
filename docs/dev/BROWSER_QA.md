# Browser, Responsive, and Accessibility QA

## Supported automated matrix

| Profile | Engine | Viewport | Journey |
|---|---|---:|---|
| desktop-chromium | Chromium | 1440×1000 | Full login, tree, analysis, versions, DSL editor, admin |
| desktop-firefox | Firefox | 1440×1000 | Same full journey on the second supported engine |
| tablet-chromium | Chromium | 1024×768 | Login, tree, keyboard navigation, versions/help, reflow |
| mobile-chromium | Chromium | 390×844 | Primary read/navigation flow and reflow |

Every profile uses `prefers-reduced-motion: reduce`, checks visible keyboard
focus, rejects external browser requests and console errors, and uploads a JSON
report plus screenshot.

## TaxDSL editor

The CodeMirror content surface has an accessible name, keyboard shortcut
metadata, and explicit help text. `Escape` moves focus to the editor container so
the next `Tab` continues through the application. `Control+Space` opens
completion and `Alt+Shift+F` formats the DSL.

The editor is included in axe scans; there is no blanket CodeMirror exclusion.

## Axe policy

Authenticated axe scans run against the eight main sections at desktop, tablet,
and mobile widths using WCAG 2.0/2.1 A and AA tags.

- Critical and serious findings fail immediately.
- Moderate findings are stored as exact profile/section/rule/target signatures.
- `.github/accessibility-baseline.json` contains reviewed existing signatures.
- Any new moderate signature fails; obsolete signatures are reported for removal.
- Minor findings remain visible in the machine-readable evidence.

A baseline update must link to a reviewed issue explaining user impact,
mitigation, owner, and removal condition. It must never be regenerated blindly.
