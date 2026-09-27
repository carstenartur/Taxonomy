# Task 2a — independent review

Reviewed range: 363618842d905807689f2b9b932d9642bd49c5d9 through
cbe2aa28ef54c5ca0323309a68ea789b4e064d7c. Read-only review; no repeated tests.

Spec compliance: changes required. Task quality: focused implementation and module
boundaries sound for tested valid payloads, with two material missing edge cases.

- High: `ReformulationPositiveReviewGuard.java:40-47` queries and compares imported
  project/requirement business keys case-sensitively. Existing materialization uses
  case-insensitive identity (`PortfolioGitService` and `ReformulationEvidenceCodec`).
  A valid conflict-bearing lowercase p/r portable document materializes canonical
  physical P/R rows while retaining lowercase evidence keys/bytes. With matching
  current version and text hash, the positive-review query misses that evidence.
  Preserve original portable bytes/hash while matching the established identity.
- Medium: `ReformulationService.java:336-339` dereferences deserialized baseline
  without a null check. Persisted JSON `null` yields 500 and malformed JSON maps to
  422, instead of generic 409 for invalid persisted baseline. Failure must not expose
  payload content and must not alter unrelated codec contracts globally.

Root inspected the cited code and confirmed the mismatch and null/error path.
Reported focused GREEN before correction: 48 positive tests. This does not cover
the two newly identified paths. Future-task coordination docs are outside review.

Fix round 1 completed; acceptance below. No unrelated full suites were repeated.

## Import-path premise correction during RED setup

The initial reviewer example reversed the casing direction. Ordinary creation
normalizes business keys to uppercase (`ProjectPortfolioService.businessKey`), so
an API-created p/r is already P/R; the importer does not rename it. An intermediate
SQL-restored test fixture was rejected before execution as evidence for this user
path. The replacement authors valid lowercase p/r portable DSL, including matching
payload keys and recomputed evidence hash, and uses the real importer with no SQL
mutation. This tests the reachable reverse-direction mismatch and preserves exact
imported bytes/hash. Baseline JSON null/malformed RED remains independently valid.

## Fix round 1 — accepted

Scoped re-review through 426287fdab65ce0c1c76aff4f74eb111f5a8c7a2: both findings
addressed; no direct new material breakage found. Read-only, no repeated tests.

- Imported selection remains exact in scope/version/hash and now case-folds business
  keys consistently with materialization. Stored row-to-payload keys remain exact;
  comparisons to canonical physical business identity are case-insensitive.
- Baseline decoding catches the proposal-local codec failure and checks null before
  use. The fixed generic 409 response does not contain stored payload content.
- Corrected reachable import RED: one expected 409-vs-200 failure. Baseline RED:
  null/malformed expected 409 but returned 500/422. Focused post-fix GREEN: guard
  14/14 plus isolation 9/9, zero failures/errors/skips. The earlier 48-test broader
  result predates this correction and has not been counted as a post-fix run.

Task 2a accepted. The full integrated gate remains with root after later tasks.
