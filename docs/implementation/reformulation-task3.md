# Task 3 — historical DOCX from frozen reformulation evidence

Start after Tasks 2a/2b are integrated and reviewed. Implement the missing fourth
historical export format without changing proposal or adoption semantics.

## Contract and module boundary

- Add `docx` to the existing exact revision and adoption-receipt export routes and
  the existing UI choices. Retain JSON/Markdown/HTML compatibility and historical
  status: a proposal revision does not become an adoption receipt after a later
  adoption. Include DE/EN labels and no implication of expert approval.
- Return actual binary DOCX with the correct MIME, safe persisted-identity filename,
  `no-store`, `nosniff` and a SHA-256 over the response bytes, not a string decoding.
- Keep frozen rendering contracts/ports export-owned and framework-free where the
  existing boundaries require it. Framework-dependent DOCX composition belongs in
  the existing architecture/export adapter. Do not introduce a portfolio dependency
  on architecture report implementations or duplicate the live report service.
- Pass saved report material and a frozen architecture document to the renderer.
  No live catalogue, current requirement, mutable snapshot lookup, LLM, adoption or
  Git write is permitted during rendering.

## Frozen architecture and gaps

- Compose from the exact stored baseline snapshot bytes, snapshot detail, catalogue,
  element and directed relation mappings. Validate cross-document source/version,
  requirement, project, repository/workspace identity and graph references before
  producing a file. Task 2a already binds baseline to the physical proposal row.
- The offer's workspace branch and historical analysis `basedOnBranch` are distinct
  coordinates. Bind each to its own frozen record. A legitimate analysis from draft
  while the offer is on main must work; neither coordinate may be silently replaced.
- Validate node/edge IDs, endpoint presence, type/direction and multiplicity. Do not
  silently collapse multiple directed relations into one or fill absent details
  from today's catalogue. Preserve statement/question/answer architecture references.
- Include frozen gap details and distinguish an unavailable gap analysis from an
  explicitly empty saved inventory. Preserve available completeness/provenance
  warnings rather than presenting a missing analysis as a clean architecture.
- Render readable original/proposal text, questions and answer history, provenance,
  validation findings, inherited evidence, navigable references and the saved graph.
  Untrusted strings remain literal data; no active external content or markup.

## Verification

TDD with real export endpoints and a real DOCX parser. Cover both revision and
receipt, historical stability after current source/catalogue changes, foreign scope,
tampered coordinates, the valid different-branch case, missing/empty gap distinction,
directed/multiple relations and safe literal rendering. Spy only to prove no live
architecture or LLM call; do not replace the exported document or graph result.

Independently render representative DE/EN DOCX with LibreOffice/Poppler and inspect
page images for clipping, overflow, readable graph/links and clear draft status.
The runtime currently exposes `soffice` and `pdftoppm`; do not assume Docker/Chrome.
Use the document skill for document-artifact inspection if applicable.

Run focused exports and relevant architecture dependency ratchet, not repetitive
full-reactor suites. Record exact counts, parser/render results, screenshots and
limitations in `docs/implementation/reformulation-task3-report.md`. Root owns the
final integrated gate. Never raise an architecture ratchet to hide a new dependency.

## Durability

Apply the mandatory completion checkpoint protocol: one implementer, no subagents,
apply_patch edits, checkpoint and root remote verification before long tests or at
most 10 minutes of editing. WIP `[skip ci]`; final green report without skip. No
merge, force-push, credential extraction, or edits to another checkout. Preserve
test evidence remotely as appropriate; build caches are not deliverables.
