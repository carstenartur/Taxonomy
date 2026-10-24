# Architecture focus lens

Date: 2026-10-24. The user requested implementation of the discussed focus-lens
architecture workflow, including the complete first editing slice.

## Intent

Make an existing architecture easier to investigate and extend. The architect
selects or sweeps a focus region, sees actual bounded background investigations,
reviews typed relationship suggestions with their source information directly on
the graph, and accepts a suggestion in one action. Existing semantic undo restores
the previous state. Layout must remain stable while investigating.

## Approach

Extend the existing architecture editor and its D3 renderer. The editor's durable
semantic workspace revision is the authority. The legacy catalogue proposal API
reads a Git projection and cannot represent uncheckpointed custom elements; it is
therefore not the editor's suggestion or acceptance boundary. A separate new
application would duplicate the product and its state. The selected approach
reuses editor reads, command validation, journal, undo, checkpoint and exports.

The immutable snapshot workbench remains an inspection surface and links to the
editor through its existing workspace workflow. Initial architecture creation
continues to use Taxonomy's existing requirement-analysis/draft workflow. This
slice makes the complete focused editing loop usable before adding multi-change
decisions or side-by-side architectural alternatives.

## User interaction

- An explicitly enabled focus lens sweeps graph nodes. Native node selection,
  keyboard selection and adding the selected element offer the same operation.
- Focus changes never move nodes or accept model changes. A short dwell groups
  pointer movement; a bounded sequential queue reuses investigations within the
  same exact context and revision.
- Show real queued, examining, suggestions available, insufficient/unassessed,
  failed and stale states. Do not invent percentages or intermediate AI phases.
- A visible per-session investigation budget is configurable. Pause prevents
  new requests; an already running request may complete. Changes in context
  invalidate results and suppress late responses.
- Suggestions are dashed directed overlays, separate from canonical relations.
  The review panel remains pinned while the focus moves. Show a small number of
  suggestions, full rationale, method and exact input source excerpts.
- A suggestion is a design hypothesis. Lexical matching is explicitly unassessed,
  and a quoted requirement does not prove a deployed relationship exists.
- Accept executes one existing semantic relation command, with source revision,
  method, rationale and bounded evidence on the relation. It has no second
  confirmation dialog. Conflicts require refresh and new investigation; no
  silent rebasing. Retry retains the same command identity.
- Dismissal is local to the current investigation; it is not global training or
  durable rejection history. Undo uses the accepted operation's UUID.

## Architecture and safety boundaries

- The app composition layer resolves scope and captures the current editor DSL.
  All pins and the workspace revision must match before any provider call.
- Suggestions refer only to actual elements from that captured architecture,
  including custom elements. The target search includes nodes outside the focus.
- Respect the DSL's directed relation compatibility rules; exclude self,
  duplicate and containment suggestions. No invented catalogue codes.
- Use the existing provider gateway, prompt budgets and analysis run controls.
  A request uses at most one logical completion. Bounded local text candidates
  remain available without a generative model and are labeled accordingly.
- Treat model responses as untrusted: accept only offered valid triples, require
  source quotations to match actual supplied fields, bound all text, and keep
  failures distinct from a successful empty result. Never claim calibrated
  confidence from a rank or model-generated percentage.
- Preserve full accepted rationale in relation extensions; audit metadata may
  use a short summary within its existing 1,000-character limit.
- Renderers emit intents and display server-projected geometry. They perform no
  transport, semantic persistence or provider calls. Continue using native
  controls, safe text rendering, EN/DE messages and existing context-path handling.
- Keep the 200-node/400-edge renderer ceilings, including suggestion overlays.
- No new frontend framework, graph dependency, CI workflow or test runner.

## Layout

The existing deterministic layer/identity order already keeps nodes stable for
relation-only changes. Retain it as the default and avoid automatic refocusing
after focus acceptance/undo. Offer an explicit server-computed crossing-reduction
ordering as an alternative, retaining the same node set and semantic layers.
Use the same strategy for exact-revision SVG/PDF exports. Optimizing crossings is
a heuristic, not a guarantee of crossing-free drawings.

## Verification

Exercise source/revision isolation, malformed model replies, exact source
quotations, valid custom-element triples, stale async responses, deduplication,
pause/budget behavior, acceptance with persisted provenance and operation-based
undo. Run the existing DSL/editor tests and relevant browser flow in addition to
the canonical repository verification where its prerequisites are available.
Never substitute a source-text assertion or synthetic diagram for a real browser
interaction. Record missing infrastructure separately from test results.
### Ordering reference

An explicit crossing-reduction request pins `layoutRevision` to the displayed
semantic workspace revision. JSON, SVG and PDF derive ordering from that same
authorized retained revision while rendering current nodes and relations. This
keeps relation acceptance/undo spatially stable without client-owned coordinates.
Negative/future references, Git checkpoint references and pins with stable layout
are rejected. Newly added elements follow retained elements deterministically.
