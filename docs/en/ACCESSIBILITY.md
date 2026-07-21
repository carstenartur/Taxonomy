# Accessibility Evidence and Conformance Plan (BITV 2.0 / WCAG 2.1)

**Last code-based review:** 21 July 2026  
**Target:** WCAG 2.1 Level AA / EN 301 549 / BITV 2.0  
**Current status:** Partially conformant — no formal BIK BITV certification has been completed.

This document records implemented accessibility measures, automated evidence, known limitations, and the manual checks required before a deployment can claim conformity. It is not a declaration of full legal compliance.

## Scope

The assessment covers the authenticated single-page application, including analysis, taxonomy browsing, architecture views, graph exploration, versioning, DSL editing, help, administration, preferences, dialogs, and status feedback.

## Implementation evidence

| Area | Current implementation | Evidence / regression protection | Status |
|---|---|---|---|
| Document language | Thymeleaf sets the page language from the active locale | Template contract and authenticated axe audit | Implemented |
| Bypass blocks | A visible-on-focus skip link targets the main content region | Markup plus focus styling | Implemented |
| Status messages | Polite and assertive ARIA live regions are available for dynamic feedback | UI helper and axe audit | Implemented |
| Main navigation | Runtime `tablist` / `tab` / `tabpanel` semantics, `aria-selected`, roving tab index, Home/End and arrow-key navigation | `taxonomy-utils.js` and axe audit | Implemented |
| Taxonomy tree | `tree`, `treeitem`, `group`, `aria-expanded`, roving focus, arrow/Home/End/Enter/Space navigation | Browser code and focused tests | Implemented |
| Scores and reasons | Tree-item accessible names include code, title, score and reason; a mutation observer keeps dynamic scoring updates synchronized | `taxonomy-utils.js` | Implemented |
| Focus indication | Custom focus-visible outlines for tree items, navigation, dialogs and node actions | CSS contract | Implemented |
| Dialogs | Bootstrap dialogs provide focus trapping; the shared score/message dialog uses native `<dialog>` and labelled controls | UI code and axe audit | Implemented |
| Admin control | Admin state derives from authenticated `ROLE_ADMIN`; icon-only display has an accessible name | Security and UI regression tests | Implemented |
| Stale-result feedback | Editing a requirement after analysis adds a visible warning and reset action | Screenshot and UI behavior tests | Implemented |
| Touch operation | Node actions are displayed on coarse pointers; important controls receive 44 px touch targets | Responsive ergonomics stylesheet | Implemented |
| Zoom and reflow | Navigation, panels and node actions wrap on narrow/zoomed layouts | Responsive ergonomics stylesheet; manual verification still required | Partial |
| Reduced motion | Animations and transitions are minimized when `prefers-reduced-motion` is active | Responsive ergonomics stylesheet | Implemented |
| Graphs and diagrams | Several views provide tables or detail lists, but not every visual relation is guaranteed to have an equivalent linear representation | Manual review required | Partial |
| DSL editor | CodeMirror exposes its own accessibility tree; it is excluded from the general axe scan and must be tested separately | Manual keyboard/screen-reader test | Partial |
| Contrast | Bootstrap defaults and explicit high-score text colors are used; a full state-by-state contrast audit remains necessary | axe detects many contrast failures, but manual checks are still required | Partial |

## Automated accessibility gate

The workflow `.github/workflows/accessibility.yml`:

1. builds and starts the real Spring Boot application;
2. signs in through the form-login page;
3. visits Analyze, Architecture, Graph, Versions, DSL, Help, Admin and Preferences;
4. runs `@axe-core/playwright` against WCAG 2.0/2.1 A and AA rules;
5. fails on critical or serious violations;
6. uploads the application log when the audit fails.

Pinned audit tooling:

- `@playwright/test` 1.61.1
- `@axe-core/playwright` 4.12.1

Automated tests cannot establish full conformity. They do not replace keyboard, screen-reader, zoom, cognitive-accessibility or domain-specific diagram reviews.

## Required manual release checks

Perform these checks for every UI release:

- [ ] Operate the complete primary workflow without a mouse.
- [ ] Verify logical focus order and focus restoration for every modal.
- [ ] Test at 200 % and 400 % browser zoom without horizontal loss of essential content.
- [ ] Test at 320 CSS pixels and on a touch device.
- [ ] Test with Windows High Contrast / forced-colors mode.
- [ ] Test with `prefers-reduced-motion` enabled.
- [ ] Test the taxonomy tree and versioning dialogs with NVDA or JAWS.
- [ ] Test the same primary workflow with VoiceOver on macOS/iOS.
- [ ] Confirm that architecture and graph information is available in a table or structured text form.
- [ ] Confirm that validation errors identify the field, explain the problem and preserve entered data.

## Known limitations

The following limitations block a claim of full WCAG/BITV conformity:

1. Complex D3 diagrams still require a complete equivalence audit against their table/detail alternatives.
2. CodeMirror requires a dedicated screen-reader and keyboard test matrix.
3. A formal BIK BITV test has not been performed.
4. Responsive behavior at 400 % zoom and on all supported mobile devices has not been independently certified.
5. Cognitive load remains high in expert areas such as selective transfer, raw DSL, Git history and conflict resolution.
6. Third-party browser assets must be available locally before a deployment can be described as fully network-isolated.

## Software-ergonomic principles

New or changed workflows must follow these rules:

- Prefer recognition over recall: searchable selectors instead of raw IDs or commit hashes.
- Show one clear primary action per task area.
- Keep diagnostics and system metrics out of the default user workspace.
- Do not hide essential actions behind hover-only interaction.
- Never use color as the only carrier of meaning.
- Avoid native `alert()` and `prompt()`; use labelled application dialogs and inline validation.
- Preserve user input after validation or network errors.
- Announce asynchronous completion and errors through live regions.
- Provide a non-graphical representation for every graph or diagram.

## Conformance statement template

A deploying authority must publish its own tested statement. Until a formal audit is complete, use wording equivalent to:

> The Taxonomy Architecture Analyzer is partially conformant with BITV 2.0 / WCAG 2.1 Level AA. Automated axe checks cover the principal authenticated application areas. Remaining limitations concern complex visualizations, the DSL editor, comprehensive screen-reader verification and formal BIK BITV certification.

The statement must include a feedback contact, preparation date, test method, known barriers and the applicable arbitration procedure.

## Related documentation

- [User Guide](USER_GUIDE.md)
- [Deployment Checklist](DEPLOYMENT_CHECKLIST.md)
- [Security](SECURITY.md)
- [Data Protection](DATA_PROTECTION.md)
- [Digital Sovereignty](DIGITAL_SOVEREIGNTY.md)
