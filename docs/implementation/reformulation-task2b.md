# Task 2b — adopted lineage, portable evidence and concrete prompt context

Start only after Task 2a is integrated and reviewed. This completes the lineage
part of completion Task 2; do not redo Task 1 or broaden module boundaries.

## Frozen lineage and portability

- When a selected source version was adopted from a proposal, freeze its exact
  scoped adoption evidence in the new baseline. Match project/requirement business
  keys, source version number and content hash, using local receipts and imported
  portable evidence. Ordinary source versions stay compatible.
- Use a framework-free, content-addressed, nonrecursive ancestry contract: immutable
  evidence entries and hash references, not recursively copied baseline archives.
  Preserve all referenced ancestors with closure and integrity checks. No local
  database IDs may be the only durable identity.
- Preserve previous statement provenance and review, questions, source resolutions,
  human answer events (including supersession, actor and rationale), and adoption
  context. New source text remains exact, but adoption/reanalysis must not relabel
  model additions or architecture hypotheses as user-originated or approved.
- Keep obsolete spans attached to their historical source in the archive, not as
  spans into the new original. Do not silently drop lineage that cannot be mapped.
- Extend the portable format with an explicit version when needed. Existing v1
  payload bytes and evidence hashes must remain stable through import/export; old
  snapshots without ancestry remain readable. Reject unsupported schemas, hash or
  identity mismatches, missing closure, cycles, duplicate roots, duplicate JSON keys
  and trailing JSON tokens before materialization. Never use permissive last-key-wins
  parsing at this evidence trust boundary.
- Continue the existing atomic portfolio checkpoint flow. No independent Git commit
  or new workspace-to-portfolio implementation dependency.

## Prompt context and source interpretation

- Every NODE, REWORD, grouping/aggregation and RECONCILE call still receives the
  complete selected original. Include concrete applicable inherited statement
  wordings/origins/reviews and human decision values/states/rationales, not merely
  archive hashes, counts or opaque IDs. Identify their historical source evidence.
- Do not put raw portable archives, old complete snapshots or obsolete source spans
  into prompts. Keep untrusted data separated from instructions. Inherited review
  does not grant a new requirement/architecture review or answer a different scope.
- Preserve rejected decisions without turning them into active prose, using Task 1
  safeguards. Resolve relevant inherited contexts deterministically; do not let model
  repetition become additional confirmation.
- Bump input/prompt encoding fingerprints as needed so old lossy cached answers
  cannot satisfy the expanded contract. Preserve scope-separated cache behavior.
- Respect existing context/budget checks. If required inherited context cannot fit,
  fail visibly; no hidden truncation or archive-only substitute.

Relevant existing seams: `FrozenReformulationEngine` currently labels its verbatim
source statement ORIGINAL; the response parser only forbids models from minting
HUMAN_DECISION. If introducing an adopted-source provenance, also prevent the model
from fabricating it and update source-only editing protections/DE-EN UI labels
(`ReformulationService.statement` and `requirement-reformulation.js` currently test
ORIGINAL explicitly). Preserve protected source behavior independently of origin.

## Verification and bounded scope

Write meaningful RED tests before product changes. Cover ordinary source, local
adoption -> reanalysis -> new offer, imported adoption -> new offer, a second
generation with deduplicated ancestry, exact v1 round trip, malformed archive cases,
and exact inherited decision content in node/reword/reconcile prompts. Include a
small-budget failure case and rejected/confirmed model provenance regression.

Use the existing adoption/checkpoint/application components; only outbound LLM
replies may be replaced in integration tests. Do not preseed a final product state
and call it an end-to-end path. Root owns the later complete civilian/browser gate.
Keep Task 2a review guard working for v1/v2 local and imported current evidence.

Run focused domain/analysis/Spring suites and the relevant architecture ratchet;
record positive test counts and explicit limitations in
`docs/implementation/reformulation-task2b-report.md`. No redundant full-reactor runs.

## Durability

Apply the completion plan's mandatory checkpoint protocol. Commit and request root
publication before each long test and at least every 10 minutes of editing; pause
until the remote SHA/full tree is verified and the local ref aligned. WIP checkpoints
use `[skip ci]`, final green evidence does not. One implementer, no subagents, no
force-push, no merge, no credential extraction, no edits to another checkout.
