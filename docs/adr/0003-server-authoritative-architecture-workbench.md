# ADR 0003: Server-authoritative Architecture Workbench

- Status: Accepted
- Date: 2026-08-05
- Related: #599, PR #600

## Context

Taxonomy can derive a `RequirementArchitectureView` from a requirement and persist that result in an immutable `RequirementAnalysisSnapshot`. It also owns a format-neutral `DiagramModel` used by diagram exporters.

The former browser PDF action did not preserve this boundary. It searched the taxonomy browser for an SVG and, if none was available, printed the current page. As a result, an export could contain the export-selection page instead of the requirement-derived architecture.

The Audio Analyzer workbench established the relevant architectural principle: the Java domain model and server projection remain authoritative, while the browser graph library is only a rendering and interaction adapter.

## Decision

The Architecture Workbench uses this server-authoritative flow:

```text
RequirementAnalysisSnapshot
  -> persisted RequirementArchitectureView
  -> neutral DiagramModel
  -> deterministic DiagramScene
  -> browser / standalone SVG / vector PDF
```

The same `DiagramScene` coordinates and semantic content are used by all three outputs. Display and export never invoke the LLM and never rebuild an old snapshot from current preferences or current taxonomy relations.

For the first read-only production slice, the browser uses the already packaged D3 runtime:

- D3 owns zoom, selection and accessible browser interaction only.
- D3 does not own semantic nodes, relationships, validation, layout or persistence.
- The browser never invents or mutates architecture content.
- SVG and PDF are rendered from the server scene, not from a screenshot of the page.

React Flow is reserved for a later editing slice where typed handles, semantic create/connect commands, undo/redo or live collaboration provide real value. Introducing React Flow for read-only rendering would add a second frontend build stack without changing the authoritative model or the exported result.

## Consequences

### Positive

- A snapshot has one inspectable architecture representation across browser, SVG and PDF.
- Exports are deterministic and reproducible without a new AI request.
- Historical results are independent of later preference changes.
- A missing architecture fails explicitly; no taxonomy or page-print fallback is allowed.
- The browser adapter can later be replaced with React Flow without changing snapshot persistence, projection APIs or export renderers.

### Trade-offs

- The first slice is read-only and does not provide React Flow editing features.
- German and other non-ASCII PDF labels use deterministic transliteration while the renderer relies on PDF standard fonts.
- Rich manual diagram layout is deferred until semantic editing is introduced.

## Guardrails

- `window.print()` is forbidden in architecture export paths.
- Once an analyzer result contains `currentArchView`, the PDF control must target the architecture graph or fail clearly; it must not silently export the taxonomy tree.
- Workbench API, SVG and PDF endpoints must resolve the same authenticated workspace and snapshot.
- Browser modules may call only the dedicated API boundary.
